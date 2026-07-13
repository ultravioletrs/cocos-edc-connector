package org.eclipse.edc.connector.cocos.spi;

import org.eclipse.edc.connector.cocos.spi.model.ComputeManifest;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class CocosManifestRegistry {

    private static final Map<String, ComputeManifest> manifests = new ConcurrentHashMap<>();
    private static final Map<String, CompletableFuture<ComputeManifest>> waiters = new ConcurrentHashMap<>();

    private CocosManifestRegistry() {}

    public static void register(String jobId, ComputeManifest manifest) {
        manifests.put(jobId, manifest);
        // Complete any futures that are blocking on waitForManifest(jobId)
        waiters.computeIfAbsent(jobId, k -> new CompletableFuture<>()).complete(manifest);
    }

    public static ComputeManifest get(String jobId) {
        return manifests.get(jobId);
    }

    public static void remove(String jobId) {
        manifests.remove(jobId);
    }

    /**
     * Returns a future that completes when a manifest is registered for the given jobId.
     * If a manifest is already registered, the returned future is already complete.
     * gRPC threads call {@code .get()} on this with no timeout to wait indefinitely.
     */
    public static CompletableFuture<ComputeManifest> waitForManifest(String jobId) {
        // If the manifest is already registered, return an already-completed future
        ComputeManifest existing = manifests.get(jobId);
        if (existing != null) {
            return CompletableFuture.completedFuture(existing);
        }
        return waiters.computeIfAbsent(jobId, k -> new CompletableFuture<>());
    }

    public static void removeWaiter(String jobId) {
        waiters.remove(jobId);
    }
}
