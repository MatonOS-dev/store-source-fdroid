package org.matonos.store.fdroid;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;

/** Reads and caches one F-Droid index-v2 catalogue after checking its signed entry point. */
public final class FdroidIndexClient {
  // Official repository index signer; source: https://f-droid.org/docs/Release_Channels_and_Signing_Keys/
  public static final String OFFICIAL_FINGERPRINT = "43238d512c1e5eb2d6569f4a3afbf5523418b82e0a3ed1552770abb9a9c9ccab";
  private static final String DEFAULT_REPO = "https://f-droid.org/repo";

  private final int sdk;
  private final List<String> abis;
  private final java.util.Set<String> features;
  private final Map<String, Object> locks = new ConcurrentHashMap<>();
  private final Map<String, Map<String, Object>> catalogues = new ConcurrentHashMap<>();
  private final Map<String, String[]> validators = new ConcurrentHashMap<>();
  private final Map<String, FutureTask<Map<String,Object>>> refreshes = new ConcurrentHashMap<>();
  private int parseCount;
  public FdroidIndexClient() { this(37, Collections.singletonList("x86_64"), null); }
  public FdroidIndexClient(int sdk, List<String> abis, java.util.Set<String> features) { this.sdk = sdk; this.abis = abis; this.features = features; }
  private boolean compatible(Map<String, Object> version) {
    Map<String, Object> manifest = map(version.get("manifest"));
    Map<String, Object> usesSdk = map(manifest.get("usesSdk"));
    if (number(usesSdk.get("minSdkVersion")) > sdk) return false;
    long max = number(usesSdk.get("maxSdkVersion")); if (max > 0 && max < sdk) return false;
    Object nativeCode = manifest.get("nativecode");
    if (nativeCode == null) nativeCode = manifest.get("nativeCode");
    if (nativeCode instanceof List && !((List<?>) nativeCode).isEmpty() && Collections.disjoint((List<?>) nativeCode, abis)) return false;
    Object required = manifest.get("features");
    if (features != null && required instanceof List) for (Object feature : (List<?>) required) if (!features.contains(text(feature))) return false;
    return true;
  }

  public List<Map<String, Object>> search(File cacheRoot, String repository, String query) throws Exception {
    return search(cacheRoot, repository, OFFICIAL_FINGERPRINT, query);
  }

  public List<Map<String, Object>> search(File cacheRoot, String repository, String fingerprint, String query) throws Exception {
    Map<String, Object> catalogue = load(cacheRoot, repository, fingerprint);
    Object packagesValue = catalogue.get("packages");
    if (!(packagesValue instanceof Map)) return Collections.emptyList();
    String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
    List<Map<String, Object>> apps = new ArrayList<>();
    for (Map.Entry<?, ?> entry : ((Map<?, ?>) packagesValue).entrySet()) {
      Map<String, Object> app = map(entry.getValue());
      if (app != null && (needle.isEmpty() || searchRank(app, needle) < Integer.MAX_VALUE)) apps.add(app);
    }
    apps.sort(searchComparator(needle));
    return apps;
  }

  static Comparator<Map<String, Object>> searchComparator(String needle) {
    return Comparator.comparingInt((Map<String, Object> app) -> searchRank(app, needle))
        .thenComparing(a -> text(a.get("name")).toLowerCase(Locale.ROOT))
        .thenComparing(a -> text(a.get("id")).toLowerCase(Locale.ROOT));
  }

  private static int searchRank(Map<String, Object> app, String needle) {
    if (needle.isEmpty()) return 0;
    String name = text(app.get("name")).toLowerCase(Locale.ROOT);
    String id = text(app.get("id")).toLowerCase(Locale.ROOT);
    String summary = text(app.get("summary")).toLowerCase(Locale.ROOT);
    String description = text(app.get("description")).toLowerCase(Locale.ROOT);
    if (name.equals(needle)) return 0;
    if (name.startsWith(needle)) return 1;
    if (containsNameWord(name, needle)) return 2;
    if (name.contains(needle)) return 3;
    if (id.contains(needle)) return 4;
    if (summary.contains(needle)) return 5;
    if (description.contains(needle)) return 6;
    return Integer.MAX_VALUE;
  }

