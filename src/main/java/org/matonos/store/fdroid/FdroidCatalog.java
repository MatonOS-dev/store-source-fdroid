package org.matonos.store.fdroid;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ConcurrentMap;

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

  static Map<String,List<Map<String,Object>>> byId(List<Map<String,Object>> apps) {
    Map<String,List<Map<String,Object>>> result=new LinkedHashMap<>();
    for(Map<String,Object> app:apps)result.computeIfAbsent(text(app.get("id")),ignored->new ArrayList<>()).add(app);
    return result;
  }

  /** Search an already loaded catalogue, preserving the index client's ranking. */
  static List<Map<String,Object>> filterSearch(List<Map<String,Object>> apps, String term) {
    String needle=term==null?"":term.trim().toLowerCase(java.util.Locale.ROOT);
    List<Map<String,Object>> result=new ArrayList<>();
    for(Map<String,Object> app:apps)if(matchesSearch(app,needle)) result.add(app);
    result.sort(FdroidIndexClient.searchComparator(needle));
    return result;
  }

  private static boolean matchesSearch(Map<String,Object> app,String needle) {
    if(needle.isEmpty())return true;
    for(String key:new String[]{"name","id","summary","description"})
      if(String.valueOf(app.getOrDefault(key,"")).toLowerCase(java.util.Locale.ROOT).contains(needle))return true;
    String name=String.valueOf(app.getOrDefault("name","")).toLowerCase(java.util.Locale.ROOT);
    int from=0;while((from=name.indexOf(needle,from))>=0){if(from==0||!Character.isLetterOrDigit(name.charAt(from-1)))return true;from++;}
    return false;
  }

  /** Rank from server download counts (log scale), with an anti-feature penalty. */
  static List<Map<String,Object>> popular(List<Map<String,Object>> apps, int limit, Map<String,Map<String,Object>> serverStats) {
    if (limit <= 0) throw new IllegalArgumentException("limit must be positive");
    List<Map<String,Object>> rows = new ArrayList<>();
    for (Map<String,Object> app : apps) {
      String id = text(app.get("id")).toLowerCase(java.util.Locale.ROOT);
      Map<String,Object> stats = serverStats.get(id);
      if (stats == null) stats = java.util.Collections.emptyMap();
      double downloads = Math.min(6.0, Math.log1p(Math.max(0, number(stats.get("downloads")))) * 0.75);
      Object anti = app.get("antiFeatures");
      int antiCount = anti instanceof List ? ((List<?>)anti).size() : 0;
      double penalty = Math.min(2.0, antiCount * 0.75);
      double score = Math.round(Math.max(0, Math.min(10, downloads * (10.0 / 6.0) - penalty)) * 10.0) / 10.0;
      app.put("popularityScore", score); rows.add(app);
    }
    rows.sort((a,b) -> { int score = Double.compare(((Number)b.get("popularityScore")).doubleValue(), ((Number)a.get("popularityScore")).doubleValue()); return score != 0 ? score : text(a.get("id")).compareToIgnoreCase(text(b.get("id"))); });
    return new ArrayList<>(rows.subList(0, Math.min(limit, rows.size())));
  }

  private static long added(Object value) {
    if (value instanceof Number) return ((Number)value).longValue();
    if (value instanceof String) try { return Long.parseLong((String)value); } catch (NumberFormatException ignored) { return 0; }
    return 0;
  }

  private static long number(Object value) { return value instanceof Number ? ((Number)value).longValue() : 0; }

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

  static <T> T withKeyLock(ConcurrentMap<String, Object> locks, String key, CheckedSupplier<T> action) throws Exception {
    Object lock = locks.computeIfAbsent(key, ignored -> new Object());
    synchronized (lock) { return action.get(); }
  }
}
