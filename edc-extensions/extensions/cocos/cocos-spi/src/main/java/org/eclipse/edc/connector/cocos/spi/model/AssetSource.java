package org.eclipse.edc.connector.cocos.spi.model;

import com.fasterxml.jackson.annotation.JsonAutoDetect;

@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class AssetSource {

    public enum Type { FILE, URL }

    private Type type;
    private String url;
    private String content;
    private boolean encrypted;
    private String kbsResourcePath;

    public AssetSource() {}

    public void setType(Type type) { this.type = type; }
    public void setUrl(String url) { this.url = url; }
    public void setContent(String content) { this.content = content; }
    public void setEncrypted(boolean encrypted) { this.encrypted = encrypted; }
    public void setKbsResourcePath(String kbsResourcePath) { this.kbsResourcePath = kbsResourcePath; }

    public Type getType() { return type; }

    public String getUrl() { return url; }

    public String getContent() { return content; }

    public boolean isEncrypted() { return encrypted; }

    public String getKbsResourcePath() { return kbsResourcePath; }

    public static Builder newInstance() { return new Builder(); }

    public static class Builder {
        private final AssetSource instance = new AssetSource();

        public Builder type(Type type) { instance.type = type; return this; }

        public Builder url(String url) { instance.url = url; return this; }

        public Builder content(String content) { instance.content = content; return this; }

        public Builder encrypted(boolean encrypted) { instance.encrypted = encrypted; return this; }

        public Builder kbsResourcePath(String path) { instance.kbsResourcePath = path; return this; }

        public AssetSource build() { return instance; }
    }
}
