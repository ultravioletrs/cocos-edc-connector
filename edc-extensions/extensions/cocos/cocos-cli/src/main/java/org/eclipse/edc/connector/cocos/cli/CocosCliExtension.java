package org.eclipse.edc.connector.cocos.cli;

import org.eclipse.edc.connector.cocos.spi.CocosCliService;
import org.eclipse.edc.runtime.metamodel.annotation.Extension;
import org.eclipse.edc.runtime.metamodel.annotation.Provider;
import org.eclipse.edc.runtime.metamodel.annotation.Setting;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;

@Extension(CocosCliExtension.NAME)
public class CocosCliExtension implements ServiceExtension {

    public static final String NAME = "CocosAI CLI";

    @Setting(description = "Absolute path to the CocosAI CLI binary", key = "cocos.cli.path", required = true)
    private String cliBinaryPath;

    @Setting(description = "Absolute path to the private key used by the CLI", key = "cocos.cli.privateKey.path", required = true)
    private String privateKeyPath;

    @Override
    public String name() {
        return NAME;
    }

    @Provider
    public CocosCliService cocosCliService(ServiceExtensionContext context) {
        return new CocosCliServiceImpl(cliBinaryPath, privateKeyPath, context.getMonitor());
    }
}
