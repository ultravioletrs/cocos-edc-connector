package org.eclipse.edc.connector.cocos.api;

import com.fasterxml.jackson.annotation.JsonAutoDetect;

/**
 * Request body for the attestation proxy endpoint.
 * The Provider Connector submits this to obtain a fresh attestation report
 * from a CVM managed by this Consumer Connector, without needing direct
 * network access to the CVM's gRPC port.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class AttestationProxyRequest {

    /** IP address of the CVM from which to fetch the attestation report. */
    private String vmIp;

    /** Nonce to embed in the attestation report (base64-encoded). */
    private String nonce;

    public AttestationProxyRequest() {}

    public AttestationProxyRequest(String vmIp, String nonce) {
        this.vmIp = vmIp;
        this.nonce = nonce;
    }

    public String getVmIp() { return vmIp; }

    public String getNonce() { return nonce; }
}
