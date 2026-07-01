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
        server = ServerBuilder.forPort(port)
                .addService(new CvmsServiceImpl())
                .intercept(new RemoteAddressInterceptor())
                .build()
                .start();
        monitor.info("CVMS gRPC Server started on port " + port);
    }

    public void stop() {
        if (server != null) {
            server.shutdown();
            monitor.info("CVMS gRPC Server stopped");
        }
    }

    private static final Context.Key<String> CLIENT_IP_KEY = Context.key("client-ip");

    private static class RemoteAddressInterceptor implements ServerInterceptor {
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
            Context context = Context.current().withValue(CLIENT_IP_KEY, ip);
            return Contexts.interceptCall(context, call, headers, next);
        }
    }

    private class CvmsServiceImpl extends ServiceGrpc.ServiceImplBase {

        @Override
        public StreamObserver<ClientStreamMessage> process(StreamObserver<ServerStreamMessage> responseObserver) {
            String clientIp = CLIENT_IP_KEY.get();
            monitor.info("Agent connected to CVMS from IP: " + clientIp + ", waiting for manifest...");

            ComputeManifest manifest = null;
            for (int i = 0; i < 60; i++) {
                manifest = CocosManifestRegistry.get(clientIp);
                if (manifest == null) {
                    manifest = CocosManifestRegistry.get("127.0.0.1");
                }
                if (manifest != null) {
                    break;
                }
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            if (manifest == null) {
                monitor.severe("No computation manifest registered for agent IP: " + clientIp);
                responseObserver.onError(new RuntimeException("No manifest registered for IP " + clientIp));
                return new StreamObserver<ClientStreamMessage>() {
                    @Override public void onNext(ClientStreamMessage value) {}
                    @Override public void onError(Throwable t) {}
                    @Override public void onCompleted() {}
                };
            }

            try {
                byte[] publicKeyDer = readPublicKeyDer(publicKeyPath);
                ComputationRunReq runReq = buildComputationRunReq(manifest, publicKeyDer);

                monitor.info("Sending computation run request to agent " + clientIp);
                responseObserver.onNext(ServerStreamMessage.newBuilder()
                        .setRunReq(runReq)
                        .build());
            } catch (Exception e) {
                monitor.severe("Failed to build or send computation manifest to agent " + clientIp, e);
                responseObserver.onError(e);
            }

            return new StreamObserver<ClientStreamMessage>() {
                @Override
                public void onNext(ClientStreamMessage value) {
                    if (value.hasAgentLog()) {
                        AgentLog log = value.getAgentLog();
                        monitor.info(String.format("[AgentLog] [%s] %s", log.getLevel(), log.getMessage()));
                    } else if (value.hasAgentEvent()) {
                        AgentEvent event = value.getAgentEvent();
                        monitor.info(String.format("[AgentEvent] [%s] %s", event.getEventType(), event.getStatus()));
                    } else if (value.hasRunRes()) {
                        RunResponse res = value.getRunRes();
                        if (res.getError() != null && !res.getError().isEmpty()) {
                            monitor.severe("Agent reported execution error: " + res.getError());
                        } else {
                            monitor.info("Agent reported run complete successfully");
                        }
                    }
                }

                @Override
                public void onError(Throwable t) {
                    monitor.severe("Error in stream from agent: " + t.getMessage(), t);
                }

                @Override
                public void onCompleted() {
                    monitor.info("Stream completed by agent");
                    responseObserver.onCompleted();
                }
            };
        }

        private ComputationRunReq buildComputationRunReq(ComputeManifest manifest, byte[] publicKeyDer) {
            ComputationRunReq.Builder builder = ComputationRunReq.newBuilder()
                    .setId(manifest.getId() != null ? manifest.getId() : "1")
                    .setName(manifest.getName() != null ? manifest.getName() : "EDC Computation")
                    .setDescription(manifest.getDescription() != null ? manifest.getDescription() : "");

            ByteString pubKeyByteString = ByteString.copyFrom(publicKeyDer);

            for (DatasetSpec datasetSpec : manifest.getDatasets()) {
                Dataset.Builder db = Dataset.newBuilder()
                        .setFilename(datasetSpec.getFilename())
                        .setHash(hexToByteString(datasetSpec.getHash()))
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
                Algorithm.Builder ab = Algorithm.newBuilder()
                        .setHash(hexToByteString(algorithmSpec.getHash()))
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
}
