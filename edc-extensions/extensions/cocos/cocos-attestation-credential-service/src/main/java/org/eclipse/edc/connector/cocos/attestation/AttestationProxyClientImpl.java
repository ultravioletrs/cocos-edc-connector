package org.eclipse.edc.connector.cocos.attestation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.eclipse.edc.http.spi.EdcHttpClient;
import org.eclipse.edc.spi.result.Result;

import java.util.Base64;

/**
 * HTTP implementation of {@link AttestationProxyClient}.
 *
 * <p>Calls the Consumer Connector's management API endpoint:
 * <pre>
 *   POST {consumerProxyBaseUrl}/cocos/computations/{jobId}/attestation
 *   Body: { "vmIp": "...", "nonce": "..." }
 *   Response: { "attestationReport": "<base64>" }
 * </pre>
 */
public class AttestationProxyClientImpl implements AttestationProxyClient {

    private static final MediaType JSON = MediaType.get("application/json");

    private final EdcHttpClient httpClient;
    private final ObjectMapper objectMapper;

    public AttestationProxyClientImpl(EdcHttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public Result<byte[]> fetchReport(String consumerProxyBaseUrl, String jobId,
                                      String vmIp, String nonce) {
        try {
            ObjectNode bodyNode = objectMapper.createObjectNode();
            bodyNode.put("vmIp", vmIp);
            bodyNode.put("nonce", nonce);
            String bodyJson = objectMapper.writeValueAsString(bodyNode);

            String url = consumerProxyBaseUrl.replaceAll("/+$", "")
                    + "/cocos/computations/" + jobId + "/attestation";

            var request = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(bodyJson, JSON))
                    .build();

            try (var response = httpClient.execute(request)) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "(no body)";
                    return Result.failure("Attestation proxy returned HTTP " + response.code()
                            + " for VM " + vmIp + ": " + errorBody);
                }

                if (response.body() == null) {
                    return Result.failure("Attestation proxy returned empty body for VM " + vmIp);
                }

                JsonNode responseJson = objectMapper.readTree(response.body().string());
                JsonNode reportNode = responseJson.get("attestationReport");
                if (reportNode == null || reportNode.isNull()) {
                    return Result.failure("Attestation proxy response missing 'attestationReport' field");
                }

                byte[] reportBytes = Base64.getDecoder().decode(reportNode.asText());
                return Result.success(reportBytes);
            }
        } catch (Exception e) {
            return Result.failure("Failed to call attestation proxy for VM " + vmIp
                    + ": " + e.getMessage());
        }
    }
}
