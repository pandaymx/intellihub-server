package dev.ppmb.intellihub.app.controller;

import dev.ppmb.intellihub.agent.tool.GovernedToolRegistry;
import dev.ppmb.intellihub.app.common.ApiResponse;
import dev.ppmb.intellihub.app.dto.system.SystemInfoResponse;
import dev.ppmb.intellihub.llm.registry.IntelliHubModelRegistry;
import dev.ppmb.intellihub.prompt.template.PromptRegistry;
import dev.ppmb.intellihub.rag.store.HybridKnowledgeStore;
import java.util.ArrayList;
import org.springframework.boot.SpringBootVersion;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运行时组件拓扑概览与系统健康状态 REST 控制器。
 */
@RestController
@RequestMapping("/api/v1/system")
public class SystemInfoController {

    private final IntelliHubModelRegistry modelRegistry;
    private final GovernedToolRegistry toolRegistry;
    private final PromptRegistry promptRegistry;
    private final HybridKnowledgeStore knowledgeStore;

    public SystemInfoController(
            IntelliHubModelRegistry modelRegistry,
            GovernedToolRegistry toolRegistry,
            PromptRegistry promptRegistry,
            HybridKnowledgeStore knowledgeStore) {
        this.modelRegistry = modelRegistry;
        this.toolRegistry = toolRegistry;
        this.promptRegistry = promptRegistry;
        this.knowledgeStore = knowledgeStore;
    }

    /**
     * 获取系统全景组件状态及统计信息。
     */
    @GetMapping("/info")
    public ApiResponse<SystemInfoResponse> getSystemInfo() {
        SystemInfoResponse response = new SystemInfoResponse();
        response.setApplication("IntelliHub Server");
        response.setJavaVersion(System.getProperty("java.version"));
        response.setSpringBootVersion(SpringBootVersion.getVersion());
        response.setDefaultTarget(modelRegistry.getDefaultChannelOrRoute());
        response.setChannels(modelRegistry.getChannelNames());
        response.setRoutes(modelRegistry.getRouteNames());
        response.setRegisteredTools(new ArrayList<>(toolRegistry.getRegisteredToolNames()));
        response.setPromptTemplateIds(new ArrayList<>(promptRegistry.listIds()));
        response.setKnowledgeBaseChunkCount(knowledgeStore.size());
        return ApiResponse.success(response);
    }
}
