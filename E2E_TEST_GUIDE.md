# Cocos E2E Integration Testing Guide (QEMU VM Mode)

This guide provides step-by-step instructions to run a complete, end-to-end integration test of the Cocos EDC Connector with the Cocos Go Agent and Trustee KBS running inside a QEMU virtual machine.

In this setup:
- The **Trustee KBS** and **EDC Connector** run on the host machine.
- The **Cocos Agent** and **Cocos Computation Runner** run inside a QEMU guest VM (representing the secure enclave).
- The agent inside the VM connects outbound to the EDC Connector on the host, receives the computation manifest, decrypts the encrypted assets (by fetching keys from the host's KBS), and executes the computation in the guest environment.

---

## 📋 Prerequisites

Ensure the following tools are installed on your host:
* **JDK 17** (e.g., packaged under `.jdk/` in `edc-extensions` or configured globally)
* **Go** (for running the encryption helper script)
* **Rust & Cargo** (for compiling the Trustee KBS)
* **QEMU** (for running the virtual machine image)
* **QEMU Guest Images & CLI**: Download `bzImage`, `rootfs.cpio.gz`, and the `cocos-cli` executable from the [Cocos v0.10.0 Release Page](https://github.com/ultravioletrs/cocos/releases/tag/v0.10.0)
* **Upstream EDC Connector**: The extensions are compatible with the upstream Eclipse EDC Connector at tag [**`v0.18.0`**](https://github.com/eclipse-edc/Connector/tree/v0.18.0)
* **Python 3** with `pandas`, `scikit-learn`, `joblib`, and `cryptography` libraries installed:
  ```bash
  pip install --break-system-packages pandas scikit-learn joblib cryptography
  ```

---

## 📦 Repositories Setup

Before beginning, clone the required repositories into your workspace directory:

1. **Cocos Repository**:
   ```bash
   git clone https://github.com/ultravioletrs/cocos
   cd cocos && git checkout v0.10.0
   ```

2. **Trustee KBS Repository**:
   ```bash
   git clone https://github.com/confidential-containers/trustee
   ```

3. **Cocos EDC Connector Repository** (contains the Cocos extensions):
   ```bash
   git clone https://github.com/ultravioletrs/cocos-edc-connector
   ```

4. **Upstream EDC Connector Repository**:
   ```bash
   git clone https://github.com/eclipse-edc/Connector
   cd Connector && git checkout v0.18.0
   ```

---

## 🛠️ Step 1: Set Up & Start Components

### 1. Trustee KBS (Host)
Ensure your LocalFs resource registry contains the keys for encryption and decryption.

1. **Patch Cargo.toml**: Navigate to the `trustee/kbs` directory and, if your host machine lacks SGX/TDX DCAP TEE libraries, disable the `all-verifier` feature in `Cargo.toml` so it compiles successfully with the sample verifier:
   ```toml
   attestation-service = { path = "../attestation-service", default-features = false, features = [], optional = true }
   ```

2. **Build KBS & CLI**:
   Run the build commands from the `trustee/kbs` directory:
   ```bash
   cd trustee/kbs
   make
   make cli
   ```

3. **Generate Keys**: Run this from the `trustee/kbs` directory to create raw 32-byte keys for your algorithm and dataset under the KBS repository path:
   ```bash
   mkdir -p kbs-data/repository/default/key-local
   openssl rand -out kbs-data/repository/default/key-local/algo-key 32
   openssl rand -out kbs-data/repository/default/key-local/dataset-key 32
   ```

4. **Verify Resource Policy**: Ensure `policy.rego` (in the `trustee/kbs` directory) is configured to allow all (sample attestation) for local testing:
   ```rego
   package policy
   default allow = true
   ```

5. **Start KBS** on port `8080` (run from the `trustee/kbs` directory):
   ```bash
   RUST_LOG=info ../target/release/kbs --config-file kbs-config.toml
   ```

### 2. Configure Agent Environment (Host)
Before booting the VM, configure your `env/environment` file on the host (which is shared with the VM via `env_share` 9p mount) so the agent knows how to dial the CVMS server without TLS:
```ini
AGENT_LOG_LEVEL="debug"
AGENT_CVM_GRPC_URL="192.168.100.15:7003"
```
*(Replace `192.168.100.15` with your host's actual LAN IP).*

### 3. Start the QEMU VM (Host)
Run this command from the directory containing the downloaded guest images `bzImage` and `rootfs.cpio.gz` (downloaded from the [Cocos v0.10.0 Release Page](https://github.com/ultravioletrs/cocos/releases/tag/v0.10.0)):
```bash
qemu-system-x86_64 \
    -enable-kvm \
    -cpu EPYC-v4 \
    -machine q35 \
    -smp 4 \
    -m 25G,slots=5,maxmem=30G \
    -no-reboot \
    -drive if=pflash,format=raw,unit=0,file=/usr/share/edk2/x64/OVMF_CODE.4m.fd,readonly=on \
    -netdev user,id=vmnic,hostfwd=tcp::7020-:7002,hostfwd=tcp::7001-:7002 \
    -device virtio-net-pci,disable-legacy=on,iommu_platform=true,netdev=vmnic,romfile= \
    -device vhost-vsock-pci,id=vhost-vsock-pci0,guest-cid=3 -vnc :0 \
    -kernel ./bzImage \
    -append "earlyprintk=serial console=ttyS0 agent.aa_kbc_params=cc_kbc::http://192.168.100.15:8080" \
    -initrd ./rootfs.cpio.gz \
    -nographic \
    -monitor pty \
    -monitor unix:monitor,server,nowait \
    -fsdev local,id=cert_fs,path=<path_to_certs_dir>,security_model=mapped \
    -device virtio-9p-pci,fsdev=cert_fs,mount_tag=certs_share \
    -fsdev local,id=env_fs,path=<path_to_env_dir>,security_model=mapped \
    -device virtio-9p-pci,fsdev=env_fs,mount_tag=env_share
```

### 4. EDC Connector (dsp-tck-connector-under-test)

Before starting the connector runtime, you must build both the Cocos extensions in this repository and the upstream Eclipse EDC Connector, and then place the Cocos extension JARs on the classpath of the connector.

1. **Build Cocos EDC Extensions**:
   Navigate to the `edc-extensions` directory and build the extension modules:
   ```bash
   cd edc-extensions
   ./gradlew build packageUpstreamDropins distZip
   cd ..
   ```
   This generates the Cocos extension JAR files in `edc-extensions/build/upstream-dropins/libs/`.

2. **Compile Upstream Connector**:
   Navigate to the **Connector** repository directory and build the distribution:
   ```bash
   cd ../Connector # Adjust to your cloned Connector repository path
   ./gradlew installDist
   cd - # Return to cocos-edc-connector
   ```

3. **Install Extensions into the Runtime Classpath**:
   Copy the built Cocos extension JARs and their dependencies into the `lib` directory of the `dsp-tck-connector-under-test` distribution so they are loaded at startup:
   ```bash
   cp edc-extensions/build/upstream-dropins/libs/*.jar ../Connector/system-tests/tck/dsp-tck-connector-under-test/build/install/dsp-tck-connector-under-test/lib/
   ```

4. **Start the Connector Runtime**:
   Start the Java control plane runtime using JDK 17 from the root of the **cocos-edc-connector** repository directory (setting `-Dcocos.kbs.url` to the host LAN IP so the guest agent can connect back to the host KBS and pointing `cocos.cli.path` to your built or downloaded `cocos-cli` executable). 
   
   Ensure that the classpath includes the lib directory of the Connector distribution (which now contains the Cocos extensions):
   ```bash
   <path_to_jdk_17>/bin/java \
        -Dweb.http.port=8082 \
        -Dweb.http.protocol.port=8083 \
        -Dweb.http.management.port=8084 \
        -Dweb.http.control.port=8085 \
        -Dcocos.cli.path=<path_to_cocos_cli_executable> \
        -Dcocos.cli.privateKey.path=<path_to_cocos>/private.pem \
        -Dcocos.cli.publicKey.path=<path_to_cocos>/public.pem \
        -Dcocos.kbs.url=http://192.168.100.15:8080 \
        -Dcocos.cvms.port=7003 \
        -Dcocos.identity.hub.url=http://localhost:8090/identity-hub \
        -cp "../Connector/system-tests/tck/dsp-tck-connector-under-test/build/install/dsp-tck-connector-under-test/lib/*" \
        org.eclipse.edc.boot.system.runtime.BaseRuntime
   ```

---

## 🔒 Step 2: Prepare Assets & Manifest JSON

For end-to-end testing, you can choose to run the computation in either **Plaintext Mode** (simplest for local testing without TEE hardware) or **Encrypted Mode** (requires a TEE environment or a KBS auth bypass configuration).

### Option A: Plaintext Mode (Recommended for Local non-TEE Verification)
You do not need to encrypt the files. The assets are uploaded directly as plaintext.

```bash
# Read algorithm details
ALGO_HASH=$(python3 -c "import hashlib; print(hashlib.sha3_256(open('test/manual/algo/lin_reg.py', 'rb').read()).hexdigest())")
ALGO_BASE64=$(python3 -c "import base64; print(base64.b64encode(open('test/manual/algo/lin_reg.py', 'rb').read()).decode('utf-8'))")

# Read dataset details
DATA_HASH=$(python3 -c "import hashlib; print(hashlib.sha3_256(open('test/manual/data/iris.csv', 'rb').read()).hexdigest())")
DATA_BASE64=$(python3 -c "import base64; print(base64.b64encode(open('test/manual/data/iris.csv', 'rb').read()).decode('utf-8'))")

# Construct plaintext payload JSON
jq -n \
  --arg jobId "test-job-015" \
  --arg callback "http://localhost:8090/callback" \
  --arg vmIp "127.0.0.1" \
  --arg algo_hash "$ALGO_HASH" \
  --arg algo_content "$ALGO_BASE64" \
  --arg data_hash "$DATA_HASH" \
  --arg data_content "$DATA_BASE64" \
  '{
    jobId: $jobId,
    towerCallbackUrl: $callback,
    units: [
      {
        vmIp: $vmIp,
        manifest: {
          id: $jobId,
          name: "test-run",
          description: "linear regression test",
          algorithm: {
            type: "python",
            filename: "lin_reg.py",
            hash: $algo_hash,
            source: {
              type: "FILE",
              content: $algo_content,
              encrypted: false
            }
          },
          datasets: [
            {
              filename: "iris.csv",
              hash: $data_hash,
              source: {
                type: "FILE",
                content: $data_content,
                encrypted: false
              }
            }
          ]
        }
      }
    ]
  }' > payload.json
```

---

### Option B: Encrypted Mode
1. **Encrypt the Assets**: Run the encryption script from the root of the **cocos** repository directory:
   ```bash
   go run scripts/encrypt.go \
          <path_to_trustee>/kbs/kbs-data/repository/default/key-local/algo-key \
          test/manual/algo/lin_reg.py \
          lin_reg.py.enc

   go run scripts/encrypt.go \
          <path_to_trustee>/kbs/kbs-data/repository/default/key-local/dataset-key \
          test/manual/data/iris.csv \
          iris.csv.enc
   ```

2. **Prepare Encrypted Manifest JSON**:
   ```bash
   # Read algorithm details
   ALGO_HASH=$(python3 -c "import hashlib; print(hashlib.sha3_256(open('test/manual/algo/lin_reg.py', 'rb').read()).hexdigest())")
   ALGO_BASE64=$(python3 -c "import base64; print(base64.b64encode(open('lin_reg.py.enc', 'rb').read()).decode('utf-8'))")

   # Read dataset details
   DATA_HASH=$(python3 -c "import hashlib; print(hashlib.sha3_256(open('test/manual/data/iris.csv', 'rb').read()).hexdigest())")
   DATA_BASE64=$(python3 -c "import base64; print(base64.b64encode(open('iris.csv.enc', 'rb').read()).decode('utf-8'))")

   # Construct encrypted payload JSON
   jq -n \
     --arg jobId "test-job-015" \
     --arg callback "http://localhost:8090/callback" \
     --arg vmIp "127.0.0.1" \
     --arg algo_hash "$ALGO_HASH" \
     --arg algo_content "$ALGO_BASE64" \
     --arg data_hash "$DATA_HASH" \
     --arg data_content "$DATA_BASE64" \
     '{
       jobId: $jobId,
       towerCallbackUrl: $callback,
       units: [
         {
           vmIp: $vmIp,
           manifest: {
             id: $jobId,
             name: "test-run",
             description: "linear regression test",
             algorithm: {
               type: "python",
               filename: "lin_reg.py",
               hash: $algo_hash,
               source: {
                 type: "FILE",
                 content: $algo_content,
                 encrypted: true,
                 kbsResourcePath: "default/key-local/algo-key"
               }
             },
             datasets: [
               {
                 filename: "iris.csv",
                 hash: $data_hash,
                 source: {
                   type: "FILE",
                   content: $data_content,
                   encrypted: true,
                   kbsResourcePath: "default/key-local/dataset-key"
                 }
               }
             ]
           }
         }
       ]
     }' > payload.json
   ```

---

## 🚀 Step 3: Trigger the Computation Request

Trigger the computation run request by POSTing the generated `payload.json` file to the EDC Connector Management API:

```bash
curl -v -X POST http://localhost:8084/api/management/cocos/computations \
  -H "Content-Type: application/json" \
  -d @payload.json
```

---

## 📈 Step 4: Verify Execution Logs & Status

1. **Check EDC Connector Logs**:
   You should see:
   ```
   INFO ... Agent connected to CVMS from IP: 192.168.100.15, waiting for manifest...
   INFO ... Sending computation run request to agent 192.168.100.15
   INFO ... Agent reported run complete successfully
   ```

2. **Query Job Status**:
   Verify the job completed successfully:
   ```bash
   curl http://localhost:8084/api/management/cocos/computations/test-job-015
   ```
   **Expected Response:**
   ```json
   {"jobId":"test-job-015","status":"COMPLETED"}
   ```
