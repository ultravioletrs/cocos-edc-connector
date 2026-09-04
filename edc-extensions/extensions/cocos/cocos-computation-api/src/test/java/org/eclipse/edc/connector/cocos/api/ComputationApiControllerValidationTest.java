package org.eclipse.edc.connector.cocos.api;

import jakarta.ws.rs.core.Response;
import org.eclipse.edc.connector.cocos.spi.CocosCliService;
import org.eclipse.edc.connector.cocos.spi.ComputationJobStore;
import org.eclipse.edc.connector.cocos.spi.ComputationOrchestrator;
import org.eclipse.edc.connector.cocos.spi.model.ComputationRequest;
import org.eclipse.edc.connector.cocos.spi.model.ComputationUnit;
import org.eclipse.edc.spi.monitor.Monitor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ComputationApiControllerValidationTest {

    private ComputationOrchestrator orchestrator;
    private CocosCliService cliService;
    private ComputationJobStore jobStore;
    private Monitor monitor;
    private ComputationApiController controller;

    @BeforeEach
    void setUp() {
        orchestrator = mock(ComputationOrchestrator.class);
        cliService = mock(CocosCliService.class);
        jobStore = mock(ComputationJobStore.class);
        monitor = mock(Monitor.class);
        controller = new ComputationApiController(orchestrator, cliService, jobStore, monitor);
    }

    @Test
    void startComputation_nullRequest_returnsBadRequest() {
        Response response = controller.startComputation(null);
        assertThat(response.getStatus()).isEqualTo(Response.Status.BAD_REQUEST.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> entity = (Map<String, Object>) response.getEntity();
        assertThat(entity).containsKey("error");
        verifyNoInteractions(orchestrator);
    }

    @Test
    void startComputation_nullJobId_returnsBadRequest() {
        ComputationRequest request = new ComputationRequest();
        request.setJobId(null);
        request.setUnits(List.of(new ComputationUnit()));

        Response response = controller.startComputation(request);
        assertThat(response.getStatus()).isEqualTo(Response.Status.BAD_REQUEST.getStatusCode());
        verifyNoInteractions(orchestrator);
    }

    @Test
    void startComputation_emptyJobId_returnsBadRequest() {
        ComputationRequest request = new ComputationRequest();
        request.setJobId("   ");
        request.setUnits(List.of(new ComputationUnit()));

        Response response = controller.startComputation(request);
        assertThat(response.getStatus()).isEqualTo(Response.Status.BAD_REQUEST.getStatusCode());
        verifyNoInteractions(orchestrator);
    }

    @Test
    void startComputation_emptyUnits_returnsBadRequest() {
        ComputationRequest request = new ComputationRequest();
        request.setJobId("valid-job-id");
        request.setUnits(Collections.emptyList());

        Response response = controller.startComputation(request);
        assertThat(response.getStatus()).isEqualTo(Response.Status.BAD_REQUEST.getStatusCode());
        verifyNoInteractions(orchestrator);
    }

    @Test
    void startComputation_validRequest_callsOrchestratorAndReturnsAccepted() {
        ComputationRequest request = new ComputationRequest();
        request.setJobId("valid-job-id");
        request.setUnits(List.of(new ComputationUnit()));

        when(orchestrator.start(request)).thenReturn("valid-job-id");

        Response response = controller.startComputation(request);
        assertThat(response.getStatus()).isEqualTo(Response.Status.ACCEPTED.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> entity = (Map<String, Object>) response.getEntity();
        assertThat(entity.get("jobId")).isEqualTo("valid-job-id");
        verify(orchestrator).start(request);
    }
}
