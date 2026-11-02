package org.eclipse.edc.connector.cocos.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.edc.connector.cocos.spi.CocosCliService;
import org.eclipse.edc.connector.cocos.spi.ComputationJobStore;
import org.eclipse.edc.connector.cocos.spi.ComputationOrchestrator;
import org.eclipse.edc.connector.cocos.spi.RemoteAssetFetcher;
import org.eclipse.edc.connector.controlplane.services.spi.catalog.CatalogService;
import org.eclipse.edc.connector.controlplane.services.spi.contractnegotiation.ContractNegotiationService;
import org.eclipse.edc.connector.controlplane.services.spi.transferprocess.TransferProcessService;
import org.eclipse.edc.http.spi.EdcHttpClient;
import org.eclipse.edc.runtime.metamodel.annotation.Extension;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.runtime.metamodel.annotation.Provider;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;

import java.util.concurrent.Executors;

@Extension(CocosOrchestratorExtension.NAME)
public class CocosOrchestratorExtension implements ServiceExtension {

    public static final String NAME = "CocosAI Orchestrator";

    private InMemoryComputationJobStore jobStore;
    private CvmsGrpcServer cvmsServer;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void initialize(ServiceExtensionContext context) {
        jobStore = new InMemoryComputationJobStore();

        int cvmsPort = context.getSetting("cocos.cvms.port", 7002);
        String publicKeyPath = context.getSetting("cocos.cli.publicKey.path", "public.pem");
        String kbsUrl = context.getSetting("cocos.kbs.url", "http://localhost:8090");

        cvmsServer = new CvmsGrpcServer(cvmsPort, publicKeyPath, kbsUrl, context.getMonitor());
        try {
            cvmsServer.start();
        } catch (Exception e) {
            throw new RuntimeException("Failed to start CVMS gRPC Server", e);
        }
    }

    @Override
    public void shutdown() {
        if (cvmsServer != null) {
            cvmsServer.stop();
        }
    }

    @Provider
    public ComputationJobStore computationJobStore() {
        return jobStore;
    }

    @Provider
    public ComputationOrchestrator computationOrchestrator(ServiceExtensionContext context) {
        // Resolve services after EDC 0.16 has built its extension graph. The
        // eager field injections create a cycle through DCP core when the
        // attestation presentation service is enabled.
        var cliService = context.getService(CocosCliService.class);
        var httpClient = context.getService(EdcHttpClient.class);
        var catalogService = context.getService(CatalogService.class);
        var contractNegotiationService = context.getService(ContractNegotiationService.class);
        var transferProcessService = context.getService(TransferProcessService.class);
        var mapper = new ObjectMapper();
        var monitor = context.getMonitor();
        var callbackClient = new TowerCallbackClient(httpClient, mapper, monitor);
        var executor = Executors.newCachedThreadPool();
        var webService = context.getService(org.eclipse.edc.web.spi.WebService.class);
        webService.registerResource("protocol", new CocosDspCallbackController());
        var protocolPort = context.getSetting("web.http.protocol.port", 49205);
        var callbackUrl = context.getSetting("cocos.dsp.callback.url",
                "http://127.0.0.1:" + protocolPort + "/api/protocol/2025-1");
        var remoteAssetFetcher = new DspRemoteAssetFetcher(catalogService, contractNegotiationService,
                transferProcessService, mapper, callbackUrl);
        return new ComputationOrchestratorImpl(cliService, jobStore, remoteAssetFetcher, callbackClient, executor, monitor);
    }
}
