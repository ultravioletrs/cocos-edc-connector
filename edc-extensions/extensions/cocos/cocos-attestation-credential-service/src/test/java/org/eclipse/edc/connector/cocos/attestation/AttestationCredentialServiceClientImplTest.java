package org.eclipse.edc.connector.cocos.attestation;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.eclipse.edc.http.spi.EdcHttpClient;
import org.eclipse.edc.iam.verifiablecredentials.spi.model.CredentialFormat;
import org.eclipse.edc.spi.result.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AttestationCredentialServiceClientImplTest {

    private final EdcHttpClient httpClient = mock(EdcHttpClient.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private AttestationCredentialServiceClientImpl client;

    @BeforeEach
    void setUp() {
        client = new AttestationCredentialServiceClientImpl(httpClient, objectMapper, "http://localhost:8080/test");
    }

    @Test
    void requestPresentation_success_withJsonObjectResponse() throws IOException {
        String responseBody = "{\n" +
                "  \"status\": \"success\",\n" +
                "  \"message\": \"AttestationCredential successfully issued.\",\n" +
                "  \"attestation_status\": \"passed\",\n" +
                "  \"ssi_wallet_response\": {\n" +
                "    \"id\": \"my-vp-id\",\n" +
                "    \"type\": [\"VerifiablePresentation\"]\n" +
                "  }\n" +
                "}";

        Response response = createMockResponse(200, responseBody);
        when(httpClient.execute(any(Request.class))).thenReturn(response);

        var result = client.requestPresentation(
                "context", "own-did", "counter-did", "counter-token", List.of("scope"), "status-jwt", "192.168.1.100");

        assertThat(result.succeeded()).isTrue();
        var containers = result.getContent();
        assertThat(containers).hasSize(1);
        var container = containers.get(0);
        assertThat(container.format()).isEqualTo(CredentialFormat.VC1_0_JWT);
        assertThat(container.presentation().getId()).isEqualTo("my-vp-id");
        assertThat(container.rawVp()).contains("my-vp-id");
    }

    @Test
    void requestPresentation_success_withTextualVpResponse() throws IOException {
        String responseBody = "{\n" +
                "  \"status\": \"success\",\n" +
                "  \"message\": \"AttestationCredential successfully issued.\",\n" +
                "  \"attestation_status\": \"passed\",\n" +
                "  \"ssi_wallet_response\": \"eyJhbGciOiJSUzI1Ni...\"\n" +
                "}";

        Response response = createMockResponse(200, responseBody);
        when(httpClient.execute(any(Request.class))).thenReturn(response);

        var result = client.requestPresentation(
                "context", "own-did", "counter-did", "counter-token", List.of("scope"), "status-jwt", "192.168.1.100");

        assertThat(result.succeeded()).isTrue();
        var containers = result.getContent();
        assertThat(containers).hasSize(1);
        var container = containers.get(0);
        assertThat(container.format()).isEqualTo(CredentialFormat.VC1_0_JWT);
        assertThat(container.rawVp()).isEqualTo("eyJhbGciOiJSUzI1Ni...");
        assertThat(container.presentation().getId()).isEqualTo("attestation-vp");
    }

    @Test
    void requestPresentation_failure_non200HttpStatus() throws IOException {
        Response response = createMockResponse(500, "Internal Server Error");
        when(httpClient.execute(any(Request.class))).thenReturn(response);

        var result = client.requestPresentation(
                "context", "own-did", "counter-did", "counter-token", List.of("scope"), "status-jwt", "192.168.1.100");

        assertThat(result.failed()).isTrue();
        assertThat(result.getFailureDetail()).contains("returned HTTP 500");
    }

    @Test
    void requestPresentation_failure_serviceLevelFailure() throws IOException {
        String responseBody = "{\n" +
                "  \"status\": \"error\",\n" +
                "  \"message\": \"Attestation verification failed at Trustee.\"\n" +
                "}";

        Response response = createMockResponse(200, responseBody);
        when(httpClient.execute(any(Request.class))).thenReturn(response);

        var result = client.requestPresentation(
                "context", "own-did", "counter-did", "counter-token", List.of("scope"), "status-jwt", "192.168.1.100");

        assertThat(result.failed()).isTrue();
        assertThat(result.getFailureDetail()).contains("Service failed: Attestation verification failed at Trustee.");
    }

    @Test
    void requestPresentation_failure_missingWalletResponse() throws IOException {
        String responseBody = "{\n" +
                "  \"status\": \"success\",\n" +
                "  \"message\": \"AttestationCredential successfully issued.\"\n" +
                "}";

        Response response = createMockResponse(200, responseBody);
        when(httpClient.execute(any(Request.class))).thenReturn(response);

        var result = client.requestPresentation(
                "context", "own-did", "counter-did", "counter-token", List.of("scope"), "status-jwt", "192.168.1.100");

        assertThat(result.failed()).isTrue();
        assertThat(result.getFailureDetail()).contains("missing 'ssi_wallet_response'");
    }

    @Test
    void requestPresentation_failure_malformedJson() throws IOException {
        Response response = createMockResponse(200, "{ malformed json");
        when(httpClient.execute(any(Request.class))).thenReturn(response);

        var result = client.requestPresentation(
                "context", "own-did", "counter-did", "counter-token", List.of("scope"), "status-jwt", "192.168.1.100");

        assertThat(result.failed()).isTrue();
        assertThat(result.getFailureDetail()).contains("Failed to request credential");
    }

    private Response createMockResponse(int code, String body) {
        Request request = new Request.Builder().url("http://localhost").build();
        return new Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(code == 200 ? "OK" : "Error")
                .body(ResponseBody.create(body, MediaType.get("application/json")))
                .build();
    }
}
