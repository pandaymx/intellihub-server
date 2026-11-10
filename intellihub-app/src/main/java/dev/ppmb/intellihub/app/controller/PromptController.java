package dev.ppmb.intellihub.app.controller;

import dev.ppmb.intellihub.app.common.ApiResponse;
import dev.ppmb.intellihub.app.dto.prompt.PromptRenderRequest;
import dev.ppmb.intellihub.app.dto.prompt.PromptRenderResponse;
import dev.ppmb.intellihub.app.dto.prompt.PromptTemplateDto;
import dev.ppmb.intellihub.app.dto.prompt.StructuredExtractRequest;
import dev.ppmb.intellihub.app.dto.prompt.StructuredExtractResponse;
import dev.ppmb.intellihub.core.exception.GlobalErrorCodeConstants;
import dev.ppmb.intellihub.core.exception.IntelliHubExceptionUtil;
import dev.ppmb.intellihub.llm.registry.IntelliHubModelRegistry;
import dev.ppmb.intellihub.prompt.render.SafePromptRenderer;
import dev.ppmb.intellihub.prompt.structured.StructuredOutputExecutor;
import dev.ppmb.intellihub.prompt.template.PromptRegistry;
import dev.ppmb.intellihub.prompt.template.PromptTemplateSpec;
import java.util.Map;
import java.util.Set;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提示词多版本模板管理、安全防注入渲染与结构化输出联调 REST 控制器。
 */
@RestController
@RequestMapping("/api/v1/prompts")
public class PromptController {

    private final PromptRegistry promptRegistry;
    private final SafePromptRenderer safePromptRenderer;
    private final StructuredOutputExecutor structuredOutputExecutor;
    private final IntelliHubModelRegistry modelRegistry;

    public PromptController(
            PromptRegistry promptRegistry,
            SafePromptRenderer safePromptRenderer,
            StructuredOutputExecutor structuredOutputExecutor,
            IntelliHubModelRegistry modelRegistry) {
        this.promptRegistry = promptRegistry;
        this.safePromptRenderer = safePromptRenderer;
        this.structuredOutputExecutor = structuredOutputExecutor;
        this.modelRegistry = modelRegistry;
    }

    /**
     * 获取所有已注册的提示词模板标识符列表。
     */
    @GetMapping
    public ApiResponse<Set<String>> listTemplates() {
        return ApiResponse.success(promptRegistry.listIds());
    }

    /**
     * 动态注册或更新多版本提示词模板。
     */
    @PostMapping
    public ApiResponse<PromptTemplateDto> registerTemplate(@RequestBody PromptTemplateDto request) {
        if (request == null || request.getId() == null || request.getId().isBlank()) {
            throw IntelliHubExceptionUtil.invalidParamException("提示词模板 id 不能为空");
        }
        if (request.getUserTemplate() == null || request.getUserTemplate().isBlank()) {
            throw IntelliHubExceptionUtil.invalidParamException("提示词模板 userTemplate 不能为空");
        }
        PromptTemplateSpec.Builder builder =
                PromptTemplateSpec.builder(request.getId().strip())
                        .version(request.getVersion())
                        .description(request.getDescription())
                        .systemTemplate(request.getSystemTemplate())
                        .userTemplate(request.getUserTemplate())
                        .guardInjection(request.isGuardInjection())
                        .wrapUserVariablesInXmlTag(request.isWrapUserVariablesInXmlTag());
        if (request.getRequiredVariables() != null) {
            builder.requiredVariables(request.getRequiredVariables());
        }
        if (request.getDefaultVariables() != null) {
            builder.defaultVariables(request.getDefaultVariables());
        }
        PromptTemplateSpec spec = builder.build();
        promptRegistry.register(spec);
        return ApiResponse.success(PromptTemplateDto.fromSpec(spec));
    }

    /**
     * 获取指定提示词模板定义（可指定版本号，默认返回当前激活的最新版本）。
     */
    @GetMapping("/{id}")
    public ApiResponse<PromptTemplateDto> getTemplate(
            @PathVariable("id") String id,
            @RequestParam(value = "version", required = false) String version) {
        PromptTemplateSpec spec;
        if (version != null && !version.isBlank()) {
            spec = promptRegistry.get(id, version.strip());
        } else {
            spec = promptRegistry.get(id);
        }
        return ApiResponse.success(PromptTemplateDto.fromSpec(spec));
    }

    /**
     * 安全防注入渲染提示词模板（严格防范提示词越狱与字符注入）。
     */
    @PostMapping("/{id}/render")
    public ApiResponse<PromptRenderResponse> renderTemplate(
            @PathVariable("id") String id, @RequestBody PromptRenderRequest request) {
        if (request == null || request.getVariables() == null) {
            throw IntelliHubExceptionUtil.invalidParamException("渲染变量 variables 不能为空");
        }

        PromptTemplateSpec spec;
        if (request.getVersion() != null && !request.getVersion().isBlank()) {
            spec = promptRegistry.get(id, request.getVersion().strip());
        } else {
            spec = promptRegistry.get(id);
        }

        Prompt prompt = safePromptRenderer.render(spec, request.getVariables());

        String systemMessage = "";
        String userMessage = "";
        for (Message m : prompt.getInstructions()) {
            if (m instanceof SystemMessage) {
                systemMessage = m.getText();
            } else if (m instanceof UserMessage) {
                userMessage = m.getText();
            }
        }

        return ApiResponse.success(
                new PromptRenderResponse(spec.id(), spec.version(), systemMessage, userMessage));
    }

    /**
     * 结构化输出提取联调：基于容错型提取器解析非结构化自然语言为规范 JSON 键值字典。
     */
    @PostMapping("/structured-extract")
    public ApiResponse<StructuredExtractResponse> extractStructuredData(
            @RequestBody StructuredExtractRequest request) {
        if (request == null || request.getText() == null || request.getText().isBlank()) {
            throw IntelliHubExceptionUtil.exception(
                    GlobalErrorCodeConstants.AI_PROMPT_EMPTY, "待提取文本不能为空");
        }

        String targetChannel =
                (request.getChannel() != null && !request.getChannel().isBlank())
                        ? request.getChannel().strip()
                        : modelRegistry.getDefaultChannelOrRoute();
        ChatModel chatModel = modelRegistry.getChatModel(targetChannel);

        StringBuilder promptText = new StringBuilder();
        promptText.append("请从以下输入内容中精准提取关键结构化信息：\n");
        if (request.getTargetFields() != null && !request.getTargetFields().isEmpty()) {
            promptText
                    .append("重点提取字段：")
                    .append(String.join(", ", request.getTargetFields()))
                    .append("\n");
        }
        promptText.append("\n【输入文本】\n").append(request.getText().strip());

        Prompt prompt = new Prompt(new UserMessage(promptText.toString()));

        long start = System.currentTimeMillis();
        Map<String, Object> extracted =
                structuredOutputExecutor.execute(
                        chatModel,
                        prompt,
                        new ParameterizedTypeReference<Map<String, Object>>() {});
        long latency = System.currentTimeMillis() - start;

        return ApiResponse.success(
                new StructuredExtractResponse(extracted, targetChannel, latency));
    }
}
