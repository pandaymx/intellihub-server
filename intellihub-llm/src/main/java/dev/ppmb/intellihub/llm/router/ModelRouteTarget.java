package dev.ppmb.intellihub.llm.router;

import dev.ppmb.intellihub.llm.config.ModelConfig;
import dev.ppmb.intellihub.llm.factory.ChatModelFactory;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.ai.chat.model.ChatModel;

/**
 * 多模型高可用路由中的单个候选目标节点定义。
 */
public final class ModelRouteTarget {

    private final String name;
    private final String provider;
    private final int weight;
    private final Supplier<ChatModel> modelSupplier;

    public ModelRouteTarget(
            String name, String provider, int weight, Supplier<ChatModel> modelSupplier) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.provider = Objects.requireNonNullElse(provider, name);
        this.weight = Math.max(1, weight);
        this.modelSupplier =
                Objects.requireNonNull(modelSupplier, "modelSupplier must not be null");
    }

    public static ModelRouteTarget of(String name, ModelConfig config) {
        return of(name, config, 1, ChatModelFactory.defaultInstance());
    }

    public static ModelRouteTarget of(
            String name, ModelConfig config, int weight, ChatModelFactory factory) {
        Objects.requireNonNull(config, "config must not be null");
        Objects.requireNonNull(factory, "factory must not be null");
        return new ModelRouteTarget(
                name, config.provider(), weight, () -> factory.createChatModel(config));
    }

    public static ModelRouteTarget ofModel(String name, String provider, ChatModel chatModel) {
        return ofModel(name, provider, 1, chatModel);
    }

    public static ModelRouteTarget ofModel(
            String name, String provider, int weight, ChatModel chatModel) {
        Objects.requireNonNull(chatModel, "chatModel must not be null");
        return new ModelRouteTarget(name, provider, weight, () -> chatModel);
    }

    public String name() {
        return name;
    }

    public String provider() {
        return provider;
    }

    public int weight() {
        return weight;
    }

    public ChatModel resolveChatModel() {
        return modelSupplier.get();
    }
}
