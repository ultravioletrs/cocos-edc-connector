package org.eclipse.edc.connector.cocos.orchestrator;

import com.google.protobuf.ByteString;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.stub.StreamObserver;
import org.eclipse.edc.connector.cocos.orchestrator.cvms.*;
import org.eclipse.edc.connector.cocos.spi.CocosAgentConnectionRegistry;
import org.eclipse.edc.connector.cocos.spi.CocosManifestRegistry;
import org.eclipse.edc.connector.cocos.spi.model.ComputeManifest;
import org.eclipse.edc.connector.cocos.spi.model.DatasetSpec;
import org.eclipse.edc.connector.cocos.spi.model.AlgorithmSpec;
import org.eclipse.edc.spi.monitor.Monitor;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.concurrent.ExecutionException;

public class CvmsGrpcServer {

    private final int port;
    private final String publicKeyPath;
    private final String kbsUrl;
    private final Monitor monitor;
    private Server server;

    public CvmsGrpcServer(int port, String publicKeyPath, String kbsUrl, Monitor monitor) {
        this.port = port;
        this.publicKeyPath = publicKeyPath;
        this.kbsUrl = kbsUrl;
        this.monitor = monitor;
    }

    public void start() throws IOException {
        CocosManifestRegistry.setOnManifestRegistered(this::sendRunRequestToConnectedAgent);
        server = ServerBuilder.forPort(port)
                .addService(new CvmsServiceImpl())
                .intercept(new RemoteAddressInterceptor(monitor))
                .build()
                .start();
        monitor.info("CVMS gRPC Server started on port " + port);
    }

    public void stop() {
        CocosManifestRegistry.setOnManifestRegistered(null);
        if (server != null) {
            server.shutdown();
            monitor.info("CVMS gRPC Server stopped");
        }
    }

    public void sendRunRequestToConnectedAgent(String jobId, ComputeManifest manifest) {
        Object rawObserver = CocosAgentConnectionRegistry.claimForJob(jobId);
        if (rawObserver == null) {
            return;
        }
        if (!CocosManifestRegistry.claimDispatch(jobId)) {
            monitor.debug("Skipping duplicate computation run request for Job ID: " + jobId);
            return;
        }
        @SuppressWarnings("unchecked")
        StreamObserver<ServerStreamMessage> observer = (StreamObserver<ServerStreamMessage>) rawObserver;
        try {
            byte[] publicKeyDer = readPublicKeyDer(publicKeyPath);
            ComputationRunReq runReq = buildComputationRunReq(manifest, publicKeyDer);
            monitor.info("Sending computation run request to existing connected agent for Job ID: " + jobId);
            synchronized (observer) {
                observer.onNext(ServerStreamMessage.newBuilder().setRunReq(runReq).build());
            }
        } catch (Exception e) {
            monitor.severe("Failed to send computation run request to connected agent: " + e.getMessage(), e);
        }
    }

    static final Context.Key<String> CLIENT_IP_KEY = Context.key("client-ip");
    static final Context.Key<String> JOB_ID_KEY = Context.key("job-id");
    static final Context.Key<String> CONNECTION_TYPE_KEY = Context.key("connection-type");

    private static class RemoteAddressInterceptor implements ServerInterceptor {
        private final Monitor monitor;

        private RemoteAddressInterceptor(Monitor monitor) {
            this.monitor = monitor;
        }

        @Override
        public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                ServerCall<ReqT, RespT> call,
                Metadata headers,
                ServerCallHandler<ReqT, RespT> next) {
            SocketAddress remoteAddr = call.getAttributes().get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR);
            String ip = "";
            if (remoteAddr instanceof InetSocketAddress) {
                ip = ((InetSocketAddress) remoteAddr).getAddress().getHostAddress();
            }

            Metadata.Key<String> jobIdMetaKey = Metadata.Key.of("job-id", Metadata.ASCII_STRING_MARSHALLER);
            String jobId = headers.get(jobIdMetaKey);
            if (jobId == null) {
                jobId = "";
            }

