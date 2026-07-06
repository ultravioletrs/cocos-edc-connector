# Cocos E2E Integration Testing Guide (QEMU VM Mode)

This guide provides step-by-step instructions to run a complete, end-to-end integration test of the Cocos EDC Connector with the Cocos Agent and Trustee KBS running inside a QEMU virtual machine.

In this setup:
- The **Trustee KBS** and **EDC Connector** run on the **host machine**.
- The **Cocos Agent** and **Cocos Computation Runner** run inside a **QEMU guest VM** (representing the secure enclave).
- The agent inside the VM connects outbound to the EDC Connector on the host via gRPC, receives the computation manifest, and executes the secure computation.

---

## 📋 Prerequisites

Ensure the following tools are installed on your host:
* **JDK 17** — required to run the EDC Connector runtime
* **Gradle** — used to build the Cocos extensions (wrapper `./gradlew` included)
* **Rust & Cargo** — for compiling the Trustee KBS from source
* **QEMU** — for running the guest VM (`qemu-system-x86_64`)
* **OVMF firmware** — UEFI firmware for QEMU (`OVMF_CODE.4m.fd`)
* **Python 3** with the following packages:
  ```bash
  pip install pandas scikit-learn joblib cryptography
  ```
* **`jq`** — for constructing the JSON payload
* **Go** — only required for Encrypted Mode asset preparation

---

## 📦 Step 0: Define Workspace Variables

Set these variables once in your shell session. All subsequent commands reference them so that the guide works from any clone location.

```bash
# Root directory of this repository (cocos-edc-connector)
export COCOS_CONNECTOR_DIR="$(pwd)"

# Path to the Cocos AI repository (contains cocos-cli binary, keys, test assets)
export COCOS_AI_DIR="<path-to-cocos-ai>"

# Path to the Trustee KBS repository
export TRUSTEE_DIR="<path-to-trustee>"

# Path to the upstream Eclipse EDC Connector repository (tag v0.18.0)
export EDC_CONNECTOR_DIR="<path-to-Connector>"

# Path to the QEMU guest kernel and initrd images (bzImage, rootfs.cpio.gz)
export GUEST_IMAGE_DIR="<path-to-guest-images>"

# Path to the OVMF firmware file
export OVMF_CODE="/usr/share/edk2/x64/OVMF_CODE.4m.fd"

# Host LAN IP reachable from inside the QEMU user-mode network (192.168.100.x)
export HOST_IP="192.168.100.15"

# The TCK connector runtime lib directory (populated after building the upstream connector)
export TCK_LIB_DIR="$EDC_CONNECTOR_DIR/system-tests/tck/dsp-tck-connector-under-test/build/install/dsp-tck-connector-under-test/lib"
```

> **Note**: Run `export COCOS_CONNECTOR_DIR="$(pwd)"` from the root of this repository. Set all other paths to match your local workspace layout.

---

## 🛠️ Step 1: Build All Components

### 1a. Build the Trustee KBS
```bash
cd "$TRUSTEE_DIR/kbs"
cargo build --release
```

### 1b. Build the Cocos EDC Extensions
```bash
cd "$COCOS_CONNECTOR_DIR/edc-extensions"
./gradlew build packageUpstreamDropins
```
This produces extension JARs under `edc-extensions/build/upstream-dropins/libs/`.

### 1c. Build the Upstream EDC Connector
```bash
cd "$EDC_CONNECTOR_DIR"
./gradlew installDist
```

---

## 🔑 Step 2: Prepare Keys & KBS Resources

Run from the `$TRUSTEE_DIR/kbs` directory:
```bash
cd "$TRUSTEE_DIR/kbs"

# Create key storage directories
mkdir -p kbs-data/repository/default/key-local

# Generate 32-byte symmetric keys for algorithm and dataset
openssl rand -out kbs-data/repository/default/key-local/algo-key 32
openssl rand -out kbs-data/repository/default/key-local/dataset-key 32
```

