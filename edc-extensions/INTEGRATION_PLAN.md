# Cocos EDC Integration Plan

## Scope

This directory contains the Cocos-owned Eclipse EDC extensions needed to connect Cocos to external control-plane and trust services while keeping the integration code in the `cocos` repository.

In scope:

- Cocos runtime integration with Eclipse EDC
- Cocos-specific EDC extensions
- public integration contracts and setup guides
- mocked and hybrid test setups

Out of scope:

- external VM provisioning systems (handled by Tower)
- external provider deployments
- external Identity Hub and ledger implementations

## Orchestration & Trust Assumptions

- **VM Provisioning via Tower**: The creation, configuration, and destruction of Confidential VMs (CVMs) using AMD SEV-SNP or Intel TDX enclaves is handled entirely by the **Tower Connector** / Tower Platform (Phase 1 of execution).
- **Cloud-Init Agent Configuration**: Tower configures the running `cocos-agent` (including its gRPC ports, server CA credentials, and outbound target address back to the connector) via **cloud-init** on VM boot.
- **Exclusion of cocos-manager**: The `cocos-manager` host component is not part of this integration. The Cocos EDC Extensions assume CVM enclaves are pre-provisioned and active before a computation is run, meaning the connector only interacts directly with the `cocos-agent` inside the VM.
- **User Authentication via OID4VP**: User authentication for dashboard portals (e.g. TITAN Dashboard) utilizing OpenID for Verifiable Presentations (OID4VP) to exchange a `MembershipCredential` for an OIDC Token is out-of-scope for Cocos AI and is managed entirely by mocked or partner-provided systems (TITAN SSI Bridge, Keycloak Custom Auth SPI, UMU Wallet, and UMU Verifier).

## Current State

The extension structure is in place and has been moved out of the Connector fork into this repository.

Available now:

- standalone Cocos EDC extension modules
- computation API and orchestration skeleton
- Cocos VM data sink
- attestation-backed credential hook
- real KBS-backed testing on the Cocos side

Missing for end-to-end operation:

- real Connector-to-Cocos CLI bridge
- real DSP consumer flow for remote assets
- real Identity Hub client integration
- dedicated tests for the moved extension project

## Modules

- `extensions/cocos/cocos-spi`
- `extensions/cocos/cocos-cli`
- `extensions/cocos/cocos-computation-api`
- `extensions/cocos/cocos-orchestrator`
- `extensions/cocos/cocos-attestation-credential-service`
- `extensions/cocos/cocos-data-sink`

## External Dependencies

Preferred validation order:

1. real KBS
2. real Identity Hub when available
3. mocks for remaining unavailable external systems

Practical rule:

- KBS can already be tested for real.
- Identity Hub should target Eclipse EDC IdentityHub when available and fall back to mocks otherwise.
- Provisioning and partner provider endpoints should be treated as external integrations.

## What We Need To Do

### 1. Publish stable integration contracts

- document the computation request contract
- document the callback contract
- document expected behavior for mocked external services

Outcome:

- external integrators can work against Cocos without reading the source tree

### 2. Add a testable standalone extension project

- add unit tests for orchestration, API, data sink, and attestation flow
- add shared test fixtures and mock HTTP services
- keep support for real KBS in environment-gated tests

Outcome:

- the integration can evolve safely outside the Connector fork

### 3. Implement the real CLI bridge

- map extension operations to `cocos-cli`
- implement agent start, uploads, attestation retrieval, and result retrieval
- add timeout and error handling

Outcome:

- an upstream EDC runtime can drive real Cocos VMs

### 4. Implement remote asset consumption

- replace the stub remote asset fetcher
- support catalog, negotiation, transfer, and payload retrieval
- keep provider-side dependencies mockable

Outcome:

- datasets and algorithms can be pulled through EDC and delivered into Cocos VMs

### 5. Implement the Identity Hub adapter

- finalize the internal adapter contract
- implement nonce and presentation requests against IdentityHub
- keep the flow testable with mocks

Outcome:

- attestation-backed credentials can be used in the DSP flow

### 6. Harden runtime behavior

- complete job lifecycle states
- improve structured logging and diagnostics
- publish a mock-first setup guide and a hybrid setup guide

Outcome:

- the integration is usable by external adopters and easier to troubleshoot

### 7. Implement the CVMS Server in Java (Connector-Side)

- define the CVMS gRPC service (based on `cvms.proto` from the `cocos-ai` repository) inside the Java codebase using `grpc-java`
- configure the gRPC server (port 7002) to accept outbound connections from enclaves' agents
- implement chunk-based streaming of the computation manifest over the active `Process` connection when `CocosCliServiceImpl.startAgent(...)` is called
- capture and route agent logs and events from the stream to the EDC monitor / structured logger

Outcome:

- the connector can bootstrap the agent and stream manifestations without using third-party sidecars

### 8. Implement KBS Decryption for Directly Uploaded Assets (Agent-Side)

- modify `/home/sammyk/Documents/cocos-ai/agent/service.go` to intercept directly uploaded algorithms (`Algo`) and datasets (`Data`)
- retrieve decryption keys from the KBS (via the enclaves' Attestation Agent / `getKeyFromKBS`) if the manifest marks them as encrypted
- decrypt incoming payloads inside the TEE enclave using AES-256-GCM before writing them to the sandbox directory

Outcome:

- direct uploads transferred via the EDC Data Plane (Model A) support hardware-attested KBS key retrieval and decryption inside the CVM

## Recommended Execution Order

1. contracts and public docs
2. tests and fixtures
3. CLI bridge
4. Go Agent direct-upload decryption modification
5. Java CVMS Server implementation
6. DSP consumer flow
7. Identity Hub adapter
8. hybrid and partner-facing setup guides

## Short-Term Next Steps

1. verify upstream EDC artifact coordinates and Java 17+ build baseline
2. add the first standalone tests for orchestrator and computation API
3. define the `cocos-cli` command mapping used by the extension modules
4. design the CVMS gRPC server extension module skeleton using `grpc-java`
5. publish the first public request and callback contracts