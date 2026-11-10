package dev.ppmb.intellihub.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ppmb.intellihub.core.context.SpringContextHolder;
import dev.ppmb.intellihub.llm.config.ModelConfig;
import dev.ppmb.intellihub.llm.factory.ChatModelFactory;
import dev.ppmb.intellihub.llm.registry.IntelliHubModelRegistry;
import dev.ppmb.intellihub.llm.router.KeyPoolChatModel;
import dev.ppmb.intellihub.llm.router.RoutingChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class IntellihubServerApplicationTests {

    @Autowired private ChatModel chatModel;

    @Autowired private ChatClient chatClient;

    @Autowired private ChatModelFactory chatModelFactory;

    @Autowired private IntelliHubModelRegistry modelRegistry;

    @Test
    void contextLoads() {
        assertThat(chatModel).isNotNull();
        assertThat(chatClient).isNotNull();
        assertThat(chatModelFactory).isNotNull();
        assertThat(modelRegistry).isNotNull();
        assertThat(SpringContextHolder.isInitialized()).isTrue();
        assertThat(SpringContextHolder.getBean(ChatClient.class)).isSameAs(chatClient);
        assertThat(SpringContextHolder.getBean(ChatModel.class)).isSameAs(chatModel);
        assertThat(ChatModelFactory.defaultInstance()).isSameAs(chatModelFactory);
        assertThat(
                        chatModelFactory.createChatModel(
                                ModelConfig.of("deepseek", "sk-test", "deepseek-chat")))
                .isNotNull();

        // 验证 @Primary ChatModel 直接指向 default-ha 高可用路由组
        assertThat(chatModel)
                .isInstanceOf(RoutingChatModel.class)
                .isSameAs(modelRegistry.getDefaultChatModel());

        // 验证 application.yml 中的多渠道与高可用路由组自动绑定
        assertThat(modelRegistry.getChannelNames())
                .contains("deepseek-primary", "qwen-backup", "ollama-local");
        assertThat(modelRegistry.getRouteNames()).contains("default-ha");
        assertThat(modelRegistry.getChatModel("deepseek-primary"))
                .isInstanceOf(KeyPoolChatModel.class);
        assertThat(modelRegistry.getDefaultChatClient()).isNotNull();
    }
}
