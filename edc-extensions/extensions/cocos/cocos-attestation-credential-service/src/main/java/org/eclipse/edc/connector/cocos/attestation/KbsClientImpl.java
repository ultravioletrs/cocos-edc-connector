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

public class KbsClientImpl implements KbsClient {

    private static final MediaType JSON = MediaType.get("application/json");

    private final EdcHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String kbsUrl;

    public KbsClientImpl(EdcHttpClient httpClient, ObjectMapper objectMapper, String kbsUrl) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.kbsUrl = kbsUrl.replaceAll("/+$", "");
    }

    @Override
    public Result<KbsAuthResult> authenticate(String teeType) {
        try {
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("version", "0.4.0");
            requestBody.put("tee", teeType.toLowerCase());
            requestBody.put("extra-params", "");

            String bodyJson = objectMapper.writeValueAsString(requestBody);
            String url = kbsUrl + "/kbs/v0/auth";

            var request = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(bodyJson, JSON))
                    .build();

            try (var response = httpClient.execute(request)) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "(no body)";
                    return Result.failure("KBS auth returned HTTP " + response.code() + ": " + errorBody);
                }

                if (response.body() == null) {
                    return Result.failure("KBS auth returned empty body");
                }

                JsonNode responseJson = objectMapper.readTree(response.body().string());
                JsonNode nonceNode = responseJson.get("nonce");
                if (nonceNode == null || nonceNode.isNull()) {
                    return Result.failure("KBS auth response missing 'nonce' field");
                }

                String cookieHeader = response.header("Set-Cookie");
                String sessionCookie = null;
                if (cookieHeader != null) {
                    for (String part : cookieHeader.split(";")) {
                        part = part.trim();
                        if (part.startsWith("KBS_SESSION_ID=")) {
                            sessionCookie = part;
                            break;
                        }
                    }
                }

                if (sessionCookie == null) {
                    return Result.failure("KBS auth response missing 'KBS_SESSION_ID' cookie");
                }

                return Result.success(new KbsAuthResult(nonceNode.asText(), sessionCookie));
            }
        } catch (Exception e) {
            return Result.failure("Failed to authenticate with Trustee KBS: " + e.getMessage());
        }
    }

    @Override
    public Result<String> verify(byte[] attestationReport, String nonce, String sessionCookie, String teeType) {
        try {
            ObjectNode root = objectMapper.createObjectNode();

            ObjectNode teeEvidence = objectMapper.createObjectNode();
            if ("sample".equalsIgnoreCase(teeType)) {
                ObjectNode primaryEvidence = objectMapper.createObjectNode();
                primaryEvidence.put("svn", "1");
                primaryEvidence.put("report_data", Base64.getEncoder().encodeToString(attestationReport));
                teeEvidence.set("primary-evidence", primaryEvidence);
            } else {
                ObjectNode primaryEvidence = objectMapper.createObjectNode();
                primaryEvidence.set("attestation_report", objectMapper.valueToTree(AttestationReportParser.parseSnpReport(attestationReport)));
                primaryEvidence.set("cert_chain", null);
                teeEvidence.set("primary-evidence", primaryEvidence);
            }
            teeEvidence.put("additional-evidence", "");
            root.set("tee-evidence", teeEvidence);

            ObjectNode runtimeData = objectMapper.createObjectNode();
            runtimeData.put("nonce", nonce);

            ObjectNode teePubkey = objectMapper.createObjectNode();
            teePubkey.put("kty", "RSA");
            teePubkey.put("alg", "RS384");
            teePubkey.put("n", "vrjOfz9Ccdgx5nQudyhdoR17V-IubWMeOZCwX_jj0hgAsz2J_pqYW08PLbK_PdiVGKPrqzmDIsLI7sA25VEnHU1uCLNwBuUiCO11_-7dYbsr4iJmG0Qu2j8DsVyT1azpJC_NG84Ty5KKthuCaPod7iI7w0LK9orSMhBEwwZDCxTWq4aYWAchc8t-emd9qOvWtVMDC2BXksRngh6X5bUYLy6AyHKvj-nUy1wgzjYQDwHMTplCoLtU-o-8SNnZ1tmRoGE9uJkBLdh5gFENabWnU5m1ZqZPdwS-qo-meMvVfJb6jJVWRpl2SUtCnYG2C32qvbWbjZ_jBPD5eunqsIo1vQ");
            teePubkey.put("e", "AQAB");
            runtimeData.set("tee-pubkey", teePubkey);

            root.set("runtime-data", runtimeData);

            String bodyJson = objectMapper.writeValueAsString(root);
            String url = kbsUrl + "/kbs/v0/attest";

            var request = new Request.Builder()
                    .url(url)
                    .addHeader("Cookie", sessionCookie)
                    .post(RequestBody.create(bodyJson, JSON))
                    .build();

            try (var response = httpClient.execute(request)) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "(no body)";
                    return Result.failure("KBS attest returned HTTP " + response.code() + ": " + errorBody);
                }

                if (response.body() == null) {
                    return Result.failure("KBS attest returned empty body");
                }

                JsonNode responseJson = objectMapper.readTree(response.body().string());
                JsonNode tokenNode = responseJson.get("token");
                if (tokenNode == null || tokenNode.isNull()) {
                    return Result.failure("KBS attest response missing 'token' field");
                }

                return Result.success(tokenNode.asText());
            }
        } catch (Exception e) {
            return Result.failure("Failed to verify attestation report with Trustee KBS: " + e.getMessage());
        }
    }
}
