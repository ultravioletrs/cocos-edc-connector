package org.eclipse.edc.connector.cocos.attestation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.edc.connector.cocos.spi.CocosCliService;
import org.eclipse.edc.http.spi.EdcHttpClient;
import org.eclipse.edc.iam.decentralizedclaims.spi.PresentationRequestService;
import org.eclipse.edc.runtime.metamodel.annotation.Extension;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.runtime.metamodel.annotation.Provider;
import org.eclipse.edc.runtime.metamodel.annotation.Setting;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;

/**
 * EDC extension that registers the CocosAI attestation-backed credential service.
 *
 * <p>Supports two modes of operation, selected via {@code cocos.attestation.mode}:
 * <ul>
 *   <li><b>consumer</b> (default): the Consumer Connector fetches attestation
 *       from its own CVMs and presents the resulting VP to the Provider.
 *       This is the standard FL Toolbox integration mode.</li>
 *   <li><b>provider</b>: the Provider Connector fetches attestation from the
 *       Consumer's CVMs via an attestation proxy API, and presents the VP to
 *       the Consumer during DSP negotiation. This matches the flow described in
 *       D3.2 Section 7.3.</li>
 * </ul>
 */
@Extension(CocosAttestationExtension.NAME)
public class CocosAttestationExtension implements ServiceExtension {

    public static final String NAME = "CocosAI Attestation Credential Service";

    /** Attestation mode: "consumer" (default) or "provider". */
    @Setting(description = "Attestation VP presentation mode. "
            + "'consumer' (default): Consumer presents attestation VP to Provider during DSP. "
            + "'provider': Provider obtains attestation via Consumer proxy and presents VP to Consumer.",
             key = "cocos.attestation.mode",
             defaultValue = "consumer")
    private String attestationMode;

    /** Base URL of the Cocos Identity Hub. Required in both modes. */
    @Setting(description = "Base URL of the CocosAI Identity Hub (CW)", key = "cocos.identity.hub.url", required = true)
    private String identityHubUrl;

    /** Base URL of the Consumer Connector's management API.
     * Required only when {@code cocos.attestation.mode=provider}.
     * Example: {@code http://consumer-connector:8181/api/management}
     */
    @Setting(description = "Base URL of the Consumer Connector's management API for the attestation proxy. "
            + "Required when cocos.attestation.mode=provider.",
             key = "cocos.attestation.proxy.url",
             required = false)
    private String attestationProxyUrl;

    /** Base URL of the Trustee Key Broker Service (KBS). */
    @Setting(description = "Base URL of the Trustee Key Broker Service (KBS)",
             key = "cocos.kbs.url",
             defaultValue = "http://localhost:8080")
    private String kbsUrl;

    /** TEE Platform type: snp or sample. */
    @Setting(description = "TEE Platform type: snp or sample",
             key = "cocos.tee.type",
             defaultValue = "snp")
    private String teeType;

    @Inject
    private CocosCliService cliService;

    @Inject
    private EdcHttpClient httpClient;

    @Override
    public String name() {
        return NAME;
    }

    @Provider
    public PresentationRequestService presentationRequestService(ServiceExtensionContext context) {
        var identityHubClient = new IdentityHubClientImpl(httpClient, identityHubUrl);
        var kbsClient = new KbsClientImpl(httpClient, new ObjectMapper(), kbsUrl);

        if ("provider".equalsIgnoreCase(attestationMode)) {
            if (attestationProxyUrl == null || attestationProxyUrl.isBlank()) {
                throw new IllegalStateException(
                        "cocos.attestation.proxy.url must be set when cocos.attestation.mode=provider");
            }
            context.getMonitor().info(NAME + ": running in PROVIDER mode — "
                    + "attestation will be fetched via Consumer proxy at " + attestationProxyUrl);
            var proxyClient = new AttestationProxyClientImpl(httpClient, new ObjectMapper());
            return new ProviderAttestationPresentationService(
                    proxyClient, identityHubClient, kbsClient, teeType, attestationProxyUrl, context.getMonitor());
        }

        // Default: consumer mode
        context.getMonitor().info(NAME + ": running in CONSUMER mode — "
                + "attestation will be fetched directly from CVMs and verified at KBS");
        return new AttestationBackedPresentationRequestService(cliService, identityHubClient, kbsClient, teeType, context.getMonitor());
    }
}
