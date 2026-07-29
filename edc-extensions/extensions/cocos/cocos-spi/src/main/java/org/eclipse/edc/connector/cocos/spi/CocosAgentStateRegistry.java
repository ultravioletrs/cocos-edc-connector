package org.eclipse.edc.connector.cocos.spi;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class CocosAgentStateRegistry {

    private static final Map<String, CompletableFuture<String>> futures = new ConcurrentHashMap<>();

    private CocosAgentStateRegistry() {}

    public static CompletableFuture<String> getOrCreate(String jobId) {
        return futures.computeIfAbsent(jobId, k -> new CompletableFuture<>());
    }

    public static void complete(String jobId, String state) {
        var future = futures.get(jobId);
        if (future != null) {
            future.complete(state);
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
