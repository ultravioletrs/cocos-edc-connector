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
import org.eclipse.edc.connector.cocos.spi.CocosAgentReadyRegistry;

import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;


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
            CocosAgentCompletionRegistry.getOrCreate(job.getJobId());
            var readyFuture = CocosAgentReadyRegistry.getOrCreate(job.getJobId());

            startAgents(job);

            // Wait indefinitely for agent to report ready (via CVMS gRPC server RunResponse)
            // Agent may come online at any time after job submission
            try {
                readyFuture.get();
            } catch (Exception e) {
                throw new RuntimeException("Agent failed to report ready on Job " + job.getJobId(), e);
            } finally {
                CocosAgentReadyRegistry.remove(job.getJobId());
            }

            uploadAssets(job);

            // Wait indefinitely for agent to report run complete via CVMS gRPC server event
            var completionFuture = CocosAgentCompletionRegistry.getOrCreate(job.getJobId());
            try {
                completionFuture.get();
            } catch (Exception e) {
                throw new RuntimeException("Computation run failed or timed out on Job " + job.getJobId(), e);
            } finally {
                CocosAgentCompletionRegistry.remove(job.getJobId());
            }

            collectResults(job);
            job.setStatus(ComputationJob.Status.COMPLETED);
            towerCallbackClient.reportSuccess(job);
        } catch (Exception e) {
            monitor.severe("Computation job " + job.getJobId() + " failed", e);
            job.setStatus(ComputationJob.Status.FAILED);
            job.setErrorMessage(e.getMessage());
            towerCallbackClient.reportFailure(job);
        } finally {
            org.eclipse.edc.connector.cocos.spi.CocosManifestRegistry.remove(job.getJobId());
        }
    }

    private void startAgents(ComputationJob job) {
        job.setStatus(ComputationJob.Status.STARTING_AGENTS);
        for (var unit : job.getUnits()) {
            var result = cliService.startAgent(unit.getAgentAddress(), unit.getManifest());
            if (result.failed()) {
                throw new RuntimeException("Failed to start agent on " + unit.getAgentAddress() + ": " + result.getFailureDetail());
            }
            monitor.debug("CocosAI agent started on " + unit.getAgentAddress());
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
            byte[] data = resolveAsset(unit.getAgentAddress(), jobId, algo.getSource(), algo.getProviderConnectorUrl());
            var result = cliService.uploadAlgorithm(unit.getAgentAddress(), algo.getFilename(), data);
            if (result.failed()) {
                throw new RuntimeException("Failed to upload algorithm " + algo.getFilename()
                        + " to " + unit.getAgentAddress() + ": " + result.getFailureDetail());
            }
        }

        for (var dataset : manifest.getDatasets()) {
            byte[] data = resolveAsset(unit.getAgentAddress(), jobId, dataset.getSource(), dataset.getProviderConnectorUrl());
            var result = cliService.uploadDataset(unit.getAgentAddress(), dataset.getFilename(), data);
            if (result.failed()) {
                throw new RuntimeException("Failed to upload dataset " + dataset.getFilename()
                        + " to " + unit.getAgentAddress() + ": " + result.getFailureDetail());
            }
        }
    }



    private byte[] resolveAsset(String agentAddress, String jobId, AssetSource source, String providerConnectorUrl) {
        if (source.getType() == AssetSource.Type.FILE) {
            if (source.getContent() != null && !source.getContent().isEmpty()) {
                return java.util.Base64.getDecoder().decode(source.getContent().trim());
            }
            if (source.getUrl() != null && !source.getUrl().isEmpty()) {
                try {
                    java.nio.file.Path localPath = java.nio.file.Path.of(source.getUrl());
                    if (java.nio.file.Files.exists(localPath)) {
                        monitor.info("Reading local asset file from path: " + source.getUrl());
                        return java.nio.file.Files.readAllBytes(localPath);
                    } else {
                        monitor.warning("Local asset file not found at path: " + source.getUrl());
                    }
                } catch (Exception e) {
                    monitor.warning("Failed to read local file from url: " + source.getUrl(), e);
                }
            }
            return new byte[0];
        }
        // Propagate VM IP and job ID into the thread-local context so that both
        // AttestationBackedPresentationRequestService (consumer mode) and
        // ProviderAttestationPresentationService (provider mode) can resolve them
        // when generating attestation-backed VPs during the DSP credential exchange.
        CocosContextHolder.setActiveVmIp(agentAddress);
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
            var result = cliService.fetchResult(unit.getAgentAddress());
            if (result.failed()) {
                throw new RuntimeException("Failed to fetch result from " + unit.getAgentAddress() + ": " + result.getFailureDetail());
            }
            byte[] content = result.getContent();
            job.setResult(unit.getAgentAddress(), content);
            monitor.debug("Result collected from " + unit.getAgentAddress());

            // Persist result payload automatically to host disk for persistent availability
            if (content != null && content.length > 0) {
                try {
                    java.nio.file.Path resultsDir = java.nio.file.Paths.get("results");
                    if (!java.nio.file.Files.exists(resultsDir)) {
                        java.nio.file.Files.createDirectories(resultsDir);
                    }
                    java.nio.file.Path file = resultsDir.resolve(job.getJobId() + "-result.zip");
                    java.nio.file.Files.write(file, content);
                    monitor.info("Automatically persisted result artifact to " + file.toAbsolutePath());
                } catch (Exception e) {
                    monitor.warning("Failed to auto-persist result artifact to disk for job " + job.getJobId(), e);
                }
            }
        }
    }


    @Override
    public org.eclipse.edc.spi.result.Result<Void> stopJob(String jobId) {
        Object rawObserver = org.eclipse.edc.connector.cocos.spi.CocosAgentConnectionRegistry.get(jobId);
        if (rawObserver == null) {
            return org.eclipse.edc.spi.result.Result.failure("No active agent connection found for Job ID: " + jobId);
        }
        @SuppressWarnings("unchecked")
        io.grpc.stub.StreamObserver<org.eclipse.edc.connector.cocos.orchestrator.cvms.ServerStreamMessage> observer = 
                (io.grpc.stub.StreamObserver<org.eclipse.edc.connector.cocos.orchestrator.cvms.ServerStreamMessage>) rawObserver;

        try {
            var future = org.eclipse.edc.connector.cocos.spi.CocosAgentStopRegistry.getOrCreate(jobId);
            observer.onNext(org.eclipse.edc.connector.cocos.orchestrator.cvms.ServerStreamMessage.newBuilder()
                    .setStopComputation(org.eclipse.edc.connector.cocos.orchestrator.cvms.StopComputation.newBuilder()
                            .setComputationId(jobId)
                            .build())
                    .build());
            
            future.get(5, java.util.concurrent.TimeUnit.SECONDS);
            
            var jobOpt = jobStore.findById(jobId);
            if (jobOpt.isPresent()) {
                var job = jobOpt.get();
                job.setStatus(ComputationJob.Status.FAILED);
                job.setErrorMessage("Terminated by user request");
            }

            return org.eclipse.edc.spi.result.Result.success();
        } catch (Exception e) {
            return org.eclipse.edc.spi.result.Result.failure("Failed to stop computation: " + e.getMessage());
        } finally {
            org.eclipse.edc.connector.cocos.spi.CocosAgentStopRegistry.remove(jobId);
            org.eclipse.edc.connector.cocos.spi.CocosManifestRegistry.remove(jobId);
        }
    }

    @Override
    public java.util.concurrent.CompletableFuture<String> queryAgentState(String jobId) {
        Object rawObserver = org.eclipse.edc.connector.cocos.spi.CocosAgentConnectionRegistry.get(jobId);
        if (rawObserver == null) {
            return java.util.concurrent.CompletableFuture.failedFuture(new RuntimeException("No active agent connection found for Job ID: " + jobId));
        }
        @SuppressWarnings("unchecked")
        io.grpc.stub.StreamObserver<org.eclipse.edc.connector.cocos.orchestrator.cvms.ServerStreamMessage> observer = 
                (io.grpc.stub.StreamObserver<org.eclipse.edc.connector.cocos.orchestrator.cvms.ServerStreamMessage>) rawObserver;

        var future = org.eclipse.edc.connector.cocos.spi.CocosAgentStateRegistry.getOrCreate(jobId);
        try {
            observer.onNext(org.eclipse.edc.connector.cocos.orchestrator.cvms.ServerStreamMessage.newBuilder()
                    .setAgentStateReq(org.eclipse.edc.connector.cocos.orchestrator.cvms.AgentStateReq.newBuilder()
                            .setId(jobId)
                            .build())
                    .build());
        } catch (Exception e) {
            org.eclipse.edc.connector.cocos.spi.CocosAgentStateRegistry.remove(jobId);
            return java.util.concurrent.CompletableFuture.failedFuture(e);
        }
        
        future.orTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
              .whenComplete((res, ex) -> org.eclipse.edc.connector.cocos.spi.CocosAgentStateRegistry.remove(jobId));

        return future;
    }
}