  private static boolean containsNameWord(String name, String needle) {
    int from = 0;
    while ((from = name.indexOf(needle, from)) >= 0) {
      if (from == 0 || !Character.isLetterOrDigit(name.charAt(from - 1))) return true;
      from++;
    }
    return false;
  }

  public Map<String, Object> details(File cacheRoot, String repository, String packageName) throws Exception {
    return details(cacheRoot, repository, OFFICIAL_FINGERPRINT, packageName);
  }

  public Map<String, Object> details(File cacheRoot, String repository, String fingerprint, String packageName) throws Exception {
    Map<String, Object> packages = map(load(cacheRoot, repository, fingerprint).get("packages"));
    return map(packages.get(packageName)).isEmpty() ? null : map(packages.get(packageName));
  }

  public List<Map<String, Object>> installedCatalogue(File cacheRoot, String repository, List<String> installedPackages) throws Exception {
    return installedCatalogue(cacheRoot, repository, OFFICIAL_FINGERPRINT, installedPackages);
  }

  public List<Map<String, Object>> installedCatalogue(File cacheRoot, String repository, String fingerprint, List<String> installedPackages) throws Exception {
    Map<String, Object> packages = map(load(cacheRoot, repository, fingerprint).get("packages"));
    List<Map<String, Object>> installed = new ArrayList<>();
    for (String packageName : installedPackages) {
      Map<String, Object> value = map(packages.get(packageName));
      if (value != null) installed.add(value);
    }
    installed.sort(Comparator.comparing(a -> text(a.get("name")).toLowerCase(Locale.ROOT)));
    return installed;
  }

  public List<Map<String, Object>> updatesCatalogue(File cacheRoot, String repository, List<Map<String, Object>> installedPackages) throws Exception {
    return updatesCatalogue(cacheRoot, repository, OFFICIAL_FINGERPRINT, installedPackages);
  }

  public List<Map<String, Object>> updatesCatalogue(File cacheRoot, String repository, String fingerprint, List<Map<String, Object>> installedPackages) throws Exception {
    return updatesFromParsedIndex(load(cacheRoot, repository, fingerprint), installedPackages);
  }

  List<Map<String, Object>> updatesFromParsedIndex(Map<String, Object> catalogue, List<Map<String, Object>> installedPackages) {
    catalogue = map(catalogue.get("packages"));
    List<Map<String, Object>> updates = new ArrayList<>();
    for (Map<String, Object> installed : installedPackages) {
      String packageName = text(installed.get("packageName"));
      Map<String, Object> stored = map(catalogue.get(packageName));
      Map<String, Object> app = stored.containsKey("packageName") ? stored : app(packageName, stored);
      if (app == null || number(app.get("versionCode")) <= number(installed.get("versionCode"))) continue;
      if (!sameSigners(strings(installed.get("signerSha256")), strings(app.get("signerSha256")))) continue;
      updates.add(app);
    }
    updates.sort(Comparator.comparing(a -> text(a.get("name")).toLowerCase(Locale.ROOT)));
    return updates;
  }

  Map<String, Object> parseIndex(String json) {
    synchronized (this) { parseCount++; }
    Object parsed = Json.parse(json);
    if (!(parsed instanceof Map)) throw new IllegalArgumentException("F-Droid index is not an object");
    return cast(parsed);
  }

