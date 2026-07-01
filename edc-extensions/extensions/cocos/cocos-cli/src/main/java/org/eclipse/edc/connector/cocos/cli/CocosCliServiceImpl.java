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
    public Result<Void> startAgent(String vmIp, ComputeManifest manifest) {
        CocosManifestRegistry.register(vmIp, manifest);
        monitor.info("Registered manifest in CVMS registry for VM IP: " + vmIp);
        return Result.success();
    }

    @Override
    public Result<Void> uploadDataset(String vmIp, String filename, byte[] data) {
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("cocos-dataset-");
            Path tempFile = tempDir.resolve(filename);
            Files.write(tempFile, data);

            String[] args = new String[]{"data", tempFile.toAbsolutePath().toString(), privateKeyPath};
            Result<byte[]> result = runCliCommand(vmIp, args, tempDir.toAbsolutePath().toString(), false, null);
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
    public Result<Void> uploadAlgorithm(String vmIp, String filename, byte[] data) {
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("cocos-algo-");
            Path tempFile = tempDir.resolve(filename);
            Files.write(tempFile, data);

            List<String> argsList = new ArrayList<>();
            argsList.add("algo");
            argsList.add(tempFile.toAbsolutePath().toString());
            argsList.add(privateKeyPath);
            if (filename.endsWith(".py")) {
                argsList.add("-a");
                argsList.add("python");
            } else if (filename.endsWith(".wasm")) {
                argsList.add("-a");
                argsList.add("wasm");
            } else if (filename.endsWith(".tar")) {
                argsList.add("-a");
                argsList.add("docker");
            } else {
                argsList.add("-a");
                argsList.add("binary");
            }

            Result<byte[]> result = runCliCommand(vmIp, argsList.toArray(new String[0]), tempDir.toAbsolutePath().toString(), false, null);
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

    @Override
    public Result<byte[]> requestAttestation(String vmIp, String nonce) {
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("cocos-attestation-");
            String[] args = new String[]{"attestation", "get", "snp", "--tee", nonce};
            return runCliCommand(vmIp, args, tempDir.toAbsolutePath().toString(), true, "attestation.bin");
        } catch (Exception e) {
            return Result.failure("Failed to request attestation: " + e.getMessage());
        } finally {
            cleanupTempDir(tempDir);
        }
    }

    @Override
    public Result<byte[]> fetchResult(String vmIp) {
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("cocos-result-");
            String[] args = new String[]{"result", privateKeyPath};
            return runCliCommand(vmIp, args, tempDir.toAbsolutePath().toString(), true, "results.zip");
        } catch (Exception e) {
            return Result.failure("Failed to fetch result: " + e.getMessage());
        } finally {
            cleanupTempDir(tempDir);
        }
    }

    private void waitForAgent(String vmIp) {
        int maxRetries = 30; // 30 seconds
        for (int i = 0; i < maxRetries; i++) {
            try (java.net.Socket socket = new java.net.Socket()) {
                socket.connect(new java.net.InetSocketAddress(vmIp, 7001), 1000);
                monitor.info("Cocos Agent is ready and listening on port 7001");
                return;
            } catch (Exception e) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        monitor.warning("Cocos Agent did not start listening on port 7001 within 30 seconds");
    }

    private Result<byte[]> runCliCommand(String vmIp, String[] args, String workingDir, boolean readOutput, String outputFile) {
        waitForAgent(vmIp);
        try {
            List<String> command = new ArrayList<>();
            command.add(cliBinaryPath);
            command.addAll(Arrays.asList(args));

            ProcessBuilder pb = new ProcessBuilder(command);
            if (workingDir != null) {
                pb.directory(new File(workingDir));
            }
            Map<String, String> env = pb.environment();
            env.put("AGENT_GRPC_URL", vmIp + ":7001");
            env.put("AGENT_GRPC_ATTESTED_TLS", "false");

            Process process = pb.start();

            StringBuilder stderr = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    stderr.append(line).append("\n");
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                monitor.severe("Cocos CLI command failed: " + String.join(" ", command) + ". Stderr: " + stderr);
                return Result.failure("Cocos CLI command failed with exit code " + exitCode + ": " + stderr.toString().trim());
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
