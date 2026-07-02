# CocosAI Computation REST API Extension

This extension exposes the REST endpoints on the EDC Management API to register secure computation run requests and track their execution status.

---

## 🚀 API Endpoints

All endpoints are hosted on the Management API port and prefix (e.g., `http://localhost:8084/api/management`).

### 1. Submit Computation Run
* **Method**: `POST`
* **Path**: `/cocos/computations`
* **Headers**: `Content-Type: application/json`
* **Request Body**: A JSON object matching the `ComputationJob` structure, containing the job ID, enclaves/units, and the computation manifest:
  ```json
  {
    "jobId": "job-123",
    "towerCallbackUrl": "http://callback-server:8090/callback",
    "units": [
      {
        "vmIp": "192.168.100.15",
        "manifest": {
          "id": "job-123",
          "name": "Linear Regression Run",
          "description": "Trains a model on dataset",
          "algorithm": {
            "type": "python",
            "filename": "lin_reg.py",
            "hash": "...",
            "source": {
              "type": "FILE",
              "content": "...",
              "encrypted": false
            }
          },
          "datasets": [
            {
              "filename": "iris.csv",
              "hash": "...",
              "source": {
                "type": "FILE",
                "content": "...",
                "encrypted": false
              }
            }
          ]
        }
      }
    ]
  }
  ```
* **Response**: `200 OK` (Empty response or UUID confirmation).

### 2. Query Computation Job Status
* **Method**: `GET`
* **Path**: `/cocos/computations/{jobId}`
* **Response**: Returns a JSON object with the job status:
  ```json
  {
    "jobId": "job-123",
    "status": "COMPLETED"
  }
  ```
  Statuses can be: `SUBMITTED`, `RUNNING`, `COMPLETED`, or `FAILED`.
