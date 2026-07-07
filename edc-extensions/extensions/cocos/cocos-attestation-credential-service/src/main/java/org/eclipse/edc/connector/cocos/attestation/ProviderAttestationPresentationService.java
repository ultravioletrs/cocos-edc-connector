package org.eclipse.edc.connector.cocos.attestation;

import org.eclipse.edc.connector.cocos.spi.CocosContextHolder;
import org.eclipse.edc.iam.decentralizedclaims.spi.PresentationRequestService;
import org.eclipse.edc.iam.verifiablecredentials.spi.model.VerifiablePresentationContainer;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.result.Result;

import java.util.List;

/**
 * Provider-side implementation of {@link PresentationRequestService}.
 *
 * <p>Used when {@code cocos.attestation.mode=provider}. Instead of the Consumer
 * fetching an attestation report from its own CVMs (as in the default consumer mode),
 * here the <em>Provider</em> Connector obtains the attestation report by calling the
 * Consumer Connector's attestation proxy API. This supports the flow described in
 * D3.2 Section 7.3 where the Provider presents the attestation-backed VP.
 *
 * <p>Flow:
 * <ol>
 *   <li>Request a nonce from the Identity Hub</li>
 *   <li>Call the Consumer Connector's attestation proxy to get the CVM report</li>
 *   <li>Exchange the report at the Identity Hub for a Verifiable Presentation</li>
 *   <li>Return the VP for inclusion in the Provider's Self-Issued Token</li>
 * </ol>
 *
 * <p>Attestation is always fresh — no caching — to preserve the security
 * guarantee that the report reflects the current state of the TEE enclave.
 */
public class ProviderAttestationPresentationService implements PresentationRequestService {

    private final AttestationProxyClient proxyClient;
    private final AttestationCredentialServiceClient client;
    private final KbsClient kbsClient;
    private final String teeType;
    private final String consumerProxyBaseUrl;
    private final Monitor monitor;

    public ProviderAttestationPresentationService(AttestationProxyClient proxyClient,
                                                  AttestationCredentialServiceClient client,
                                                  KbsClient kbsClient,
                                                  String teeType,
                                                  String consumerProxyBaseUrl,
                                                  Monitor monitor) {
        this.proxyClient = proxyClient;
        this.client = client;
        this.kbsClient = kbsClient;
        this.teeType = teeType;
        this.consumerProxyBaseUrl = consumerProxyBaseUrl;
        this.monitor = monitor;
    }

    @Override
    public Result<List<VerifiablePresentationContainer>> requestPresentation(
            String participantContextId,
            String ownDid,
            String counterPartyDid,
            String counterPartyToken,
            List<String> scopes) {

        // Step 1: get a nonce and session cookie from the Trustee KBS
        var authResult = kbsClient.authenticate(teeType);
        if (authResult.failed()) {
            return Result.failure("Provider attestation: failed to authenticate with Trustee KBS: "
                    + authResult.getFailureDetail());
        }
        String kbsNonce = authResult.getContent().getNonce();
        String kbsSessionCookie = authResult.getContent().getSessionCookie();

        // Step 2: resolve active VM IP and Job ID from thread-local context
        // (set by ComputationOrchestratorImpl when dispatching the DSP request)
        var vmIp = CocosContextHolder.getActiveVmIp().orElse(null);
        if (vmIp == null) {
            return Result.failure("Provider attestation: no active CocosAI VM in context — "
                    + "cannot determine which CVM to attest");
        }

        var jobId = CocosContextHolder.getActiveJobId().orElse(null);
        if (jobId == null) {
            return Result.failure("Provider attestation: no active computation job ID in context — "
                    + "cannot route attestation proxy request");
        }

        monitor.debug("Provider attestation: requesting report for VM " + vmIp
                + " (job " + jobId + ") via Consumer proxy at " + consumerProxyBaseUrl);

        // Step 3: call the Consumer Connector's attestation proxy to get the report
        var reportResult = proxyClient.fetchReport(consumerProxyBaseUrl, jobId, vmIp, kbsNonce);
        if (reportResult.failed()) {
            return Result.failure("Provider attestation: failed to obtain attestation report via proxy: "
                    + reportResult.getFailureDetail());
        }

        monitor.debug("Provider attestation: obtained report for VM " + vmIp
                + ", verifying with Trustee KBS");

        // Step 4: verify attestation report at Trustee KBS to get the Status JWT
        var verifyResult = kbsClient.verify(reportResult.getContent(), kbsNonce, kbsSessionCookie, teeType);
        if (verifyResult.failed()) {
            return Result.failure("Provider attestation: Trustee verification failed: "
                    + verifyResult.getFailureDetail());
        }
        var statusJwt = verifyResult.getContent();

        monitor.debug("Provider attestation: obtained report for VM " + vmIp
                + ", requesting VP from Identity Hub");

        // Step 5: exchange the Status JWT for a VP at the Identity Hub
        return client.requestPresentation(
                participantContextId,
                ownDid,
                counterPartyDid,
                counterPartyToken,
                scopes,
                statusJwt,
                vmIp);
    }
}
