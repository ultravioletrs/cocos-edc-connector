package org.eclipse.edc.connector.cocos.attestation;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.edc.connector.cocos.spi.CocosCliService;
import org.eclipse.edc.connector.cocos.spi.ComputationJobStore;
import org.eclipse.edc.http.spi.EdcHttpClient;
import org.eclipse.edc.spi.monitor.Monitor;

import java.util.Map;

/**
 * Temporary test endpoint for exchanging a live Cocos CVM attestation for a
 * Trustee KBS token. This is intentionally separate from the raw attestation
 * proxy endpoint and should be removed after ACS integration testing.
 */
@Path("/cocos")
@Produces(MediaType.APPLICATION_JSON)
public class TemporaryAttestationTokenController {

    private final CocosCliService cliService;
    private final ComputationJobStore jobStore;
    private final KbsClient kbsClient;
    private final String teeType;
    private final Monitor monitor;

    public TemporaryAttestationTokenController(CocosCliService cliService,
                                               ComputationJobStore jobStore,
                                               EdcHttpClient httpClient,
                                               String kbsUrl,
                                               String teeType,
                                               Monitor monitor) {
        this.cliService = cliService;
        this.jobStore = jobStore;
        this.kbsClient = new KbsClientImpl(httpClient, new ObjectMapper(), kbsUrl);
        this.teeType = teeType;
        this.monitor = monitor;
    }

    @POST
    @Path("/computations/{jobId}/attestation-token")
    public Response getAttestationToken(@PathParam("jobId") String jobId) {
        var job = jobStore.findById(jobId);
        if (job.isEmpty() || job.get().getUnits().isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "No computation job or VM found with id: " + jobId))
                    .build();
        }

        String vmIp = job.get().getUnits().get(0).getVmIp();
        if (vmIp == null || vmIp.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "The computation job has no registered VM address"))
                    .build();
        }

        monitor.info("Temporary attestation-token endpoint: obtaining KBS token for job " + jobId
                + " from VM " + vmIp);

        var auth = kbsClient.authenticate(teeType);
        if (auth.failed()) {
            return failure("KBS authentication failed: " + auth.getFailureDetail());
        }

        String nonce = auth.getContent().getNonce();
        String sessionCookie = auth.getContent().getSessionCookie();
        var reportData = kbsClient.reportData(nonce, teeType);
        if (reportData.failed()) {
            return failure("KBS report-data preparation failed: " + reportData.getFailureDetail());
        }

        var evidence = cliService.requestAttestation(vmIp, nonce, reportData.getContent());
        if (evidence.failed()) {
            return failure("CVM attestation failed: " + evidence.getFailureDetail());
        }

        var token = kbsClient.verify(evidence.getContent(), nonce, sessionCookie, teeType);
        if (token.failed()) {
            return failure("KBS attestation verification failed: " + token.getFailureDetail());
        }

        return Response.ok(Map.of(
                "jobId", jobId,
                "vmIp", vmIp,
                "tee", teeType,
                "token", token.getContent(),
                "temporary", true
        )).build();
    }

    private Response failure(String message) {
        monitor.warning(message);
        return Response.status(Response.Status.BAD_GATEWAY)
                .entity(Map.of("error", message, "temporary", true))
                .build();
    }
}
