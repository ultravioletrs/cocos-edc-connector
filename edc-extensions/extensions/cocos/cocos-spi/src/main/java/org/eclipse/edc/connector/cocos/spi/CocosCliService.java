package org.eclipse.edc.connector.cocos.spi;

import org.eclipse.edc.connector.cocos.spi.model.ComputeManifest;
import org.eclipse.edc.spi.result.Result;

public interface CocosCliService {

    Result<Void> startAgent(String vmIp, ComputeManifest manifest);

    Result<Void> uploadDataset(String vmIp, String filename, byte[] data);

    Result<Void> uploadAlgorithm(String vmIp, String filename, byte[] data);

    default Result<Void> uploadAlgorithm(String vmIp, String filename, String algorithmType, byte[] data) {
        return uploadAlgorithm(vmIp, filename, data);
    }

    Result<byte[]> requestAttestation(String vmIp, String nonce);

    /** Fetch evidence with report data already bound to a KBS runtime-data payload. */
    default Result<byte[]> requestAttestation(String vmIp, String nonce, String reportData) {
        return requestAttestation(vmIp, reportData);
    }

    Result<byte[]> fetchResult(String vmIp);
}
