package org.eclipse.edc.connector.cocos.cli;

import org.eclipse.edc.connector.cocos.spi.CocosCliService;
import org.eclipse.edc.connector.cocos.spi.CocosManifestRegistry;
import org.eclipse.edc.connector.cocos.spi.model.ComputeManifest;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.result.Result;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class CocosCliServiceImpl implements CocosCliService {

    private final String cliBinaryPath;
    private final String privateKeyPath;
    private final Monitor monitor;

    public CocosCliServiceImpl(String cliBinaryPath, String privateKeyPath, Monitor monitor) {
        this.cliBinaryPath = cliBinaryPath;
        this.privateKeyPath = privateKeyPath;
        this.monitor = monitor;
    }

    @Override
    public Result<Void> startAgent(String agentAddress, ComputeManifest manifest) {
        CocosManifestRegistry.register(manifest.getId(), manifest);
        monitor.info("Registered manifest in CVMS registry for Job ID: " + manifest.getId());
        return Result.success();
    }

    @Override
    public Result<Void> uploadDataset(String agentAddress, String filename, byte[] data) {
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("cocos-dataset-");
            Path tempFile = tempDir.resolve(filename);
            Files.write(tempFile, data);

            String[] args = new String[]{"data", tempFile.toAbsolutePath().toString(), privateKeyPath};
            Result<byte[]> result = runCliCommand(agentAddress, args, tempDir.toAbsolutePath().toString(), false, null);
            if (result.failed()) {
                return Result.failure(result.getFailureDetail());
            }
            return Result.success();
        } catch (Exception e) {
            return Result.failure("Failed to upload dataset: " + e.getMessage());
        } finally {
            cleanupTempDir(tempDir);
        }
    }

    @Override
    public Result<Void> uploadAlgorithm(String agentAddress, String filename, byte[] data) {
        return uploadAlgorithm(agentAddress, filename, null, data);
    }

    @Override
    public Result<Void> uploadAlgorithm(String agentAddress, String filename, String algorithmType, byte[] data) {
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("cocos-algo-");
            Path tempFile = tempDir.resolve(filename);
            Files.write(tempFile, data);

            String algoType = resolveAlgorithmType(filename, algorithmType);

            String[] args = new String[]{"algo", tempFile.toAbsolutePath().toString(), privateKeyPath, "-a", algoType};
            Result<byte[]> result = runCliCommand(agentAddress, args, tempDir.toAbsolutePath().toString(), false, null);
            if (result.failed()) {
                return Result.failure(result.getFailureDetail());
            }
            return Result.success();
        } catch (Exception e) {
            return Result.failure("Failed to upload algorithm: " + e.getMessage());
        } finally {
            cleanupTempDir(tempDir);
        }
    }

    private String resolveAlgorithmType(String filename, String algorithmType) {
        if (algorithmType != null && !algorithmType.trim().isEmpty()) {
            return algorithmType.trim().toLowerCase();
        }
        if (filename != null) {
            if (filename.endsWith(".py")) {
                return "python";
            } else if (filename.endsWith(".tar") || filename.endsWith(".tar.gz")) {
                return "docker";
            } else if (filename.endsWith(".wasm")) {
                return "wasm";
            }
        }
        return "bin";
    }

    @Override
    public Result<byte[]> requestAttestation(String agentAddress, String nonce) {
        String[] args = new String[]{"attestation", privateKeyPath, "--nonce", nonce};
        return runCliCommand(agentAddress, args, null, false, null);
    }

    @Override
    public Result<byte[]> fetchResult(String agentAddress) {
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("cocos-result-");
            String[] args = new String[]{"result", privateKeyPath};
            Result<byte[]> result = runCliCommand(agentAddress, args, tempDir.toAbsolutePath().toString(), true, "results.zip");
            if (result.failed()) {
                return Result.failure(result.getFailureDetail());
            }
            return Result.success(result.getContent());
        } catch (Exception e) {
            return Result.failure("Failed to fetch result: " + e.getMessage());
        } finally {
            cleanupTempDir(tempDir);
        }
    }

    private String[] parseHostAndPort(String agentAddress) {
        if (agentAddress == null || agentAddress.isEmpty()) {
            String defaultPortStr = System.getProperty("cocos.agent.port", "49209");
            return new String[]{"127.0.0.1", defaultPortStr};
        }
        if (agentAddress.contains(":")) {
            return agentAddress.split(":", 2);
        }
        String defaultPortStr = System.getProperty("cocos.agent.port", "49209");
        return new String[]{agentAddress, defaultPortStr};
    }

    private Result<Void> waitForAgent(String agentAddress) {
        String[] hostPort = parseHostAndPort(agentAddress);
        String host = hostPort[0];
        int port;
        try {
            port = Integer.parseInt(hostPort[1]);
        } catch (Exception e) {
            port = 49209;
        }

        int maxRetries = 30; // 30 seconds
        for (int i = 0; i < maxRetries; i++) {
            try (java.net.Socket socket = new java.net.Socket()) {
                socket.connect(new java.net.InetSocketAddress(host, port), 1000);
                socket.setSoTimeout(500);
                int bytesRead = socket.getInputStream().read();
                if (bytesRead == -1) {
                    throw new java.io.IOException("Connection closed immediately by peer");
                }
                monitor.info("Cocos Agent is ready and listening on " + host + ":" + port);
                return Result.success();
            } catch (java.net.SocketTimeoutException ste) {
                // Connection remains open (no data sent by gRPC server), indicating a live backend
                monitor.info("Cocos Agent is ready and listening on " + host + ":" + port);
                return Result.success();
            } catch (Exception e) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return Result.failure("Interrupted while waiting for Cocos Agent on " + host + ":" + port);
                }
            }
        }
        var message = "Cocos Agent did not become reachable on " + host + ":" + port + " within 30 seconds";
        monitor.warning(message);
        return Result.failure(message);
    }

    private Result<byte[]> runCliCommand(String agentAddress, String[] args, String workingDir, boolean readOutput, String outputFile) {
        var readiness = waitForAgent(agentAddress);
        if (readiness.failed()) {
            return Result.failure(readiness.getFailureDetail());
        }
        try {
            List<String> command = new ArrayList<>();
            command.add(cliBinaryPath);
            command.addAll(Arrays.asList(args));

            ProcessBuilder pb = new ProcessBuilder(command);
            if (workingDir != null) {
                pb.directory(new File(workingDir));
            }
            Map<String, String> env = pb.environment();
            String[] hostPort = parseHostAndPort(agentAddress);
            String fullTargetUrl = hostPort[0] + ":" + hostPort[1];
            env.put("AGENT_GRPC_URL", fullTargetUrl);
            env.put("AGENT_GRPC_ATTESTED_TLS", "false");
            monitor.info("Running Cocos CLI command '" + args[0] + "' against agent " + fullTargetUrl);

            Process process = pb.start();

            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();

            Thread stdoutThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stdout.append(line).append("\n");
                    }
                } catch (Exception ignored) {}
            });
            stdoutThread.start();

            Thread stderrThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stderr.append(line).append("\n");
                    }
                } catch (Exception ignored) {}
            });
            stderrThread.start();

            boolean finished = process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                monitor.severe("Cocos CLI command timed out: " + String.join(" ", command));
                return Result.failure("Cocos CLI command timed out after 60 seconds");
            }

            try {
                stdoutThread.join(1000);
                stderrThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                monitor.warning("Interrupted while waiting for Cocos CLI output reader threads");
            }

            int exitCode = process.exitValue();
            String combinedOutput = stdout.toString().trim();
            if (combinedOutput.length() > 0) {
                monitor.info("Cocos CLI output:\n" + combinedOutput);
            }

            boolean hasFailureIcon = combinedOutput.contains("❌");
            boolean hasFailedMessage = combinedOutput.toLowerCase().contains("failed to");
            boolean hasUnavailableAgent = combinedOutput.toLowerCase().contains("agent service is unavailable");

            if (exitCode != 0 || hasFailureIcon || hasFailedMessage || hasUnavailableAgent) {
                String errorMsg = stderr.toString().trim();
                if (errorMsg.isEmpty()) {
                    errorMsg = combinedOutput;
                }
                monitor.severe("Cocos CLI command failed: " + String.join(" ", command) + ". Error: " + errorMsg);
                return Result.failure("Cocos CLI command failed: " + errorMsg);
            }

            if (readOutput && outputFile != null) {
                File file = new File(workingDir, outputFile);
                if (!file.exists()) {
                    return Result.failure("Output file " + outputFile + " was not created");
                }
                byte[] content = Files.readAllBytes(file.toPath());
                return Result.success(content);
            }

            return Result.success(new byte[0]);
        } catch (Exception e) {
            monitor.severe("Failed to execute Cocos CLI command", e);
            return Result.failure("Failed to execute Cocos CLI command: " + e.getMessage());
        }
    }

    private void cleanupTempDir(Path tempDir) {
        if (tempDir == null) return;
        try {
            Files.walk(tempDir)
                 .sorted((p1, p2) -> p2.compareTo(p1))
                 .map(Path::toFile)
                 .forEach(File::delete);
        } catch (Exception e) {
            monitor.warning("Failed to cleanup temp directory " + tempDir + ": " + e.getMessage());
        }
    }
}
