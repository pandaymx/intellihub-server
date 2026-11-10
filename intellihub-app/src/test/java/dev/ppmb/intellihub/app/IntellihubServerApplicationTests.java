package dev.ppmb.intellihub.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ppmb.intellihub.core.context.SpringContextHolder;
import dev.ppmb.intellihub.llm.config.ModelConfig;
import dev.ppmb.intellihub.llm.factory.ChatModelFactory;
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

    @Test
    void contextLoads() {
        assertThat(chatModel).isNotNull();
        assertThat(chatClient).isNotNull();
        assertThat(chatModelFactory).isNotNull();
        assertThat(SpringContextHolder.isInitialized()).isTrue();
        assertThat(SpringContextHolder.getBean(ChatClient.class)).isSameAs(chatClient);
        assertThat(SpringContextHolder.getBean(ChatModel.class)).isSameAs(chatModel);
        assertThat(ChatModelFactory.defaultInstance()).isSameAs(chatModelFactory);
        assertThat(
                        chatModelFactory.createChatModel(
                                ModelConfig.of("deepseek", "sk-test", "deepseek-chat")))
                .isNotNull();
    }
}
