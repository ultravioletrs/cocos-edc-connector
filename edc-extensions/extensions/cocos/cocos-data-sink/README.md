# CocosAI VM Data Sink Extension

This extension integrates with the Eclipse EDC Data Plane. It intercepts asset data transfers that target a `CocosVm` destination, reads their bytes, and pipes them directly into the target Confidential VM enclave using the `CocosCliService`.

---

## ⚙️ Data Address Properties

When configuring a transfer process that writes to the Cocos Data Sink, the destination `DataAddress` must define the following properties:

| Property Key | Type | Default Value | Description |
|---|---|---|---|
| `type` | **Required** | None | Must be set to `CocosVm` to select this data sink. |
| `cocos.vm.ip` | **Required** | None | IP address of the target Confidential VM guest agent. |
| `cocos.filename` | **Required** | None | The filename under which the asset will be saved inside the enclave. |
| `cocos.asset.kind` | Optional | `DATASET` | Kind of the asset. Allowed values are `DATASET` and `ALGORITHM`. |

---

## 🧱 Key Components

1. **`CocosVmDataSinkFactory`**:
   - Implements the EDC `DataSinkFactory` interface.
   - Binds to the `CocosVm` destination type and validates the destination properties.
   
2. **`CocosVmDataSink`**:
   - Implements the EDC `DataSink` interface.
   - Handles the asynchronous data stream, downloading all bytes from the source, and executing the `uploadDataset` or `uploadAlgorithm` commands via the CLI service to push the asset directly into the enclave.
