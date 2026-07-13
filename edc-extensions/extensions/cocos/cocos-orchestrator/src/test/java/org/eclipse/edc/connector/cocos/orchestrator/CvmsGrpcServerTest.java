package org.eclipse.edc.connector.cocos.orchestrator;

import io.grpc.Context;
import io.grpc.stub.StreamObserver;
import org.eclipse.edc.connector.cocos.orchestrator.cvms.*;
import org.eclipse.edc.connector.cocos.spi.CocosManifestRegistry;
import org.eclipse.edc.connector.cocos.spi.CocosAgentCompletionRegistry;
import org.eclipse.edc.connector.cocos.spi.CocosAgentReadyRegistry;
import org.eclipse.edc.connector.cocos.spi.model.ComputeManifest;
import org.eclipse.edc.spi.monitor.Monitor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CvmsGrpcServerTest {

    private final Monitor monitor = mock(Monitor.class);
    private Path tempKeyFile;
    private CvmsGrpcServer server;
    private CvmsGrpcServer.CvmsServiceImpl service;

    @BeforeEach
    void setUp() throws IOException {
        tempKeyFile = Files.createTempFile("temp-public-key", ".pem");
        String dummyPubKey = "-----BEGIN PUBLIC KEY-----\n" +
                "MFwwDQYJKoZIhvcNAQEBBQADSwAwSAJBAL6m8zW4R12x/YI6lQ2t3XmN6rUqF4v9yQ4Bw3F0vY+d1qE/9r3ZqE/9r3ZqE/9r3ZqE/9r3ZqE/9r3ZqE/9r3cCAwEAAQ==\n" +
                "-----END PUBLIC KEY-----";
        Files.writeString(tempKeyFile, dummyPubKey);

        server = new CvmsGrpcServer(9999, tempKeyFile.toAbsolutePath().toString(), "http://kbs", monitor);
        service = server.new CvmsServiceImpl();
    }

    @AfterEach
    void tearDown() throws IOException {
        Files.deleteIfExists(tempKeyFile);
    }

    @Test
    @SuppressWarnings("unchecked")
    void process_sendsRunRequestWhenManifestRegistered() throws Exception {
        String jobId = "test-job-id";
        var manifest = ComputeManifest.newInstance().id("manifest-id").build();

        StreamObserver<ServerStreamMessage> responseObserver = mock(StreamObserver.class);

        Context context = Context.current()
                .withValue(CvmsGrpcServer.CLIENT_IP_KEY, "192.168.1.10")
                .withValue(CvmsGrpcServer.JOB_ID_KEY, jobId)
                .withValue(CvmsGrpcServer.CONNECTION_TYPE_KEY, "agent");

        CompletableFuture<StreamObserver<ClientStreamMessage>> clientObserverFuture = new CompletableFuture<>();

        var scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.schedule(() -> {
            CocosManifestRegistry.register(jobId, manifest);
            scheduler.shutdown();
        }, 100, TimeUnit.MILLISECONDS);

        context.run(() -> {
            var observer = service.process(responseObserver);
            clientObserverFuture.complete(observer);
        });

        var clientObserver = clientObserverFuture.get();

        ArgumentCaptor<ServerStreamMessage> messageCaptor = ArgumentCaptor.forClass(ServerStreamMessage.class);
        verify(responseObserver, timeout(2000)).onNext(messageCaptor.capture());

        var sentMessage = messageCaptor.getValue();
        assertThat(sentMessage.hasRunReq()).isTrue();
        assertThat(sentMessage.getRunReq().getId()).isEqualTo("manifest-id");

        var completionFuture = CocosAgentCompletionRegistry.getOrCreate(jobId);
        var readyFuture = CocosAgentReadyRegistry.getOrCreate(jobId);

        clientObserver.onNext(ClientStreamMessage.newBuilder()
                .setAgentEvent(AgentEvent.newBuilder().setEventType("Status").setStatus("Ready").build())
                .build());

        assertThat(completionFuture.isDone()).isTrue();

        clientObserver.onNext(ClientStreamMessage.newBuilder()
                .setRunRes(RunResponse.newBuilder().setError("Execution error").build())
                .build());

        assertThat(readyFuture.isCompletedExceptionally()).isTrue();
    }
}
