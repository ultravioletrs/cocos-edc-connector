package org.eclipse.edc.connector.cocos.attestation;

import org.eclipse.edc.iam.verifiablecredentials.spi.model.VerifiablePresentationContainer;
import org.eclipse.edc.spi.result.Result;

import java.util.List;

/**
 * Client for communicating with the UMU Attestation Credential Service.
 * Used to exchange the Trustee verification token for a Verifiable Presentation (VP).
 */
public interface AttestationCredentialServiceClient {

    /**
     * Requests a Verifiable Presentation (VP) by sending the Trustee KBS verification JWT.
     *
     * @param participantContextId Context ID of the participant
     * @param ownDid               The Connector's own DID
     * @param counterPartyDid      The counterparty's DID
     * @param counterPartyToken    The token/ticket of the counterparty
     * @param scopes               Requested validation scopes
     * @param attestationJwt       The Status JWT returned by the Trustee KBS
     * @param vmIp                 The IP address of the target Cocos CVM
     * @return a List of Verifiable Presentation containers on success, or a failure status
     */
    Result<List<VerifiablePresentationContainer>> requestPresentation(
            String participantContextId,
            String ownDid,
            String counterPartyDid,
            String counterPartyToken,
            List<String> scopes,
            String attestationJwt,
            String vmIp);
}
