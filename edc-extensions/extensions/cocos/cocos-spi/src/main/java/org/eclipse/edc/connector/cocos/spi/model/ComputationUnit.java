package org.eclipse.edc.connector.cocos.spi.model;

import com.fasterxml.jackson.annotation.JsonAutoDetect;

@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class ComputationUnit {

    private String agentAddress;
    private String vmIp;
    private ComputeManifest manifest;

    public ComputationUnit() {}

    public void setAgentAddress(String agentAddress) { this.agentAddress = agentAddress; }
    public void setVmIp(String vmIp) { this.vmIp = vmIp; }
    public void setManifest(ComputeManifest manifest) { this.manifest = manifest; }

    public String getAgentAddress() {
        if (agentAddress != null && !agentAddress.isEmpty()) {
            return agentAddress;
        }
        return vmIp;
    }

    public String getVmIp() { return getAgentAddress(); }

    public ComputeManifest getManifest() { return manifest; }

    public static Builder newInstance() { return new Builder(); }

    public static class Builder {
        private final ComputationUnit instance = new ComputationUnit();

        public Builder agentAddress(String agentAddress) { instance.agentAddress = agentAddress; return this; }

        public Builder vmIp(String vmIp) { instance.vmIp = vmIp; return this; }

        public Builder manifest(ComputeManifest manifest) { instance.manifest = manifest; return this; }

        public ComputationUnit build() { return instance; }
    }
}
