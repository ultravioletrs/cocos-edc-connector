# CocosAI Attestation Credential Service Extension

This extension integrates hardware attestation verification with the Eclipse EDC decentralized identity framework. It registers an `AttestationBackedPresentationRequestService` to validate enclaves before releasing catalog assets, negotiating contracts, or initiating transfers.

---

## ⚙️ Configuration

This extension requires the following property to be set:

| Property Key | Type | Description |
|---|---|---|
| `cocos.identity.hub.url` | **Required** | Base URL of the Cocos Identity Hub (CW) service. |

---

## 🧱 Key Components

1. **`AttestationBackedPresentationRequestService`**:
   - Implements EDC's `PresentationRequestService`.
   - Intercepts requests for credentials and initiates an attestation-backed credential flow.

2. **`IdentityHubClientImpl`**:
   - Web client interface communicating with the Cocos Identity Hub (CW).

---

## ⚠️ Unresolved Implementation Gaps

* **`IdentityHubClientImpl`**:
  - Both `getNonce` and `getVerifiablePresentation` methods currently return `Result.failure()`.
  - These must be implemented once the partner team finalises the Cocos Identity Hub (CW) API.
  - When implemented:
    * `getNonce` will fetch a nonce from `{identityHubBaseUrl}/nonce`.
    * `getVerifiablePresentation` will submit the attestation quote to `{identityHubBaseUrl}/presentations` and retrieve the Verifiable Presentation (VP).
