package org.matonos.store.fdroid;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/** Pure catalogue operations shared by the Binder adapter and host tests. */
final class FdroidCatalog {
  private FdroidCatalog() { }

  static <T> List<T> page(List<T> values, int offset, int limit) {
    if (offset < 0 || limit <= 0) throw new IllegalArgumentException("offset must be non-negative and limit must be positive");
    if (offset >= values.size()) return new ArrayList<>();
    return new ArrayList<>(values.subList(offset, (int)Math.min(values.size(), (long)offset + limit)));
  }

  static List<Map<String, Object>> recentlyAdded(List<Map<String, Object>> apps) {
    List<Map<String, Object>> result = new ArrayList<>(apps);
    result.sort((left, right) -> {
      long a = added(left.get("added")), b = added(right.get("added"));
      int order = Long.compare(b, a);
      return order != 0 ? order : text(left.get("id")).compareToIgnoreCase(text(right.get("id")));
    });
    return result;
  }

  /** F-Droid has no install counts: combine known-app curation (0–4), release history (0–2.5),
   * catalogue longevity (0–1), recent update (0–1.5), and a 0–2 anti-feature penalty. */
  static List<Map<String,Object>> popular(List<Map<String,Object>> apps, int limit) {
    if (limit <= 0) throw new IllegalArgumentException("limit must be positive");
    long now = System.currentTimeMillis() / 1000L;
    List<Map<String,Object>> rows = new ArrayList<>();
    for (Map<String,Object> app : apps) {
      Map<String,Object> row = new LinkedHashMap<>(app);
      long added = added(app.get("added"));
      int count = app.get("versionCount") instanceof Number ? ((Number)app.get("versionCount")).intValue() : 1;
      String id = text(app.get("id")).toLowerCase(java.util.Locale.ROOT);
      double known = isKnownApp(id) ? 4.0 : 0.0;
      double history = Math.min(2.5, Math.log1p(Math.max(0, count - 1)) * 0.9);
      double longevity = added <= 0 ? 0 : Math.min(1.0, Math.max(0, (now - added) / (5.0 * 365 * 86400)));
      long updated = added(app.get("lastUpdated"));
      double freshness = updated <= 0 ? 0 : 1.5 * Math.max(0, 1.0 - Math.max(0, now - updated) / (2.0 * 365 * 86400));
      Object anti = app.get("antiFeatures");
      int antiCount = anti instanceof List ? ((List<?>)anti).size() : 0;
      double penalty = Math.min(2.0, antiCount * 0.75);
      double score = Math.round(Math.max(0, Math.min(10, known + history + longevity + freshness - penalty)) * 10.0) / 10.0;
      row.put("popularityScore", score); rows.add(row);
    }
    rows.sort((a,b) -> { int score = Double.compare(((Number)b.get("popularityScore")).doubleValue(), ((Number)a.get("popularityScore")).doubleValue()); return score != 0 ? score : text(a.get("id")).compareToIgnoreCase(text(b.get("id"))); });
    return new ArrayList<>(rows.subList(0, Math.min(limit, rows.size())));
  }

  private static boolean isKnownApp(String id) {
    return id.equals("org.fdroid.fdroid") || id.startsWith("org.mozilla.fennec")
        || id.equals("org.mozilla.firefox") || id.equals("us.spotco.fennec_dos")
        || id.startsWith("org.torproject.android") || id.equals("org.thoughtcrime.securesms")
        || id.equals("im.molly.app") || id.equals("org.schabi.newpipe")
        || id.equals("org.videolan.vlc") || id.equals("org.kde.kdeconnect_tp")
        || id.startsWith("app.organicmaps") || id.equals("net.osmand.plus")
        || id.equals("de.danoeh.antennapod") || id.equals("com.x8bit.bitwarden")
        || id.equals("ch.protonvpn.android") || id.equals("eu.faircode.email")
        || id.equals("com.nutomic.syncthingandroid") || id.equals("im.vector.app")
        || id.equals("com.termux") || id.equals("com.kunzisoft.keepass.libre")
        || id.equals("at.bitfire.davdroid") || id.equals("com.keylesspalace.tusky")
        || id.startsWith("ch.deletescape.lawnchair.plah")
        || id.equals("com.simplemobiletools.calendar.pro")
        || id.equals("com.retroarch");
  }

  private static long added(Object value) {
    if (value instanceof Number) return ((Number)value).longValue();
    if (value instanceof String) try { return Long.parseLong((String)value); } catch (NumberFormatException ignored) { return 0; }
    return 0;
  }

  private static String text(Object value) { return value == null ? "" : String.valueOf(value); }

  static List<Map<String, Object>> candidates(List<Map<String, Object>> apps, String repository) {
    List<Map<String, Object>> result = new ArrayList<>();
    for (Map<String, Object> app : apps) {
      Map<String, Object> row = new LinkedHashMap<>(app);
      row.put("repo", repository);
      result.add(row);
    }
    return result;
  }

  static Map<String, Object> repository(List<Map<String, Object>> repositories, String id) {
    for (Map<String, Object> repo : repositories) if (id.equals(repo.get("id"))) return repo;
    throw new IllegalArgumentException("Unknown repository " + id);
  }

  static <T, R> List<Future<R>> submitOrdered(List<T> inputs, ExecutorService executor, CheckedFunction<T, R> action) {
    List<Future<R>> futures = new ArrayList<>();
    for (T input : inputs) futures.add(executor.submit(() -> action.apply(input)));
    return futures;
  }

  interface CheckedFunction<T, R> { R apply(T value) throws Exception; }

  static <T> List<T> cachedQueryPage(Map<String, List<T>> cache, String mode, String term, int offset,
      CheckedSupplier<List<T>> refresh) throws Exception {
    String key = mode + "\n" + (term == null ? "" : term);
    if (offset == 0) {
      List<T> result = refresh.get();
      cache.put(key, result);
      return result;
    }
    List<T> result = cache.get(key);
    return result == null ? refresh.get() : result;
  }

  interface CheckedSupplier<T> { T get() throws Exception; }
}
