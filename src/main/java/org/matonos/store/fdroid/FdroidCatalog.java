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
