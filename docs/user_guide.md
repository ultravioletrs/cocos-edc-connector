# Cocos EDC Connector - User Guide

This guide explains how to use the Cocos EDC Connector to submit and run confidential computations.

---

## 🏗️ High-Level Architecture & Workflow

The Cocos EDC Connector bridges the Eclipse Dataspace Components (EDC) framework with the Confidential OS (Cocos Agent) running inside a Confidential VM (CVM) or Enclave. 

The standard lifecycle of a computation job is:

```mermaid
sequenceaway
    participant Client
    participant EDC as EDC Connector
    participant Agent as Cocos Agent
    participant KBS as Trustee KBS

    Client ->> EDC: POST /api/management/cocos/computations (Job Payload)
    Note over EDC: Registers manifest in CocosManifestRegistry
    Note over EDC: Starts QEMU / Cloud CVM Instance
    Agent -->> EDC: Connects to CVMS gRPC Port (49203)
    EDC ->> Agent: Delivers ComputationRunReq (Manifest + User Key)
    EDC ->> Agent: Uploads Algorithm & Dataset via cocos-cli
    Note over Agent: Performs Attestation with KBS (if encrypted)
    Note over Agent: Executes Computation
    Agent -->> EDC: Sends AgentEvent (Completed / Failed) via log-forwarder
    EDC ->> Client: Callback Notification or Status updated to COMPLETED/FAILED
```

---

## 🔌 API Endpoints

The Cocos extension exposes a dedicated management API on the EDC Connector (typically configured on port `49204`).

### 1. Submit a Computation Job
* **Endpoint**: `POST http://<connector-host>:49204/api/management/cocos/computations`
* **Content-Type**: `application/json`
* **Payload**: Specifies the job metadata, datasets, and algorithms to run. See the [Example Payload](./example-payload.json) for a complete template.

#### Payload Properties:
* `jobId` (String): A unique identifier for the computation session.
* `towerCallbackUrl` (String): The webhook endpoint EDC will trigger with results/errors once execution finishes.
* `units` (Array): A list of execution environments.
  * `vmIp` (String): The IP address of the agent/CVM (or `127.0.0.1` when using port-forwarding/tunnels).
  * `manifest` (Object): The definition of the computation.
    * `id` (String): Unique identifier.
    * `name` (String): Readable name.
    * `algorithm` (Object): Contains the algorithm script `content` (base64-encoded) or a remote source URL, along with its execution `type` (e.g. `python`).
    * `datasets` (Array): A list of datasets with filename, hash, and content/source.

### 2. Query Job Status
* **Endpoint**: `GET http://<connector-host>:49204/api/management/cocos/computations/{jobId}`
* **Response**: Returns the status of the job execution.
  * `status`: `UPLOADING` | `COMPLETED` | `FAILED`

---

## 🛡️ Key Management & Attestation (Trustee KBS)

If the algorithm or dataset sources are encrypted, Cocos Agent performs hardware attestation to fetch keys from the **Trustee Key Broker Service (KBS)**:
1. **Public Keys**: The user's public key (e.g., `public.pem`) is embedded inside the run request.
2. **Attestation Policy**: The KBS verifies the CVM measurement (TDX/SEV-SNP) against a predefined Open Policy Agent (OPA) policy (`policy.rego`).
3. **Decryption**: Once attested, the agent is securely provisioned the decryption keys to access and execute the assets.
