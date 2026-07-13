package org.eclipse.edc.connector.cocos.datasink;

import org.eclipse.edc.connector.dataplane.spi.pipeline.DataSink;
import org.eclipse.edc.connector.dataplane.spi.pipeline.DataSource;
import org.eclipse.edc.connector.dataplane.spi.pipeline.StreamResult;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.connector.cocos.spi.InMemoryBufferRegistry;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

public class InMemoryDataSink implements DataSink {

    private final String bufferId;
    private final ExecutorService executorService;
    private final Monitor monitor;

    public InMemoryDataSink(String bufferId, ExecutorService executorService, Monitor monitor) {
        this.bufferId = bufferId;
        this.executorService = executorService;
        this.monitor = monitor;
    }

    @Override
    public CompletableFuture<StreamResult<Object>> transfer(DataSource source) {
        return CompletableFuture.supplyAsync(() -> {
            var partsResult = source.openPartStream();
            if (partsResult.failed()) {
                String errorMsg = "Failed to open data source: " + String.join(", ", partsResult.getFailureMessages());
                InMemoryBufferRegistry.fail(bufferId, errorMsg);
                return StreamResult.error(errorMsg);
            }

            try (var parts = partsResult.getContent()) {
                var part = parts.findFirst().orElse(null);
                if (part == null) {
                    String errorMsg = "Data source contained no parts";
                    InMemoryBufferRegistry.fail(bufferId, errorMsg);
                    return StreamResult.error(errorMsg);
                }

                byte[] data;
                try {
                    data = part.openStream().readAllBytes();
                } catch (IOException e) {
                    String errorMsg = "Failed to read data part: " + e.getMessage();
                    InMemoryBufferRegistry.fail(bufferId, errorMsg);
                    return StreamResult.error(errorMsg);
                }

                InMemoryBufferRegistry.complete(bufferId, data);
                monitor.debug("In-memory transfer complete for buffer: " + bufferId);
                return StreamResult.success();
            }
        }, executorService);
    }
}
