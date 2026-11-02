# Catalog-backed Cocos computation validation

This runbook records the catalog integration check against the EDC 0.16/DSP
2025-1 setup used by UMU. It deliberately contains no host credentials or
private keys.

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
part of the DSP negotiation path and must be reachable by the provider.

## Validation status

The following boundary was verified on 2026-11-02:

- the managed connector requested the separate provider catalog successfully;
- the catalog response contained the registered asset and offer;
- the real connected CVM accepted the manifest and entered `UPLOADING`;
- the connector passed catalog lookup and contract initiation without the
  previous null-provider exception.

The complete transfer was not marked successful in this runbook. The
catalog-backed computation reached the provider and the CVM, but the
disposable EDC 0.16 provider used for the local test generated a provider-side
negotiation ID that did not match the consumer-side ID when returning the
agreement. The consumer therefore received a 404 for the agreement callback
and timed out. This is a provider-harness interoperability/configuration
boundary, not evidence that the catalog request or CVM manifest path failed.
The disposable harness also used a test-only protocol validator and token; it
must not be used as a production authentication configuration.

An unauthenticated direct POST to the provider negotiation route returned
`401 Unauthorized`, while the catalog request itself returned `200`. The real
UMU dataspace test must therefore use the participant credentials, token/STS,
and ACS configuration supplied by UMU. Do not replace this with a forged token
or disable authentication in a production deployment.
Before calling this a full E2E success, repeat the same steps with the UMU
provider endpoint and its configured participant credentials/ACS and verify
that the result archive contains the expected output (`42` for the addition
smoke test).
