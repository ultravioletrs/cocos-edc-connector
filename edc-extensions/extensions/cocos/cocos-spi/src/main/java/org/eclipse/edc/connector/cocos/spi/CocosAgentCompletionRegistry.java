package org.eclipse.edc.connector.cocos.spi;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class CocosAgentCompletionRegistry {

    private static final Map<String, CompletableFuture<Void>> futures = new ConcurrentHashMap<>();

    private CocosAgentCompletionRegistry() {}

    public static CompletableFuture<Void> getOrCreate(String vmIp) {
        return futures.computeIfAbsent(vmIp, k -> new CompletableFuture<>());
    }

    public static void complete(String vmIp) {
        var future = futures.get(vmIp);
        if (future != null) {
            future.complete(null);
        }
    }

    public static void fail(String vmIp, String error) {
        var future = futures.get(vmIp);
        if (future != null) {
            future.completeExceptionally(new RuntimeException(error));
        }
    }

    public static void remove(String vmIp) {
        futures.remove(vmIp);
    }
}
