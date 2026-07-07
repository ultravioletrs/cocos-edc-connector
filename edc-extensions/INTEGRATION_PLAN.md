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
* **Target File**: [StubRemoteAssetFetcher.java](extensions/cocos/cocos-orchestrator/src/main/java/org/eclipse/edc/connector/cocos/orchestrator/StubRemoteAssetFetcher.java)
* **Status**: Currently a stub returning `CompletableFuture.failedFuture(new UnsupportedOperationException())`.
* **Objective**: Resolve remote assets specified with `AssetSource.Type.REMOTE` in the computation manifest.
* **Required Flow to Build**:
  1. **Fetch Catalog**: Call the provider connector's DSP protocol URL (`providerConnectorUrl`) to retrieve the catalog. Search for the catalog offer matching the target `assetId`.
  2. **Contract Negotiation**: Initiate contract negotiation (`ContractNegotiation`) using the retrieved contract offer and its policies.
  3. **Transfer Process**: Once negotiation is complete and a contract agreement is reached, start a `TransferProcess` requesting the transfer of the asset.
  4. **Data Reception**: Pipe the incoming data plane stream from the provider's data plane, collect the data as bytes, and return them via `CompletableFuture<byte[]>`.

---

### Gap B: Identity Hub Presentation APIs (Resolved)
* **Target File**: [AttestationCredentialServiceClientImpl.java](extensions/cocos/cocos-attestation-credential-service/src/main/java/org/eclipse/edc/connector/cocos/attestation/AttestationCredentialServiceClientImpl.java)
* **Status**: **RESOLVED**. Implemented to communicate with the Attestation Credential Service at `/attestation-cred-service/parse`.
* **Objective**: Interface with the UMU Attestation Credential Service APIs to fetch Verifiable Presentations.
* **Required Flow**:
  1. **POST Presentation**: Send a HTTP POST request to `{baseUrl}/attestation-cred-service/parse` containing the Status JWT (obtained from Trustee KBS). Return the Verifiable Presentation (VP) container list as `Result<List<VerifiablePresentationContainer>>`.

---

### Gap C: Unit Testing Coverage
* **Target Location**: `src/test/java` directories
* **Status**: Partially Addressed (JUnit 5 + Mockito + AssertJ test framework configured and integrated).
* **TCK Runtime Note**: `cocos-attestation-credential-service` requires `decentralized-claims-spi` from the EDC DCP/Identity Hub module. The bare TCK control plane does **not** ship this SPI. When deploying to the TCK connector for E2E testing, exclude this JAR from the runtime lib directory. It should only be deployed against a full DCP-capable connector.
* **Completed Tests**:
  - [AttestationBackedPresentationRequestServiceTest](extensions/cocos/cocos-attestation-credential-service/src/test/java/org/eclipse/edc/connector/cocos/attestation/AttestationBackedPresentationRequestServiceTest.java): Verifies the authentication and verification sequences against the Trustee KBS.
* **Remaining Areas for Test Coverage**:
  1. **`ComputationOrchestratorImpl`**: Test the orchestration lifecycle state machine (start agents, upload assets, wait for completion, collect results).
  2. **`CvmsGrpcServer`**: Test the bidirectional gRPC stream handler (receiving enclaves registration, sending manifest, forwarding logs).
  3. **`CocosCliServiceImpl`**: Mock subprocess execution and verify CLI argument construction for different commands.

---

## ✅ 3. Implemented: Event-Driven Orchestration (CVMS gRPC Stream)

The computation orchestrator now uses the **CVMS bidirectional gRPC stream** to synchronize execution rather than polling.

### Design

The `CvmsGrpcServer` maintains a persistent bidirectional gRPC stream with each connected agent. The agent pushes events (`AgentLog`, `AgentEvent`, `RunResponse`) over this stream in real time. The previous implementation discarded `RunResponse` without signaling the orchestrator thread.

### Components

* **[CocosAgentCompletionRegistry](extensions/cocos/cocos-spi/src/main/java/org/eclipse/edc/connector/cocos/spi/CocosAgentCompletionRegistry.java)** *(NEW)*:
  A static `ConcurrentHashMap<String, CompletableFuture<Void>>` keyed by VM IP. Provides three operations:
  - `getOrCreate(vmIp)` — called by the orchestrator thread before dispatching the manifest; returns a future it will block on.
  - `complete(vmIp)` — called by the gRPC server thread when `RunResponse` arrives with no error.
  - `fail(vmIp, error)` — called by the gRPC server thread when `RunResponse` contains an error string.

* **[CvmsGrpcServer](extensions/cocos/cocos-orchestrator/src/main/java/org/eclipse/edc/connector/cocos/orchestrator/CvmsGrpcServer.java)** *(MODIFIED)*:
  `onNext(ClientStreamMessage)` now signals the registry when `RunResponse` is received:
  ```java
  } else if (value.hasRunRes()) {
      RunResponse res = value.getRunRes();
      if (res.getError() != null && !res.getError().isEmpty()) {
          CocosAgentCompletionRegistry.fail(clientIp, res.getError());
      } else {
          CocosAgentCompletionRegistry.complete(clientIp);
      }
  }
  ```

* **[ComputationOrchestratorImpl](extensions/cocos/cocos-orchestrator/src/main/java/org/eclipse/edc/connector/cocos/orchestrator/ComputationOrchestratorImpl.java)** *(MODIFIED)*:
  After uploading assets, the orchestrator thread blocks on the completion future (with a 10-minute timeout) before fetching results exactly once:
  ```java
  var completionFuture = CocosAgentCompletionRegistry.getOrCreate(unit.getVmIp());
  completionFuture.get(10, TimeUnit.MINUTES);
  // Only reached after RunResponse event — no polling needed
  cliService.fetchResult(unit.getVmIp());
  ```

### Verified Execution
The full E2E run confirmed the correct event-driven sequencing in the connector logs:
```
INFO Registered manifest in CVMS registry for VM IP: 192.168.100.15
INFO Sending computation run request to agent 192.168.100.15
INFO Agent reported run complete successfully
```
Status API confirmed completion:
```json
{"status":"COMPLETED","jobId":"test-job-015"}
```
