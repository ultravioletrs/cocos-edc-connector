# Cocos EDC Connector - Developer Guide

This guide details the internal structure, components, extension registries, and development workflow for developers working on the Cocos EDC Connector codebase.

---

## 📂 Codebase & Module Structure

The connector code is structured into three Gradle modules within the `edc-extensions/extensions/cocos` directory:

### 1. `cocos-spi`
* **Path**: [`cocos-spi`](../edc-extensions/extensions/cocos/cocos-spi)
* **Purpose**: Defines shared models, APIs, and thread-safe registries used across different extensions.
* **Key Registries**:
  * [`CocosManifestRegistry`](../edc-extensions/extensions/cocos/cocos-spi/src/main/java/org/eclipse/edc/connector/cocos/spi/CocosManifestRegistry.java): Coordinates manifest registrations for CVMS. gRPC worker threads wait on this until the orchestrator registers the job manifest.
  * [`CocosAgentReadyRegistry`](../edc-extensions/extensions/cocos/cocos-spi/src/main/java/org/eclipse/edc/connector/cocos/spi/CocosAgentReadyRegistry.java): Tracks when the agent reports a successful connection and is ready to receive commands.
  * [`CocosAgentCompletionRegistry`](../edc-extensions/extensions/cocos/cocos-spi/src/main/java/org/eclipse/edc/connector/cocos/spi/CocosAgentCompletionRegistry.java): Block-and-resume synchronization point for the main orchestrator thread, waiting for agent completion or failure signals.

### 2. `cocos-cli`
* **Path**: [`cocos-cli`](../edc-extensions/extensions/cocos/cocos-cli)
* **Purpose**: Communicates with the local `cocos-cli` CLI command execution.
* **Key Components**:
  * [`CocosCliServiceImpl`](../edc-extensions/extensions/cocos/cocos-cli/src/main/java/org/eclipse/edc/connector/cocos/cli/CocosCliServiceImpl.java): Wraps the command process invocation to run standard algorithms/dataset upload tasks against the agent's endpoint.

### 3. `cocos-orchestrator`
* **Path**: [`cocos-orchestrator`](../edc-extensions/extensions/cocos/cocos-orchestrator)
* **Purpose**: Implements the gRPC CVMS protocol server and orchestrates the lifecycle execution sequence.
* **Key Components**:
  * [`CvmsGrpcServer`](../edc-extensions/extensions/cocos/cocos-orchestrator/src/main/java/org/eclipse/edc/connector/cocos/orchestrator/CvmsGrpcServer.java): Implements `ServiceGrpc.ServiceImplBase` to handle agent streaming. Supports both standard `agent` connections (for handshakes/manifest delivery) and `log-forwarder` connections (which streams logs/events).
  * [`ComputationOrchestratorImpl`](../edc-extensions/extensions/cocos/cocos-orchestrator/src/main/java/org/eclipse/edc/connector/cocos/orchestrator/ComputationOrchestratorImpl.java): Defines the main running sequence. Spawns threads to wait indefinitely for agent ready states, run the uploads, and wait on execution results.

---

## 🔨 Building the Connector Extensions

### Prerequisites
* **Java Development Kit (JDK)**: Java 21 is required (matching the Eclipse EDC requirements).
* Ensure your `JAVA_HOME` environment variable points to a valid JDK 21 installation.

### Building
Use the Gradle wrapper from the `edc-extensions` directory to build the extension modules:

```bash
# Navigate to the edc-extensions directory
cd edc-extensions

# Build the extensions (skipping unit tests for fast compilation)
./gradlew :extensions:cocos:cocos-spi:build :extensions:cocos:cocos-cli:build :extensions:cocos:cocos-orchestrator:build -x test
```

> [!TIP]
> If you have multiple JDK versions installed and JDK 21 is not your system default, you can specify `JAVA_HOME` inline:
> ```bash
> JAVA_HOME=/path/to/your/jdk-21 ./gradlew :extensions:cocos:cocos-spi:build :extensions:cocos:cocos-cli:build :extensions:cocos:cocos-orchestrator:build -x test
> ```

### Deployment
Once built successfully, copy the compiled JAR files:
* `extensions/cocos/cocos-spi/build/libs/cocos-spi-*.jar`
* `extensions/cocos/cocos-cli/build/libs/cocos-cli-*.jar`
* `extensions/cocos/cocos-orchestrator/build/libs/cocos-orchestrator-*.jar`

into your local EDC Connector deployment's `lib/` directory.