Ensure `policy.rego` in `$TRUSTEE_DIR/kbs` allows all requests (permissive policy for local testing):
```rego
package policy
default allow = true
```

---

## 🚀 Step 3: Start All Services

Open **three separate terminals**, one for each service. Each service runs in the foreground so you can observe its logs directly.

### Terminal 1 — Trustee KBS
```bash
cd "$TRUSTEE_DIR/kbs"
RUST_LOG=info ../target/release/kbs --config-file kbs-config.toml
```
Expected output:
```
INFO  kbs::api_server  Starting HTTP server at [0.0.0.0:8080]
```

### Terminal 2 — Configure Agent Environment & Boot QEMU VM
Before launching the VM, write the agent environment configuration into the 9p-shared `env/` directory so the guest agent knows how to dial the CVMS gRPC server:

```bash
cat > "$COCOS_CONNECTOR_DIR/env/environment" <<EOF
AGENT_LOG_LEVEL="debug"
AGENT_CVM_GRPC_URL="${HOST_IP}:7003"
EOF
```

Then boot the QEMU guest VM:
```bash
qemu-system-x86_64 \
    -enable-kvm \
    -cpu EPYC-v4 \
    -machine q35 \
    -smp 4 \
    -m 25G,slots=5,maxmem=30G \
    -no-reboot \
    -drive if=pflash,format=raw,unit=0,file="$OVMF_CODE",readonly=on \
    -netdev user,id=vmnic,hostfwd=tcp::7020-:7002,hostfwd=tcp::7001-:7002 \
    -device virtio-net-pci,disable-legacy=on,iommu_platform=true,netdev=vmnic,romfile= \
    -device vhost-vsock-pci,id=vhost-vsock-pci0,guest-cid=3 \
    -vnc :0 \
    -kernel "$GUEST_IMAGE_DIR/bzImage" \
    -append "earlyprintk=serial console=ttyS0 agent.aa_kbc_params=cc_kbc::http://${HOST_IP}:8080" \
    -initrd "$GUEST_IMAGE_DIR/rootfs.cpio.gz" \
    -nographic \
    -monitor pty \
    -monitor unix:monitor,server,nowait \
    -fsdev local,id=cert_fs,path="$COCOS_CONNECTOR_DIR/certs",security_model=mapped \
    -device virtio-9p-pci,fsdev=cert_fs,mount_tag=certs_share \
    -fsdev local,id=env_fs,path="$COCOS_CONNECTOR_DIR/env",security_model=mapped \
    -device virtio-9p-pci,fsdev=env_fs,mount_tag=env_share
```

Wait for the VM to reach `Welcome to Cocos` on the console — this takes approximately 2 minutes (including a 90-second TPM device timeout on non-TEE hardware).

### Terminal 3 — EDC Connector

Install the Cocos extensions into the TCK runtime classpath, then remove the two extensions that require components the bare TCK control plane doesn't provide:

| Extension JAR | Why removed from TCK |
|---|---|
| `cocos-data-sink-*.jar` | Requires the EDC data plane (not loaded by control-plane-only TCK) |
| `cocos-attestation-credential-service-*.jar` | Requires `decentralized-claims-spi` (DCP identity hub SPI not in TCK) |

```bash
# Copy all drop-ins, then remove EDC core jars that may have been bundled,
# and exclude the two extensions incompatible with the TCK control plane.
cp "$COCOS_CONNECTOR_DIR/edc-extensions/build/upstream-dropins/libs/"*.jar "$TCK_LIB_DIR/"
find "$TCK_LIB_DIR" -name "*-0.17.0.jar" -delete
rm -f "$TCK_LIB_DIR/cocos-data-sink-"*.jar
rm -f "$TCK_LIB_DIR/cocos-attestation-credential-service-"*.jar
```

Then start the connector. Replace `<path-to-jdk17>` with your JDK 17 `bin/` directory:

