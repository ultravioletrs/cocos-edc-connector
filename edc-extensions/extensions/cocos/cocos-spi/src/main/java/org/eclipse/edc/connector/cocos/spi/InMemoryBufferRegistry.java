package org.eclipse.edc.connector.cocos.spi;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;

/**
 * A registry to share downloaded assets between the data plane's custom in-memory
 * data sink and the control plane's DSP remote asset fetcher thread.
 */
public class InMemoryBufferRegistry {

    private static final Map<String, CompletableFuture<byte[]>> BUFFERS = new ConcurrentHashMap<>();

    public static CompletableFuture<byte[]> getOrCreate(String bufferId) {
        return BUFFERS.computeIfAbsent(bufferId, k -> new CompletableFuture<>());
    }

    public static void complete(String bufferId, byte[] data) {
        var future = BUFFERS.remove(bufferId);
        if (future != null) {
            future.complete(data);
        }
    }

    public static void fail(String bufferId, String reason) {
        var future = BUFFERS.remove(bufferId);
        if (future != null) {
            future.completeExceptionally(new RuntimeException(reason));
        }
    }

    public static void remove(String bufferId) {
        BUFFERS.remove(bufferId);
    }
}