  Map<String,Object> parseCatalogueForTest(File file) throws Exception { return parseCatalogue(file); }
  private Map<String,Object> parseCatalogue(File file) throws Exception {
    synchronized (this) { parseCount++; }
    Map<String,Object> packages = new LinkedHashMap<>();
    try (Reader reader = new InputStreamReader(new java.io.FileInputStream(file), "UTF-8")) {
      JsonStream.packages(reader, (id, raw) -> { Map<String,Object> value = app(id, raw); if (value != null) packages.put(id, value); });
    }
    return Collections.singletonMap("packages", packages);
  }

  Map<String, Object> detailsFromParsedIndex(Map<String, Object> index, String packageName) {
    return app(packageName, map(map(index.get("packages")).get(packageName)));
  }

  void verifyEntryJar(byte[] jarBytes, String expectedFingerprint) throws Exception {
    // JarFile reads the central directory; JarInputStream only verifies when
    // META-INF/MANIFEST.MF comes first, which F-Droid's entry.jar does not do.
    File temp = File.createTempFile("entry", ".jar");
    try {
      try (FileOutputStream out = new FileOutputStream(temp)) { out.write(jarBytes); }
      boolean foundContent = false;
      try (JarFile jar = new JarFile(temp, true)) {
        byte[] buffer = new byte[8192];
        for (JarEntry entry : Collections.list(jar.entries())) {
          if (entry.isDirectory() || entry.getName().startsWith("META-INF/")) continue;
          try (InputStream in = jar.getInputStream(entry)) { while (in.read(buffer) != -1) { } }
          Certificate[] certificates = entry.getCertificates();
          if (certificates == null || certificates.length == 0) throw new SecurityException("Unsigned entry in F-Droid entry JAR: " + entry.getName());
          boolean signerMatched = false;
          for (Certificate certificate : certificates) {
            if (fingerprint(certificate.getEncoded()).equals(normalizeFingerprint(expectedFingerprint))) signerMatched = true;
          }
          if (!signerMatched) throw new SecurityException("F-Droid entry JAR signer fingerprint did not match");
          foundContent = true;
        }
      }
      if (!foundContent) throw new SecurityException("F-Droid entry JAR has no signed content");
    } finally {
      if (!temp.delete() && temp.exists()) throw new java.io.IOException("Cannot delete temporary entry JAR " + temp);
    }
  }

  void verifyIndexHash(byte[] index, String expectedHash) throws Exception {
    if (!fingerprint(index).equals(normalizeFingerprint(expectedHash))) throw new SecurityException("F-Droid index SHA-256 mismatch");
  }

  private Map<String, Object> load(File cacheRoot, String repository, String expectedFingerprint) throws Exception {
    String repo = (repository == null || repository.isEmpty()) ? DEFAULT_REPO : repository;
    String signer = FdroidRepository.normalizeFingerprint(expectedFingerprint);
    String key = fingerprint((repo + "\n" + signer).getBytes("UTF-8"));
    FutureTask<Map<String,Object>> created=new FutureTask<>(() -> loadLocked(cacheRoot,repo,signer,key));
    FutureTask<Map<String,Object>> task=refreshes.putIfAbsent(key,created);
    if(task==null){task=created;task.run();}
    try{return task.get();}catch(java.util.concurrent.ExecutionException error){Throwable cause=error.getCause();if(cause instanceof Exception)throw (Exception)cause;throw new IllegalStateException("F-Droid refresh failed for "+repo,cause);}finally{refreshes.remove(key,task);}
  }

