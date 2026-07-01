# Cocos Eclipse EDC Extensions

This repository contains the Cocos-owned Eclipse Dataspace Components (EDC) extensions. It provides the integration bridge between the Cocos Confidential Computing platform (secure enclaves) and standard dataspace connectors.

By compiling these modules as standalone extensions, Cocos support can be plugged into any upstream deployment of the [Eclipse EDC Connector](https://github.com/eclipse-edc/Connector) as drop-in libraries without carrying or maintaining a long-lived fork of the connector codebase.

---

## 📂 Repository Layout

* **`edc-extensions/`**: The Gradle project containing all Cocos-specific Java EDC extension modules:
  * [`cocos-spi`](edc-extensions/extensions/cocos/cocos-spi): Shared interfaces, domain models, and helper classes.
  * [`cocos-cli`](edc-extensions/extensions/cocos/cocos-cli): Adapter invoking `cocos-cli` as a subprocess bridge to manage enclave states, check readiness, and trigger uploads/retrievals.
  * [`cocos-computation-api`](edc-extensions/extensions/cocos/cocos-computation-api): REST API endpoints exposed by the Management API to receive and track computation run requests.
  * [`cocos-orchestrator`](edc-extensions/extensions/cocos/cocos-orchestrator): Execution flow manager and host-side CVMS (Confidential VM Service) gRPC server that streams manifests to guest agents.
  * [`cocos-attestation-credential-service`](edc-extensions/extensions/cocos/cocos-attestation-credential-service): Verifiable Presentation (VP) generation hook validating hardware attestation quotes at the Cocos Identity Hub.
  * [`cocos-data-sink`](edc-extensions/extensions/cocos/cocos-data-sink): Data plane transfer receiver that intercepts transferred assets and pipes them directly into the enclave agent via `cocos-cli`.
* [**`E2E_TEST_GUIDE.md`**](E2E_TEST_GUIDE.md): Complete instructions for running end-to-end integration tests using a local QEMU VM containing all in-enclave components (agent, etc.) and a Trustee KBS.
* [**`payload.json`**](payload.json): Sample computation request JSON payload utilized in end-to-end integration testing.

---

## 🛠️ Build & Packaging

The extensions are designed to build against standard upstream Eclipse EDC releases using **Java 17+** and Gradle.

To compile and package the extensions:

1. Navigate to the `edc-extensions` directory:
   ```bash
   cd edc-extensions
   ```
2. Build the project and package upstream-ready drop-in jars:
   ```bash
   ./gradlew build packageUpstreamDropins distZip
   ```

### Output Artifacts:
* **Drop-in JAR files**: Located in `build/upstream-dropins/libs/*.jar`
* **Distribution Zip Bundle**: Located in `build/distributions/cocos-edc-extensions-<version>.zip`

---

## 🚀 Upstream Integration & Deployment Model

The built JARs are standard EDC `ServiceExtension` artifacts. To deploy them alongside an upstream EDC Connector:

1. **Obtain Base Runtime**: Start from an upstream `eclipse-edc/Connector` runtime built from source, or use their official Docker base images.
2. **Add to Classpath**: Copy the generated Cocos extension JAR files into the runtime's classpath/library directory (or volume mount them to the drop-ins directory).
3. **Configure Settings**: Provide configuration properties for the Cocos modules (e.g., configuring `org.eclipse.edc.connector.cocos.cli.path` to point to the local `cocos-cli` executable on the host).
4. **Boot**: Run the base connector runtime; the EDC extension loader automatically detects, registers, and starts the Cocos modules at boot time.

For details on the deployment architecture and notes, see [**UPSTREAM_DEPLOYMENT.md**](edc-extensions/UPSTREAM_DEPLOYMENT.md).

---

## 📖 Additional Documentation

For more detailed information, refer to:
* 📐 [**ARCHITECTURE.md**](edc-extensions/ARCHITECTURE.md): System component overview, UML sequence diagrams of the multi-phase execution, and resource transfer models (Model A vs. Model B).
* 🧪 [**E2E_TEST_GUIDE.md**](E2E_TEST_GUIDE.md): Tutorial on how to run a local integration test verifying the entire data transfer, attestation, key release, and computation loop.