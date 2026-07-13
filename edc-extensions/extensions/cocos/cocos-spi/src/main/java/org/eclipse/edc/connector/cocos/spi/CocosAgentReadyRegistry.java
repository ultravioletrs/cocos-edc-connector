package org.eclipse.edc.connector.cocos.spi;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class CocosAgentReadyRegistry {

    private static final Map<String, CompletableFuture<Void>> futures = new ConcurrentHashMap<>();

    private CocosAgentReadyRegistry() {}

    public static CompletableFuture<Void> getOrCreate(String jobId) {
        return futures.computeIfAbsent(jobId, k -> new CompletableFuture<>());
    }

    public static void complete(String jobId) {
        var future = futures.get(jobId);
        if (future != null) {
            future.complete(null);
        }
    }

    public static void fail(String jobId, String error) {
        var future = futures.get(jobId);
        if (future != null) {
            future.completeExceptionally(new RuntimeException(error));
        }
    }

    public static void remove(String jobId) {
        futures.remove(jobId);
    }
}
