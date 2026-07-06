package org.eclipse.edc.connector.cocos.attestation;

import org.eclipse.edc.spi.result.Result;

/**
 * HTTP client for calling the Consumer Connector's attestation proxy API.
 *
 * <p>Used by the Provider Connector (in {@code provider} attestation mode) to
 * obtain a fresh hardware attestation report from a CVM managed by the Consumer,
 * without requiring direct network access to the CVM's gRPC port.
 */
public interface AttestationProxyClient {

    /**
     * Fetches a raw attestation report from the specified CVM via the Consumer
     * Connector's attestation proxy endpoint.
     *
     * @param consumerProxyBaseUrl base URL of the Consumer Connector's management API
     *                             (e.g. {@code http://consumer-connector:8181/api/management})
     * @param jobId                the computation job ID to which the CVM belongs
     * @param vmIp                 the IP address of the CVM to attest
     * @param nonce                nonce string to embed in the attestation report
     * @return the raw attestation report bytes on success, or a failure detail
     */
    Result<byte[]> fetchReport(String consumerProxyBaseUrl, String jobId, String vmIp, String nonce);
}
