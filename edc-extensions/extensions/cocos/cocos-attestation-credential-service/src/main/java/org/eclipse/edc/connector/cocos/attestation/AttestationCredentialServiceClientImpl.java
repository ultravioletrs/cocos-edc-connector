package org.eclipse.edc.connector.cocos.attestation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.eclipse.edc.http.spi.EdcHttpClient;
import org.eclipse.edc.iam.verifiablecredentials.spi.model.CredentialFormat;
import org.eclipse.edc.iam.verifiablecredentials.spi.model.VerifiablePresentation;
import org.eclipse.edc.iam.verifiablecredentials.spi.model.VerifiablePresentationContainer;
import org.eclipse.edc.spi.result.Result;

import java.util.List;

/**
 * Implementation of {@link AttestationCredentialServiceClient}.
 * Communicates with the service at the endpoint {@code /attestation-cred-service/parse}.
 */
public class AttestationCredentialServiceClientImpl implements AttestationCredentialServiceClient {

    private static final MediaType JSON = MediaType.get("application/json");

    private final EdcHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String serviceBaseUrl;

    public AttestationCredentialServiceClientImpl(EdcHttpClient httpClient, ObjectMapper objectMapper, String serviceBaseUrl) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.serviceBaseUrl = serviceBaseUrl.replaceAll("/+$", "");
    }

    @Override
    public Result<List<VerifiablePresentationContainer>> requestPresentation(
            String participantContextId,
            String ownDid,
            String counterPartyDid,
            String counterPartyToken,
            List<String> scopes,
            String attestationJwt,
            String vmIp) {

        try {
            // Construct request payload to match the expected Trustee verification output format
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("token", attestationJwt);
            String requestBodyJson = objectMapper.writeValueAsString(requestBody);

            String url = serviceBaseUrl + "/attestation-cred-service/parse";

            var request = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(requestBodyJson, JSON))
                    .build();

            try (var response = httpClient.execute(request)) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "(no body)";
                    return Result.failure("Attestation Credential Service returned HTTP " + response.code() + ": " + errorBody);
                }

                if (response.body() == null) {
                    return Result.failure("Attestation Credential Service returned empty body");
                }

                JsonNode responseJson = objectMapper.readTree(response.body().string());
                JsonNode statusNode = responseJson.get("status");
                if (statusNode == null || !"success".equalsIgnoreCase(statusNode.asText())) {
                    String msg = responseJson.has("message") ? responseJson.get("message").asText() : "unknown error";
                    return Result.failure("Attestation Credential Service failed: " + msg);
                }

                JsonNode ssiNode = responseJson.get("ssi_wallet_response");
                if (ssiNode == null || ssiNode.isNull()) {
                    return Result.failure("Attestation Credential Service response missing 'ssi_wallet_response'");
                }

                // Extract the verifiable presentation representation
                String rawVp;
                if (ssiNode.isTextual()) {
                    rawVp = ssiNode.asText();
                } else {
                    rawVp = objectMapper.writeValueAsString(ssiNode);
                }

                // Construct VerifiablePresentation container required by Eclipse EDC identity framework
                var vp = VerifiablePresentation.Builder.newInstance()
                        .id(ssiNode.has("id") ? ssiNode.get("id").asText() : "attestation-vp")
                        .type("VerifiablePresentation")
                        .build();

                var container = new VerifiablePresentationContainer(rawVp, CredentialFormat.VC1_0_JWT, vp);
                return Result.success(List.of(container));
            }

        } catch (Exception e) {
            return Result.failure("Failed to request credential from Attestation Credential Service: " + e.getMessage());
        }
    }
}
