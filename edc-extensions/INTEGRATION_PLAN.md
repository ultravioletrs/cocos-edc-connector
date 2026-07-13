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

### Gap A: DSP Consumer Flow (Resolved)
* **Target File**: [DspRemoteAssetFetcher.java](extensions/cocos/cocos-orchestrator/src/main/java/org/eclipse/edc/connector/cocos/orchestrator/DspRemoteAssetFetcher.java)
* **Status**: **RESOLVED**. Implemented full programmatic DSP consumer sequence.
* **Objective**: Resolve remote assets specified with `AssetSource.Type.REMOTE` in the computation manifest.
* **Flow Built**:
  1. **Fetch Catalog**: Calls `CatalogService.requestCatalog(...)` to retrieve the provider's DCAT catalog as JSON-LD, recursively searches for the dataset matching the requested asset ID, and extracts the policy offer ID.
  2. **Contract Negotiation**: Initiates `ContractRequest` via `ContractNegotiationService.initiateNegotiation(...)`, polling the status until it is finalized.
  3. **Transfer Process**: Initiates transfer specifying the `"InMemory"` destination type and a unique buffer ID.
  4. **Data Reception**: The custom [InMemoryDataSink](extensions/cocos/cocos-data-sink/src/main/java/org/eclipse/edc/connector/cocos/datasink/InMemoryDataSink.java) intercepts the stream, extracts raw bytes, and completes the transaction using the `InMemoryBufferRegistry` buffer.

---

### Gap B: Identity Hub Presentation APIs (Resolved)
* **Target File**: [AttestationCredentialServiceClientImpl.java](extensions/cocos/cocos-attestation-credential-service/src/main/java/org/eclipse/edc/connector/cocos/attestation/AttestationCredentialServiceClientImpl.java)
* **Status**: **RESOLVED**. Implemented to communicate with the Attestation Credential Service at `/attestation-cred-service/parse`.
* **Objective**: Interface with the UMU Attestation Credential Service APIs to fetch Verifiable Presentations.
* **Required Flow**:
  1. **POST Presentation**: Send a HTTP POST request to `{baseUrl}/attestation-cred-service/parse` containing the Status JWT (obtained from Trustee KBS). Return the Verifiable Presentation (VP) container list as `Result<List<VerifiablePresentationContainer>>`.

---

### Gap C: Unit & E2E Mock Testing (Resolved)
* **Target Location**: `src/test/java` directories
* **Status**: **RESOLVED**. Configured JUnit 5 + Mockito + AssertJ. Built a comprehensive test suite to mock all external UMU and provider components (Identity Hub, Catalog, Negotiation, and Data Transfer).
* **Completed Test Suite**:
  - [DspRemoteAssetFetcherTest](extensions/cocos/cocos-orchestrator/src/test/java/org/eclipse/edc/connector/cocos/orchestrator/DspRemoteAssetFetcherTest.java): Mocks the catalog service, contract negotiation states, and transfer process to simulate a complete E2E download using the in-memory sink registry.
  - [ComputationOrchestratorImplTest](extensions/cocos/cocos-orchestrator/src/test/java/org/eclipse/edc/connector/cocos/orchestrator/ComputationOrchestratorImplTest.java): Simulates the asynchronous agent launch, readiness checks, file uploads, and results retrieval.
  - [CvmsGrpcServerTest](extensions/cocos/cocos-orchestrator/src/test/java/org/eclipse/edc/connector/cocos/orchestrator/CvmsGrpcServerTest.java): Tests the bidirectional gRPC stream, validating manifest transfers and status event callbacks from simulated agents.
  - [CocosCliServiceImplTest](extensions/cocos/cocos-cli/src/test/java/org/eclipse/edc/connector/cocos/cli/CocosCliServiceImplTest.java): Simulates agent health socket probes and verifies subprocess execution commands.

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