  private Map<String,Object> loadLocked(File cacheRoot,String repo,String signer,String key) throws Exception {
    Object lock = locks.computeIfAbsent(key, ignored -> new Object());
    synchronized (lock) {
    File repoCache = new File(cacheRoot, key);
    File indexFile = new File(repoCache, "index-v2.json");
    loadValidators(repoCache, key);
    // Validators are only sent when a verified index exists to answer a 304 from.
    boolean haveIndex = catalogues.containsKey(key) || indexFile.isFile();
    HttpURLConnection response = request(repo + "/entry.jar", haveIndex ? validators.get(key) : null);
    int status = response.getResponseCode();
    if (status == HttpURLConnection.HTTP_NOT_MODIFIED) {
      response.disconnect();
      Map<String, Object> cached = catalogues.get(key);
      if (cached != null) return cached;
      if (!indexFile.isFile()) throw new IllegalStateException("F-Droid returned 304 without cached index for " + repo);
      Map<String,Object> parsed = parseCatalogue(indexFile); catalogues.put(key, parsed); return parsed;
    }
    if (status < 200 || status >= 300) { response.disconnect(); throw new IllegalStateException("F-Droid returned HTTP " + status + " for " + repo + "/entry.jar"); }
    byte[] entryJar;
    String etag=response.getHeaderField("ETag"), modified=response.getHeaderField("Last-Modified");
    try (InputStream input=response.getInputStream()) { entryJar=readAll(input); } finally { response.disconnect(); }
    String[] fresh = {etag == null ? "" : etag, modified == null ? "" : modified};
    verifyEntryJar(entryJar, signer);
    byte[] entryBytes = verifiedEntryJson(entryJar);
    Map<String, Object> entry = parseIndex(new String(entryBytes, "UTF-8"));
    Map<String, Object> indexInfo = map(entry.get("index"));
    String expectedHash = text(indexInfo.get("sha256"));
    if (expectedHash.isEmpty()) throw new IllegalArgumentException("F-Droid entry has no index hash");
    if (indexFile.isFile()) {
      if (fingerprint(indexFile).equals(normalizeFingerprint(expectedHash))) {
        Map<String,Object> current=catalogues.get(key);
        if (current == null) { current=parseCatalogue(indexFile); catalogues.put(key, current); }
        storeValidators(repoCache, key, fresh); return current;
      }
    }
    if (!repoCache.exists() && !repoCache.mkdirs()) throw new IllegalStateException("Cannot create F-Droid cache");
    File temporary = File.createTempFile("index-v2-", ".tmp", repoCache);
    try {
      String downloadedHash = download(repo + "/index-v2.json", temporary);
      if (!downloadedHash.equals(normalizeFingerprint(expectedHash))) throw new SecurityException("F-Droid index SHA-256 mismatch");
      Map<String,Object> parsed=parseCatalogue(temporary);
      if (!temporary.renameTo(indexFile)) throw new java.io.IOException("Cannot atomically replace F-Droid index cache");
      catalogues.put(key, parsed);
    } finally { if (temporary.exists() && !temporary.delete()) throw new java.io.IOException("Cannot delete temporary F-Droid index " + temporary); }
    Map<String,Object> parsed=catalogues.get(key);
    storeValidators(repoCache, key, fresh); return parsed;
    }
  }

  private void storeValidators(File directory, String key, String[] values) throws Exception {
    validators.put(key, values); saveValidators(directory, key, values);
  }

  synchronized int parseCountForTest() { return parseCount; }
  private void loadValidators(File directory, String key) throws Exception {
    if (validators.containsKey(key)) return;
    File file=new File(directory,"entry-http.properties");
    if (!file.isFile()) return;
    Properties p=new Properties(); try(InputStream in=new java.io.FileInputStream(file)){p.load(in);}
    validators.put(key,new String[]{p.getProperty("etag",""),p.getProperty("lastModified","")});
  }
  private void saveValidators(File directory,String key,String[] values) throws Exception {
    if (!directory.exists() && !directory.mkdirs()) throw new IllegalStateException("Cannot create F-Droid cache directory " + directory);
    Properties p=new Properties(); p.setProperty("etag",values[0]); p.setProperty("lastModified",values[1]);
    try(FileOutputStream out=new FileOutputStream(new File(directory,"entry-http.properties"))){p.store(out,"F-Droid entry HTTP validators");}
  }

