package org.eclipse.edc.connector.cocos.orchestrator;

import org.eclipse.edc.connector.cocos.spi.CocosCliService;
import org.eclipse.edc.connector.cocos.spi.CocosContextHolder;
import org.eclipse.edc.connector.cocos.spi.ComputationJobStore;
import org.eclipse.edc.connector.cocos.spi.ComputationOrchestrator;
import org.eclipse.edc.connector.cocos.spi.RemoteAssetFetcher;
import org.eclipse.edc.connector.cocos.spi.model.AssetSource;
import org.eclipse.edc.connector.cocos.spi.model.ComputationJob;
import org.eclipse.edc.connector.cocos.spi.model.ComputationRequest;
import org.eclipse.edc.connector.cocos.spi.model.ComputationUnit;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.result.Result;
import org.eclipse.edc.connector.cocos.spi.CocosAgentCompletionRegistry;

import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

public class ComputationOrchestratorImpl implements ComputationOrchestrator {

    private final CocosCliService cliService;
    private final ComputationJobStore jobStore;
    private final RemoteAssetFetcher remoteAssetFetcher;
    private final TowerCallbackClient towerCallbackClient;
    private final ExecutorService executor;
    private final Monitor monitor;

    public ComputationOrchestratorImpl(CocosCliService cliService,
                                       ComputationJobStore jobStore,
                                       RemoteAssetFetcher remoteAssetFetcher,
                                       TowerCallbackClient towerCallbackClient,
                                       ExecutorService executor,
                                       Monitor monitor) {
        this.cliService = cliService;
        this.jobStore = jobStore;
        this.remoteAssetFetcher = remoteAssetFetcher;
        this.towerCallbackClient = towerCallbackClient;
        this.executor = executor;
        this.monitor = monitor;
    }

    @Override
    public String start(ComputationRequest request) {
        var job = new ComputationJob(request.getJobId(), request.getTowerCallbackUrl(), request.getUnits());
        jobStore.save(job);
        executor.submit(() -> runJob(job));
        return job.getJobId();
    }

    @Override
    public Optional<ComputationJob> getJob(String jobId) {
        return jobStore.findById(jobId);
    }

    private void runJob(ComputationJob job) {
        try {
            // Pre-create the completion future to prevent race conditions where agent completes before future is created
            for (var unit : job.getUnits()) {
                CocosAgentCompletionRegistry.getOrCreate(unit.getVmIp());
            }

            startAgents(job);
            uploadAssets(job);

            // Wait for agent to report run complete via CVMS gRPC server event
            for (var unit : job.getUnits()) {
                var completionFuture = CocosAgentCompletionRegistry.getOrCreate(unit.getVmIp());
                try {
                    completionFuture.get(10, TimeUnit.MINUTES);
                } catch (Exception e) {
                    throw new RuntimeException("Computation run failed or timed out on VM " + unit.getVmIp(), e);
                } finally {
                    CocosAgentCompletionRegistry.remove(unit.getVmIp());
                }
            }

            collectResults(job);
            job.setStatus(ComputationJob.Status.COMPLETED);
            towerCallbackClient.reportSuccess(job);
        } catch (Exception e) {
            monitor.severe("Computation job " + job.getJobId() + " failed", e);
            job.setStatus(ComputationJob.Status.FAILED);
            job.setErrorMessage(e.getMessage());
            towerCallbackClient.reportFailure(job);
        }
    }

    private void startAgents(ComputationJob job) {
        job.setStatus(ComputationJob.Status.STARTING_AGENTS);
        for (var unit : job.getUnits()) {
            var result = cliService.startAgent(unit.getVmIp(), unit.getManifest());
            if (result.failed()) {
                throw new RuntimeException("Failed to start agent on " + unit.getVmIp() + ": " + result.getFailureDetail());
            }
            monitor.debug("CocosAI agent started on " + unit.getVmIp());
        }
    }

    private void uploadAssets(ComputationJob job) {
        job.setStatus(ComputationJob.Status.UPLOADING);
        for (var unit : job.getUnits()) {
            uploadUnitAssets(job.getJobId(), unit);
        }
    }

    private void uploadUnitAssets(String jobId, ComputationUnit unit) {
        var manifest = unit.getManifest();

        var algo = manifest.getAlgorithm();
        if (algo != null) {
            byte[] data = resolveAsset(unit.getVmIp(), jobId, algo.getSource(), algo.getProviderConnectorUrl());
            var result = cliService.uploadAlgorithm(unit.getVmIp(), algo.getFilename(), data);
            if (result.failed()) {
                throw new RuntimeException("Failed to upload algorithm " + algo.getFilename()
                        + " to " + unit.getVmIp() + ": " + result.getFailureDetail());
            }
        }

        for (var dataset : manifest.getDatasets()) {
            byte[] data = resolveAsset(unit.getVmIp(), jobId, dataset.getSource(), dataset.getProviderConnectorUrl());
            var result = cliService.uploadDataset(unit.getVmIp(), dataset.getFilename(), data);
            if (result.failed()) {
                throw new RuntimeException("Failed to upload dataset " + dataset.getFilename()
                        + " to " + unit.getVmIp() + ": " + result.getFailureDetail());
            }
        }
    }



    private byte[] resolveAsset(String vmIp, String jobId, AssetSource source, String providerConnectorUrl) {
        if (source.getType() == AssetSource.Type.FILE) {
            if (source.getContent() == null) {
                return new byte[0];
            }
            return java.util.Base64.getDecoder().decode(source.getContent().trim());
        }
        // Propagate VM IP and job ID into the thread-local context so that both
        // AttestationBackedPresentationRequestService (consumer mode) and
        // ProviderAttestationPresentationService (provider mode) can resolve them
        // when generating attestation-backed VPs during the DSP credential exchange.
        CocosContextHolder.setActiveVmIp(vmIp);
        if (jobId != null) {
            CocosContextHolder.setActiveJobId(jobId);
        }
        try {
            return remoteAssetFetcher.fetch(providerConnectorUrl, source.getUrl()).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while fetching remote asset from " + source.getUrl(), e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Failed to fetch remote asset from " + source.getUrl(), e.getCause());
        } finally {
            CocosContextHolder.clear();
        }
    }

    private void collectResults(ComputationJob job) {
        job.setStatus(ComputationJob.Status.COLLECTING_RESULTS);
        for (var unit : job.getUnits()) {
            var result = cliService.fetchResult(unit.getVmIp());
            if (result.failed()) {
                throw new RuntimeException("Failed to fetch result from " + unit.getVmIp() + ": " + result.getFailureDetail());
            }
            job.setResult(unit.getVmIp(), result.getContent());
            monitor.debug("Result collected from " + unit.getVmIp());
        }
    }
}
