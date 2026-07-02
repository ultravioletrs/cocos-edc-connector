# Security Policy

## Supported Versions

Currently, the following versions of the Cocos EDC Connector receive security updates:

| Version | Supported | Notes |
| ------- | --------- | ----- |
| 0.1.x   | ✅ Yes    | Under active development / pre-release. |

---

## Reporting a Vulnerability

If you discover a security vulnerability in this project, please **do not report it publicly via GitHub Issues**. Instead, please report it via the following process:

1. Email a detailed description of the vulnerability to the project maintainers (security@ultravioletrs.com).
2. Include steps to reproduce the issue, potential impact, and any mitigation ideas.
3. We will acknowledge receipt of your report within 48 hours and work with you to coordinate a security advisory and patch.

---

## 🔒 Confidential Computing Security Considerations

Because this project serves as a bridge to standard Confidential Computing (TEE) enclaves, the following security practices are critical:

1. **Private Key Management**:
   - The private key specified by `cocos.cli.privateKey.path` is used to sign commands sent to enclaves.
   - Ensure this key is protected via file system permissions, and never expose it in logs or configuration files.

2. **KBS Trust Anchor**:
   - The Key Broker Service (KBS) URL specified by `cocos.kbs.url` is trusted to release secrets only to verified enclaves.
   - Use TLS (HTTPS) in production for KBS communication to prevent man-in-the-middle attacks.

3. **Attestation Reports**:
   - The attestation report from the enclave must be cryptographically validated at the Identity Hub (CW).
   - Ensure the Identity Hub (CW) configuration holds a strict policy that matches correct measurements (MRENCLAVE / MRSIGNER) of the guest VM.
