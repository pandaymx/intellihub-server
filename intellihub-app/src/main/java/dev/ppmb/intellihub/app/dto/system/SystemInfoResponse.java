package dev.ppmb.intellihub.app.dto.system;

import java.util.List;
import java.util.Set;

/**
 * 系统运行时拓扑与各组件就绪概况。
 */
public class SystemInfoResponse {

    private String application;
    private String javaVersion;
    private String springBootVersion;
    private String defaultTarget;
    private Set<String> channels;
    private Set<String> routes;
    private List<String> registeredTools;
    private List<String> promptTemplateIds;
    private int knowledgeBaseChunkCount;

    public SystemInfoResponse() {}

    public String getApplication() {
        return application;
    }

    public void setApplication(String application) {
        this.application = application;
    }

    public String getJavaVersion() {
        return javaVersion;
    }

    public void setJavaVersion(String javaVersion) {
        this.javaVersion = javaVersion;
    }

    public String getSpringBootVersion() {
        return springBootVersion;
    }

    public void setSpringBootVersion(String springBootVersion) {
        this.springBootVersion = springBootVersion;
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

    public List<String> getRegisteredTools() {
        return registeredTools;
    }

    public void setRegisteredTools(List<String> registeredTools) {
        this.registeredTools = registeredTools;
    }

    public List<String> getPromptTemplateIds() {
        return promptTemplateIds;
    }

    public void setPromptTemplateIds(List<String> promptTemplateIds) {
        this.promptTemplateIds = promptTemplateIds;
    }

    public int getKnowledgeBaseChunkCount() {
        return knowledgeBaseChunkCount;
    }

    public void setKnowledgeBaseChunkCount(int knowledgeBaseChunkCount) {
        this.knowledgeBaseChunkCount = knowledgeBaseChunkCount;
    }
}
