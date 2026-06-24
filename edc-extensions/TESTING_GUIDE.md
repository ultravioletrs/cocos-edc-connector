# Cocos EDC Integration Testing Guide

This guide details how to verify and test the Cocos EDC Extensions connector integration using a hybrid setup: running real Cocos AI components (agent, CLI, KBS) locally, while using lightweight mocks for external partner systems (Identity Hub, Provider Connector, and DLT).

---

## 1. Test Scenario & Flow Design

We will run a supervised Machine Learning workload inside a local Cocos Agent:
* **Algorithm**: `lin_reg.py` (a Python scikit-learn linear regression script).
* **Dataset**: `iris.csv` (a tabular CSV dataset).
* **Mocks**: Simulates the OID4VP identity hub exchange, DLT ledger validations, and DSP negotiations.

### How the Agent Receives the Manifest
In the Cocos AI architecture, the `cocos-agent` does **not** expose a gRPC endpoint to receive the manifest directly. Instead:
1. The agent acts as a **gRPC client** and dials outbound on startup to a **CVMS (Confidential VM Service) Server** (using the `Process` RPC defined in `cvms.proto`).
2. The CVMS Server streams the manifest (`ComputationRunReq`) in chunks down the active gRPC stream to the agent.
3. Once the agent receives the manifest, it initializes the computation and starts its local `AgentService` gRPC server (listening on port `7001`).

Since the current Java connector (`CC_EDC`) is incomplete and does not yet have a built-in CVMS gRPC server, we use the Go **CVMS Test Server** (`/test/cvms/main.go` inside `cocos-ai`) as a standalone sidecar during integration testing.

---

## 2. Prerequisites & Asset Setup

1. **Local Dependencies**: Java 17+, Go 1.21+, Python 3.9+ (with `pandas` and `scikit-learn` installed).
2. **KBS (Key Broker Service)**: Start the local Trustee KBS on `http://localhost:8080` in sample mode.
3. **Store keys in KBS**:
   Generate random keys and upload them to the KBS for your test dataset and algorithm:
   ```bash
   # Generate keys
   openssl rand -out algo.key 32
   openssl rand -out dataset.key 32

   # Store in KBS
   kbs-client --url http://localhost:8080 config set-resource --path default/key/algo-key --resource-file algo.key
   kbs-client --url http://localhost:8080 config set-resource --path default/key/dataset-key --resource-file dataset.key
   ```
4. **Prepare Test Files**:
   Create a directory `/tmp/provider-assets/` and create the test algorithm and dataset files:
   
   **`/tmp/provider-assets/lin_reg.py`**:
   ```python
   import pandas as pd
   from sklearn.linear_model import LinearRegression
   import sys
   import os

   data = pd.read_csv(sys.argv[1])
   X = data[['feature1', 'feature2']]
   y = data['target']

   model = LinearRegression()
   model.fit(X, y)

   os.makedirs("results", exist_ok=True)
   with open("results/output.txt", "w") as f:
       f.write(f"Coefficients: {model.coef_}\n")
       f.write(f"Intercept: {model.intercept_}\n")
   print("Success: Model trained successfully!")
   ```

   **`/tmp/provider-assets/iris.csv`**:
   ```csv
   feature1,feature2,target
   5.1,3.5,0
   4.9,3.0,0
   6.2,3.4,1
   5.9,3.0,1
   ```

---

## 3. Step-by-Step Setup

### Step 1: Start the Mock Provider & Trust Services
Create a file named `mock_services.py` inside a temporary directory and run it:

```python
import os
from flask import Flask, jsonify, request, send_from_directory
import uuid

app = Flask(__name__)
ASSETS_DIR = "/tmp/provider-assets"

# Mock Identity Hub (CW)
@app.route("/identity-hub/nonce", methods=["GET"])
def get_nonce():
    return jsonify({"nonce": "mock-challenge-nonce-1234567890"})

@app.route("/identity-hub/presentation", methods=["POST"])
def generate_vp():
    return jsonify({"verifiablePresentation": "eyJhbGciOiJSUzI1NiIsImtpZCI6Im1vY2sifQ.ey..."})

# Mock Provider Connector Exposing Catalog
@app.route("/provider/catalog", methods=["GET"])
def get_catalog():
    return jsonify({
        "id": "provider-catalog",
        "assets": [
            {
                "id": "lin-reg-algo-asset",
                "properties": {
                    "name": "lin_reg.py", 
                    "type": "algorithm",
                    "url": "http://localhost:8090/files/lin_reg.py"
                }
            },
            {
                "id": "iris-dataset-asset",
                "properties": {
                    "name": "iris.csv", 
                    "type": "dataset",
                    "url": "http://localhost:8090/files/iris.csv"
                }
            }
        ]
    })

@app.route("/provider/contractnegotiations", methods=["POST"])
def contract_negotiation():
    return jsonify({"id": str(uuid.uuid4()), "state": "AGREED"})

@app.route("/provider/transferprocesses", methods=["POST"])
def start_transfer():
    return jsonify({"id": str(uuid.uuid4()), "state": "STARTED"})

# File Server representing the Provider's storage backing
@app.route("/files/<path:filename>", methods=["GET"])
def serve_files(filename):
    return send_from_directory(ASSETS_DIR, filename)

if __name__ == "__main__":
    app.run(port=8090)
```
Run the mocks:
```bash
python3 mock_services.py
```