```bash
cd "$COCOS_CONNECTOR_DIR"

<path-to-jdk17>/java \
    -Dweb.http.port=8082 \
    -Dweb.http.protocol.port=8083 \
    -Dweb.http.management.port=8084 \
    -Dweb.http.control.port=8085 \
    -Dcocos.cli.path="$COCOS_AI_DIR/build/cocos-cli" \
    -Dcocos.cli.privateKey.path="$COCOS_AI_DIR/private.pem" \
    -Dcocos.cli.publicKey.path="$COCOS_AI_DIR/public.pem" \
    -Dcocos.kbs.url="http://${HOST_IP}:8080" \
    -Dcocos.cvms.port=7003 \
    -Dcocos.identity.hub.url="http://localhost:8090/identity-hub" \
    -cp "$TCK_LIB_DIR/*" \
    org.eclipse.edc.boot.system.runtime.BaseRuntime
```

Expected output:
```
INFO  CVMS gRPC Server started on port 7003
INFO  92 service extensions started
INFO  Runtime ... ready
```

Shortly after the VM boots, you will also see:
```
INFO  Agent connected to CVMS from IP: <vm-ip>, waiting for manifest...
```

---

## 📄 Step 4: Prepare the Computation Payload

Run from the root of this repository (`$COCOS_CONNECTOR_DIR`). Choose **Option A** for local testing without TEE hardware or **Option B** for an encrypted run.

### Option A: Plaintext Mode (Recommended for local testing)

```bash
cd "$COCOS_CONNECTOR_DIR"

ALGO_FILE="$COCOS_AI_DIR/test/manual/algo/lin_reg.py"
DATA_FILE="$COCOS_AI_DIR/test/manual/data/iris.csv"

ALGO_HASH=$(python3 -c "import hashlib; print(hashlib.sha3_256(open('$ALGO_FILE','rb').read()).hexdigest())")
ALGO_B64=$(python3  -c "import base64;  print(base64.b64encode(open('$ALGO_FILE','rb').read()).decode())")
DATA_HASH=$(python3 -c "import hashlib; print(hashlib.sha3_256(open('$DATA_FILE','rb').read()).hexdigest())")
DATA_B64=$(python3  -c "import base64;  print(base64.b64encode(open('$DATA_FILE','rb').read()).decode())")

jq -n \
  --arg jobId      "test-job-001" \
  --arg callback   "http://localhost:8090/callback" \
  --arg vmIp       "$HOST_IP" \
  --arg algoHash   "$ALGO_HASH" \
  --arg algoB64    "$ALGO_B64" \
  --arg dataHash   "$DATA_HASH" \
  --arg dataB64    "$DATA_B64" \
  '{
    jobId: $jobId,
    towerCallbackUrl: $callback,
    units: [{
      vmIp: $vmIp,
      manifest: {
        id: $jobId,
        name: "lin-reg-test",
        description: "Iris linear regression",
        algorithm: {
          type: "python",
          filename: "lin_reg.py",
          hash: $algoHash,
          source: { type: "FILE", content: $algoB64, encrypted: false }
        },
        datasets: [{
          filename: "iris.csv",
          hash: $dataHash,
          source: { type: "FILE", content: $dataB64, encrypted: false }
        }]
      }
    }]
  }' > payload.json

echo "payload.json created ($(wc -c < payload.json) bytes)"
```

---

### Option B: Encrypted Mode

1. **Encrypt assets** from the `$COCOS_AI_DIR` root:
   ```bash
   cd "$COCOS_AI_DIR"

   go run scripts/encrypt.go \
       "$TRUSTEE_DIR/kbs/kbs-data/repository/default/key-local/algo-key" \
       test/manual/algo/lin_reg.py \
       lin_reg.py.enc

   go run scripts/encrypt.go \
       "$TRUSTEE_DIR/kbs/kbs-data/repository/default/key-local/dataset-key" \
       test/manual/data/iris.csv \
       iris.csv.enc
   ```

