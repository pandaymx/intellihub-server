package dev.ppmb.intellihub.llm.config;

/**
 * AI 模型厂商与构建器能力元数据枚举（Capability Metadata）。
 *
 * <p>用于声明各 {@link ModelProviderType} 或 {@link dev.ppmb.intellihub.llm.spi.ChatModelBuilderSpi}
 * 支持的高级特性，供上层配置校验、参数自动裁剪与前端动态表单渲染联动使用。
 */
public enum ModelCapability {

    /** 支持流式响应输出（Flux Stream） */
    STREAMING("streaming", "流式响应输出"),

    /** 支持工具/函数调用（Tool / Function Calling） */
    TOOL_CALLING("tool-calling", "工具/函数调用"),

    /** 支持多模态视觉输入（图片/文档 Media） */
    VISION("vision", "多模态视觉输入"),

    /** 支持结构化输出（JSON Object / JSON Schema） */
    STRUCTURED_OUTPUT("structured-output", "结构化 JSON 输出"),

    /** 支持深度思考模式开关（{@link ModelConfig#EXTRA_THINKING_ENABLED}） */
    THINKING_SWITCH("thinking-switch", "深度思考开关"),

    /** 支持思考 Token 预算配置（{@link ModelConfig#EXTRA_THINKING_BUDGET}） */
    THINKING_BUDGET("thinking-budget", "思考 Token 预算"),

    /** 支持推理强度档位配置（{@link ModelConfig#EXTRA_REASONING_EFFORT}，如 low/medium/high） */
    REASONING_EFFORT("reasoning-effort", "推理强度档位"),

    /** 支持频率惩罚与存在惩罚参数（{@code frequencyPenalty} / {@code presencePenalty}） */
    PENALTY_PARAMETERS("penalty-parameters", "频率与存在惩罚参数"),

    /** 支持 Top-K 采样参数（{@code topK}） */
    TOP_K_SAMPLING("top-k-sampling", "Top-K 采样参数"),

    /** 支持进程内本地模型权重文件加载（{@link ModelConfig#EXTRA_JINFER_MODEL_PATH}） */
    LOCAL_WEIGHTS("local-weights", "本地权重加载"),

    /** 支持自定义网关或私有代理 Base URL（{@code baseUrl}） */
    CUSTOM_BASE_URL("custom-base-url", "自定义网关地址");

    private final String code;
    private final String description;

    ModelCapability(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }
}