### Step 2: Start the Go CVMS Test Server
Because the Java connector is currently an orchestration skeleton, we run the Go CVMS Test Server (`/test/cvms/main.go` from the `cocos-ai` repository) on port `7002` to handle the gRPC manifest streaming:
1. Navigate to `/home/sammyk/Documents/cocos-ai`
2. Build the binaries:
   ```bash
   make agent cli
   ```
3. Start the CVMS test server:
   ```bash
   HOST=localhost PORT=7002 go run ./test/cvms/main.go \
     -public-key-path ./public.pem \
     -attested-tls-bool false
   ```

### Step 3: Start the Cocos Agent (Simulated CVM)
Run the `cocos-agent` binary locally. On boot, it automatically dials the CVMS test server on port `7002` and waits to receive the manifest:
```bash
# Configure agent settings to connect outbound to CVMS Server
export AGENT_CVM_GRPC_HOST=localhost
export AGENT_CVM_GRPC_PORT=7002
export AGENT_GRPC_HOST=localhost
export AGENT_GRPC_PORT=7001

./build/cocos-agent
```

### Step 4: Run the EDC Connector
Navigate to `/home/sammyk/Documents/cocos-edc-connector/edc-extensions/` and start your local EDC runtime (port `8080`):
```bash
./gradlew build packageUpstreamDropins
java -Dorg.eclipse.edc.connector.cocos.cli.path=/home/sammyk/Documents/cocos-ai/build/cocos-cli \
     -jar build/upstream-dropins/libs/cocos-orchestrator.jar
```

---

## 4. Trigger and Verify Computation

When we trigger the computation, the CVMS Test Server will stream the manifest to the agent, which then spins up its local gRPC server (port `7001`). The EDC Data Plane can then use `cocos-cli` to upload the provider-negotiated assets directly.

Calculate the SHA3-256 hashes of the files:
```bash
ALGO_HASH=$(/home/sammyk/Documents/cocos-ai/build/cocos-cli checksum /tmp/provider-assets/lin_reg.py 2>&1 | awk '{print $NF}')
DATASET_HASH=$(/home/sammyk/Documents/cocos-ai/build/cocos-cli checksum /tmp/provider-assets/iris.csv 2>&1 | awk '{print $NF}')
```

Trigger the computation via the EDC Connector REST API:
```bash
curl -X POST http://localhost:8080/cocos/computations \
  -H "Content-Type: application/json" \
  -d '{
    "jobId": "test-job-001",
    "towerCallbackUrl": "http://localhost:8090/callback",
    "units": [
      {
        "vmIp": "127.0.0.1",
        "manifest": {
          "computation_id": "test-job-001",
          "algorithm": {
            "type": "python",
            "filename": "lin_reg.py",
            "hash": "'"$ALGO_HASH"'"
          },
          "datasets": [
            {
              "filename": "iris.csv",
              "hash": "'"$DATASET_HASH"'"
            }
          ]
        }
      }
    ]
  }'
```

### Verification Checklist
1. **Outbound Stream Connects**: The agent console shows it connected to CVMS on startup.
2. **Manifest Dispatch**: Once the curl command hits `CC_EDC`, verify the CVMS Test Server sends the manifest to the agent. The agent logs show `received computation manifest`.
3. **Data Upload**: The EDC Connector downloads the assets via its data plane from `http://localhost:8090/files/...` and uploads them to the agent via `cocos-cli` on port `7001`.
4. **Execution**: Verify `lin_reg.py` executes inside the agent sandbox, reads the uploaded `iris.csv`, and writes its trained outputs inside `/cocos/results/`.
