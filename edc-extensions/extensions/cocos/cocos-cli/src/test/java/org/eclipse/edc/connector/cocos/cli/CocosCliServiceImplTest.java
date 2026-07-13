package org.eclipse.edc.connector.cocos.cli;

import org.eclipse.edc.connector.cocos.spi.CocosCliService;
import org.eclipse.edc.spi.monitor.Monitor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CocosCliServiceImplTest {

    private final Monitor monitor = mock(Monitor.class);
    private ServerSocket agentSocket;
    private ExecutorService executor;
    private Path tempKeyFile;

    @BeforeEach
    void setUp() throws IOException {
        tempKeyFile = Files.createTempFile("dummy-key", ".pem");
        Files.writeString(tempKeyFile, "dummy private key");

        agentSocket = new ServerSocket(7001);
        executor = Executors.newSingleThreadExecutor();
        executor.submit(() -> {
            try {
                while (!agentSocket.isClosed()) {
                    Socket client = agentSocket.accept();
                    client.getOutputStream().write(1);
                    client.close();
                }
            } catch (IOException ignored) {}
        });
    }

    @AfterEach
    void tearDown() throws IOException {
        if (agentSocket != null) {
            agentSocket.close();
        }
        if (executor != null) {
            executor.shutdownNow();
        }
        Files.deleteIfExists(tempKeyFile);
    }

    @Test
    void uploadDataset_executesCliSuccessfully() {
        String truePath = System.getProperty("os.name").toLowerCase().contains("win") ? "cmd.exe" : "true";
        CocosCliService service = new CocosCliServiceImpl(truePath, tempKeyFile.toAbsolutePath().toString(), monitor);

        var result = service.uploadDataset("127.0.0.1", "dataset.csv", "data".getBytes());
        assertThat(result.succeeded()).isTrue();
    }

    @Test
    void uploadAlgorithm_executesCliSuccessfully() {
        String truePath = System.getProperty("os.name").toLowerCase().contains("win") ? "cmd.exe" : "true";
        CocosCliService service = new CocosCliServiceImpl(truePath, tempKeyFile.toAbsolutePath().toString(), monitor);

        var result = service.uploadAlgorithm("127.0.0.1", "algo.py", "print(1)".getBytes());
        assertThat(result.succeeded()).isTrue();
    }
}