            Metadata.Key<String> connTypeMetaKey = Metadata.Key.of("connection-type", Metadata.ASCII_STRING_MARSHALLER);
            String connectionType = headers.get(connTypeMetaKey);
            if (connectionType == null) {
                connectionType = "agent";
            }

            monitor.info("CVMS metadata keys=" + headers.keys()
                    + ", job-id=" + jobId + ", connection-type=" + connectionType);

            Context context = Context.current()
                    .withValue(CLIENT_IP_KEY, ip)
                    .withValue(JOB_ID_KEY, jobId)
                    .withValue(CONNECTION_TYPE_KEY, connectionType);
            return Contexts.interceptCall(context, call, headers, next);
        }
    }

    private static StreamObserver<ClientStreamMessage> emptyObserver() {
        return new StreamObserver<ClientStreamMessage>() {
            @Override public void onNext(ClientStreamMessage value) {}
            @Override public void onError(Throwable t) {}
            @Override public void onCompleted() {}
        };
    }

    class CvmsServiceImpl extends ServiceGrpc.ServiceImplBase {

        @Override
        public StreamObserver<ClientStreamMessage> process(StreamObserver<ServerStreamMessage> responseObserver) {
            String clientIp = CLIENT_IP_KEY.get();
            String jobId = JOB_ID_KEY.get();
            String connectionType = CONNECTION_TYPE_KEY.get();

            monitor.info("Agent connected to CVMS from IP: " + clientIp
                    + " with Job ID: " + jobId
                    + ", connection-type: " + connectionType
                    + ", waiting for manifest...");

            if (jobId == null || jobId.isEmpty()) {
                String activeJob = CocosManifestRegistry.getFirstRegisteredJobId();
                if (activeJob != null) {
                    jobId = activeJob;
                    monitor.info("Using active registered job ID: " + jobId + " for connection-type: " + connectionType);
                }
            }

            final String effectiveJobId = jobId;

            // Log-forwarder connections: forward AgentLog and AgentEvent messages immediately (no RunReq, no wait for manifest).
            if ("log-forwarder".equals(connectionType)) {
                monitor.info("Log-forwarder stream initialized from " + clientIp + " for Job ID: " + effectiveJobId);
                return new StreamObserver<ClientStreamMessage>() {
                    @Override
                    public void onNext(ClientStreamMessage value) {
                        if (value.hasAgentLog()) {
                            AgentLog log = value.getAgentLog();
                            monitor.info(String.format("[AgentLog] [%s] %s", log.getLevel(), log.getMessage()));
                        } else if (value.hasAgentEvent()) {
                            AgentEvent event = value.getAgentEvent();
                            monitor.info(String.format("[AgentEvent] [%s] %s (job: %s)", event.getEventType(), event.getStatus(), event.getComputationId()));
                            String targetJobId = (event.getComputationId() != null && !event.getComputationId().isEmpty())
                                    ? event.getComputationId() : effectiveJobId;
                            if (targetJobId == null || targetJobId.isEmpty()) {
                                targetJobId = CocosManifestRegistry.getFirstRegisteredJobId();
                            }
                            handleAgentEvent(targetJobId, event);
                        }
                    }
                    @Override
                    public void onError(Throwable t) {
                        monitor.warning("Log-forwarder stream error from " + clientIp + ": " + t.getMessage());
                    }
                    @Override
                    public void onCompleted() {
                        monitor.info("Log-forwarder stream completed from " + clientIp);
                        responseObserver.onCompleted();
                    }
                };
            }

            // Agent connection: register immediately upon connection so status queries and stop requests work anytime.
            if ("agent".equals(connectionType)) {
                if (effectiveJobId == null || effectiveJobId.isEmpty()) {
                    Object previous = CocosAgentConnectionRegistry.registerIdle(responseObserver);
                    if (previous instanceof StreamObserver<?> oldObserver && previous != responseObserver) {
                        @SuppressWarnings("unchecked")
                        StreamObserver<ServerStreamMessage> oldStream = (StreamObserver<ServerStreamMessage>) oldObserver;
                        oldStream.onCompleted();
                    }
                    monitor.info("Registered idle agent connection for " + clientIp + "; waiting for a computation");
                } else {
                    CocosAgentConnectionRegistry.register(effectiveJobId, responseObserver);
                }
                monitor.info("Registered agent connection for Job ID: " + effectiveJobId + " from IP: " + clientIp);

                // Asynchronously wait for or consume the manifest without blocking the gRPC transport thread.
                if (effectiveJobId != null && !effectiveJobId.isEmpty()) CocosManifestRegistry.waitForManifest(effectiveJobId).thenAccept(manifest -> {
                    if (!CocosManifestRegistry.claimDispatch(effectiveJobId)) {
                        monitor.debug("Skipping duplicate computation run request for Job ID: " + effectiveJobId);
                        return;
                    }
                    try {
                        byte[] publicKeyDer = readPublicKeyDer(publicKeyPath);
                        ComputationRunReq runReq = buildComputationRunReq(manifest, publicKeyDer);
                        monitor.info("Sending computation run request to agent " + clientIp + " for Job ID: " + effectiveJobId);
                        synchronized (responseObserver) {
                            responseObserver.onNext(ServerStreamMessage.newBuilder().setRunReq(runReq).build());
                        }
                    } catch (Exception e) {
                        monitor.severe("Failed to build or send computation manifest to agent " + clientIp, e);
                    }
                });
            }

            final String resolvedKey = jobId;

            return new StreamObserver<ClientStreamMessage>() {

                private String jobKey() {
                    return resolvedKey.isEmpty() ? CocosAgentConnectionRegistry.getJobId(responseObserver) : resolvedKey;
                }

                @Override
                public void onNext(ClientStreamMessage value) {
                    if (value.hasAgentLog()) {
                        AgentLog log = value.getAgentLog();
                        monitor.info(String.format("[AgentLog] [%s] %s", log.getLevel(), log.getMessage()));
                    } else if (value.hasAgentEvent()) {
                        AgentEvent event = value.getAgentEvent();
                        monitor.info(String.format("[AgentEvent] [%s] %s (job: %s)", event.getEventType(), event.getStatus(), event.getComputationId()));
                        String targetJobId = (event.getComputationId() != null && !event.getComputationId().isEmpty())
                                ? event.getComputationId() : jobKey();
                        handleAgentEvent(targetJobId, event);
                    } else if (value.hasRunRes()) {
                        RunResponse res = value.getRunRes();
                        if (res.getError() != null && !res.getError().isEmpty()) {
                            monitor.severe("Agent reported execution error: " + res.getError());
                            org.eclipse.edc.connector.cocos.spi.CocosAgentCompletionRegistry.fail(
                                    jobKey(), res.getError());
                            org.eclipse.edc.connector.cocos.spi.CocosAgentReadyRegistry.fail(
                                    jobKey(), res.getError());
                        } else {
                            monitor.info("Agent reported run complete successfully");
                            org.eclipse.edc.connector.cocos.spi.CocosAgentReadyRegistry.complete(jobKey());
                        }
                    } else if (value.hasStopComputationRes()) {
                        StopComputationResponse res = value.getStopComputationRes();
                        monitor.info("Agent reported stop computation response: " + res.getMessage());
                        org.eclipse.edc.connector.cocos.spi.CocosAgentStopRegistry.complete(jobKey(), res.getMessage());
                    } else if (value.hasAgentStateRes()) {
                        AgentStateRes res = value.getAgentStateRes();
                        monitor.info("Agent reported state: " + res.getState());
                        org.eclipse.edc.connector.cocos.spi.CocosAgentStateRegistry.complete(jobKey(), res.getState());
                    }
                }

                @Override
                public void onError(Throwable t) {
                    monitor.severe("Error in stream from agent: " + t.getMessage(), t);
                    if ("agent".equals(connectionType)) {
                        org.eclipse.edc.connector.cocos.spi.CocosAgentConnectionRegistry.unregister(jobKey() == null ? CocosAgentConnectionRegistry.IDLE_KEY : jobKey(), responseObserver);
                    }
                }

                @Override
                public void onCompleted() {
                    monitor.info("Stream completed by agent");
                    if ("agent".equals(connectionType)) {
                        org.eclipse.edc.connector.cocos.spi.CocosAgentConnectionRegistry.unregister(jobKey() == null ? CocosAgentConnectionRegistry.IDLE_KEY : jobKey(), responseObserver);
                    }
                    responseObserver.onCompleted();
                }
            };
        }

        private void handleAgentEvent(String targetJobId, AgentEvent event) {
            if (targetJobId == null || targetJobId.isEmpty()) {
                return;
            }
            String status = event.getStatus();
            String eventType = event.getEventType();

            if ("InProgress".equalsIgnoreCase(status) || "ReceivingAlgorithm".equalsIgnoreCase(eventType)) {
                CocosManifestRegistry.markAccepted(targetJobId);
                org.eclipse.edc.connector.cocos.spi.CocosAgentReadyRegistry.complete(targetJobId);
            }

            if ("Ready".equalsIgnoreCase(status) || "Completed".equalsIgnoreCase(status)
                    || "ResultsConsumed".equalsIgnoreCase(eventType) || "ConsumingResults".equalsIgnoreCase(eventType)
                    || "Complete".equalsIgnoreCase(eventType)) {
                org.eclipse.edc.connector.cocos.spi.CocosAgentCompletionRegistry.complete(targetJobId);
            } else if ("Failed".equalsIgnoreCase(status) || "Failed".equalsIgnoreCase(eventType) || "RunFailed".equalsIgnoreCase(eventType)) {
                org.eclipse.edc.connector.cocos.spi.CocosAgentCompletionRegistry.fail(targetJobId, "Agent computation execution failed");
            }
        }
    }

    private ComputationRunReq buildComputationRunReq(ComputeManifest manifest, byte[] publicKeyDer) {
            ComputationRunReq.Builder builder = ComputationRunReq.newBuilder()
                    .setId(manifest.getId() != null ? manifest.getId() : "1")
                    .setName(manifest.getName() != null ? manifest.getName() : "EDC Computation")
                    .setDescription(manifest.getDescription() != null ? manifest.getDescription() : "");

            ByteString pubKeyByteString = ByteString.copyFrom(publicKeyDer);

            for (DatasetSpec datasetSpec : manifest.getDatasets()) {
                String dsHash = datasetSpec.getHash();
                if ((dsHash == null || dsHash.isEmpty()) && datasetSpec.getSource() != null && datasetSpec.getSource().getContent() != null) {
                    try {
                        byte[] contentBytes = java.util.Base64.getDecoder().decode(datasetSpec.getSource().getContent().trim());
                        byte[] digest = java.security.MessageDigest.getInstance("SHA3-256").digest(contentBytes);
                        dsHash = java.util.HexFormat.of().formatHex(digest);
                    } catch (Exception ignored) {}
                }
                Dataset.Builder db = Dataset.newBuilder()
                        .setFilename(datasetSpec.getFilename())
                        .setHash(hexToByteString(dsHash))
                        .setUserKey(pubKeyByteString);

                if (datasetSpec.getSource() != null) {
                    Source.Builder sb = Source.newBuilder()
                            .setType("http")
                            .setUrl(datasetSpec.getSource().getUrl() != null ? datasetSpec.getSource().getUrl() : "")
                            .setEncrypted(datasetSpec.getSource().isEncrypted())
                            .setKbsResourcePath(datasetSpec.getSource().getKbsResourcePath() != null ? datasetSpec.getSource().getKbsResourcePath() : "");
                    db.setSource(sb);

                    if (datasetSpec.getSource().isEncrypted() && kbsUrl != null && !kbsUrl.isEmpty()) {
                        db.setKbs(KBSConfig.newBuilder()
                                .setUrl(kbsUrl)
                                .setEnabled(true)
                                .build());
                    }
                }
                builder.addDatasets(db.build());
            }

            AlgorithmSpec algorithmSpec = manifest.getAlgorithm();
            if (algorithmSpec != null) {
                String algoHash = algorithmSpec.getHash();
                if ((algoHash == null || algoHash.isEmpty()) && algorithmSpec.getSource() != null && algorithmSpec.getSource().getContent() != null) {
                    try {
                        byte[] contentBytes = java.util.Base64.getDecoder().decode(algorithmSpec.getSource().getContent().trim());
                        byte[] digest = java.security.MessageDigest.getInstance("SHA3-256").digest(contentBytes);
                        algoHash = java.util.HexFormat.of().formatHex(digest);
                    } catch (Exception ignored) {}
                }
                Algorithm.Builder ab = Algorithm.newBuilder()
                        .setHash(hexToByteString(algoHash))
                        .setUserKey(pubKeyByteString);

                if (algorithmSpec.getType() != null) {
                    ab.setAlgoType(algorithmSpec.getType());
                }

                if (algorithmSpec.getSource() != null) {
                    Source.Builder sb = Source.newBuilder()
                            .setType("http")
                            .setUrl(algorithmSpec.getSource().getUrl() != null ? algorithmSpec.getSource().getUrl() : "")
                            .setEncrypted(algorithmSpec.getSource().isEncrypted())
                            .setKbsResourcePath(algorithmSpec.getSource().getKbsResourcePath() != null ? algorithmSpec.getSource().getKbsResourcePath() : "");
                    ab.setSource(sb);

                    if (algorithmSpec.getSource().isEncrypted() && kbsUrl != null && !kbsUrl.isEmpty()) {
                        ab.setKbs(KBSConfig.newBuilder()
                                .setUrl(kbsUrl)
                                .setEnabled(true)
                                .build());
                    }
                }
                builder.setAlgorithm(ab.build());
            }

            builder.addResultConsumers(ResultConsumer.newBuilder().setUserKey(pubKeyByteString).build());

            String agentPort = "7001";
            if (manifest.getAgentConfig() != null && manifest.getAgentConfig().getPort() > 0) {
                agentPort = String.valueOf(manifest.getAgentConfig().getPort());
            }

            builder.setAgentConfig(AgentConfig.newBuilder()
                    .setPort(agentPort)
                    .setAttestedTls(false)
                    .build());

            return builder.build();
        }

        private ByteString hexToByteString(String hex) {
            if (hex == null || hex.isEmpty()) {
                return ByteString.copyFrom(new byte[32]);
            }
            try {
                byte[] bytes = new byte[hex.length() / 2];
                for (int i = 0; i < bytes.length; i++) {
                    int index = i * 2;
                    int v = Integer.parseInt(hex.substring(index, index + 2), 16);
                    bytes[i] = (byte) v;
                }
                return ByteString.copyFrom(bytes);
            } catch (Exception e) {
                return ByteString.copyFrom(new byte[32]);
            }
        }

        private byte[] readPublicKeyDer(String path) {
            try {
                String content = new String(Files.readAllBytes(Paths.get(path)));
                String clean = content
                        .replace("-----BEGIN PUBLIC KEY-----", "")
                        .replace("-----END PUBLIC KEY-----", "")
                        .replaceAll("\\s+", "");
                return Base64.getDecoder().decode(clean);
            } catch (Exception e) {
                throw new RuntimeException("Failed to read public key from " + path, e);
            }
        }
}
