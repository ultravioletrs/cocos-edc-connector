package org.eclipse.edc.connector.cocos.attestation;

import org.eclipse.edc.connector.cocos.spi.CocosCliService;
import org.eclipse.edc.connector.cocos.spi.CocosContextHolder;
import org.eclipse.edc.iam.decentralizedclaims.spi.PresentationRequestService;
import org.eclipse.edc.iam.verifiablecredentials.spi.model.VerifiablePresentationContainer;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.result.Result;

import java.util.List;

public class AttestationBackedPresentationRequestService implements PresentationRequestService {

    private final CocosCliService cliService;
    private final IdentityHubClient identityHubClient;
    private final KbsClient kbsClient;
    private final String teeType;
    private final Monitor monitor;

    public AttestationBackedPresentationRequestService(CocosCliService cliService,
                                                       IdentityHubClient identityHubClient,
                                                       KbsClient kbsClient,
                                                       String teeType,
                                                       Monitor monitor) {
        this.cliService = cliService;
        this.identityHubClient = identityHubClient;
        this.kbsClient = kbsClient;
        this.teeType = teeType;
        this.monitor = monitor;
    }

    @Override
    public Result<List<VerifiablePresentationContainer>> requestPresentation(
            String participantContextId,
            String ownDid,
            String counterPartyDid,
            String counterPartyToken,
            List<String> scopes) {

        // 1. Authenticate with Trustee KBS to get the session nonce and cookie
        var authResult = kbsClient.authenticate(teeType);
        if (authResult.failed()) {
            return Result.failure("Failed to authenticate with Trustee KBS: " + authResult.getFailureDetail());
        }
        var kbsNonce = authResult.getContent().getNonce();
        var kbsSessionCookie = authResult.getContent().getSessionCookie();

        var vmIp = CocosContextHolder.getActiveVmIp().orElse(null);
        if (vmIp == null) {
            return Result.failure("No active CocosAI VM in context — attestation cannot proceed");
        }

        // 2. Request attestation report from CVM using Trustee KBS nonce
        var attestationResult = cliService.requestAttestation(vmIp, kbsNonce);
        if (attestationResult.failed()) {
            return Result.failure("Failed to obtain attestation report from VM " + vmIp
                    + ": " + attestationResult.getFailureDetail());
        }

        monitor.debug("Obtained attestation report from " + vmIp + ", verifying with Trustee KBS");

        // 3. Verify attestation report at Trustee KBS to get the Status JWT
        var verifyResult = kbsClient.verify(attestationResult.getContent(), kbsNonce, kbsSessionCookie, teeType);
        if (verifyResult.failed()) {
            return Result.failure("Trustee attestation verification failed: " + verifyResult.getFailureDetail());
        }
        var statusJwt = verifyResult.getContent();

        monitor.debug("Attestation report verified with Trustee KBS, requesting VP from Identity Hub");

        // 4. Request Verifiable Presentation from UMU Attestation Credential Service using Status JWT and CVM IP
        return identityHubClient.requestPresentation(
                participantContextId,
                ownDid,
                counterPartyDid,
                counterPartyToken,
                scopes,
                statusJwt,
                vmIp);
    }
}
