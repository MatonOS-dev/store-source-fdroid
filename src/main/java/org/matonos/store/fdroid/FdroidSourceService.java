package org.matonos.store.fdroid;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.pm.*;
import android.os.*;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import org.matonos.store.source.*;
import org.matonos.store.source.types.*;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/** Signed F-Droid catalogues and verified downloads; only the store installs packages. */
public final class FdroidSourceService extends Service {
  private static final String TAG = "FdroidSource";
  private FdroidRepositories repositories;
  private FdroidIndexClient client;
  private static final String STATS_URL = "https://maton.hanro50.net.za/api/fdroid";
  private volatile Map<String, Map<String, Object>> serverStats = Collections.emptyMap();
  private volatile long statsFetchedAt;
  private final AtomicBoolean statsRefresh = new AtomicBoolean();
  private BroadcastReceiver packageReceiver;
  private final Map<String, InstallTask> installs = new ConcurrentHashMap<>();
  private final OperationTable operations = new OperationTable();
  private final Map<String, List<Map<String,Object>>> queryResults = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, Object> queryLocks = new ConcurrentHashMap<>();
  private final Map<String, Long> queryTimes = new ConcurrentHashMap<>();
  private static final long QUERY_CACHE_MILLIS = 60_000L;
  private final java.util.concurrent.atomic.AtomicLong queryGeneration = new java.util.concurrent.atomic.AtomicLong();
  private final ExecutorService repoQueries = Executors.newCachedThreadPool();

  @Override public void onCreate() {
    super.onCreate(); repositories = new FdroidRepositories(this);
    packageReceiver = new BroadcastReceiver() {
      @Override public void onReceive(android.content.Context context, Intent intent) { invalidateQueries(); }
    };
    android.content.IntentFilter packageFilter = new android.content.IntentFilter();
    packageFilter.addAction(Intent.ACTION_PACKAGE_ADDED); packageFilter.addAction(Intent.ACTION_PACKAGE_REMOVED); packageFilter.addAction(Intent.ACTION_PACKAGE_REPLACED);
    packageFilter.addDataScheme("package");
    registerReceiver(packageReceiver, packageFilter, android.content.Context.RECEIVER_NOT_EXPORTED);
    Set<String> features = new HashSet<>();
    for (FeatureInfo f : getPackageManager().getSystemAvailableFeatures()) if (f.name != null) features.add(f.name);
    client = new FdroidIndexClient(37, Arrays.asList(Build.SUPPORTED_ABIS), features);
    try {
      android.content.SharedPreferences cache = getSharedPreferences("fdroid-server-stats", MODE_PRIVATE);
      String raw = cache.getString("json", null); statsFetchedAt = cache.getLong("fetched", 0);
      if (raw != null) serverStats = parseStats(new JSONObject(raw));
    } catch (Exception error) { Log.w(TAG, "Reading cached F-Droid stats failed", error); }
    refreshStats(false);
  }

  @Override public void onDestroy() {
    if (packageReceiver != null) unregisterReceiver(packageReceiver);
    repoQueries.shutdownNow();
    super.onDestroy();
  }

  private interface Call<T> { T run() throws Exception; }
  private <T> T call(String context, Call<T> action) {
    try { return action.run(); }
    catch (IllegalArgumentException | UnsupportedOperationException error) { throw error; }
    catch (Exception error) { Log.e(TAG, context + " failed", error); throw new IllegalStateException(context + ": " + error.getMessage(), error); }
  }

