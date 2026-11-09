package dev.ppmb.intellihub.server;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class IntellihubServerApplicationTests {

    @Autowired private ChatModel chatModel;

    @Autowired private ChatClient chatClient;

    @Test
    void contextLoads() {
        assertThat(chatModel).isNotNull();
        assertThat(chatClient).isNotNull();
    }
}
