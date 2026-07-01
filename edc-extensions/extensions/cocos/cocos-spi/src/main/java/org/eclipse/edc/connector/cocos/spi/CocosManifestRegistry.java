package org.eclipse.edc.connector.cocos.spi;

import org.eclipse.edc.connector.cocos.spi.model.ComputeManifest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class CocosManifestRegistry {

    private static final Map<String, ComputeManifest> manifests = new ConcurrentHashMap<>();

    private CocosManifestRegistry() {}

    public static void register(String vmIp, ComputeManifest manifest) {
        manifests.put(vmIp, manifest);
    }

    public static ComputeManifest get(String vmIp) {
        return manifests.get(vmIp);
    }

    public static void remove(String vmIp) {
        manifests.remove(vmIp);
    }
}
