# CocosAI Orchestrator Extension

This extension manages the execution flow of secure enclaves (VMs) from agent start to completion, handles manifest serialization, streams enclaves manifests, and routes logs. It implements the host-side CVMS (Confidential VM Service) server using gRPC.

---

## ⚙️ Configuration

This extension can be configured with the following properties:

| Property Key | Type | Default Value | Description |
|---|---|---|---|
| `cocos.cvms.port` | Optional | `7002` | Port on which the host-side CVMS gRPC server listens. |
| `cocos.cli.publicKey.path` | Optional | `public.pem` | Absolute path to the public key (PEM format) used by the CVMS server. |
| `cocos.kbs.url` | Optional | `http://localhost:8090` | Trustee Key Broker Service (KBS) URL sent to the guest enclaves for key release. |

---

## 🛠️ Key Components

1. **`ComputationOrchestratorImpl`**:
   - Manages the lifecycle state machine of each computation job:
     * **`startAgents`**: Waits for guest agent registration over gRPC stream.
     * **`uploadAssets`**: Resolves dataset and algorithm payloads and pipes them into the enclave via `cocos-cli`.
     * **`collectResults`**: Triggers output retrieval commands upon completion and posts job statuses to callbacks.
     
2. **`CvmsGrpcServer`**:
   - Hosts the bidirectional gRPC streaming server defined by `cvms.proto`.
   - Sends the serialized manifest payload to the enclaves agent and receives live log/event streams back to be logged locally.

3. **`TowerCallbackClient`**:
   - Triggers POST requests back to the configured `towerCallbackUrl` reporting job success or failure.

---

## ⚠️ Unresolved Implementation Gaps

* **`StubRemoteAssetFetcher`**:
  - Located in this module. Currently return an `UnsupportedOperationException`. 
  - Resolving assets declared as `AssetSource.Type.REMOTE` requires implementing a Dataspace Protocol (DSP) catalog client. Until built, only `AssetSource.Type.FILE` (inline) is supported.