  private final ISoftwareSource.Stub binder = new ISoftwareSource.Stub() {
    @Override public SourceInfo info() {
      SourceInfo info = new SourceInfo(); info.version = ISoftwareSource.VERSION; info.revision = ISoftwareSource.REVISION;
      info.name = "fdroid"; info.label = "F-Droid"; info.description = "Applications from signed software repositories.";
      info.capabilities = ISoftwareSource.CAP_ANTI_FEATURES | ISoftwareSource.CAP_PERMISSIONS;
      return info;
    }
    @Override public AppListing[] search(String term, int offset, int limit) {
      return call("search", () -> listingArray(FdroidCatalog.page(listings(queryCatalogue("search", term, offset)), offset, limit)));
    }
    @Override public AppListing[] collection(String kind, int offset, int limit) {
      if (!"new".equals(kind)) return new AppListing[0];
      return call("collection kind=" + kind, () -> listingArray(FdroidCatalog.page(listings(queryCatalogue("new", "", offset)), offset, limit)));
    }
    @Override public AppDetails details(String id, String repo) {
      return call("details id=" + id + " repo=" + repo, () -> {
        Map<String,Object> app=find(id,repo);
        try { app.put("repoOptions",repoOptionsFor(id)); }
        catch(Exception error){Log.w(TAG,"Repository choices unavailable for app="+id+"; showing selected repository only",error);}
        return detailsObject(app);
      });
    }
    @Override public Review[] reviews(String id, int offset, int limit) { throw new UnsupportedOperationException("F-Droid reviews are not supported"); }
    @Override public AppListing[] installed(int offset, int limit) {
      return call("installed", () -> listingArray(FdroidCatalog.page(listings(queryCatalogue("installed", "", offset)), offset, limit)));
    }
    @Override public AppListing[] updates(int offset, int limit) {
      return call("updates", () -> listingArray(FdroidCatalog.page(listings(queryCatalogue("updates", "", offset)), offset, limit)));
    }
    @Override public SourceSetting[] settings() {
      return call("settings", () -> {
        List<SourceSetting> rows = new ArrayList<>();
        SourceSetting stats = new SourceSetting(); stats.id = "share_stats"; stats.label = "Share anonymous download statistics"; stats.type = "boolean";
        stats.value = getSharedPreferences("fdroid-settings", MODE_PRIVATE).getBoolean("share_stats", true) ? "true" : "false"; rows.add(stats);
        SourceSetting add = new SourceSetting(); add.id = "add_repository"; add.label = "Add repository"; add.type = "action";
        SourceSetting url = new SourceSetting(); url.id = "url"; url.label = "Repository URL"; url.type = "text";
        SourceSetting name = new SourceSetting(); name.id = "name"; name.label = "Name"; name.type = "text";
        SourceSetting fingerprint = new SourceSetting(); fingerprint.id = "fingerprint"; fingerprint.label = "Signing fingerprint (SHA-256)"; fingerprint.type = "text";
        add.fields = new SourceSetting[]{url, name, fingerprint}; rows.add(add);
        for (Map<String,Object> repo : repositories.list()) {
          SourceSetting group = new SourceSetting(); group.id = "repository:" + repo.get("id"); group.label = text(repo.get("label")); group.type = "group";
          group.removable = !Boolean.TRUE.equals(repo.get("builtIn"));
          SourceSetting enabled = new SourceSetting(); enabled.id = "repo-enabled:" + repo.get("id"); enabled.label = "Enabled"; enabled.type = "boolean"; enabled.value = String.valueOf(repo.get("enabled"));
          SourceSetting repoUrl = settingLine("url", "URL", text(repo.get("url")));
          SourceSetting count = settingLine("apps", "App count", ((Number)repo.get("appCount")).intValue() < 0 ? "Unknown" : String.valueOf(repo.get("appCount")));
          long refreshed = ((Number)repo.get("lastRefreshed")).longValue();
          SourceSetting last = settingLine("refreshed", "Last refreshed", refreshed == 0 ? "Never" : java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(refreshed * 1000L)));
          group.children = new SourceSetting[]{enabled, repoUrl, count, last}; rows.add(group);
        }
        return rows.toArray(new SourceSetting[0]);
      });
    }
    private SourceSetting settingLine(String id, String label, String value) {
      SourceSetting line = new SourceSetting(); line.id = id; line.label = label; line.type = "text"; line.value = value; line.readOnly = true; return line;
    }
    @Override public String setSetting(String id, String value) {
      if ("share_stats".equals(id)) {
        if (!"true".equals(value) && !"false".equals(value)) return "Expected a boolean value";
        if (!getSharedPreferences("fdroid-settings", MODE_PRIVATE).edit().putBoolean("share_stats", Boolean.parseBoolean(value)).commit()) return "Could not save setting";
        return null;
      }
      if (id != null && id.startsWith("repo-enabled:")) {
        try { repositories.setEnabled(id.substring("repo-enabled:".length()), Boolean.parseBoolean(value)); invalidateQueries(); return null; }
        catch (Exception error) { return error.getMessage(); }
      }
      if (id != null && id.startsWith("repository:") && value == null) {
        try { repositories.remove(id.substring("repository:".length())); invalidateQueries(); return null; }
        catch (Exception error) { return error.getMessage(); }
      }
      return "Unknown or read-only setting";
    }
    @Override public ActionResult runAction(String id, String[] values, boolean confirmed) {
      if (!"add_repository".equals(id)) { ActionResult result = new ActionResult(); result.error = "Unknown action"; return result; }
      String url = values != null && values.length > 0 ? values[0].trim() : "";
      String name = values != null && values.length > 1 ? values[1].trim() : "";
      String fingerprint = values != null && values.length > 2 ? values[2].trim() : "";
      if (url.isEmpty() || fingerprint.isEmpty()) { ActionResult result = new ActionResult(); result.error = "URL and signing fingerprint are required"; return result; }
      try { fingerprint = FdroidRepository.normalizeFingerprint(fingerprint); }
      catch (Exception error) { ActionResult result = new ActionResult(); result.error = error.getMessage(); return result; }
      if (!confirmed) { ActionResult result = new ActionResult(); result.confirmTitle = "Trust repository signer?"; result.confirmMessage = "Add " + url + " using signing fingerprint " + fingerprint + "? Only trust a signer you verified."; return result; }
      try {
        String repo = repositories.add(android.net.Uri.parse(url).buildUpon().appendQueryParameter("fingerprint", fingerprint).build().toString(), name);
        client.search(getFilesDir(), repo, fingerprint, ""); repositories.setBroken(repo, false);
        invalidateQueries();
        return new ActionResult();
      } catch (Exception error) { ActionResult result = new ActionResult(); result.error = error.getMessage(); return result; }
    }
    @Override public void install(String id, String repo, String op, IInstallCallback callback) {
      if (id == null || id.isEmpty()) throw new IllegalArgumentException("Package id required");
      if (repo == null || repo.isEmpty()) throw new IllegalArgumentException("Repository id required");
      if (op == null || op.isEmpty()) throw new IllegalArgumentException("Operation id required");
      if (callback == null) throw new IllegalArgumentException("Install callback required");
      InstallTask task = new InstallTask(id, repo, op, callback);
      if (installs.putIfAbsent(op, task) != null) throw new IllegalArgumentException("Operation already running: " + op);
      operations.start(op);
      task.thread = new Thread(() -> prepare(task), "fdroid-install"); task.thread.start();
    }
    @Override public void cancel(String op) {
      InstallTask task = installs.get(op);
      if (task == null) return;
      task.cancelled.set(true);
      HttpURLConnection connection = task.connection;
      if (connection != null) connection.disconnect();
      Thread thread = task.thread;
      if (thread != null) thread.interrupt();
    }
    @Override public boolean running(String op) { return operations.running(op); }
    @Override public PopularApp[] getPopularApps(int limit) {
      return call("popularApps", () -> {
        refreshStats(false);
        List<Map<String,Object>> rows = FdroidCatalog.popular(queryCatalogue("search", "", 0), limit, serverStats);
        List<PopularApp> result = new ArrayList<>();
        for (Map<String,Object> row : rows) {
          PopularApp item = new PopularApp(); item.app = listingArray(Collections.singletonList(row))[0];
          item.score = ((Number)row.get("popularityScore")).doubleValue(); result.add(item);
        }
        return result.toArray(new PopularApp[0]);
      });
    }

  };
  @Override public IBinder onBind(Intent intent) { return binder; }

  private List<Map<String, Object>> listings(List<Map<String, Object>> apps) {
    List<Map<String, Object>> result = new ArrayList<>();
    for (Map<String, Object> app : apps) {
      Map<String, Object> row = new LinkedHashMap<>();
      for (String key : new String[]{"id", "packageName", "name", "summary", "icon", "developer", "installed", "versionName", "repo", "added", "lastUpdated", "versionCount", "antiFeatures", "repoOptions"}) if (app.containsKey(key)) row.put(key, app.get(key));
      result.add(row);
    }
    return result;
  }
  private AppListing[] listingArray(List<Map<String, Object>> rows) {
    AppListing[] result = new AppListing[rows.size()];
    for (int i = 0; i < rows.size(); i++) {
      Map<String, Object> row = rows.get(i); AppListing item = new AppListing();
      item.id = text(row.get("id")); item.name = text(row.get("name")); item.summary = text(row.get("summary"));
      item.icon = text(row.get("icon")); item.developer = text(row.get("developer"));
      item.installed = Boolean.TRUE.equals(row.get("installed")); item.repo = text(row.get("repo"));
      item.versionName = nullable(row.get("versionName")); item.packageName = nullable(row.get("packageName")); item.badges = badges(row); result[i] = item;
      item.added = Math.max(0, number(row.get("added")));
      Object rawOptions=row.get("repoOptions");if(rawOptions instanceof List){List<?> values=(List<?>)rawOptions;item.repoOptions=new RepoOption[values.size()];for(int j=0;j<values.size();j++){if(!(values.get(j) instanceof Map))continue;Map<?,?> value=(Map<?,?>)values.get(j);RepoOption option=new RepoOption();option.repoId=text(value.get("repoId"));option.name=text(value.get("name"));option.version=nullable(value.get("version"));option.installable=!Boolean.FALSE.equals(value.get("installable"));option.note=nullable(value.get("note"));item.repoOptions[j]=option;}}
    }
    return result;
  }
  private AppDetails detailsObject(Map<String, Object> app) {
    AppDetails result = new AppDetails(); result.listing = listingArray(Collections.singletonList(app))[0];
    result.description = text(app.get("description")); result.descriptionMarkdown = FdroidMarkdown.convert(result.description); result.screenshots = strings(app.get("screenshots"));
    result.permissions = strings(app.get("permissions")); result.license = nullable(app.get("license")); result.homepage = nullable(app.get("homepage"));
    result.downloadSize = number(app.get("downloadSize")); result.installedSize = number(app.get("installedSize"));
    result.antiFeatures = antiFeatures(app.get("antiFeatures")); result.safety = safety(app.get("safety"));
    return result;
  }
  private AppBadge[] badges(Map<String,Object> row) {
    List<AppBadge> result=new ArrayList<>();long added=number(row.get("added"));
    if(added>0&&System.currentTimeMillis()/1000L-added<=30L*86400){AppBadge badge=new AppBadge();badge.id="new";badge.label="New";badge.icon="fiber_new";result.add(badge);}
    List<?> features=row.get("antiFeatures") instanceof List?(List<?>)row.get("antiFeatures"):Collections.emptyList();
    boolean proprietary=false;
    for(Object value:features){if(!(value instanceof Map))continue;Map<?,?> feature=(Map<?,?>)value;String id=text(feature.get("id"));String lower=id.toLowerCase(Locale.ROOT);if(lower.contains("nonfree")||lower.contains("non-free"))proprietary=true;
      AppBadge badge=new AppBadge();badge.id=id.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]","-");badge.label=text(feature.get("title")).isEmpty()?id:text(feature.get("title"));badge.icon=lower.contains("track")?"visibility":lower.contains("ad")?"ads_click":"warning";result.add(badge);}
    if(!proprietary){AppBadge badge=new AppBadge();badge.id="open-source";badge.label="Open source";badge.icon="code";result.add(badge);}
    return result.toArray(new AppBadge[0]);
  }
  private AntiFeature[] antiFeatures(Object raw) {
    if (!(raw instanceof List)) return new AntiFeature[0]; List<?> values = (List<?>)raw; AntiFeature[] result = new AntiFeature[values.size()];
    for (int i=0;i<result.length;i++) { Map<?,?> m=(Map<?,?>)values.get(i); AntiFeature a=new AntiFeature(); a.id=text(m.get("id")); a.title=text(m.get("title")); a.description=text(m.get("description")); a.reason=text(m.get("reason")); result[i]=a; } return result;
  }
  private Safety safety(Object raw) {
    if (!(raw instanceof Map)) return null; Map<?,?> map=(Map<?,?>)raw; Safety s=new Safety(); s.level=text(map.get("level"));
    Object rawItems=map.get("items"); if (rawItems instanceof List) { List<?> values=(List<?>)rawItems; s.items=new SafetyItem[values.size()]; for(int i=0;i<s.items.length;i++){ Map<?,?> m=(Map<?,?>)values.get(i); SafetyItem item=new SafetyItem(); item.id=text(m.get("id")); item.title=text(m.get("title")); item.description=text(m.get("description")); item.severity=text(m.get("severity")); s.items[i]=item; } } return s;
  }
  private String[] strings(Object raw) { if (!(raw instanceof List)) return new String[0]; List<?> values=(List<?>)raw; String[] result=new String[values.size()]; for(int i=0;i<result.length;i++) result[i]=text(values.get(i)); return result; }
  private static String nullable(Object value) { return value == null ? null : String.valueOf(value); }

  private List<Map<String, Object>> installedVersions() throws Exception {
    List<Map<String, Object>> result = new ArrayList<>();
    for (PackageInfo p : getPackageManager().getInstalledPackages(PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES))) {
      if (p.signingInfo == null) continue;
      List<String> signers = new ArrayList<>();
      for (Signature s : p.signingInfo.getApkContentsSigners()) { StringBuilder hex = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(s.toByteArray())) hex.append(String.format(Locale.ROOT, "%02x", b & 255)); signers.add(hex.toString()); }
      Collections.sort(signers); Map<String, Object> row = new LinkedHashMap<>(); row.put("packageName", p.packageName); row.put("versionCode", p.getLongVersionCode()); row.put("signerSha256", signers); result.add(row);
    } return result;
  }
  private List<Map<String, Object>> catalogue(String mode, String term) throws Exception {
    List<Map<String, Object>> result = new ArrayList<>();
    List<Map<String, Object>> installed = installedVersions();
    List<String> names = new ArrayList<>(); for (Map<String, Object> p : installed) names.add(text(p.get("packageName")));
    List<Map<String,Object>> enabled=new ArrayList<>();
    for (Map<String,Object> repository : repositories.list()) if (Boolean.TRUE.equals(repository.get("enabled"))) enabled.add(repository);
    List<Future<List<Map<String,Object>>>> futures=FdroidCatalog.submitOrdered(enabled,repoQueries,
      repo->queryRepository(repo,mode,term,installed,names));
    int succeeded=0; Exception last=null;
    for(int repoIndex=0;repoIndex<enabled.size();repoIndex++) {
      Map<String,Object> repo=enabled.get(repoIndex); String url=text(repo.get("url"));
      try { List<Map<String,Object>> apps=futures.get(repoIndex).get(); repositories.setBroken(url,false);
        if("search".equals(mode)&&(term==null||term.isEmpty()))repositories.markRefreshed(url,apps.size());else repositories.markChecked(url);succeeded++;
        for(Map<String,Object> app:apps){decorate(app,url); app.put("repo",url); if(mode.equals("installed")||mode.equals("updates"))app.put("installed",true); result.add(app);}
      } catch(Exception error) { Throwable cause=error instanceof java.util.concurrent.ExecutionException ? error.getCause() : error; Exception failure=cause instanceof Exception?(Exception)cause:new Exception(cause); Log.e(TAG,mode+" repository="+url+" failed",failure); repositories.setBroken(url,true); last=failure; }
    }
    attachRepoOptions(result);
    if("installed".equals(mode)) result.removeIf(app -> !Boolean.TRUE.equals(app.get("candidateInstallable")));
    if(succeeded==0 && last!=null) throw last; return result;
  }
  private List<Map<String,Object>> queryRepository(Map<String,Object> repo,String mode,String term,List<Map<String,Object>> installed,List<String> names) throws Exception {
      String url=text(repo.get("url")), fingerprint=text(repo.get("fingerprint")); List<Map<String,Object>> apps;
      if(mode.equals("updates")) apps=client.updatesCatalogue(getFilesDir(),url,fingerprint,installed);
      else if(mode.equals("installed")) apps=client.installedCatalogue(getFilesDir(),url,fingerprint,names);
      else if(mode.equals("new")) apps=client.search(getFilesDir(),url,fingerprint,"");
      else apps=client.search(getFilesDir(),url,fingerprint,term);
      List<Map<String,Object>> accepted=new ArrayList<>();
      for(Map<String,Object> app:FdroidCatalog.candidates(apps,url)) {
        Map<String,Object> current=null;for(Map<String,Object> p:installed)if(p.get("packageName").equals(app.get("id")))current=p;
        app.put("candidateInstallable",current==null||current.get("signerSha256").equals(app.get("signerSha256")));
        app.put("installed",current!=null);
        if(mode.equals("installed")&&current==null)continue;
        accepted.add(app);
      }
      return accepted;
  }
  private void attachRepoOptions(List<Map<String,Object>> rows) {
    Map<String,List<Map<String,Object>>> candidates=new LinkedHashMap<>();
    for(Map<String,Object> row:rows)candidates.computeIfAbsent(text(row.get("id")),key->new ArrayList<>()).add(row);
    Map<String,Map<String,Object>> repositoryById=new HashMap<>();for(Map<String,Object> repository:repositories.list())repositoryById.put(text(repository.get("url")),repository);
    for(Map<String,Object> row:rows){List<Map<String,Object>> offers=candidates.get(text(row.get("id")));List<Map<String,Object>> options=new ArrayList<>();Set<String> seen=new HashSet<>();
      for(Map<String,Object> offer:offers){String repo=text(offer.get("repo"));if(!seen.add(repo))continue;Map<String,Object> repository=repositoryById.get(repo);Map<String,Object> option=new LinkedHashMap<>();option.put("repoId",repo);option.put("name",repository==null?repo:text(repository.get("label")));
        boolean installable=!Boolean.FALSE.equals(offer.get("candidateInstallable"));option.put("installable",installable);if(!installable)option.put("note","Different signing key");options.add(option);}
      if(Boolean.TRUE.equals(row.get("installed"))){String origin=getSharedPreferences("fdroid-install-origin",MODE_PRIVATE).getString(text(row.get("id")),null);if(origin!=null){row.put("repo",origin);if(seen.add(origin)){Map<String,Object> repository=repositoryById.get(origin);Map<String,Object> option=new LinkedHashMap<>();option.put("repoId",origin);option.put("name",repository==null?origin:text(repository.get("label")));option.put("installable",false);option.put("note","Installed repository is unavailable");options.add(option);}}}
      row.put("repoOptions",options);
    }
  }

  private void invalidateQueries() {
    synchronized (queryResults) {
      queryGeneration.incrementAndGet();
      queryResults.clear();
      queryTimes.clear();
    }
  }
  private List<Map<String,Object>> queryCatalogue(String mode,String term,int offset) throws Exception {
    String key=(mode.equals("search")||mode.equals("new"))?"catalogue\n":"mode\n"+mode+"\n"+(term==null?"":term);
    List<Map<String,Object>> cached=queryResults.get(key);
    if(freshQuery(key,cached))return queryView(mode,term,cached);
    return FdroidCatalog.withKeyLock(queryLocks,key,()->{
      List<Map<String,Object>> lockedCache=queryResults.get(key);
      if(freshQuery(key,lockedCache))return queryView(mode,term,lockedCache);
      long generation=queryGeneration.get();
      List<Map<String,Object>> result=catalogue(key.startsWith("catalogue")?"search":mode,"");
      synchronized (queryResults) {
        if(queryGeneration.get()==generation) {
          queryResults.put(key,result);
          queryTimes.put(key,System.currentTimeMillis());
        }
      }
      return queryView(mode,term,result);
    });
  }
  private List<Map<String,Object>> queryView(String mode,String term,List<Map<String,Object>> rows) {
    if(mode.equals("new"))return FdroidCatalog.recentlyAdded(rows);
    if(mode.equals("search")&&term!=null&&!term.trim().isEmpty())return FdroidCatalog.filterSearch(rows,term);
    return rows;
  }
  private List<Map<String,Object>> repoOptionsFor(String id) throws Exception {
    List<Map<String,Object>> options=new ArrayList<>(); Set<String> seen=new HashSet<>();
    List<Map<String,Object>> installed=installedVersions(); Map<String,Object> current=null;
    for(Map<String,Object> row:installed)if(id.equals(row.get("packageName")))current=row;
    for(Map<String,Object> repo:repositories.list())if(Boolean.TRUE.equals(repo.get("enabled"))){
      String url=text(repo.get("url")); Map<String,Object> app=client.details(getFilesDir(),url,text(repo.get("fingerprint")),id);
      if(app==null)continue; boolean installable=current==null||current.get("signerSha256").equals(app.get("signerSha256"));
      Map<String,Object> option=new LinkedHashMap<>();option.put("repoId",url);option.put("name",text(repo.get("label")).isEmpty()?url:text(repo.get("label")));option.put("installable",installable);if(!installable)option.put("note","Different signing key");options.add(option);seen.add(url);
    }
    String origin=getSharedPreferences("fdroid-install-origin",MODE_PRIVATE).getString(id,null);
    if(current!=null&&origin!=null&&seen.add(origin)){Map<String,Object> option=new LinkedHashMap<>();option.put("repoId",origin);option.put("name",origin);option.put("installable",false);option.put("note","Installed repository is unavailable");options.add(option);}
    return options;
  }
  private boolean freshQuery(String key,List<Map<String,Object>> cached) {
    Long fetched=queryTimes.get(key);
    return cached!=null&&fetched!=null&&System.currentTimeMillis()-fetched<QUERY_CACHE_MILLIS;
  }
  private Map<String,Object> find(String id, String repoId) throws Exception {
    try { Map<String,Object> repo=FdroidCatalog.repository(repositories.list(),repoId);
      String url=text(repo.get("url")); Map<String,Object> app=client.details(getFilesDir(),url,text(repo.get("fingerprint")),id);
      if(app==null) throw new IllegalArgumentException("No F-Droid candidate " + id + " in repository " + repoId);
      for(Map<String,Object> existing:installedVersions()) if(id.equals(existing.get("packageName")) && !existing.get("signerSha256").equals(app.get("signerSha256"))) throw new IllegalArgumentException("Installed signer does not match repository candidate");
      decorate(app,url); app.put("repo",url); return app;
    } catch (IllegalArgumentException error) { throw error; }
  }
  private void decorate(Map<String,Object> app,String url) { app.put("origin",url); app.put("repository",url); String icon=text(app.get("icon")); if(icon.startsWith(FdroidRepositories.OFFICIAL_URL+"/")) app.put("icon",url+icon.substring(FdroidRepositories.OFFICIAL_URL.length())); FdroidSafety.apply(app); }

  private static final class InstallTask {
    final String id, repo, op; final IInstallCallback callback; final AtomicBoolean terminal=new AtomicBoolean(), cancelled=new AtomicBoolean();
    volatile Thread thread; volatile HttpURLConnection connection;
    InstallTask(String id,String repo,String op,IInstallCallback callback){this.id=id;this.repo=repo;this.op=op;this.callback=callback;}
  }
  private void prepare(InstallTask task) {
    File cache=null; List<ParcelFileDescriptor> descriptors=new ArrayList<>();
    try {
      ensureActive(task); task.callback.progress(task.op,"Preparing",-1,0,-1); Map<String,Object> app=find(task.id,task.repo);
      cache=java.nio.file.Files.createTempDirectory(getCacheDir().toPath(),"fdroid-").toFile(); List<?> files=(List<?>)app.get("apkFiles"); List<String> names=new ArrayList<>(); int index=0;
      for(Object raw:files){ ensureActive(task); Map<?,?> file=(Map<?,?>)raw; File target=new File(cache,index+".apk"); download(text(app.get("origin")),text(file.get("name")),target,task,index++,files.size());
        new FdroidApkInstaller().verify(target,text(file.get("sha256"))); String name=ApkManifest.sessionName(target,task.id); if(names.contains(name))throw new IllegalArgumentException("Duplicate APK split "+name); names.add(name); descriptors.add(ParcelFileDescriptor.open(target,ParcelFileDescriptor.MODE_READ_ONLY)); }
      ensureActive(task); if(!names.contains("base.apk"))throw new IllegalArgumentException("F-Droid package has no base APK"); task.callback.progress(task.op,"Verified APKs",100,0,0);
      task.callback.commit(task.op,task.id,names.toArray(new String[0]),descriptors.toArray(new ParcelFileDescriptor[0]),new String[0],new ICommitResult.Stub(){@Override public void result(boolean ok,String error){
        if(!task.terminal.compareAndSet(false,true)){Log.e(TAG,"Duplicate commit result op="+task.op);return;}
        try{if(task.cancelled.get())task.callback.failed(task.op,"cancelled");else if(ok){getSharedPreferences("fdroid-install-origin",MODE_PRIVATE).edit().putString(task.id,task.repo).apply();task.callback.done(task.op);if(getSharedPreferences("fdroid-settings",MODE_PRIVATE).getBoolean("share_stats",true))postInstall(task.id);}else{Log.e(TAG,"Commit failed op="+task.op+" package="+task.id+": "+error);task.callback.failed(task.op,error);}}catch(RemoteException gone){Log.e(TAG,"Delivering terminal result op="+task.op+" failed",gone);}finally{operations.finish(task.op);installs.remove(task.op,task);}
      }});
    } catch(Exception error) {
      String message=task.cancelled.get()?"cancelled":text(error.getMessage()); if(!task.cancelled.get())Log.e(TAG,"Install op="+task.op+" package="+task.id+" failed",error);
      finishFailed(task,message);
    } finally {
      for(ParcelFileDescriptor fd:descriptors){Log.i(TAG,"close fd="+fd.getFd()+" at FdroidSourceService.prepare");try{fd.close();}catch(IOException error){Log.e(TAG,"Closing APK descriptor op="+task.op,error);}}
      if(cache!=null){File[] entries=cache.listFiles();if(entries==null)Log.e(TAG,"Cannot list install cache "+cache);else for(File file:entries)if(!file.delete())Log.e(TAG,"Cannot delete "+file);if(!cache.delete())Log.e(TAG,"Cannot delete "+cache);}
      invalidateQueries();
      if(task.terminal.get())installs.remove(task.op,task);
    }
  }
  private void finishFailed(InstallTask task,String error){ if(task.terminal.compareAndSet(false,true)){try{task.callback.failed(task.op,error);}catch(RemoteException gone){Log.e(TAG,"Reporting failed install op="+task.op,gone);}finally{operations.finish(task.op);installs.remove(task.op,task);}} }
  private void ensureActive(InstallTask task) throws IOException { if(task.cancelled.get()||Thread.currentThread().isInterrupted())throw new IOException("cancelled"); }
  private void download(String repo,String path,File destination,InstallTask task,int index,int count)throws Exception{
    ensureActive(task);String name=path.replaceFirst("^/+","");if(Arrays.asList(name.split("/")).contains(".."))throw new IllegalArgumentException("Invalid APK path "+path);
    HttpURLConnection connection=(HttpURLConnection)new URL(repo+"/"+name).openConnection();task.connection=connection;connection.setConnectTimeout(20000);connection.setReadTimeout(60000);
    try{int status=connection.getResponseCode();if(status<200||status>=300)throw new IOException("HTTP "+status+" downloading "+connection.getURL());long total=connection.getContentLengthLong(),done=0,reported=0;
      try(InputStream in=connection.getInputStream();FileOutputStream out=new FileOutputStream(destination)){byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1){ensureActive(task);out.write(buffer,0,n);done+=n;long now=SystemClock.elapsedRealtime();if(now-reported>=250){task.callback.progress(task.op,"Downloading APK "+(index+1)+" of "+count,total>0?(int)((index*100L+done*100/total)/count):-1,done,total);reported=now;}}}
    }finally{task.connection=null;connection.disconnect();}
  }

  private void refreshStats(boolean force) {
    long now = System.currentTimeMillis();
    if (!force && now - statsFetchedAt < 24L * 60 * 60 * 1000) return;
    if (!statsRefresh.compareAndSet(false, true)) return;
    Thread thread = new Thread(() -> {
      try {
        HttpURLConnection connection = (HttpURLConnection)new URL(STATS_URL + "/stats").openConnection();
        connection.setConnectTimeout(5000); connection.setReadTimeout(5000);
        try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
          byte[] buffer = new byte[8192]; int n; while ((n = in.read(buffer)) != -1 && out.size() < 2 * 1024 * 1024) out.write(buffer, 0, n);
          JSONObject root = new JSONObject(out.toString("UTF-8"));
          serverStats = parseStats(root); statsFetchedAt = System.currentTimeMillis();
          getSharedPreferences("fdroid-server-stats", MODE_PRIVATE).edit().putString("json", root.toString()).putLong("fetched", statsFetchedAt).apply();
        } finally { connection.disconnect(); }
      } catch (Exception error) { Log.w(TAG, "Refreshing F-Droid stats failed; using cached values", error); }
      finally { statsRefresh.set(false); }
    }, "fdroid-stats"); thread.setDaemon(true); thread.start();
  }

  private Map<String, Map<String, Object>> parseStats(JSONObject root) throws Exception {
    Map<String, Map<String, Object>> result = new HashMap<>();
    java.util.Iterator<String> keys = root.keys(); while (keys.hasNext()) { String key = keys.next(); JSONObject value = root.getJSONObject(key); Map<String, Object> row = new HashMap<>(); row.put("downloads", value.optInt("downloads")); result.put(key.toLowerCase(Locale.ROOT), row); }
    return result;
  }

  private void postInstall(String packageName) {
    Thread thread = new Thread(() -> {
      try {
        HttpURLConnection connection = (HttpURLConnection)new URL(STATS_URL + "/install").openConnection();
        connection.setConnectTimeout(5000); connection.setReadTimeout(5000); connection.setRequestMethod("POST"); connection.setDoOutput(true); connection.setRequestProperty("Content-Type", "application/json");
        byte[] body = new JSONObject().put("packageName", packageName).toString().getBytes("UTF-8");
        try (OutputStream out = connection.getOutputStream()) { out.write(body); }
        int status = connection.getResponseCode(); if (status < 200 || status >= 300) Log.w(TAG, "F-Droid install stats returned HTTP " + status);
        connection.disconnect();
      } catch (Exception error) { Log.w(TAG, "Sending F-Droid install stats failed for " + packageName, error); }
    }, "fdroid-install-stats"); thread.setDaemon(true); thread.start();
  }
  private static String text(Object value){return value==null?"":String.valueOf(value);}
  private static long number(Object value){return value instanceof Number?((Number)value).longValue():-1;}
}
