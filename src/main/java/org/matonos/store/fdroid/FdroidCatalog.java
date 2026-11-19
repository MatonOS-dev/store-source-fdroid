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

  /** Heuristic score: 0–4 recency (last 2 years), 0–3 catalogue versions, 0–3 known app signal. */
  static List<Map<String,Object>> popular(List<Map<String,Object>> apps, int limit) {
    if (limit <= 0) throw new IllegalArgumentException("limit must be positive");
    long now = System.currentTimeMillis() / 1000L;
    List<Map<String,Object>> rows = new ArrayList<>();
    for (Map<String,Object> app : apps) {
      Map<String,Object> row = new LinkedHashMap<>(app);
      long added = added(app.get("added"));
      double recency = added <= 0 ? 0 : 4.0 * Math.max(0, 1.0 - (now - added) / (2.0 * 365 * 86400));
      Object versions = app.get("versions");
      int count = versions instanceof List ? ((List<?>)versions).size() : 1;
      double history = Math.min(3.0, Math.log1p(Math.max(0, count - 1)) * 1.5);
      String id = text(app.get("id")).toLowerCase(java.util.Locale.ROOT);
      double known = (id.contains("firefox") || id.contains("signal") || id.contains("vlc") || id.contains("kde") || id.contains("f-droid")) ? 3.0 : 0.0;
      double score = Math.round(Math.min(10, recency + history + known) * 10.0) / 10.0;
      row.put("popularityScore", score); rows.add(row);
    }
    rows.sort((a,b) -> { int score = Double.compare(((Number)b.get("popularityScore")).doubleValue(), ((Number)a.get("popularityScore")).doubleValue()); return score != 0 ? score : text(a.get("id")).compareToIgnoreCase(text(b.get("id"))); });
    return new ArrayList<>(rows.subList(0, Math.min(limit, rows.size())));
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
