# Changelog

All notable changes to the Cocos EDC Connector project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [0.3.0-SNAPSHOT] - 2026-07-29

### Added
- **Concurrent CVM Orchestration**: Support for orchestrating multiple Confidential VMs concurrently.
- **Local File-Based Asset Resolution**: Added support for resolving local assets directly from the filesystem.
- **Agent State Querying & Computation Termination**: Implemented gRPC-based agent lifecycle management including `CocosAgentConnectionRegistry`, `CocosAgentStateRegistry`, and `CocosAgentStopRegistry` along with corresponding REST endpoints.
- **Extended Test Coverage**: Added comprehensive unit tests for `DspRemoteAssetFetcher`.

### Changed
- **Remote Deployment Documentation**: Added guides for remote deployment and agent state querying/termination endpoints in `docs/user_guide.md`.

---

## [0.2.0-SNAPSHOT] - 2026-07-13

### Added
- **Unit and E2E Mock Tests**: Comprehensive test suite verifying the `ComputationOrchestratorImpl`, `CvmsGrpcServer`, `CocosCliServiceImpl`, and `DspRemoteAssetFetcher`.
- **`InMemory` Data Sink**: Registered `InMemoryDataSink` and `InMemoryDataSinkFactory` in `cocos-data-sink` to handle in-memory stream transfers.

### Changed / Resolved Gaps
- **Job ID Synchronization**: Decoupled agent orchestration from IP addresses by using Job ID for task synchronization.
- **Programmatic DSP Consumer Flow**: Replaced the `StubRemoteAssetFetcher` stub with a fully functioning `DspRemoteAssetFetcher` that resolves remote assets by programmatically calling Catalog, Contract Negotiation, and Transfer Process services.
- **Identity Hub Integration**: Implemented Attestation Credential Service client parsing logic inside `AttestationCredentialServiceClientImpl` communicating with the `/attestation-cred-service/parse` API.

---

## [0.1.0-SNAPSHOT] - 2026-07-02

### Added
- **`cocos-spi`**: Domain models and core service definitions (`ComputeManifest`, `ComputationJob`, `AlgorithmSpec`, `DatasetSpec`, `AssetSource`, `CocosCliService`, etc.).
- **`cocos-cli`**: Subprocess bridge orchestrating `cocos-cli` for enclave readiness, data upload, algo upload, attestation retrieval, and result collection.
- **`cocos-orchestrator`**: In-memory job store, host-side CVMS gRPC server implementing gRPC stream protocol, manifest serialization, and guest agent status callbacks.
- **`cocos-computation-api`**: REST management API endpoints to register computation jobs and fetch job statuses.
- **`cocos-attestation-credential-service`**: Extended Verifiable Presentation (VP) request service hooking into EDC catalog/negotiation/transfer phases.
- **`cocos-data-sink`**: Custom data plane transfer receiver that intercepts EDC transfers and uploads assets to the enclave.
- **Gradle Build Pipeline**: Complete multi-project build setup with Gradle distribution packaging tasks.