2. **Build the payload** from `$COCOS_CONNECTOR_DIR`:
   ```bash
   cd "$COCOS_CONNECTOR_DIR"

   ALGO_FILE="$COCOS_AI_DIR/test/manual/algo/lin_reg.py"   # hash of plaintext
   DATA_FILE="$COCOS_AI_DIR/test/manual/data/iris.csv"      # hash of plaintext

   ALGO_HASH=$(python3 -c "import hashlib; print(hashlib.sha3_256(open('$ALGO_FILE','rb').read()).hexdigest())")
   ALGO_B64=$(python3  -c "import base64;  print(base64.b64encode(open('$COCOS_AI_DIR/lin_reg.py.enc','rb').read()).decode())")
   DATA_HASH=$(python3 -c "import hashlib; print(hashlib.sha3_256(open('$DATA_FILE','rb').read()).hexdigest())")
   DATA_B64=$(python3  -c "import base64;  print(base64.b64encode(open('$COCOS_AI_DIR/iris.csv.enc','rb').read()).decode())")

   jq -n \
     --arg jobId    "test-job-001" \
     --arg callback "http://localhost:8090/callback" \
     --arg vmIp     "$HOST_IP" \
     --arg algoHash "$ALGO_HASH" --arg algoB64 "$ALGO_B64" \
     --arg dataHash "$DATA_HASH" --arg dataB64 "$DATA_B64" \
     '{
       jobId: $jobId,
       towerCallbackUrl: $callback,
       units: [{
         vmIp: $vmIp,
         manifest: {
           id: $jobId,
           name: "lin-reg-encrypted",
           description: "Iris linear regression (encrypted)",
           algorithm: {
             type: "python",
             filename: "lin_reg.py",
             hash: $algoHash,
             source: {
               type: "FILE", content: $algoB64,
               encrypted: true, kbsResourcePath: "default/key-local/algo-key"
             }
           },
           datasets: [{
             filename: "iris.csv",
             hash: $dataHash,
             source: {
               type: "FILE", content: $dataB64,
               encrypted: true, kbsResourcePath: "default/key-local/dataset-key"
             }
           }]
         }
       }]
     }' > payload.json

   echo "payload.json created ($(wc -c < payload.json) bytes)"
   ```

---

## ▶️ Step 5: Trigger the Computation

```bash
curl -X POST http://localhost:8084/api/management/cocos/computations \
     -H "Content-Type: application/json" \
     -d @payload.json
```

Expected response:
```json
{"jobId":"test-job-001"}
```

The connector immediately accepts the request (`202 Accepted`) and begins asynchronous orchestration.

---

## 📈 Step 6: Verify Execution

### Watch the connector logs (Terminal 3)
The orchestrator is **event-driven** — it blocks on the CVMS bidirectional gRPC stream and proceeds only when the agent sends a `RunResponse` event. No polling occurs.

Expected sequence:
```
INFO  Registered manifest in CVMS registry for VM IP: <vm-ip>
INFO  Cocos Agent is ready and listening on port 7001
INFO  Sending computation run request to agent <vm-ip>
INFO  [AgentEvent] [cos-run] RUNNING
INFO  [AgentLog]   [info] Starting computation
INFO  Agent reported run complete successfully
```

### Poll job status
```bash
curl http://localhost:8084/api/management/cocos/computations/test-job-001
```

Expected response:
```json
{"jobId":"test-job-001","status":"COMPLETED"}
```

### Possible status values
| Status | Meaning |
|---|---|
| `STARTING_AGENTS` | Registering manifest in the CVMS registry |
| `UPLOADING` | Uploading algorithm and dataset to the agent |
| `COLLECTING_RESULTS` | Fetching result archive from the agent |
| `COMPLETED` | Computation finished successfully |
| `FAILED` | An error occurred — check connector logs for details |
