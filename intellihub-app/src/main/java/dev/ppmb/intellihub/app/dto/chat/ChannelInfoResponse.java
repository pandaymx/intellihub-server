package dev.ppmb.intellihub.app.dto.chat;

import java.util.Set;

/**
 * AI 模型渠道与高可用路由拓扑信息。
 */
public class ChannelInfoResponse {

    private String defaultTarget;
    private Set<String> channels;
    private Set<String> routes;

    public ChannelInfoResponse() {}

    public ChannelInfoResponse(String defaultTarget, Set<String> channels, Set<String> routes) {
        this.defaultTarget = defaultTarget;
        this.channels = channels;
        this.routes = routes;
    }

    public String getDefaultTarget() {
        return defaultTarget;
    }

    public void setDefaultTarget(String defaultTarget) {
        this.defaultTarget = defaultTarget;
    }

    public Set<String> getChannels() {
        return channels;
    }

    public void setChannels(Set<String> channels) {
        this.channels = channels;
    }

    public Set<String> getRoutes() {
        return routes;
    }

    public void setRoutes(Set<String> routes) {
        this.routes = routes;
    }
}
