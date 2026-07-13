package org.eclipse.edc.connector.cocos.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.edc.connector.cocos.spi.RemoteAssetFetcher;
import org.eclipse.edc.connector.cocos.spi.InMemoryBufferRegistry;
import org.eclipse.edc.connector.controlplane.services.spi.catalog.CatalogService;
import org.eclipse.edc.connector.controlplane.services.spi.contractnegotiation.ContractNegotiationService;
import org.eclipse.edc.connector.controlplane.services.spi.transferprocess.TransferProcessService;
import org.eclipse.edc.connector.controlplane.contract.spi.types.negotiation.ContractRequest;
import org.eclipse.edc.connector.controlplane.contract.spi.types.offer.ContractOffer;
import org.eclipse.edc.connector.controlplane.transfer.spi.types.TransferRequest;
import org.eclipse.edc.participantcontext.spi.types.ParticipantContext;
import org.eclipse.edc.spi.query.QuerySpec;
import org.eclipse.edc.spi.types.domain.DataAddress;
import org.eclipse.edc.policy.model.Policy;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class DspRemoteAssetFetcher implements RemoteAssetFetcher {

    private final CatalogService catalogService;
    private final ContractNegotiationService contractNegotiationService;
    private final TransferProcessService transferProcessService;
    private final ObjectMapper objectMapper;

    public DspRemoteAssetFetcher(CatalogService catalogService,
                                 ContractNegotiationService contractNegotiationService,
                                 TransferProcessService transferProcessService,
                                 ObjectMapper objectMapper) {
        this.catalogService = catalogService;
        this.contractNegotiationService = contractNegotiationService;
        this.transferProcessService = transferProcessService;
        this.objectMapper = objectMapper;
    }

    @Override
    public CompletableFuture<byte[]> fetch(String providerConnectorUrl, String assetUrl) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // 1. Fetch Catalog
                var participantContext = ParticipantContext.Builder.newInstance()
                        .participantContextId("default")
                        .identity("default")
                        .build();

                var catalogFuture = catalogService.requestCatalog(
                        participantContext,
                        providerConnectorUrl,
                        "dataspace-protocol-http",
                        null,
                        QuerySpec.max()
                );

                var catalogResult = catalogFuture.get(30, TimeUnit.SECONDS);
                if (catalogResult.failed()) {
                    throw new RuntimeException("Failed to fetch catalog: " + String.join(", ", catalogResult.getFailure().getMessages()));
                }

                byte[] catalogBytes = catalogResult.getContent();
                var offer = findOfferForAsset(catalogBytes, assetUrl);

                // 2. Initiate Contract Negotiation
                var contractRequest = ContractRequest.Builder.newInstance()
                        .protocol("dataspace-protocol-http")
                        .counterPartyAddress(providerConnectorUrl)
                        .contractOffer(offer)
                        .build();

                var negotiationResult = contractNegotiationService.initiateNegotiation(participantContext, contractRequest);
                if (negotiationResult.failed()) {
                    throw new RuntimeException("Failed to initiate contract negotiation: " + negotiationResult.getFailureDetail());
                }
                var negotiation = negotiationResult.getContent();
                String negotiationId = negotiation.getId();

                // 3. Poll Contract Negotiation State
                String agreementId = null;
                long start = System.currentTimeMillis();
                while (System.currentTimeMillis() - start < 60000) { // 1 minute timeout
                    String state = contractNegotiationService.getState(negotiationId);
                    if ("FINALIZED".equals(state) || "AGREED".equals(state) || "VERIFIED".equals(state)) {
                        var agreement = contractNegotiationService.getForNegotiation(negotiationId);
                        if (agreement != null) {
                            agreementId = agreement.getId();
                            break;
                        }
                    } else if ("TERMINATED".equals(state)) {
                        throw new RuntimeException("Contract negotiation was terminated");
                    }
                    Thread.sleep(500);
                }

                if (agreementId == null) {
                    throw new RuntimeException("Contract negotiation timed out without reaching agreement");
                }

                // 4. Request Transfer
                String bufferId = UUID.randomUUID().toString();
                var bufferFuture = InMemoryBufferRegistry.getOrCreate(bufferId);

                var destination = DataAddress.Builder.newInstance()
                        .type("InMemory")
                        .property("cocos.buffer.id", bufferId)
                        .build();

                var transferRequest = TransferRequest.Builder.newInstance()
                        .protocol("dataspace-protocol-http")
                        .counterPartyAddress(providerConnectorUrl)
                        .contractId(agreementId)
                        .dataDestination(destination)
                        .build();

                var transferResult = transferProcessService.initiateTransfer(participantContext, transferRequest);
                if (transferResult.failed()) {
                    InMemoryBufferRegistry.remove(bufferId);
                    throw new RuntimeException("Failed to initiate transfer process: " + transferResult.getFailureDetail());
                }

                // 5. Wait for Data Plane Stream to populate buffer
                try {
                    return bufferFuture.get(2, TimeUnit.MINUTES); // 2 minute transfer timeout
                } finally {
                    InMemoryBufferRegistry.remove(bufferId);
                }

            } catch (Exception e) {
                throw new RuntimeException("DSP consumer flow execution failed", e);
            }
        });
    }

    private ContractOffer findOfferForAsset(byte[] catalogBytes, String assetId) throws Exception {
        var root = objectMapper.readTree(catalogBytes);
        var datasetNode = findDataset(root, assetId);
        if (datasetNode == null) {
            throw new IllegalArgumentException("Asset " + assetId + " not found in catalog");
        }

        var policyNode = datasetNode.get("odrl:hasPolicy");
        if (policyNode == null) {
            policyNode = datasetNode.get("hasPolicy");
        }
        if (policyNode == null) {
            throw new IllegalArgumentException("Asset " + assetId + " has no policy offer in catalog");
        }

        String policyId = null;
        if (policyNode.isObject()) {
            policyId = policyNode.has("@id") ? policyNode.get("@id").asText() : (policyNode.has("id") ? policyNode.get("id").asText() : null);
        } else if (policyNode.isArray() && !policyNode.isEmpty()) {
            var firstPolicy = policyNode.get(0);
            policyId = firstPolicy.has("@id") ? firstPolicy.get("@id").asText() : (firstPolicy.has("id") ? firstPolicy.get("id").asText() : null);
        }

        if (policyId == null) {
            policyId = "offer-for-" + assetId;
        }

        var policy = Policy.Builder.newInstance()
                .target(assetId)
                .build();

        return ContractOffer.Builder.newInstance()
                .id(policyId)
                .assetId(assetId)
                .policy(policy)
                .build();
    }

    private JsonNode findDataset(JsonNode root, String assetId) {
        var datasets = root.get("dcat:dataset");
        if (datasets == null) {
            datasets = root.get("dataset");
        }
        if (datasets == null) {
            return findDatasetRecursively(root, assetId);
        }
        return searchDatasetNode(datasets, assetId);
    }

    private JsonNode searchDatasetNode(JsonNode datasets, String assetId) {
        if (datasets.isArray()) {
            for (var dataset : datasets) {
                if (matchesAssetId(dataset, assetId)) {
                    return dataset;
                }
            }
        } else if (datasets.isObject()) {
            if (matchesAssetId(datasets, assetId)) {
                return datasets;
            }
        }
        return null;
    }

    private boolean matchesAssetId(JsonNode dataset, String assetId) {
        var idNode = dataset.get("@id");
        if (idNode == null) {
            idNode = dataset.get("id");
        }
        return idNode != null && (idNode.asText().equals(assetId) || idNode.asText().endsWith("/" + assetId));
    }

    private JsonNode findDatasetRecursively(JsonNode node, String assetId) {
        if (node.isObject()) {
            if (node.has("@type") && (node.get("@type").asText().contains("Dataset") || node.get("@type").asText().contains("dataset"))) {
                if (matchesAssetId(node, assetId)) {
                    return node;
                }
            }
            var fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                var res = findDatasetRecursively(field.getValue(), assetId);
                if (res != null) {
                    return res;
                }
            }
        } else if (node.isArray()) {
            for (var child : node) {
                var res = findDatasetRecursively(child, assetId);
                if (res != null) {
                    return res;
                }
            }
        }
        return null;
    }
}
