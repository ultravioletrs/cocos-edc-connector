# Cocos EDC Connector — Integration Plan & Implementation Gaps

This document describes how to integrate the Cocos EDC extensions with an upstream Eclipse EDC Connector deployment, outlines the current packaging model, and details the implementation gaps that need to be completed by development partners.

---

## 📐 1. Integration & Architecture Model

The Cocos extensions are designed as modular, standalone `ServiceExtension` libraries. They plug into an upstream Eclipse EDC Connector runtime (at tag [**`v0.18.0`**](https://github.com/eclipse-edc/Connector/tree/v0.18.0)) by adding the compiled JARs to the connector's classpath.

At startup, the Eclipse EDC Bootloader dynamically discovers these extensions via Java `ServiceLoader` manifests located in `META-INF/services/org.eclipse.edc.spi.system.ServiceExtension` inside each module's JAR.

### Gradle Packaging Tasks
Inside the `edc-extensions` directory, the following Gradle tasks coordinate compiling and bundling:
* **`packageUpstreamDropins`**: A `Sync` task that compiles all 6 Cocos sub-modules, resolves non-standard external dependencies (excluding core libraries like Jackson or EDC dependencies which are provided by the upstream runtime), and copies everything to `build/upstream-dropins/libs/`. It also bundles `README.md` and this `INTEGRATION_PLAN.md` into the distribution root.
* **`distZip`**: Packs the output of `packageUpstreamDropins` into a standard zip archive under `build/distributions/cocos-edc-extensions-<version>.zip`.

---

## ❌ 2. Unresolved Implementation Gaps

Other development partners must implement the following components before the Cocos connector can be fully operational.

### Gap A: DSP Consumer Flow (`StubRemoteAssetFetcher`)
* **Target File**: [StubRemoteAssetFetcher.java](file:///home/sammyk/Documents/cocos-edc-connector/edc-extensions/extensions/cocos/cocos-orchestrator/src/main/java/org/eclipse/edc/connector/cocos/orchestrator/StubRemoteAssetFetcher.java)
* **Status**: Currently a stub returning `CompletableFuture.failedFuture(new UnsupportedOperationException())`.
* **Objective**: Resolve remote assets specified with `AssetSource.Type.REMOTE` in the computation manifest.
* **Required Flow to Build**:
  1. **Fetch Catalog**: Call the provider connector's DSP protocol URL (`providerConnectorUrl`) to retrieve the catalog. Search for the catalog offer matching the target `assetId`.
  2. **Contract Negotiation**: Initiate contract negotiation (`ContractNegotiation`) using the retrieved contract offer and its policies.
  3. **Transfer Process**: Once negotiation is complete and a contract agreement is reached, start a `TransferProcess` requesting the transfer of the asset.
  4. **Data Reception**: Pipe the incoming data plane stream from the provider's data plane, collect the data as bytes, and return them via `CompletableFuture<byte[]>`.

---

### Gap B: Identity Hub Nonce & Presentation APIs (`IdentityHubClientImpl`)
* **Target File**: [IdentityHubClientImpl.java](file:///home/sammyk/Documents/cocos-edc-connector/edc-extensions/extensions/cocos/cocos-attestation-credential-service/src/main/java/org/eclipse/edc/connector/cocos/attestation/IdentityHubClientImpl.java)
* **Status**: Both methods (`getNonce` and `getVerifiablePresentation`) return `Result.failure("not yet implemented")`.
* **Objective**: Interface with the Cocos Identity Hub (CW) APIs to validate hardware attestation reports and fetch credentials.
* **Required Flow to Build**:
  1. **GET Nonce**: Send a HTTP GET request to `{identityHubBaseUrl}/nonce`. Extract the nonce string from the response and return as `Result<String>`.
  2. **POST Presentation**: Send a HTTP POST request to `{identityHubBaseUrl}/presentations` containing the hardware attestation report/quote. Return the Verifiable Presentation (VP) token/string as `Result<String>`.

---

### Gap C: Unit Testing Coverage
* **Target Location**: No `src/test/java` directories currently exist in the codebase.
* **Objective**: Set up JUnit 5 + Mockito test suites.
* **Prioritized Areas for Test Coverage**:
  1. **`ComputationOrchestratorImpl`**: Test the orchestration lifecycle state machine (start agents, upload assets, wait for completion, collect results).
  2. **`CvmsGrpcServer`**: Test the bidirectional gRPC stream handler (receiving enclaves registration, sending manifest, forwarding logs).
  3. **`CocosCliServiceImpl`**: Mock subprocess execution and verify CLI argument construction for different commands.
  4. **`AttestationBackedPresentationRequestService`**: Mock Identity Hub responses and verify attestation-backed credential flow.
