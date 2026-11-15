package org.matonos.store.fdroid;

import android.app.Service;
import android.content.Intent;
import android.content.pm.*;
import android.os.*;
import android.util.Log;
import org.matonos.store.source.*;
import org.matonos.store.source.types.*;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/** Signed F-Droid catalogues and verified downloads; only the store installs packages. */
public final class FdroidSourceService extends Service {
  private static final String TAG = "FdroidSource";
  private FdroidRepositories repositories;
  private FdroidIndexClient client;
  private final Map<String, InstallTask> installs = new ConcurrentHashMap<>();
  private final Map<String, List<Map<String,Object>>> queryResults = new ConcurrentHashMap<>();
  private final ExecutorService repoQueries = Executors.newCachedThreadPool();

  @Override public void onCreate() {
    super.onCreate(); repositories = new FdroidRepositories(this);
    Set<String> features = new HashSet<>();
    for (FeatureInfo f : getPackageManager().getSystemAvailableFeatures()) if (f.name != null) features.add(f.name);
    client = new FdroidIndexClient(37, Arrays.asList(Build.SUPPORTED_ABIS), features);
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
      info.name = "fdroid"; info.label = "F-Droid";
      info.capabilities = ISoftwareSource.CAP_REPOSITORIES | ISoftwareSource.CAP_ANTI_FEATURES | ISoftwareSource.CAP_PERMISSIONS;
      return info;
    }
    @Override public AppListing[] search(String term, int offset, int limit) {
      return call("search", () -> listingArray(FdroidCatalog.page(listings(queryCatalogue("search", term, offset)), offset, limit)));
    }
    @Override public AppDetails details(String id, String repo) {
      return call("details id=" + id + " repo=" + repo, () -> detailsObject(find(id, repo)));
    }
    @Override public Review[] reviews(String id, int offset, int limit) { throw new UnsupportedOperationException("F-Droid reviews are not supported"); }
    @Override public AppListing[] installed(int offset, int limit) {
      return call("installed", () -> listingArray(FdroidCatalog.page(listings(queryCatalogue("installed", "", offset)), offset, limit)));
    }
    @Override public AppListing[] updates(int offset, int limit) {
      return call("updates", () -> listingArray(FdroidCatalog.page(listings(queryCatalogue("updates", "", offset)), offset, limit)));
    }
    @Override public Repo[] repos(int offset, int limit) {
      return call("repos", () -> repoArray(FdroidCatalog.page(repositories.list(), offset, limit)));
    }
    @Override public void addRepo(String url) { call("addRepo", () -> {
      String repo = repositories.add(url);
      for (Map<String, Object> r : repositories.list()) if (repo.equals(r.get("url"))) {
        try { client.search(getFilesDir(), repo, text(r.get("fingerprint")), ""); repositories.setBroken(repo, false); }
        catch (Exception error) { Log.e(TAG, "Verifying added repository " + repo + " failed", error); throw error; }
      }
      return null;
    }); }
    @Override public void delRepo(String id) { call("delRepo", () -> { repositories.remove(id); return null; }); }
    @Override public void install(String id, String repo, String op, IInstallCallback callback) {
      if (id == null || id.isEmpty()) throw new IllegalArgumentException("Package id required");
      if (repo == null || repo.isEmpty()) throw new IllegalArgumentException("Repository id required");
      if (op == null || op.isEmpty()) throw new IllegalArgumentException("Operation id required");
      if (callback == null) throw new IllegalArgumentException("Install callback required");
      InstallTask task = new InstallTask(id, repo, op, callback);
      if (installs.putIfAbsent(op, task) != null) throw new IllegalArgumentException("Operation already running: " + op);
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
  };
  @Override public IBinder onBind(Intent intent) { return binder; }

