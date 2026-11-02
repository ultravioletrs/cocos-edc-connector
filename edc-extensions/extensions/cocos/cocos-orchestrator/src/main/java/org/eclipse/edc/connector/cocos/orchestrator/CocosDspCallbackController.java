package org.eclipse.edc.connector.cocos.orchestrator;

import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;

/** Receives EDC callback envelopes at the DSP callback base address. */
@Path("/2025-1")
public class CocosDspCallbackController {

    @POST
    public Response receiveCallback() {
        return Response.ok().build();
    }
}
