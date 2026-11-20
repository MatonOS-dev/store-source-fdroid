package org.matonos.store.fdroid;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class FdroidIndexClientTest {
  public static void main(String[] args) throws Exception {
    testMarkdownConversion();
    if (args.length == 1) { testLargeStreamingIndex(new File(args[0])); return; }
    File signedJar = new File(args[0]);
    File certFile = new File(args[1]);
    String fingerprint = fingerprint(CertificateFactory.getInstance("X.509").generateCertificate(new FileInputStream(certFile)).getEncoded());
    FdroidIndexClient client = new FdroidIndexClient();
    client.verifyEntryJar(bytes(signedJar), fingerprint);
    expectFailure(() -> client.verifyEntryJar(bytes(signedJar), "00".repeat(32)), "wrong fingerprint");

    File damaged = new File(signedJar.getParentFile(), "damaged.jar");
    damageSignedContent(signedJar, damaged);
    expectFailure(() -> client.verifyEntryJar(bytes(damaged), fingerprint), "damaged JAR signature");
    expectFailure(() -> client.verifyIndexHash("index data".getBytes(StandardCharsets.UTF_8), "00".repeat(32)), "index hash mismatch");

    String fixture = "{\"packages\":{\"org.example.demo\":{\"added\":1712345678,\"metadata\":{\"name\":{\"en-US\":\"Demo App\"},\"summary\":{\"en-US\":\"A small fixture\"},\"icon\":{\"en-US\":{\"name\":\"/icons/demo.png\"}},\"icons-192\":{\"en-US\":{\"name\":\"/icons/demo-192.png\"}},\"icons-640\":{\"en-US\":{\"name\":\"/icons/demo-640.png\"}}},\"versions\":{\"abc\":{\"file\":[{\"name\":\"/demo.apk\",\"sha256\":\"abc123\"},{\"name\":\"/demo-split.apk\",\"sha256\":\"def456\"}],\"manifest\":{\"versionName\":\"1.2\",\"versionCode\":12,\"signer\":{\"sha256\":[\"aa11\"]}}}}}}}";
    java.util.Map<String, Object> parsed = client.parseIndex(fixture);
    java.util.Map<String, Object> app = client.detailsFromParsedIndex(parsed, "org.example.demo");
    check("Demo App".equals(app.get("name")), "parser name");
    check("A small fixture".equals(app.get("summary")), "parser summary");
    check("https://f-droid.org/repo/icons/demo-640.png".equals(app.get("icon")), "icon chooses the highest source density meeting 2x row size instead of the undersized default");
    java.util.Map<String,Object> v2IconIndex = client.parseIndex(fixture.replace("\"icons-192\"", "\"iconv2\""));
    java.util.Map<String,Object> v2IconApp = client.detailsFromParsedIndex(v2IconIndex, "org.example.demo");
    check("https://f-droid.org/repo/icons/demo-192.png".equals(v2IconApp.get("icon")), "index-v2 iconv2 listing asset is preferred");
    check("/demo.apk".equals(app.get("apkName")), "parser APK name");
    check("abc123".equals(app.get("apkSha256")), "parser APK hash");
    check(((java.util.List<?>) app.get("apkFiles")).size() == 2, "parser split APK files");
    check(((Number) app.get("versionCode")).longValue() == 12, "parser version code");
    check(((Number) app.get("added")).longValue() == 1712345678L, "index added timestamp retained for New collection");
    File streamedIndex = new File(signedJar.getParentFile(), "added-index.json");
    Files.writeString(streamedIndex.toPath(), fixture, StandardCharsets.UTF_8);
    java.util.Map<String,Object> streamed = client.parseCatalogueForTest(streamedIndex);
    java.util.Map<String,Object> streamedApp = (java.util.Map<String,Object>)((java.util.Map<?,?>)streamed.get("packages")).get("org.example.demo");
    check(((Number)streamedApp.get("added")).longValue() == 1712345678L, "streaming cached-index parser retains the added timestamp");
    Files.delete(streamedIndex.toPath());
    testApkHashBeforeCommit(signedJar.getParentFile());
    testUpdateEligibility(client, parsed);
    testRepositoryChanges();
    testCompatibility();
    testSearchRanking();
    testRecentlyAddedCollection();
    testPopularityScoring();
    testSafety();
    testManifest(signedJar.getParentFile());
    testTypedCatalogContract();
    testParallelRepoOrderAndPageCache();
    testHttpCatalogueCache(signedJar, certFile, new File(args[2]), new File(args[3]), new File(args[4]));
    testOperationTable();
    System.out.println("PASS: signed JAR, signer pin, index hash, index parser, APK hash before commit, update signer/version checks, repository trust/removal");
  }

  private static void testMarkdownConversion() {
    String converted=FdroidMarkdown.convert("<p>Thunderbird <strong>mail</strong></p><p>What you can do</p><ul><li>Use one app with an optional Unified Inbox.</li><li><a href=\"https://thunderbird.net\">Visit Thunderbird</a></li></ul>");
    check(converted.contains("Thunderbird **mail**")&&converted.contains("What you can do")&&converted.contains("optional Unified Inbox")&&converted.contains("[Visit Thunderbird](https://thunderbird.net)"),"F-Droid Thunderbird HTML description converts to CommonMark");
  }
  private static void testSearchRanking() {
    java.util.Map<String,Object> bendy = new java.util.LinkedHashMap<>();
    bendy.put("id", "com.example.bendystraw"); bendy.put("name", "BendyStraw");
    bendy.put("summary", "An app for managing NewPipe databases");
    java.util.Map<String,Object> newpipe = new java.util.LinkedHashMap<>();
    newpipe.put("id", "org.schabi.newpipe"); newpipe.put("name", "NewPipe");
    newpipe.put("summary", "A video player");
    java.util.Map<String,Object> prefix = new java.util.LinkedHashMap<>();
    prefix.put("id", "org.example.newpipe.fork"); prefix.put("name", "NewPipe Fork");
    java.util.Map<String,Object> idMatch = new java.util.LinkedHashMap<>();
    idMatch.put("id", "org.example.newpipe.extra"); idMatch.put("name", "Other App");
    java.util.List<java.util.Map<String,Object>> results = new java.util.ArrayList<>(java.util.List.of(bendy, idMatch, prefix, newpipe));
    results.sort(FdroidIndexClient.searchComparator("newpipe"));
    check(results.equals(java.util.List.of(newpipe, prefix, idMatch, bendy)),
        "NewPipe exact name precedes prefix, summary-only BendyStraw, and package ID match");
    check(results.subList(0, 2).equals(java.util.List.of(newpipe, prefix))
        && results.subList(2, 4).equals(java.util.List.of(idMatch, bendy)),
        "search ranking stays fixed across offset pages");
    java.util.Map<String,Object> word = new java.util.LinkedHashMap<>();
    word.put("id", "org.example.other"); word.put("name", "Player NewPipe Edition");
    check(FdroidIndexClient.searchComparator("newpipe").compare(word, idMatch) < 0,
        "name word match precedes summary match");
  }

  private static void testRecentlyAddedCollection() {
    java.util.Map<String,Object> old=new java.util.LinkedHashMap<>();old.put("id","org.example.old");old.put("added",100L);
    java.util.Map<String,Object> newest=new java.util.LinkedHashMap<>();newest.put("id","org.example.newest");newest.put("added",300L);
    java.util.Map<String,Object> middle=new java.util.LinkedHashMap<>();middle.put("id","org.example.middle");middle.put("added","200");
    java.util.List<java.util.Map<String,Object>> sorted=FdroidCatalog.recentlyAdded(java.util.List.of(old,newest,middle));
    check("org.example.newest".equals(sorted.get(0).get("id"))&&"org.example.middle".equals(sorted.get(1).get("id"))&&"org.example.old".equals(sorted.get(2).get("id")),
        "new collection is ordered by added timestamp from the cached index");
  }
  private static void testPopularityScoring() {
    java.util.Map<String,Object> quiet=new java.util.LinkedHashMap<>();quiet.put("id","org.example.quiet");
    java.util.Map<String,Object> popular=new java.util.LinkedHashMap<>();popular.put("id","org.example.popular");
    java.util.Map<String,java.util.Map<String,Object>> stats=new java.util.HashMap<>();
    stats.put("org.example.popular",java.util.Map.of("downloads",10000,"ratingCount",20,"ratingAvg",4.5,"ratingHistogram",new Object[]{0,1,2,5,12}));
    java.util.List<java.util.Map<String,Object>> scored=FdroidCatalog.popular(java.util.List.of(quiet,popular),2,stats);
    check(scored.size()==2&&"org.example.popular".equals(scored.get(0).get("id")),"server download and rating stats rank the popular app first");
    check(((Number)scored.get(0).get("popularityScore")).doubleValue()<=10.0,"server popularity score stays within the 0–10 range");
    check(((Number)scored.get(1).get("popularityScore")).doubleValue()==0.0,"empty server stats give a new app a zero score");
    check(((Number)scored.get(0).get("serverRatingCount")).intValue()==20,"server rating summary is retained for the contract parcelable");
  }
  private static void testLargeStreamingIndex(File file) throws Exception {
    check(file.length() >= 100L * 1024 * 1024, "synthetic index is at least 100 MiB");
    java.util.Map<String,Object> index = new FdroidIndexClient().parseCatalogueForTest(file);
    java.util.Map<String,Object> packages = (java.util.Map<String,Object>) index.get("packages");
    check(packages.size() == 1, "large index retains only searchable package catalogue");
    java.util.Map<String,Object> app = (java.util.Map<String,Object>) packages.get("org.example.demo");
    check(app != null && "Stream Demo".equals(app.get("name")), "large index app remains searchable");
    System.out.println("PASS: 100 MiB streaming index searchable under configured heap; retained package count=" + packages.size());
  }

  private static void testOperationTable() throws Exception {
    OperationTable table = new OperationTable();
    check(!table.running("unknown"), "unknown operation is not running");
    table.start("slow"); check(table.running("slow"), "operation running while work is in progress");
    table.finish("slow"); check(!table.running("slow"), "operation stops after done");
    table.start("failed"); table.finish("failed"); check(!table.running("failed"), "failed operation is not running");
    table.start("cancelled"); table.finish("cancelled"); check(!table.running("cancelled"), "cancelled operation is not running");
  }

  private static void testHttpCatalogueCache(File entryJar, File cert, File indexFile, File changedJar, File changedIndex) throws Exception {
    String signer = fingerprint(bytes(cert));
    byte[] firstJar = bytes(entryJar), secondJar = bytes(changedJar), firstIndex = bytes(indexFile), secondIndex = bytes(changedIndex);
    AtomicInteger entryRequests = new AtomicInteger(), indexRequests = new AtomicInteger();
    java.util.concurrent.atomic.AtomicReference<Boolean> changed = new java.util.concurrent.atomic.AtomicReference<>(false);
    java.util.concurrent.atomic.AtomicBoolean brokenIndexOnce = new java.util.concurrent.atomic.AtomicBoolean();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/repo/entry.jar", exchange -> {
      entryRequests.incrementAndGet();
      String currentEtag = changed.get() ? "\"v2\"" : "\"v1\"";
      if (currentEtag.equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
        exchange.sendResponseHeaders(304, -1); exchange.close(); return;
      }
      byte[] body = changed.get() ? secondJar : firstJar;
      exchange.getResponseHeaders().add("ETag", changed.get() ? "\"v2\"" : "\"v1\"");
      if (entryRequests.get() == 1) try { Thread.sleep(350); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
      exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
    });
    server.createContext("/repo/index-v2.json", exchange -> {
      indexRequests.incrementAndGet(); byte[] body = changed.get() ? secondIndex : firstIndex;
      if (brokenIndexOnce.getAndSet(false)) body = "{}".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
    });
    server.start();
    File cache = Files.createTempDirectory("fdroid-cache-test").toFile();
    String repo = "http://127.0.0.1:" + server.getAddress().getPort() + "/repo";
    try {
      FdroidIndexClient client = new FdroidIndexClient();
      java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
      CountDownLatch start = new CountDownLatch(1);
      try {
        java.util.concurrent.Future<?> a = pool.submit(() -> { try { start.await(); client.search(cache, repo, signer, ""); } catch (Exception e) { throw new RuntimeException(e); } });
        java.util.concurrent.Future<?> b = pool.submit(() -> { try { start.await(); client.search(cache, repo, signer, ""); } catch (Exception e) { throw new RuntimeException(e); } });
        start.countDown(); a.get(); b.get();
      } finally { pool.shutdownNow(); }
      check(entryRequests.get() == 1 && indexRequests.get() == 1, "concurrent loads share one entry and index download");
      int parses = client.parseCountForTest();
      client.search(cache, repo, signer, "");
      check(entryRequests.get() == 2 && indexRequests.get() == 1, "304 retains verified index without downloading it");
      check(client.parseCountForTest() == parses, "304 keeps parsed catalogue without reparsing");
      changed.set(true);
      client.search(cache, repo, signer, "");
      check(indexRequests.get() == 2, "changed entry index hash downloads replacement index");
      check(client.parseCountForTest() == parses + 2, "changed hash reparses entry and catalogue");
      FdroidIndexClient cold = new FdroidIndexClient();
      // A fresh client with a 304 must recover the verified catalogue from disk.
      cold.search(cache, repo, signer, "");
      check(cold.parseCountForTest() == 1, "cold 304 parses verified index from disk");
      // A failed verification must not leave validators behind: the next load
      // has no index to answer a 304 from and must fetch the entry again.
      File failedCache = Files.createTempDirectory("fdroid-cache-failed").toFile();
      brokenIndexOnce.set(true);
      boolean rejected = false;
      try { new FdroidIndexClient().search(failedCache, repo, signer, ""); } catch (Exception expected) { rejected = true; }
      check(rejected, "index with wrong hash is rejected");
      new FdroidIndexClient().search(failedCache, repo, signer, "");
    } finally {
      server.stop(0);
      File[] files = cache.listFiles(); if (files != null) for (File file : files) { File[] nested=file.listFiles(); if(nested!=null)for(File child:nested)child.delete(); file.delete(); }
      cache.delete();
    }
  }

  private static void testParallelRepoOrderAndPageCache() throws Exception {
    java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
      long started = System.nanoTime();
      java.util.List<java.util.concurrent.Future<java.util.List<String>>> futures = FdroidCatalog.submitOrdered(java.util.List.of("first", "second"), pool, repo -> {
        Thread.sleep(450);
        return java.util.List.of(repo + "-result");
      });
      java.util.List<String> merged = new java.util.ArrayList<>();
      for (java.util.concurrent.Future<java.util.List<String>> future : futures) merged.addAll(future.get());
      long elapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
      check(merged.equals(java.util.List.of("first-result", "second-result")), "parallel repo merge retains input order");
      check(elapsedMs < 800, "slow repositories execute in parallel (elapsed " + elapsedMs + "ms)");
    } finally { pool.shutdownNow(); }

    java.util.Map<String, java.util.List<String>> cache = new java.util.HashMap<>();
    java.util.concurrent.atomic.AtomicInteger refreshes = new java.util.concurrent.atomic.AtomicInteger();
    FdroidCatalog.CheckedSupplier<java.util.List<String>> refresh = () -> {
      refreshes.incrementAndGet(); return java.util.List.of("a", "b", "c");
    };
    java.util.List<String> firstPage = FdroidCatalog.page(FdroidCatalog.cachedQueryPage(cache, "search", "term", 0, refresh), 0, 2);
    java.util.List<String> secondPage = FdroidCatalog.page(FdroidCatalog.cachedQueryPage(cache, "search", "term", 2, refresh), 2, 2);
    check(firstPage.equals(java.util.List.of("a", "b")) && secondPage.equals(java.util.List.of("c")), "page two slices the cached query result");
    check(refreshes.get() == 1, "page two does not refresh catalogue query");
  }

  private static void testTypedCatalogContract() throws Exception {
    java.util.Map<String,Object> app = new java.util.LinkedHashMap<>(); app.put("id", "org.example.same");
    java.util.List<java.util.Map<String,Object>> first = FdroidCatalog.candidates(java.util.List.of(app), "https://one.example/repo");
    java.util.List<java.util.Map<String,Object>> second = FdroidCatalog.candidates(java.util.List.of(app), "https://two.example/repo");
    java.util.List<java.util.Map<String,Object>> combined = new java.util.ArrayList<>(); combined.addAll(first); combined.addAll(second);
    check(combined.size() == 2, "same package remains two repo candidates");
    check("org.example.same".equals(combined.get(0).get("id")), "candidate id stays package name");
    check("https://one.example/repo".equals(combined.get(0).get("repo")), "first candidate repo id");
    check("https://two.example/repo".equals(combined.get(1).get("repo")), "second candidate repo id");
    java.util.Map<String,Object> one = new java.util.LinkedHashMap<>(); one.put("id", "https://one.example/repo"); one.put("url", "https://one.example/repo");
    java.util.Map<String,Object> two = new java.util.LinkedHashMap<>(); two.put("id", "https://two.example/repo"); two.put("url", "https://two.example/repo");
    java.util.List<java.util.Map<String,Object>> repos = java.util.List.of(one, two);
    check(FdroidCatalog.repository(repos, "https://two.example/repo") == two, "details and install resolve the selected repo only");
    expectIllegalArgument(() -> FdroidCatalog.repository(repos, "missing"), "unknown candidate repo");
    java.util.List<Integer> values = java.util.List.of(0,1,2,3,4);
    check(FdroidCatalog.page(values, 1, 2).equals(java.util.List.of(1,2)), "page offset and limit");
    check(FdroidCatalog.page(values, 4, 20).equals(java.util.List.of(4)), "short final page");
    check(FdroidCatalog.page(values, 8, 2).isEmpty(), "past-end page");
    expectIllegalArgument(() -> FdroidCatalog.page(values, -1, 2), "negative page offset");
    expectIllegalArgument(() -> FdroidCatalog.page(values, 0, 0), "zero page limit");
  }

  private static void testCompatibility() {
    FdroidIndexClient client = new FdroidIndexClient(37, java.util.List.of("x86_64"), java.util.Set.of("android.hardware.camera"));
    String versions = "{\"packages\":{\"p\":{\"versions\":{"
      + "\"good\":{\"file\":{\"name\":\"base.apk\",\"sha256\":\"abc\"},\"manifest\":{\"versionCode\":1,\"usesSdk\":{\"minSdkVersion\":37},\"nativecode\":[\"x86_64\"]}},"
      + "\"badSdk\":{\"file\":{\"name\":\"base.apk\",\"sha256\":\"abc\"},\"manifest\":{\"versionCode\":3,\"usesSdk\":{\"minSdkVersion\":38}}},"
      + "\"badAbi\":{\"file\":{\"name\":\"base.apk\",\"sha256\":\"abc\"},\"manifest\":{\"versionCode\":2,\"nativecode\":[\"arm64-v8a\"]}}}}}}";
    check(((Number)client.detailsFromParsedIndex(client.parseIndex(versions), "p").get("versionCode")).intValue() == 1, "newest compatible version");
  }
  private static void testSafety() {
    java.util.Map<String,Object> app = new java.util.LinkedHashMap<>();
    app.put("rawAntiFeatures", java.util.List.of(java.util.Map.of("id", "Tracking", "reason", "Reports location")));
    FdroidSafety.apply(app);
    check("potentially-unsafe".equals(((java.util.Map<?,?>)app.get("safety")).get("level")), "tracking is dangerous");
    app.put("rawAntiFeatures", java.util.List.of(java.util.Map.of("id", "UnknownFeature", "reason", "")));
    FdroidSafety.apply(app);
    check("probably-safe".equals(((java.util.Map<?,?>)app.get("safety")).get("level")), "unknown anti feature warns");
  }
  private static void testManifest(File directory) throws Exception {
    // Real aapt2-produced manifests, including a standalone split.
    for (String name : new String[]{"base", "split"}) {
      File apk = new File(directory, name + ".apk");
      if (!apk.isFile()) throw new AssertionError("Missing aapt2 fixture " + apk);
      check((name.equals("base") ? "base.apk" : "split_config.en.apk").equals(ApkManifest.sessionName(apk, "org.example.demo")), "manifest " + name);
      try { ApkManifest.sessionName(apk, "org.example.other"); throw new AssertionError("Accepted wrong package"); } catch (java.io.IOException expected) { }
    }
  }

  private static void testRepositoryChanges() throws Exception {
    String fingerprint = "ab".repeat(32);
    check(fingerprint.equals(FdroidRepository.normalizeFingerprint(fingerprint.toUpperCase(Locale.ROOT))), "repository fingerprint is normalized");
    expectIllegalArgument(() -> FdroidRepository.normalizeFingerprint(""), "fingerprint is required");
    check("xn--bcher-kva.example".equals(FdroidRepository.displayHost("https://bücher.example/repo")), "non-ASCII host is shown as punycode");
    java.util.Map<String, Object> removedRepoApp = new java.util.LinkedHashMap<>();
    removedRepoApp.put("id", "org.example.removed"); removedRepoApp.put("repository", "https://repo.example/fdroid");
    check(FdroidRepository.visibleApps(java.util.Collections.singletonList(removedRepoApp), java.util.Collections.emptySet()).isEmpty(),
        "removed repository apps disappear from search results");
  }

  private static void testUpdateEligibility(FdroidIndexClient client, java.util.Map<String, Object> index) {
    java.util.Map<String, Object> installed = new java.util.LinkedHashMap<>();
    installed.put("packageName", "org.example.demo"); installed.put("versionCode", 10L);
    installed.put("signerSha256", java.util.Collections.singletonList("aa11"));
    check(client.updatesFromParsedIndex(index, java.util.Collections.singletonList(installed)).size() == 1,
        "higher version with matching signer is offered");
    installed.put("signerSha256", java.util.Collections.singletonList("bb22"));
    check(client.updatesFromParsedIndex(index, java.util.Collections.singletonList(installed)).isEmpty(),
        "signer mismatch does not offer update");
    installed.put("signerSha256", java.util.Collections.singletonList("aa11")); installed.put("versionCode", 12L);
    check(client.updatesFromParsedIndex(index, java.util.Collections.singletonList(installed)).isEmpty(),
        "same version does not offer update");
  }

  private static void testApkHashBeforeCommit(File directory) throws Exception {
    File apk = new File(directory, "download.apk");
    try (FileOutputStream output = new FileOutputStream(apk)) { output.write("verified APK bytes".getBytes(StandardCharsets.UTF_8)); }
    String goodHash = fingerprint(bytes(apk));
    FdroidApkInstaller installer = new FdroidApkInstaller();
    installer.verify(apk, goodHash);
    expectFailure(() -> installer.verify(apk, "00".repeat(32)), "APK hash mismatch");
  }

  private static void damageSignedContent(File original, File damaged) throws Exception {
    try (ZipInputStream in = new ZipInputStream(new FileInputStream(original)); ZipOutputStream out = new ZipOutputStream(new FileOutputStream(damaged))) {
      ZipEntry entry;
      while ((entry = in.getNextEntry()) != null) {
        byte[] data = read(in);
        if (entry.getName().equals("entry.json")) data = "{\"changed\":true}".getBytes(StandardCharsets.UTF_8);
        out.putNextEntry(new ZipEntry(entry.getName())); out.write(data); out.closeEntry();
      }
    }
  }
  private interface Checked { void run() throws Exception; }
  private static void expectFailure(Checked action, String label) throws Exception {
    try { action.run(); throw new AssertionError("Accepted " + label); } catch (SecurityException expected) { }
  }
  private static void expectIllegalArgument(Checked action, String label) throws Exception {
    try { action.run(); throw new AssertionError("Accepted " + label); } catch (IllegalArgumentException expected) { }
  }
  private static void check(boolean condition, String label) { if (!condition) throw new AssertionError("Failed " + label); }
  private static byte[] bytes(File file) throws Exception { try (FileInputStream in = new FileInputStream(file)) { return read(in); } }
  private static byte[] read(java.io.InputStream in) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int count;
    while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count); return out.toByteArray();
  }
  private static String fingerprint(byte[] data) throws Exception {
    StringBuilder out = new StringBuilder();
    for (byte b : MessageDigest.getInstance("SHA-256").digest(data)) out.append(String.format(Locale.ROOT, "%02x", b & 0xff));
    return out.toString();
  }
}
