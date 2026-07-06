package org.eclipse.edc.connector.cocos.attestation;

import org.eclipse.edc.connector.cocos.spi.CocosCliService;
import org.eclipse.edc.connector.cocos.spi.CocosContextHolder;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.result.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AttestationBackedPresentationRequestServiceTest {

    private final CocosCliService cliService = mock(CocosCliService.class);
    private final IdentityHubClient identityHubClient = mock(IdentityHubClient.class);
    private final KbsClient kbsClient = mock(KbsClient.class);
    private final Monitor monitor = mock(Monitor.class);
    private final String teeType = "snp";

    private AttestationBackedPresentationRequestService service;

    @BeforeEach
    void setUp() {
        service = new AttestationBackedPresentationRequestService(
                cliService, identityHubClient, kbsClient, teeType, monitor);
    }

    @AfterEach
    void tearDown() {
        CocosContextHolder.clear();
    }

    @Test
    void requestPresentation_successfulFlow() {
        String vmIp = "192.168.1.100";
        CocosContextHolder.setActiveVmIp(vmIp);

        String kbsNonce = "test-nonce-12345";
        String sessionCookie = "KBS_SESSION_ID=abc";
        byte[] rawReport = "raw-report-data".getBytes();
        String statusJwt = "signed.status.jwt";

        // Mock Trustee Authentication
        when(kbsClient.authenticate(teeType)).thenReturn(
                Result.success(new KbsClient.KbsAuthResult(kbsNonce, sessionCookie)));

        // Mock CLI fetching attestation report
        when(cliService.requestAttestation(vmIp, kbsNonce)).thenReturn(Result.success(rawReport));

        // Mock Trustee Verification
        when(kbsClient.verify(rawReport, kbsNonce, sessionCookie, teeType)).thenReturn(Result.success(statusJwt));

        // Mock Identity Hub VP Presentation Request
        when(identityHubClient.requestPresentation(
                eq("context-id"), eq("own-did"), eq("counter-did"), eq("counter-token"), any(), eq(statusJwt), eq(vmIp)))
                .thenReturn(Result.success(List.of()));

        // Run the service
        var result = service.requestPresentation("context-id", "own-did", "counter-did", "counter-token", List.of("scope1"));

        // Verify successful execution and correct interactions
        assertThat(result.succeeded()).isTrue();

        verify(kbsClient).authenticate(teeType);
        verify(cliService).requestAttestation(vmIp, kbsNonce);
        verify(kbsClient).verify(rawReport, kbsNonce, sessionCookie, teeType);
        verify(identityHubClient).requestPresentation(
                "context-id", "own-did", "counter-did", "counter-token", List.of("scope1"), statusJwt, vmIp);
    }

    @Test
    void requestPresentation_kbsAuthFailure_returnsFailure() {
        when(kbsClient.authenticate(teeType)).thenReturn(Result.failure("Auth error"));

        var result = service.requestPresentation("context-id", "own-did", "counter-did", "counter-token", List.of("scope"));

        assertThat(result.failed()).isTrue();
        assertThat(result.getFailureDetail()).contains("Failed to authenticate with Trustee KBS: Auth error");
    }

    @Test
    void requestPresentation_noActiveVm_returnsFailure() {
        String kbsNonce = "nonce";
        String cookie = "cookie";
        when(kbsClient.authenticate(teeType)).thenReturn(
                Result.success(new KbsClient.KbsAuthResult(kbsNonce, cookie)));

        var result = service.requestPresentation("context-id", "own-did", "counter-did", "counter-token", List.of("scope"));

        assertThat(result.failed()).isTrue();
        assertThat(result.getFailureDetail()).contains("No active CocosAI VM in context");
    }
}
