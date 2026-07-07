# CocosAI Attestation Credential Service Extension

This extension integrates hardware attestation verification with the Eclipse EDC decentralized identity framework. It registers an `AttestationBackedPresentationRequestService` to validate enclaves before releasing catalog assets, negotiating contracts, or initiating transfers.

The communication is routed through the **Attestation Credential Service** which verifies the attestation evidence and requests credential issuance from the control plane and issuer.

---

## ⚙️ Configuration

The extension is configured using the following system properties:

| Property Key | Type | Description |
|---|---|---|
| `cocos.attestation.credential.service.url` | **Required** (or fallback) | Base URL of the CocosAI Attestation Credential Service. |
| `cocos.identity.hub.url` | **Deprecated** | Base URL of the Cocos Identity Hub (CW) service. Used as a fallback if `cocos.attestation.credential.service.url` is not set. |
| `cocos.kbs.url` | Optional | Base URL of the Trustee Key Broker Service (KBS). Default: `http://localhost:8080` |
| `cocos.tee.type` | Optional | TEE Platform type: `snp` or `sample`. Default: `snp` |

---

## 🧱 Key Components

1. **`AttestationBackedPresentationRequestService`**:
   - Implements EDC's `PresentationRequestService`.
   - Intercepts requests for credentials and initiates the attestation-backed credential flow.
   
2. **`ProviderAttestationPresentationService`**:
   - Implements provider-side presentation requesting.

3. **`AttestationCredentialServiceClientImpl`**:
   - HTTP client communicating with the Attestation Credential Service.

---

## 🔌 API Integration Guide (for UMU Team)

The **AttestationCredentialServiceClient** sends a request to the Attestation Credential Service to issue Verifiable Presentations using the Trustee KBS verification results.

### 1. Request Presentation Endpoint

- **HTTP Method**: `POST`
- **Path**: `/attestation-cred-service/parse`
- **Headers**:
  - `Content-Type: application/json`

#### Request Payload
The request body contains the raw verification status JWT returned by the Trustee KBS:
```json
{
  "token": "eyJhbGciOiJSUzI1Ni..."
}
```

### 2. Expected Response Payload
The service must return a JSON response containing the success status and the issued Verifiable Presentation (VP) in the `ssi_wallet_response` field:

```json
{
  "status": "success",
  "message": "AttestationCredential successfully issued.",
  "attestation_status": "passed",
  "ssi_wallet_response": {
    "id": "urn:uuid:895fe868-80f4-42f8-9588-e9ad7be7c0db",
    "type": ["VerifiablePresentation"],
    "verifiableCredential": [
      {
        "id": "urn:uuid:credential-id",
        "type": ["VerifiableCredential", "AttestationCredential"],
        "issuer": "did:web:issuer-domain",
        "credentialSubject": {
          "id": "did:web:subject-domain",
          "attestation_status": "passed"
        }
      }
    ]
  }
}
```

*Note: The `ssi_wallet_response` field can also be a raw JWT string (e.g., `"eyJhbGciOi..."` representing the Verifiable Presentation JWT) and will be correctly parsed by the client.*

---

## 🧪 Running Unit Tests
To run unit tests for this extension, execute:
```bash
JAVA_HOME=/opt/android-studio/jbr/ ./gradlew test
```
