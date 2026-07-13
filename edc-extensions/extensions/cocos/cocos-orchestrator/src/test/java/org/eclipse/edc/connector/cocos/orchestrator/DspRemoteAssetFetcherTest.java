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
}
