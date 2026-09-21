package org.eclipse.edc.connector.cocos.spi;

import org.eclipse.edc.connector.cocos.spi.model.ComputeManifest;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public class CocosManifestRegistry {

    private static final Map<String, ComputeManifest> manifests = new ConcurrentHashMap<>();
    private static final Map<String, CompletableFuture<ComputeManifest>> waiters = new ConcurrentHashMap<>();
    private static final Map<String, Integer> dispatchAttempts = new ConcurrentHashMap<>();
    private static final java.util.Set<String> accepted = ConcurrentHashMap.newKeySet();
    private static volatile BiConsumer<String, ComputeManifest> onManifestRegistered;

    private static volatile String latestJobId;

    private CocosManifestRegistry() {}

    public static void setOnManifestRegistered(BiConsumer<String, ComputeManifest> listener) {
        onManifestRegistered = listener;
    }

    public static void register(String jobId, ComputeManifest manifest) {
        manifests.put(jobId, manifest);
        latestJobId = jobId;
        CompletableFuture<ComputeManifest> waiter = waiters.get(jobId);
        if (waiter != null && !waiter.isDone()) {
            waiter.complete(manifest);
        } else if (onManifestRegistered != null) {
            onManifestRegistered.accept(jobId, manifest);
        }
    }

    public static ComputeManifest get(String jobId) {
        return manifests.get(jobId);
    }

    public static void remove(String jobId) {
        manifests.remove(jobId);
        dispatchAttempts.remove(jobId);
        accepted.remove(jobId);
        if (jobId != null && jobId.equals(latestJobId)) {
            latestJobId = null;
        }
    }

    /**
     * Claims the manifest dispatch for a job. Reconnecting agents must not receive
     * duplicate run requests for the same computation lifecycle.
     */
    public static boolean claimDispatch(String jobId) {
        if (jobId == null || accepted.contains(jobId)) {
            return false;
        }
        return dispatchAttempts.putIfAbsent(jobId, 1) == null;
    }

    /** Allow a reconnecting agent to receive the same manifest on a new stream. */
    public static void resetDispatch(String jobId) {
        if (jobId != null && !accepted.contains(jobId)) {
            dispatchAttempts.remove(jobId);
        }
    }

    public static void markAccepted(String jobId) {
        if (jobId != null) {
            accepted.add(jobId);
        }
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

    public static String getFirstRegisteredJobId() {
        if (latestJobId != null) {
            return latestJobId;
        }
        if (!manifests.isEmpty()) {
            return manifests.keySet().iterator().next();
        }
        if (!waiters.isEmpty()) {
            return waiters.keySet().iterator().next();
        }
        return null;
    }
}
