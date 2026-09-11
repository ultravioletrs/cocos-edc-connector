package org.eclipse.edc.connector.cocos.spi;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class CocosAgentConnectionRegistry {
    public static final String IDLE_KEY = "__idle__";
    private static final Map<String, Object> REGISTRY = new ConcurrentHashMap<>();

    private CocosAgentConnectionRegistry() {}

    public static void register(String jobId, Object observer) {
        REGISTRY.put(jobId, observer);
    }

    public static synchronized Object get(String jobId) {
        Object observer = REGISTRY.get(jobId);
        return observer != null ? observer : REGISTRY.get(IDLE_KEY);
    }

    public static synchronized Object registerIdle(Object observer) {
        return REGISTRY.put(IDLE_KEY, observer);
    }

    public static synchronized Object claimForJob(String jobId) {
        Object observer = REGISTRY.remove(IDLE_KEY);
        if (observer != null) {
            REGISTRY.put(jobId, observer);
            return observer;
        }
        return REGISTRY.get(jobId);
    }

    public static String getJobId(Object observer) {
        for (var entry : REGISTRY.entrySet()) {
            if (entry.getValue() == observer && !IDLE_KEY.equals(entry.getKey())) {
                return entry.getKey();
            }
        }
        return null;
    }

    public static void unregister(String jobId, Object observer) {
        REGISTRY.remove(jobId, observer);
    }

    public static Map<String, Object> getAll() {
        return java.util.Collections.unmodifiableMap(REGISTRY);
    }
}
