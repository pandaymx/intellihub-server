package dev.ppmb.intellihub.core.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ppmb.intellihub.core.exception.IntelliHubException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class SpringContextHolderTest {

    @AfterEach
    void tearDown() {
        SpringContextHolder.clearHolder();
    }

    @Test
    void getBean_whenContextInitialized_returnsRegisteredBean() {
        try (AnnotationConfigApplicationContext ctx =
                new AnnotationConfigApplicationContext(TestConfig.class)) {
            assertThat(SpringContextHolder.isInitialized()).isTrue();
            assertThat(SpringContextHolder.getBean(DummyAiService.class).greet())
                    .isEqualTo("hello-ai");
            assertThat(SpringContextHolder.getBean("dummyAiService", DummyAiService.class))
                    .isNotNull();
            assertThat(SpringContextHolder.getBeanIfAvailable(DummyAiService.class)).isPresent();
            assertThat(SpringContextHolder.getBeansOfType(DummyAiService.class)).hasSize(1);
        }
        assertThat(SpringContextHolder.isInitialized()).isFalse();
    }

    @Test
    void getBean_whenContextNotInitialized_throwsIntelliHubException() {
        SpringContextHolder.clearHolder();
        assertThat(SpringContextHolder.getBeanIfAvailable(DummyAiService.class)).isEmpty();
        assertThatThrownBy(() -> SpringContextHolder.getBean(DummyAiService.class))
                .isInstanceOf(IntelliHubException.class);
    }

    @Configuration
    static class TestConfig {

        @Bean
        SpringContextHolder springContextHolder() {
            return new SpringContextHolder();
        }

        @Bean
        DummyAiService dummyAiService() {
            return new DummyAiService();
        }
    }

    static class DummyAiService {
        String greet() {
            return "hello-ai";
        }
    }
}
