package org.eclipse.edc.connector.cocos.datasink;

import org.eclipse.edc.connector.dataplane.spi.pipeline.DataSink;
import org.eclipse.edc.connector.dataplane.spi.pipeline.DataSinkFactory;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.result.Result;
import org.eclipse.edc.spi.types.domain.transfer.DataFlowStartMessage;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.ExecutorService;

public class InMemoryDataSinkFactory implements DataSinkFactory {

    public static final String IN_MEMORY_TYPE = "InMemory";
    public static final String PROP_BUFFER_ID = "cocos.buffer.id";

    private final ExecutorService executorService;
    private final Monitor monitor;

    public InMemoryDataSinkFactory(ExecutorService executorService, Monitor monitor) {
        this.executorService = executorService;
        this.monitor = monitor;
    }

    @Override
    public String supportedType() {
        return IN_MEMORY_TYPE;
    }

    @Override
    public @NotNull Result<Void> validateRequest(DataFlowStartMessage request) {
        var address = request.getDestinationDataAddress();
        if (address.getStringProperty(PROP_BUFFER_ID) == null) {
            return Result.failure("Missing required destination property: " + PROP_BUFFER_ID);
        }
        return Result.success();
    }

    @Override
    public DataSink createSink(DataFlowStartMessage request) {
        var address = request.getDestinationDataAddress();
        var bufferId = address.getStringProperty(PROP_BUFFER_ID);
        return new InMemoryDataSink(bufferId, executorService, monitor);
    }
}
