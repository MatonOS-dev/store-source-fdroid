package org.matonos.store.fdroid;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Thread-safe set of install operation IDs that have not reported a terminal callback. */
final class OperationTable {
  private final Set<String> running = ConcurrentHashMap.newKeySet();

  void start(String operationId) {
    if (!running.add(operationId)) throw new IllegalArgumentException("Operation already running: " + operationId);
  }

  void finish(String operationId) { running.remove(operationId); }

  boolean running(String operationId) { return operationId != null && running.contains(operationId); }
}
