package org.eclipse.edc.connector.cocos.spi;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class CocosAgentConnectionRegistry {
    private static final Map<String, Object> REGISTRY = new ConcurrentHashMap<>();

    private CocosAgentConnectionRegistry() {}

    public static void register(String jobId, Object observer) {
        REGISTRY.put(jobId, observer);
    }

    public static Object get(String jobId) {
        return REGISTRY.get(jobId);
    }

    public static void unregister(String jobId, Object observer) {
        REGISTRY.remove(jobId, observer);
    }
}
