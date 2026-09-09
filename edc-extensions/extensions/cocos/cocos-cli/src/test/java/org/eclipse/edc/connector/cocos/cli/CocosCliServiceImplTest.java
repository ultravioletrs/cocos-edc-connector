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
import java.util.List;
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

        agentSocket = new ServerSocket(0);
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

        var result = service.uploadDataset(agentAddress(), "dataset.csv", "data".getBytes());
        assertThat(result.succeeded()).isTrue();
    }

    @Test
    void uploadAlgorithm_executesCliSuccessfully() {
        String truePath = System.getProperty("os.name").toLowerCase().contains("win") ? "cmd.exe" : "true";
        CocosCliService service = new CocosCliServiceImpl(truePath, tempKeyFile.toAbsolutePath().toString(), monitor);

        var result = service.uploadAlgorithm(agentAddress(), "algo.py", "print(1)".getBytes());
        assertThat(result.succeeded()).isTrue();
    }

    @Test
    void uploadAlgorithm_usesDeclaredAlgorithmType() throws IOException {
        Path captureFile = Files.createTempFile("cocos-cli-args", ".txt");
        Path cliScript = Files.createTempFile("cocos-cli", ".sh");
        Files.writeString(cliScript, String.join("\n", List.of(
                "#!/bin/sh",
                "printf '%s\\n' \"$@\" > \"" + captureFile.toAbsolutePath() + "\""
        )));
        assertThat(cliScript.toFile().setExecutable(true)).isTrue();

        CocosCliService service = new CocosCliServiceImpl(cliScript.toAbsolutePath().toString(), tempKeyFile.toAbsolutePath().toString(), monitor);

        var result = service.uploadAlgorithm(agentAddress(), "addition.py", "python", "print(20 + 22)".getBytes());

        assertThat(result.succeeded()).isTrue();
        var capturedArgs = Files.readAllLines(captureFile);
        assertThat(capturedArgs).hasSize(5);
        assertThat(capturedArgs.get(0)).isEqualTo("algo");
        assertThat(capturedArgs.get(1)).endsWith("/addition.py");
        assertThat(capturedArgs.get(2)).isEqualTo(tempKeyFile.toAbsolutePath().toString());
        assertThat(capturedArgs.get(3)).isEqualTo("-a");
        assertThat(capturedArgs.get(4)).isEqualTo("python");

        Files.deleteIfExists(cliScript);
        Files.deleteIfExists(captureFile);
    }

    @Test
    void uploadAlgorithm_zeroExitWithUnavailableAgentOutputFails() throws IOException {
        Path cliScript = Files.createTempFile("cocos-cli-unavailable", ".sh");
        Files.writeString(cliScript, String.join("\n", List.of(
                "#!/bin/sh",
                "printf '%s\\n' 'Failed to connect to agent: agent service is unavailable'"
        )));
        assertThat(cliScript.toFile().setExecutable(true)).isTrue();

        CocosCliService service = new CocosCliServiceImpl(cliScript.toAbsolutePath().toString(), tempKeyFile.toAbsolutePath().toString(), monitor);

        var result = service.uploadAlgorithm(agentAddress(), "addition.py", "python", "print(20 + 22)".getBytes());

        assertThat(result.failed()).isTrue();
        assertThat(result.getFailureDetail()).contains("agent service is unavailable");

        Files.deleteIfExists(cliScript);
    }

    private String agentAddress() {
        return "127.0.0.1:" + agentSocket.getLocalPort();
    }
}
