package org.eclipse.edc.connector.cocos.spi;

import java.util.Optional;

/**
 * Thread-local context holder for the active CocosAI computation context.
 *
 * <p>Used to propagate the active CVM IP address and computation job ID through
 * the EDC service call chain — specifically from {@code ComputationOrchestratorImpl}
 * (where the DSP consumer flow is initiated) into the
 * {@code AttestationBackedPresentationRequestService} and
 * {@code ProviderAttestationPresentationService} (where the VP is generated).
 *
 * <p>Both fields must be cleared after each DSP interaction via {@link #clear()}.
 */
public class CocosContextHolder {

    private static final ThreadLocal<String> activeVmIp = new ThreadLocal<>();
    private static final ThreadLocal<String> activeJobId = new ThreadLocal<>();

    private CocosContextHolder() {}

    public static void setActiveVmIp(String vmIp) {
        activeVmIp.set(vmIp);
    }

    public static Optional<String> getActiveVmIp() {
        return Optional.ofNullable(activeVmIp.get());
    }

    /**
     * Sets the computation job ID that is currently being orchestrated.
     * Required by {@code ProviderAttestationPresentationService} to route
     * the attestation proxy request to the correct job on the Consumer Connector.
     */
    public static void setActiveJobId(String jobId) {
        activeJobId.set(jobId);
    }

    /**
     * Returns the active computation job ID, or empty if not set.
     */
    public static Optional<String> getActiveJobId() {
        return Optional.ofNullable(activeJobId.get());
    }

    /**
     * Clears all thread-local context. Must be called in a {@code finally} block
     * after each DSP interaction to prevent context leaking across requests.
     */
    public static void clear() {
        activeVmIp.remove();
        activeJobId.remove();
    }
}
