# CocosAI SPI Extension

This module defines the Service Provider Interface (SPI) for the Cocos EDC integration. It contains the core domain models, shared interfaces, and exceptions used by all other extension modules.

---

## 📂 Key Components & SPI Interfaces

The module defines the following key interfaces under `org.eclipse.edc.connector.cocos.spi`:

1. **`CocosCliService`**:
   - Interface to run `cocos-cli` subprocess commands (`data`, `algo`, `attestation get`, and `result`).
   
2. **`ComputationOrchestrator`**:
   - Service orchestrating the lifecycle of secure computation jobs running inside confidential VMs.

3. **`ComputationJobStore`**:
   - Store responsible for tracking current states of `ComputationJob` runs.

4. **`RemoteAssetFetcher`**:
   - SPI interface to resolve remote asset payloads (e.g. from catalog negotiation via Dataspace Protocol).

---

## 🧱 Key Domain Models

* **`ComputeManifest`**: The configuration manifest streamed to enclaves via the CVMS server.
* **`ComputationJob`**: Tracking entity matching an active job's UUID, status, and enclaves.
* **`AssetSource`**: Metadata describing the location (Inline content or Remote catalog references) and encryption status of datasets/algorithms.
