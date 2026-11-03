# Catalog-backed Cocos computation validation

This runbook records the catalog integration check against the EDC 0.16/DSP
2025-1 setup used by UMU. It deliberately contains no host credentials or
private keys.

The UMU launcher must be used as the runtime base. The standalone deployment
previously tested on the host was EDC `0.18.0`, while the supplied UMU launcher
and its provider are EDC `0.16.0`; those versions must not be mixed. Build the
launcher-integrated runtime with the procedure in
[`TITAN_0.16_INTEGRATION.md`](../edc-extensions/TITAN_0.16_INTEGRATION.md).

## Verified flow

1. Register an asset at the provider connector. The asset must have an HTTP
   data address and a contract definition whose selector matches the asset ID.
2. Request the provider catalog from the consumer management API:

   ```bash
   curl -sS -X POST \
     -H 'Content-Type: application/json' \
     --data '{
       "@context": {"@vocab": "https://w3id.org/edc/v0.0.1/ns/"},
       "@type": "CatalogRequest",
       "counterPartyAddress": "<PROVIDER_PROTOCOL_URL>",
       "counterPartyId": "<PROVIDER_PARTICIPANT_ID>",
       "protocol": "dataspace-protocol-http:2025-1",
       "additionalScopes": [],
       "querySpec": {"offset": 0, "limit": 50, "filterExpression": []}
     }' \
     <CONSUMER_MANAGEMENT_URL>/api/management/v3/catalog/request
   ```

   The response must contain the requested dataset ID, an `hasPolicy` offer,
   and the provider `participantId`.

3. Submit a Cocos computation whose algorithm source identifies the catalog
   asset and provider protocol endpoint:

   ```json
   {
     "jobId": "<CVM_JOB_ID>",
     "units": [{
       "agentAddress": "<CVM_AGENT_ADDRESS>",
       "manifest": {
         "id": "<CVM_JOB_ID>",
         "algorithm": {
           "filename": "addition.py",
           "type": "python",
           "providerConnectorUrl": "<PROVIDER_PROTOCOL_URL>",
           "source": {
             "type": "URL",
             "url": "<CATALOG_ASSET_ID>",
             "encrypted": false
           }
         }
       }
     }]
   }
   ```

4. Poll `/api/management/cocos/computations/<jobId>` until `COMPLETED` or
   `FAILED`, then download `/result` and inspect the result archive.

## Code behavior covered by this check

The connector uses `dataspace-protocol-http:2025-1` consistently for catalog,
contract negotiation, and transfer. When reconstructing a `ContractOffer`
from the catalog, it copies the catalog root `participantId` into the policy
assigner. EDC uses that value as the provider ID when creating the contract
request; omitting it causes a null provider ID and an immediate negotiation
failure.

The contract request also carries a consumer-side DSP callback base. By
default this is `http://127.0.0.1:<web.http.protocol.port>/api/protocol/2025-1`;
deployments with a routable hostname should set `cocos.dsp.callback.url` to
the corresponding externally reachable protocol base. The callback address is
part of the DSP negotiation path and must be reachable by the provider. The
Cocos orchestrator registers a small `POST` receiver at this base path so EDC
callback envelopes receive an HTTP 200; the EDC DSP negotiation routes under
`/negotiations/{id}/...` remain owned by the EDC protocol extension.

For EDC 0.16, the attestation extension resolves the computation job store
after extension graph construction. This avoids a dependency cycle through
DCP core while retaining the same management endpoint behavior.

## Validation status

The following boundary was verified on 2026-11-02 and 2026-11-03:

- the managed connector requested the separate provider catalog successfully;
- the catalog response contained the registered asset and offer;
- the real connected CVM accepted the manifest and entered `UPLOADING`;
- the connector passed catalog lookup and contract initiation without the
  previous null-provider exception;
- the provider reached the agreement stage and sent the agreement callback
  through the EDC 0.16 DSP webhook path;
- the UMU dataplane public API was started with its required separate public
  binding (`web.http.public.port` and `web.http.public.path=/public`), and the
  provider selector advertised `HttpData-PULL` at that endpoint;
- the provider created a state-600 transfer with a signed EDR endpoint; and
- the connector fetched the algorithm bytes from that EDR and reached the
  real CVM's algorithm-upload phase.

The UMU 0.16 launcher used for this validation is a control-plane-only
consumer and has no embedded consumer dataplane. The connector therefore
consumes the provider's ready EDR directly, including its authorization
property, instead of waiting for an in-memory sink that is not present.

Two connector-side execution bugs were found and fixed:

1. A catalog URL asset was fetched only after the manifest was dispatched, so
   the guest received an empty hash before the connector uploaded the real
   bytes. Remote assets are now resolved before manifest registration, their
   SHA3-256 hash is populated, and the same bytes are reused for upload.
2. `cocos-cli` reports some failures on stderr while exiting with code zero.
   The connector now evaluates stdout and stderr together and fails the job
   promptly instead of leaving it in `ReceivingAlgorithm`.

The connector also clears stale manifest/dispatch state when a job ID is
reused. This keeps a reconnecting CVM idle until the new lifecycle is ready.

The connector sets the EDC 0.16 `HttpData-PULL` transfer type on the transfer
request. The host validation command passed all eight `cocos-orchestrator`
tests and built the UMU-compatible
`cocos-orchestrator-0.16.0-titan.jar`.

### Self-hosted catalog check

To test without waiting for UMU's live participant, a disposable EDC 0.16
provider and UMU dataplane were started on the TDX host. The provider exposed
the asset `catalog-local-addition-final-20261103`, and the consumer retrieved
it through the catalog at HTTP 200. The catalog response included the asset's
`HttpData-PULL` distribution and `provider-local` participant ID. The provider
dataplane self-registered at `/api/control/v1/dataplanes`, created the signed
EDR, and the connector reached `cocos-cli algo` for the real CVM.

After deploying the tested 0.16 jars and rebooting the CVM into a clean agent
state, the catalog-backed addition completed successfully on 2026-11-03:

- job/CVM/manifest ID: `sami-uef-desat-20261009`;
- catalog asset: `catalog-e2e-final`;
- agent state: `Complete`;
- guest log: `Addition complete: 20 + 22 = 42`.

The disposable provider, dataplane, proxies, and fixture server were used only
for this local validation. The test CVM and the UMU-compatible connector were
left running for further joint testing.

The disposable provider used test-only validators and tokens; they must not be
used as production authentication configuration.

An unauthenticated direct POST to the provider negotiation route returned
`401 Unauthorized`, while the catalog request itself returned `200`. The real
UMU dataspace test must therefore use the participant credentials, token/STS,
and ACS configuration supplied by UMU. Do not replace this with a forged token
or disable authentication in a production deployment.
The local flow is complete up to the UMU boundary. The remaining joint test
requires UMU's real provider catalog URL, participant identity/credentials,
STS or token configuration, dataplane endpoint/policies, and ACS endpoint. The
production integration should use UMU's participant credential service rather
than the temporary validators from this local harness.
