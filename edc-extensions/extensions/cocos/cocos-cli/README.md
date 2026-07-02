# CocosAI CLI Extension

This extension integrates the host-side Java EDC Connector with the `cocos-cli` Go binary by executing subprocesses via `ProcessBuilder`. It implements the `CocosCliService` interface defined in `cocos-spi`.

---

## ⚙️ Configuration

This extension requires the following properties to be set:

| Property Key | Type | Description |
|---|---|---|
| `cocos.cli.path` | **Required** | Absolute path to the `cocos-cli` binary. |
| `cocos.cli.privateKey.path` | **Required** | Absolute path to the private key (PEM format) used to sign enclave commands. |

---

## 🛠️ Functionality

The extension performs the following CLI operations:

1. **Agent Readiness Check**:
   - Attempts to dial TCP port `7001` on the Guest VM with a 30-second timeout to confirm the Guest Agent has successfully booted and is ready to accept commands.
   
2. **Dataset & Algorithm Upload**:
   - Executes `cocos-cli data` and `cocos-cli algo` commands to push raw and encrypted datasets and algorithms into the running enclaves.

3. **Attestation Retrieval**:
   - Executes `cocos-cli attestation get` to retrieve hardware attestation quotes from the running Guest VM.

4. **Result Collection**:
   - Executes `cocos-cli result` to retrieve computed outputs from the enclaves after job completion.