  private byte[] verifiedEntryJson(byte[] jarBytes) throws Exception {
    try (JarInputStream jar = new JarInputStream(new ByteArrayInputStream(jarBytes), true)) {
      JarEntry entry;
      while ((entry = jar.getNextJarEntry()) != null) if ("entry.json".equals(entry.getName())) return readAll(jar);
    }
    throw new IllegalArgumentException("F-Droid entry JAR does not contain entry.json");
  }

  private String download(String address, File destination) throws Exception {
    HttpURLConnection connection = request(address, null);
    connection.setConnectTimeout(20000); connection.setReadTimeout(60000); connection.setInstanceFollowRedirects(true);
    connection.setRequestProperty("User-Agent", "MatonOS-Software-Center/1.0");
    try {
      if (connection.getResponseCode() < 200 || connection.getResponseCode() >= 300) throw new IllegalStateException("F-Droid returned HTTP " + connection.getResponseCode());
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(destination)) {
        byte[] buffer = new byte[32768]; int count;
        while ((count = input.read(buffer)) != -1) { output.write(buffer, 0, count); digest.update(buffer, 0, count); }
        output.getFD().sync();
      }
      StringBuilder hash = new StringBuilder(); for (byte b : digest.digest()) hash.append(String.format(Locale.ROOT, "%02x", b & 0xff)); return hash.toString();
    } finally { connection.disconnect(); }
  }
  private HttpURLConnection request(String address,String[] conditions) throws Exception {
    HttpURLConnection connection=(HttpURLConnection)new URL(address).openConnection();
    connection.setConnectTimeout(20000); connection.setReadTimeout(60000); connection.setInstanceFollowRedirects(true);
    connection.setRequestProperty("User-Agent", "MatonOS-Software-Center/1.0");
    if(conditions!=null){if(!conditions[0].isEmpty())connection.setRequestProperty("If-None-Match",conditions[0]);if(!conditions[1].isEmpty())connection.setRequestProperty("If-Modified-Since",conditions[1]);}
    return connection;
  }

  private Map<String, Object> app(String packageName, Map<String, Object> raw) {
    Map<String, Object> metadata = map(raw.get("metadata"));
    Map<String, Object> versions = map(raw.get("versions"));
    if (versions.isEmpty()) return null;
    Map<String, Object> latest = null;
    for (Object value : versions.values()) {
      Map<String, Object> version = map(value);
      if (!compatible(version)) continue;
      if (latest == null || number(map(version.get("manifest")).get("versionCode")) > number(map(latest.get("manifest")).get("versionCode"))) latest = version;
    }
    if (latest == null) return null;
    Map<String, Object> manifest = map(latest.get("manifest"));
    Object rawFile = latest.get("file");
    List<Map<String, Object>> files = new ArrayList<>();
    if (rawFile instanceof List) {
      for (Object value : (List<?>) rawFile) files.add(map(value));
    } else if (rawFile instanceof Map) {
      files.add(map(rawFile));
    }
    if (files.isEmpty()) return null;
    Map<String, Object> file = files.get(0);
    List<Map<String, String>> apkFiles = new ArrayList<>();
    for (Map<String, Object> item : files) {
      String name = text(item.get("name"));
      String sha256 = text(item.get("sha256"));
      if (!name.isEmpty() && !sha256.isEmpty()) {
        Map<String, String> apk = new LinkedHashMap<>();
        apk.put("name", name); apk.put("sha256", sha256); apkFiles.add(apk);
      }
    }
    if (apkFiles.isEmpty()) return null;
    String icon = localized(metadata.get("icon"));
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", packageName); result.put("packageName", packageName); result.put("name", fallback(localized(metadata.get("name")), packageName));
    result.put("summary", localized(metadata.get("summary"))); result.put("icon", icon.isEmpty() ? "" : absolutePath(icon));
    result.put("ref", packageName); result.put("description", localized(metadata.get("description")));
    result.put("screenshots", Collections.emptyList());
    result.put("versionName", text(manifest.get("versionName"))); result.put("versionCode", number(manifest.get("versionCode")));
    result.put("signerSha256", strings(map(manifest.get("signer")).get("sha256")));
    result.put("apkName", text(file.get("name"))); result.put("apkSha256", text(file.get("sha256"))); result.put("apkFiles", apkFiles);
    result.put("repository", "");
    // index-v2: per-version antiFeatures {id: {locale: reason}}; usesPermission [{name}].
    List<Map<String, String>> antiFeatures = new ArrayList<>();
    for (Map.Entry<String, Object> entry : map(latest.get("antiFeatures")).entrySet()) {
      Map<String, String> anti = new LinkedHashMap<>();
      anti.put("id", entry.getKey()); anti.put("reason", localized(entry.getValue())); antiFeatures.add(anti);
    }
    result.put("rawAntiFeatures", antiFeatures);
    List<String> permissions = new ArrayList<>();
    Object uses = manifest.get("usesPermission");
    if (uses instanceof List) for (Object value : (List<?>) uses) {
      String name = text(map(value).get("name"));
      if (!name.isEmpty()) permissions.add(name);
    }
    result.put("permissions", permissions);
    result.put("developer", text(metadata.get("authorName")));
    result.put("license", text(metadata.get("license")));
    result.put("homepage", fallback(text(metadata.get("webSite")), text(metadata.get("sourceCode"))));
    if (file.get("size") instanceof Number) result.put("downloadSize", number(file.get("size")));
    return result;
  }

  private String absolutePath(String path) { return DEFAULT_REPO + (path.startsWith("/") ? path : "/" + path); }
  private static String localized(Object value) {
    if (value instanceof String) return (String) value;
    Map<String, Object> values = map(value);
    Object localized = values.get("en-US"); if (localized == null) localized = values.get("en");
    if (localized == null && !values.isEmpty()) localized = values.values().iterator().next();
    if (localized instanceof Map) localized = map(localized).get("name");
    String result = text(localized);
    return result;
  }
  private static String fallback(String value, String other) { return value.isEmpty() ? other : value; }
  private static Map<String, Object> map(Object value) { return value instanceof Map ? cast(value) : Collections.emptyMap(); }
  @SuppressWarnings("unchecked") private static Map<String, Object> cast(Object value) { return (Map<String, Object>) value; }
  private static String text(Object value) { return value == null ? "" : String.valueOf(value); }
  private static List<String> strings(Object value) {
    List<String> result = new ArrayList<>();
    if (value instanceof List) for (Object item : (List<?>) value) result.add(normalizeFingerprint(text(item)));
    else if (value instanceof String && !((String) value).isEmpty()) result.add(normalizeFingerprint((String) value));
    Collections.sort(result);
    return result;
  }
  private static boolean sameSigners(List<String> installed, List<String> repository) {
    return !installed.isEmpty() && installed.equals(repository);
  }
  private static long number(Object value) { return value instanceof Number ? ((Number) value).longValue() : 0; }
  private static String normalizeFingerprint(String value) { return value == null ? "" : value.replace(":", "").replace(" ", "").toLowerCase(Locale.ROOT); }
  private static String fingerprint(byte[] bytes) throws Exception {
    byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes); StringBuilder out = new StringBuilder();
    for (byte b : hash) out.append(String.format(Locale.ROOT, "%02x", b & 0xff)); return out.toString();
  }
  private static String fingerprint(File file) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    try (InputStream input = new java.io.FileInputStream(file)) { byte[] buffer = new byte[32768]; int n; while ((n=input.read(buffer))!=-1) digest.update(buffer,0,n); }
    StringBuilder out = new StringBuilder(); for (byte b : digest.digest()) out.append(String.format(Locale.ROOT, "%02x", b & 0xff)); return out.toString();
  }
  private static byte[] readAll(InputStream input) throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[16384]; int read;
    while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read); return output.toByteArray();
  }
}
