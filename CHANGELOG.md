# Changelog

All notable changes to the Cocos EDC Connector project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [0.2.0-SNAPSHOT] - 2026-07-13

### Added
- **Unit and E2E Mock Tests**: Comprehensive test suite verifying the `ComputationOrchestratorImpl`, `CvmsGrpcServer`, `CocosCliServiceImpl`, and `DspRemoteAssetFetcher`.
- **`InMemory` Data Sink**: Registered `InMemoryDataSink` and `InMemoryDataSinkFactory` in `cocos-data-sink` to handle in-memory stream transfers.

### Changed / Resolved Gaps
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
