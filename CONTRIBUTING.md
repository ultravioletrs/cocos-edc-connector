# Contributing to Cocos EDC Connector

We welcome contributions to the Cocos EDC Connector! This document provides guidelines for setting up your development environment, building the project, and submitting changes.

---

## 🛠️ Developer Setup & Prerequisites

To develop, build, and test the extension modules, you will need the following tools:

1. **Java Development Kit (JDK) 17+**:
   - The extensions are written in Java and compiled targetting Java 17 compatibility.
   - Set `JAVA_HOME` pointing to your JDK 17 installation.

2. **Gradle**:
   - The project uses the Gradle wrapper (`./gradlew`) included in the `edc-extensions` directory. There is no need to install Gradle globally.

3. **Protobuf & gRPC Compiler**:
   - The `cocos-orchestrator` module uses a gRPC interface to stream manifests to enclaves.
   - The Java stubs are automatically generated during the build via the `com.google.protobuf` Gradle plugin.
   - Ensure the protobuf plugin can compile the schemas in `edc-extensions/extensions/cocos/cocos-orchestrator/src/main/proto/`.

4. **Go & Rust (Optional - for E2E Tests)**:
   - **Go**: Required to compile/run the asset encryption scripts.
   - **Rust**: Required to build the Trustee KBS and related attestation services.
   - **QEMU**: Required to boot guest images for local VM integration testing.

---

## 📂 Codebase Structure

The project code is located in the `edc-extensions/` directory and is split into the following Gradle subprojects:
* **`cocos-spi`**: Domain models and service interfaces.
* **`cocos-cli`**: Subprocess executor for `cocos-cli`.
* **`cocos-computation-api`**: Management API endpoints exposing REST actions.
* **`cocos-orchestrator`**: CVMS server and state engine orchestrating jobs.
* **`cocos-attestation-credential-service`**: Verifiable Presentation hooks.
* **`cocos-data-sink`**: EDC Data Plane receiver piping data to enclaves.

---

## 🚀 Development Workflow

### 1. Build and Code Generation
To compile the codebase and generate protobuf stubs:
```bash
cd edc-extensions
./gradlew compileJava
```

### 2. Testing
Currently, the codebase has a major testing gap: **no unit tests exist yet**. 
If you are contributing code changes, you are highly encouraged to add unit tests under `src/test/java/` for the modified modules.

To run the unit tests:
```bash
./gradlew test
```

### 3. Packaging
To bundle the extensions for drop-in deployment:
```bash
./gradlew build packageUpstreamDropins distZip
```
This produces:
* JAR files in `edc-extensions/build/upstream-dropins/libs/`
* A distribution zip in `edc-extensions/build/distributions/`

---

## 📝 Coding Guidelines

* **Code Style**: We follow standard Java/Gradle conventions.
* **Interfaces First**: Declare models and service API boundaries in `cocos-spi`. Keep implementation modules dependent on the SPI.
* **No Hardcoded Configurations**: Always expose configurations using `@Setting` properties in extensions or through `ServiceExtensionContext.getSetting()`.
* **Error Handling**: Do not throw unchecked exceptions or bubble up raw stack traces. Use `Result` containers (e.g., `Result.failure("...")`) for predictable errors.
* **Preserve Comments**: Maintain all docstrings and structural comments when editing existing files.
