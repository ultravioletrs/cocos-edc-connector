package org.eclipse.edc.connector.cocos.api;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.edc.connector.cocos.spi.CocosCliService;
import org.eclipse.edc.connector.cocos.spi.ComputationJobStore;
import org.eclipse.edc.connector.cocos.spi.ComputationOrchestrator;
import org.eclipse.edc.connector.cocos.spi.model.ComputationRequest;
import org.eclipse.edc.spi.monitor.Monitor;

import java.util.Base64;
import java.util.Map;

@Consumes({MediaType.APPLICATION_JSON})
@Produces({MediaType.APPLICATION_JSON})
@Path("/cocos")
public class ComputationApiController {

    private final ComputationOrchestrator orchestrator;
    private final CocosCliService cliService;
    private final ComputationJobStore jobStore;
    private final Monitor monitor;

    public ComputationApiController(ComputationOrchestrator orchestrator,
                                    CocosCliService cliService,
                                    ComputationJobStore jobStore,
                                    Monitor monitor) {
        this.orchestrator = orchestrator;
        this.cliService = cliService;
        this.jobStore = jobStore;
        this.monitor = monitor;
    }

    @POST
    @Path("/computations")
    public Response startComputation(ComputationRequest request) {
        var jobId = orchestrator.start(request);
        return Response.accepted(Map.of("jobId", jobId)).build();
    }

    @GET
    @Path("/computations/{jobId}")
    public Response getComputation(@PathParam("jobId") String jobId) {
        return orchestrator.getJob(jobId)
                .map(job -> Response.ok(Map.of(
                        "jobId", job.getJobId(),
                        "status", job.getStatus().name()
                )).build())
                .orElse(Response.status(Response.Status.NOT_FOUND).build());
    }

    /**
     * Attestation Proxy Endpoint.
     *
     * <p>Allows the Provider Connector to obtain a fresh hardware attestation report
     * from a CVM managed by this Consumer Connector, without requiring direct network
     * access to the CVM's gRPC port (7002). This is the integration point for the
     * Provider-presents-VP flow described in D3.2 Section 7.3.
     *
     * <p>Security: this endpoint runs on the EDC management API port and inherits
     * its authentication (API key / token). The request is validated against the
     * known job and VM IP to prevent unauthorized attestation requests.
     *
     * @param jobId   the computation job ID whose CVMs should be attested
     * @param request body containing the target {@code vmIp} and {@code nonce}
     * @return 200 with base64-encoded attestation report, 400 on bad input,
     *         404 if the job/VM is not found, or 500 on agent failure
     */
    @POST
    @Path("/computations/{jobId}/attestation")
    public Response getAttestationReport(@PathParam("jobId") String jobId,
                                         AttestationProxyRequest request) {
        if (request == null || request.getVmIp() == null || request.getNonce() == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "vmIp and nonce are required"))
                    .build();
        }

        // Validate that the jobId exists and the vmIp belongs to it
        var jobOpt = jobStore.findById(jobId);
        if (jobOpt.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "No computation job found with id: " + jobId))
                    .build();
        }

        var job = jobOpt.get();
        boolean vmBelongsToJob = job.getUnits().stream()
                .anyMatch(unit -> request.getVmIp().equals(unit.getVmIp()));
        if (!vmBelongsToJob) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "VM " + request.getVmIp()
                            + " is not part of job " + jobId))
                    .build();
        }

        monitor.debug("Attestation proxy: fetching report from VM " + request.getVmIp()
                + " for job " + jobId);

        var result = cliService.requestAttestation(request.getVmIp(), request.getNonce());
        if (result.failed()) {
            monitor.warning("Attestation proxy failed for VM " + request.getVmIp()
                    + ": " + result.getFailureDetail());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Failed to obtain attestation report: "
                            + result.getFailureDetail()))
                    .build();
        }

        var encoded = Base64.getEncoder().encodeToString(result.getContent());
        return Response.ok(new AttestationProxyResponse(encoded)).build();
    }
}
