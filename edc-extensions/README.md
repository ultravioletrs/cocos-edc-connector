# Cocos EDC Extensions

This directory contains the Gradle project packaging all Cocos-specific Eclipse Dataspace Components (EDC) extension modules.

These extensions are compiled as drop-in libraries and can be added to the classpath of the TITAN Eclipse EDC Connector runtime (version `0.16.0`).

## 📂 Layout

* **`extensions/cocos/`**: Subprojects implementing SPI, CLI, computation REST API, orchestrator, attestation credential service, and data sink.
* **`UPSTREAM_DEPLOYMENT.md`**: Guide for deploying these extensions.
* **`INTEGRATION_PLAN.md`**: Technical plan detailing the integration model and unresolved functional gaps.

## 🛠️ Build

To compile and package the extensions, run:
```bash
./gradlew build packageUpstreamDropins distZip
```
Output artifacts will be generated in the `build/` directory.
