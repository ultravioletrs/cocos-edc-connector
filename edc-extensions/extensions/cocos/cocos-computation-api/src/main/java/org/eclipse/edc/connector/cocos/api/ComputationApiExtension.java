package org.eclipse.edc.connector.cocos.api;

import com.fasterxml.jackson.core.StreamReadConstraints;
import org.eclipse.edc.connector.cocos.spi.CocosCliService;
import org.eclipse.edc.connector.cocos.spi.ComputationJobStore;
import org.eclipse.edc.connector.cocos.spi.ComputationOrchestrator;
import org.eclipse.edc.runtime.metamodel.annotation.Extension;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;
import org.eclipse.edc.web.spi.WebService;

@Extension(ComputationApiExtension.NAME)
public class ComputationApiExtension implements ServiceExtension {

    public static final String NAME = "CocosAI Computation API";
    private static final int MAX_INLINE_ASSET_STRING_LENGTH = 256 * 1024 * 1024;

    static {
        // Inline FILE assets are base64 encoded. Raise Jackson's default 20 MB
        // string limit so partner validation can submit larger assets; remote
        // provider/KBS sources remain preferable for production-scale payloads.
        StreamReadConstraints.overrideDefaultStreamReadConstraints(
                StreamReadConstraints.builder()
                        .maxStringLength(MAX_INLINE_ASSET_STRING_LENGTH)
                        .build());
    }

    @Inject
    private WebService webService;

    @Inject
    private ComputationOrchestrator orchestrator;

    @Inject
    private CocosCliService cliService;

    @Inject
    private ComputationJobStore jobStore;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void initialize(ServiceExtensionContext context) {
        webService.registerResource("management",
                new ComputationApiController(orchestrator, cliService, jobStore, context.getMonitor()));
    }
}
