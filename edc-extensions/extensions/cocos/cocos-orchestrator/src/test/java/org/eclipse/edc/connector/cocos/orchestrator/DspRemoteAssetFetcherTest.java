package org.eclipse.edc.connector.cocos.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.edc.connector.cocos.spi.InMemoryBufferRegistry;
import org.eclipse.edc.connector.controlplane.services.spi.catalog.CatalogService;
import org.eclipse.edc.connector.controlplane.services.spi.contractnegotiation.ContractNegotiationService;
import org.eclipse.edc.connector.controlplane.services.spi.transferprocess.TransferProcessService;
import org.eclipse.edc.connector.controlplane.contract.spi.types.negotiation.ContractNegotiation;
import org.eclipse.edc.connector.controlplane.contract.spi.types.agreement.ContractAgreement;
import org.eclipse.edc.connector.controlplane.transfer.spi.types.TransferProcess;
import org.eclipse.edc.spi.response.StatusResult;
import org.eclipse.edc.spi.result.ServiceResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DspRemoteAssetFetcherTest {

    private final CatalogService catalogService = mock(CatalogService.class);
    private final ContractNegotiationService contractNegotiationService = mock(ContractNegotiationService.class);
    private final TransferProcessService transferProcessService = mock(TransferProcessService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private DspRemoteAssetFetcher fetcher;

    @BeforeEach
    void setUp() {
        fetcher = new DspRemoteAssetFetcher(catalogService, contractNegotiationService, transferProcessService, objectMapper);
    }

    @Test
    @SuppressWarnings("unchecked")
    void fetch_success() throws Exception {
        String providerUrl = "http://provider/dsp";
        String assetId = "dataset-01";

        String catalogJson = "{\n" +
                "  \"@context\": \"http://www.w3.org/ns/dcat#\",\n" +
                "  \"dcat:dataset\": {\n" +
                "    \"@id\": \"" + assetId + "\",\n" +
                "    \"odrl:hasPolicy\": {\n" +
                "      \"@id\": \"policy-offer-01\"\n" +
                "    }\n" +
                "  }\n" +
                "}";

        when(catalogService.requestCatalog(any(), eq(providerUrl), anyString(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(StatusResult.success(catalogJson.getBytes())));

        var negotiation = mock(ContractNegotiation.class);
        when(negotiation.getId()).thenReturn("negotiation-01");
        when(contractNegotiationService.initiateNegotiation(any(), any()))
                .thenReturn(ServiceResult.success(negotiation));

        when(contractNegotiationService.getState("negotiation-01")).thenReturn("FINALIZED");

        var agreement = mock(ContractAgreement.class);
        when(agreement.getId()).thenReturn("agreement-01");
        when(contractNegotiationService.getForNegotiation("negotiation-01")).thenReturn(agreement);

        var transferProcess = mock(TransferProcess.class);
        when(transferProcessService.initiateTransfer(any(), any()))
                .thenReturn(ServiceResult.success(transferProcess));

        var fetchFuture = fetcher.fetch(providerUrl, assetId);

        var requestCaptor = ArgumentCaptor.forClass(org.eclipse.edc.connector.controlplane.transfer.spi.types.TransferRequest.class);

        verify(transferProcessService, timeout(2000)).initiateTransfer(any(), requestCaptor.capture());
        var transferRequest = requestCaptor.getValue();
        String bufferId = transferRequest.getDataDestination().getStringProperty("cocos.buffer.id");
        assertThat(bufferId).isNotNull();

        InMemoryBufferRegistry.complete(bufferId, "asset-data-bytes".getBytes());

        var resultBytes = fetchFuture.get(2, TimeUnit.SECONDS);
        assertThat(new String(resultBytes)).isEqualTo("asset-data-bytes");
    }

    @Test
    void fetch_multipleDatasets_matchesCorrectAsset() throws Exception {
        String providerUrl = "http://provider/dsp";
        String targetAssetId = "dataset-target";

        String catalogJson = "{\n" +
                "  \"@context\": {\n" +
                "    \"dcat\": \"http://www.w3.org/ns/dcat#\",\n" +
                "    \"odrl\": \"http://www.w3.org/ns/odrl/2/\"\n" +
                "  },\n" +
                "  \"dcat:dataset\": [\n" +
                "    {\n" +
                "      \"@id\": \"dataset-other\",\n" +
                "      \"odrl:hasPolicy\": { \"@id\": \"policy-other\" }\n" +
                "    },\n" +
                "    {\n" +
                "      \"@id\": \"" + targetAssetId + "\",\n" +
                "      \"odrl:hasPolicy\": { \"@id\": \"policy-target\" }\n" +
                "    }\n" +
                "  ]\n" +
                "}";

        when(catalogService.requestCatalog(any(), eq(providerUrl), anyString(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(StatusResult.success(catalogJson.getBytes())));

        var negotiation = mock(ContractNegotiation.class);
        when(negotiation.getId()).thenReturn("negotiation-02");
        when(contractNegotiationService.initiateNegotiation(any(), any()))
                .thenReturn(ServiceResult.success(negotiation));

        when(contractNegotiationService.getState("negotiation-02")).thenReturn("FINALIZED");

        var agreement = mock(ContractAgreement.class);
        when(agreement.getId()).thenReturn("agreement-02");
        when(contractNegotiationService.getForNegotiation("negotiation-02")).thenReturn(agreement);

        var transferProcess = mock(TransferProcess.class);
        when(transferProcessService.initiateTransfer(any(), any()))
                .thenReturn(ServiceResult.success(transferProcess));

        var fetchFuture = fetcher.fetch(providerUrl, targetAssetId);

        var requestCaptor = ArgumentCaptor.forClass(org.eclipse.edc.connector.controlplane.transfer.spi.types.TransferRequest.class);
        verify(transferProcessService, timeout(2000)).initiateTransfer(any(), requestCaptor.capture());
        var transferRequest = requestCaptor.getValue();
        String bufferId = transferRequest.getDataDestination().getStringProperty("cocos.buffer.id");

        InMemoryBufferRegistry.complete(bufferId, "correct-asset-data".getBytes());

        var resultBytes = fetchFuture.get(2, TimeUnit.SECONDS);
        assertThat(new String(resultBytes)).isEqualTo("correct-asset-data");
    }

    @Test
    void fetch_complexJsonLdWithGraph() throws Exception {
        String providerUrl = "http://provider/dsp";
        String targetAssetId = "dataset-in-graph";

        // Real standard EDC Catalogs enclose catalog items in a "@graph" node
        String catalogJson = "{\n" +
                "  \"@context\": \"http://www.w3.org/ns/dcat#\",\n" +
                "  \"@graph\": [\n" +
                "    {\n" +
                "      \"@id\": \"catalog-root\",\n" +
                "      \"dataset\": [\n" +
                "        {\n" +
                "          \"@id\": \"" + targetAssetId + "\",\n" +
                "          \"@type\": \"dcat:Dataset\",\n" +
                "          \"hasPolicy\": { \"@id\": \"policy-graph\" }\n" +
                "        }\n" +
                "      ]\n" +
                "    }\n" +
                "  ]\n" +
                "}";

        when(catalogService.requestCatalog(any(), eq(providerUrl), anyString(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(StatusResult.success(catalogJson.getBytes())));

        var negotiation = mock(ContractNegotiation.class);
        when(negotiation.getId()).thenReturn("negotiation-03");
        when(contractNegotiationService.initiateNegotiation(any(), any()))
                .thenReturn(ServiceResult.success(negotiation));

        when(contractNegotiationService.getState("negotiation-03")).thenReturn("FINALIZED");

        var agreement = mock(ContractAgreement.class);
        when(agreement.getId()).thenReturn("agreement-03");
        when(contractNegotiationService.getForNegotiation("negotiation-03")).thenReturn(agreement);

        var transferProcess = mock(TransferProcess.class);
        when(transferProcessService.initiateTransfer(any(), any()))
                .thenReturn(ServiceResult.success(transferProcess));

        var fetchFuture = fetcher.fetch(providerUrl, targetAssetId);

        var requestCaptor = ArgumentCaptor.forClass(org.eclipse.edc.connector.controlplane.transfer.spi.types.TransferRequest.class);
        verify(transferProcessService, timeout(2000)).initiateTransfer(any(), requestCaptor.capture());
        var transferRequest = requestCaptor.getValue();
        String bufferId = transferRequest.getDataDestination().getStringProperty("cocos.buffer.id");

        InMemoryBufferRegistry.complete(bufferId, "graph-data".getBytes());

        var resultBytes = fetchFuture.get(2, TimeUnit.SECONDS);
        assertThat(new String(resultBytes)).isEqualTo("graph-data");
    }

    @Test
    void fetch_assetNotFound_throwsException() {
        String providerUrl = "http://provider/dsp";
        String missingAssetId = "non-existent-asset";

        String catalogJson = "{\n" +
                "  \"@context\": \"http://www.w3.org/ns/dcat#\",\n" +
                "  \"dcat:dataset\": {\n" +
                "    \"@id\": \"dataset-01\",\n" +
                "    \"odrl:hasPolicy\": { \"@id\": \"policy-01\" }\n" +
                "  }\n" +
                "}";

        when(catalogService.requestCatalog(any(), eq(providerUrl), anyString(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(StatusResult.success(catalogJson.getBytes())));

        var fetchFuture = fetcher.fetch(providerUrl, missingAssetId);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> fetchFuture.get(2, TimeUnit.SECONDS))
                .hasMessageContaining("DSP consumer flow execution failed")
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasRootCauseMessage("Asset " + missingAssetId + " not found in catalog");
    }
}
