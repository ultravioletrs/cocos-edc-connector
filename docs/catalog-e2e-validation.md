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

The final algorithm result was not marked successful. The UMU 0.16 launcher
used for this validation is a control-plane-only consumer and has no embedded
consumer dataplane. The connector therefore consumes the provider's ready EDR
directly, including its authorization property, instead of waiting forever for
an in-memory sink that is not present. This is the expected behavior for this
launcher layout and is covered by the connector build and integration run.

The remaining failure occurred inside the test CVM: after the connector
started `cocos-cli algo`, the guest agent stayed in `ReceivingAlgorithm` until
the 60-second CLI timeout. No result archive was produced, so the expected
`42` output has not been claimed. The catalog lookup, DSP negotiation,
contract agreement, provider transfer, EDR retrieval, and manifest handoff
were successful before that guest-side timeout. Capture `/var/log/cocos/*`
from the guest and the connector log around the timeout before changing the
UMU launcher or dataspace configuration.

The connector sets the EDC 0.16 `HttpData-PULL` transfer type on the transfer
request. The host validation command passed all eight `cocos-orchestrator`
tests and built the UMU-compatible
`cocos-orchestrator-0.16.0-titan.jar`.

The disposable provider used test-only validators and tokens; they must not be
used as production authentication configuration.

An unauthenticated direct POST to the provider negotiation route returned
`401 Unauthorized`, while the catalog request itself returned `200`. The real
UMU dataspace test must therefore use the participant credentials, token/STS,
and ACS configuration supplied by UMU. Do not replace this with a forged token
or disable authentication in a production deployment.
Before calling this a full E2E success, repeat the same steps with the UMU
provider endpoint and its configured participant credentials/STS/ACS, ensure
the provider creates a valid transfer token for the dataplane, restart the CVM
with a clean agent state, and verify that the result archive contains the
expected output (`42` for the addition smoke test). The production integration
should use UMU's real participant credential service rather than copying the
temporary validator jars from the local test harness.
