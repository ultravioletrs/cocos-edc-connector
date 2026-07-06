package org.eclipse.edc.connector.cocos.attestation;

import org.eclipse.edc.spi.result.Result;

public interface KbsClient {
    Result<KbsAuthResult> authenticate(String teeType);
    Result<String> verify(byte[] attestationReport, String nonce, String sessionCookie, String teeType);

    class KbsAuthResult {
        private final String nonce;
        private final String sessionCookie;

        public KbsAuthResult(String nonce, String sessionCookie) {
            this.nonce = nonce;
            this.sessionCookie = sessionCookie;
        }

        public String getNonce() {
            return nonce;
        }

        public String getSessionCookie() {
            return sessionCookie;
        }
    }
}
