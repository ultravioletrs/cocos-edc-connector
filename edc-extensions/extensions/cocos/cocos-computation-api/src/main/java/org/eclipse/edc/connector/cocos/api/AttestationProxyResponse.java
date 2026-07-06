package org.eclipse.edc.connector.cocos.api;

import com.fasterxml.jackson.annotation.JsonAutoDetect;

/**
 * Response body from the attestation proxy endpoint.
 * Contains the raw attestation report fetched from the CVM Agent,
 * encoded as a base64 string for safe transport over JSON.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class AttestationProxyResponse {

    /** Base64-encoded raw attestation report bytes from the CVM Agent. */
    private String attestationReport;

    public AttestationProxyResponse() {}

    public AttestationProxyResponse(String attestationReport) {
        this.attestationReport = attestationReport;
    }

    public String getAttestationReport() { return attestationReport; }
}