  private List<Map<String, Object>> listings(List<Map<String, Object>> apps) {
    List<Map<String, Object>> result = new ArrayList<>();
    for (Map<String, Object> app : apps) {
      Map<String, Object> row = new LinkedHashMap<>();
      for (String key : new String[]{"id", "packageName", "name", "summary", "icon", "developer", "installed", "versionName", "repo"}) if (app.containsKey(key)) row.put(key, app.get(key));
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
      item.versionName = nullable(row.get("versionName")); item.packageName = nullable(row.get("packageName")); result[i] = item;
    }
    return result;
  }
  private Repo[] repoArray(List<Map<String, Object>> rows) {
    Repo[] result = new Repo[rows.size()];
    for (int i = 0; i < rows.size(); i++) { Map<String, Object> row = rows.get(i); Repo item = new Repo();
      item.id = text(row.get("id")); item.url = text(row.get("url")); item.label = text(row.get("label"));
      item.official = Boolean.TRUE.equals(row.get("official")); item.broken = Boolean.TRUE.equals(row.get("broken")); result[i] = item; }
    return result;
  }
  private AppDetails detailsObject(Map<String, Object> app) {
    AppDetails result = new AppDetails(); result.listing = listingArray(Collections.singletonList(app))[0];
    result.description = text(app.get("description")); result.screenshots = strings(app.get("screenshots"));
    result.permissions = strings(app.get("permissions")); result.license = nullable(app.get("license")); result.homepage = nullable(app.get("homepage"));
    result.downloadSize = number(app.get("downloadSize")); result.installedSize = number(app.get("installedSize"));
    result.antiFeatures = antiFeatures(app.get("antiFeatures")); result.safety = safety(app.get("safety"));
    return result;
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
    List<Map<String, Object>> installed = mode.equals("search") ? Collections.emptyList() : installedVersions();
    List<String> names = new ArrayList<>(); for (Map<String, Object> p : installed) names.add(text(p.get("packageName")));
    List<Map<String,Object>> enabled=repositories.list();
    List<Future<List<Map<String,Object>>>> futures=FdroidCatalog.submitOrdered(enabled,repoQueries,
      repo->queryRepository(repo,mode,term,installed,names));
    int succeeded=0; Exception last=null;
    for(int repoIndex=0;repoIndex<enabled.size();repoIndex++) {
      Map<String,Object> repo=enabled.get(repoIndex); String url=text(repo.get("url"));
      try { List<Map<String,Object>> apps=futures.get(repoIndex).get(); repositories.setBroken(url,false); succeeded++;
        for(Map<String,Object> app:apps){decorate(app,url); app.put("repo",url); if(!mode.equals("search"))app.put("installed",true); result.add(app);}
      } catch(Exception error) { Throwable cause=error instanceof java.util.concurrent.ExecutionException ? error.getCause() : error; Exception failure=cause instanceof Exception?(Exception)cause:new Exception(cause); Log.e(TAG,mode+" repository="+url+" failed",failure); repositories.setBroken(url,true); last=failure; }
    }
    if(succeeded==0 && last!=null) throw last; return result;
  }
  private List<Map<String,Object>> queryRepository(Map<String,Object> repo,String mode,String term,List<Map<String,Object>> installed,List<String> names) throws Exception {
      String url=text(repo.get("url")), fingerprint=text(repo.get("fingerprint")); List<Map<String,Object>> apps;
      if(mode.equals("updates")) apps=client.updatesCatalogue(getFilesDir(),url,fingerprint,installed);
      else if(mode.equals("installed")) apps=client.installedCatalogue(getFilesDir(),url,fingerprint,names);
      else apps=client.search(getFilesDir(),url,fingerprint,term);
      List<Map<String,Object>> accepted=new ArrayList<>();
      for(Map<String,Object> app:FdroidCatalog.candidates(apps,url)) {
        if(mode.equals("installed")){Map<String,Object> current=null;for(Map<String,Object> p:installed)if(p.get("packageName").equals(app.get("id")))current=p;
          if(current==null||!current.get("signerSha256").equals(app.get("signerSha256")))continue;}
        accepted.add(app);
      }
      return accepted;
  }
  private List<Map<String,Object>> queryCatalogue(String mode,String term,int offset) throws Exception {
    return FdroidCatalog.cachedQueryPage(queryResults,mode,term,offset,()->catalogue(mode,term));
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
        if(!task.terminal.compareAndSet(false,true)){Log.e(TAG,"Duplicate commit result op="+task.op);return;} installs.remove(task.op,task);
        try{if(task.cancelled.get())task.callback.failed(task.op,"cancelled");else if(ok)task.callback.done(task.op);else{Log.e(TAG,"Commit failed op="+task.op+" package="+task.id+": "+error);task.callback.failed(task.op,error);}}catch(RemoteException gone){Log.e(TAG,"Delivering terminal result op="+task.op+" failed",gone);}
      }});
    } catch(Exception error) {
      String message=task.cancelled.get()?"cancelled":text(error.getMessage()); if(!task.cancelled.get())Log.e(TAG,"Install op="+task.op+" package="+task.id+" failed",error);
      finishFailed(task,message);
    } finally {
      for(ParcelFileDescriptor fd:descriptors){Log.i(TAG,"close fd="+fd.getFd()+" at FdroidSourceService.prepare");try{fd.close();}catch(IOException error){Log.e(TAG,"Closing APK descriptor op="+task.op,error);}}
      if(cache!=null){File[] entries=cache.listFiles();if(entries==null)Log.e(TAG,"Cannot list install cache "+cache);else for(File file:entries)if(!file.delete())Log.e(TAG,"Cannot delete "+file);if(!cache.delete())Log.e(TAG,"Cannot delete "+cache);}
      if(task.terminal.get())installs.remove(task.op,task);
    }
  }
  private void finishFailed(InstallTask task,String error){ if(task.terminal.compareAndSet(false,true)){installs.remove(task.op,task);try{task.callback.failed(task.op,error);}catch(RemoteException gone){Log.e(TAG,"Reporting failed install op="+task.op,gone);}} }
  private void ensureActive(InstallTask task) throws IOException { if(task.cancelled.get()||Thread.currentThread().isInterrupted())throw new IOException("cancelled"); }
  private void download(String repo,String path,File destination,InstallTask task,int index,int count)throws Exception{
    ensureActive(task);String name=path.replaceFirst("^/+","");if(Arrays.asList(name.split("/")).contains(".."))throw new IllegalArgumentException("Invalid APK path "+path);
    HttpURLConnection connection=(HttpURLConnection)new URL(repo+"/"+name).openConnection();task.connection=connection;connection.setConnectTimeout(20000);connection.setReadTimeout(60000);
    try{int status=connection.getResponseCode();if(status<200||status>=300)throw new IOException("HTTP "+status+" downloading "+connection.getURL());long total=connection.getContentLengthLong(),done=0,reported=0;
      try(InputStream in=connection.getInputStream();FileOutputStream out=new FileOutputStream(destination)){byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1){ensureActive(task);out.write(buffer,0,n);done+=n;long now=SystemClock.elapsedRealtime();if(now-reported>=250){task.callback.progress(task.op,"Downloading APK "+(index+1)+" of "+count,total>0?(int)((index*100L+done*100/total)/count):-1,done,total);reported=now;}}}
    }finally{task.connection=null;connection.disconnect();}
  }
  private static String text(Object value){return value==null?"":String.valueOf(value);}
  private static long number(Object value){return value instanceof Number?((Number)value).longValue():-1;}
}
