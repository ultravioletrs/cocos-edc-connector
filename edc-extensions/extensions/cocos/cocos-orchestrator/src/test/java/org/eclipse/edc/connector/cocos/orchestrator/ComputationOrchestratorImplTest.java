package org.eclipse.edc.connector.cocos.orchestrator;

import org.eclipse.edc.connector.cocos.spi.CocosCliService;
import org.eclipse.edc.connector.cocos.spi.ComputationJobStore;
import org.eclipse.edc.connector.cocos.spi.RemoteAssetFetcher;
import org.eclipse.edc.connector.cocos.spi.CocosAgentCompletionRegistry;
import org.eclipse.edc.connector.cocos.spi.CocosAgentReadyRegistry;
import org.eclipse.edc.connector.cocos.spi.model.*;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.result.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ComputationOrchestratorImplTest {

    private final CocosCliService cliService = mock(CocosCliService.class);
    private final ComputationJobStore jobStore = mock(ComputationJobStore.class);
    private final RemoteAssetFetcher remoteAssetFetcher = mock(RemoteAssetFetcher.class);
    private final TowerCallbackClient callbackClient = mock(TowerCallbackClient.class);
    private final Monitor monitor = mock(Monitor.class);
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private ComputationOrchestratorImpl orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new ComputationOrchestratorImpl(cliService, jobStore, remoteAssetFetcher, callbackClient, executor, monitor);
    }

    @Test
    void start_submitsAndRunsJobSuccessfully() throws Exception {
        String jobId = "job-1";
        String vmIp = "192.168.1.100";
        var manifest = ComputeManifest.newInstance()
                .algorithm(AlgorithmSpec.newInstance()
                        .filename("algo.py")
                        .source(AssetSource.newInstance().type(AssetSource.Type.FILE).content("YWxnby1jb250ZW50").build())
                        .build())
                .datasets(List.of(DatasetSpec.newInstance()
                        .filename("data.csv")
                        .source(AssetSource.newInstance().type(AssetSource.Type.FILE).content("ZGF0YS1jb250ZW50").build())
                        .build()))
                .build();

        var unit = ComputationUnit.newInstance()
                .vmIp(vmIp)
                .manifest(manifest)
                .build();

        var request = ComputationRequest.newInstance()
                .jobId(jobId)
                .towerCallbackUrl("http://tower/callback")
                .units(List.of(unit))
                .build();

        when(cliService.startAgent(eq(vmIp), any())).thenReturn(Result.success());
        when(cliService.uploadAlgorithm(eq(vmIp), anyString(), any())).thenReturn(Result.success());
        when(cliService.uploadDataset(eq(vmIp), anyString(), any())).thenReturn(Result.success());
        when(cliService.fetchResult(eq(vmIp))).thenReturn(Result.success("result-bytes".getBytes()));

        // Trigger asynchronous ready and complete events to simulate agent behavior
        var scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.schedule(() -> {
            CocosAgentReadyRegistry.complete(jobId);
            CocosAgentCompletionRegistry.complete(jobId);
            scheduler.shutdown();
        }, 100, TimeUnit.MILLISECONDS);

        String returnedId = orchestrator.start(request);
        assertThat(returnedId).isEqualTo(jobId);

        // Wait for job execution to complete in the executor
        verify(callbackClient, timeout(2000)).reportSuccess(any(ComputationJob.class));
        verify(cliService).startAgent(eq(vmIp), any());
        verify(cliService).uploadAlgorithm(eq(vmIp), eq("algo.py"), any());
        verify(cliService).uploadDataset(eq(vmIp), eq("data.csv"), any());
        verify(cliService).fetchResult(eq(vmIp));
    }
}
