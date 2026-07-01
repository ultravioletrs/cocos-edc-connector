package org.eclipse.edc.connector.cocos.spi.model;

import com.fasterxml.jackson.annotation.JsonAutoDetect;

@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class DatasetSpec {

    private String filename;
    private String hash;
    private AssetSource source;
    private String providerConnectorUrl;

    public DatasetSpec() {}

    public void setFilename(String filename) { this.filename = filename; }
    public void setHash(String hash) { this.hash = hash; }
    public void setSource(AssetSource source) { this.source = source; }
    public void setProviderConnectorUrl(String providerConnectorUrl) { this.providerConnectorUrl = providerConnectorUrl; }

    public String getFilename() { return filename; }

    public String getHash() { return hash; }

    public AssetSource getSource() { return source; }

    public String getProviderConnectorUrl() { return providerConnectorUrl; }

    public static Builder newInstance() { return new Builder(); }

    public static class Builder {
        private final DatasetSpec instance = new DatasetSpec();

        public Builder filename(String filename) { instance.filename = filename; return this; }

        public Builder hash(String hash) { instance.hash = hash; return this; }

        public Builder source(AssetSource source) { instance.source = source; return this; }

        public Builder providerConnectorUrl(String url) { instance.providerConnectorUrl = url; return this; }

        public DatasetSpec build() { return instance; }
    }
}
