# TITAN EDC 0.16 Integration

The supplied launcher is an EDC `0.16.0` build. Cocos is kept as source modules
inside the launcher build so every Cocos jar is compiled against the same EDC
API and dependency set as the TITAN control plane and data plane.

## Stage the extension

From this repository, run the staging script against an unpacked copy of
`edc-launchers.zip`:

```bash
./edc-extensions/scripts/integrate-titan-launcher.sh /path/to/edc-launchers
```

The script is deliberately refusing to overwrite an existing
`extensions/cocos` directory. It adds the six Cocos projects to the launcher
settings and adds only the missing dependency aliases to the launcher's
existing EDC 0.16 version catalog.

## Build and verify

```bash
cd /path/to/edc-launchers
./gradlew \
  :extensions:cocos:cocos-spi:compileJava \
  :extensions:cocos:cocos-cli:compileJava \
  :extensions:cocos:cocos-computation-api:compileJava \
  :extensions:cocos:cocos-orchestrator:compileJava \
  :extensions:cocos:cocos-attestation-credential-service:compileJava \
  :extensions:cocos:cocos-data-sink:compileJava
./build-images.sh
```

The Dockerfiles already put `/app/extensions/*` ahead of the TITAN runtime
jars. Put these Cocos extensions in the control-plane image:

- `cocos-spi`
- `cocos-cli`
- `cocos-computation-api`
- `cocos-orchestrator`
- `cocos-attestation-credential-service`

Put these in the data-plane image:

- `cocos-spi`
- `cocos-cli`
- `cocos-data-sink`

The launcher build should copy the resulting jars into the corresponding
`launchers/controlplane/extensions` and `launchers/dataplane/extensions`
directories before Docker builds. Do not use the standalone Cocos 0.17 bundle
for this launcher; that would mix EDC API generations.

## Runtime configuration

Keep the launcher's existing TITAN, catalog, IdentityHub, and data-plane
configuration. Add the Cocos settings documented in the individual extension
READMEs, especially `cocos.cli.path`, the CVM/agent address mapping, KBS/ACS
URLs, and the connector participant identity. The Cocos orchestrator then uses
the normal EDC catalog, contract-negotiation, and transfer services before
passing the selected manifest and transferred bytes to the CVM.

## Verification status

All six Cocos production modules compile against the supplied EDC 0.16.0
launcher. The existing repository tests pass against the native EDC 0.17 build.
The remaining end-to-end test requires the TITAN participant's reachable DSP
endpoint, catalog asset/offer, policy, data sink, and UMU ACS URL; those values
are environment-specific and are not present in the launcher archive.
