package org.eclipse.edc.connector.cocos.spi.model;

import com.fasterxml.jackson.annotation.JsonAutoDetect;

@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class AlgorithmSpec {

    private String filename;
    private String hash;
    private String type;
    private AssetSource source;
    private String providerConnectorUrl;

    public AlgorithmSpec() {}

    public void setFilename(String filename) { this.filename = filename; }
    public void setHash(String hash) { this.hash = hash; }
    public void setType(String type) { this.type = type; }
    public void setSource(AssetSource source) { this.source = source; }
    public void setProviderConnectorUrl(String providerConnectorUrl) { this.providerConnectorUrl = providerConnectorUrl; }

    public String getFilename() { return filename; }

    public String getHash() { return hash; }

    public String getType() { return type; }

    public AssetSource getSource() { return source; }

    public String getProviderConnectorUrl() { return providerConnectorUrl; }

    public static Builder newInstance() { return new Builder(); }

    public static class Builder {
        private final AlgorithmSpec instance = new AlgorithmSpec();

        public Builder filename(String filename) { instance.filename = filename; return this; }

        public Builder hash(String hash) { instance.hash = hash; return this; }

        public Builder type(String type) { instance.type = type; return this; }

        public Builder source(AssetSource source) { instance.source = source; return this; }

        public Builder providerConnectorUrl(String url) { instance.providerConnectorUrl = url; return this; }

        public AlgorithmSpec build() { return instance; }
    }
}
