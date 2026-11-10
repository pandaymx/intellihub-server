package dev.ppmb.intellihub.prompt.template;

import java.util.Objects;

/**
 * Few-Shot 上下文学习示例对（一轮 User 提问 + 期望的 Assistant 回答）。
 *
 * @param user 示例用户输入
 * @param assistant 示例助手标准输出
 */
public record FewShotExample(String user, String assistant) {

    public FewShotExample {
        Objects.requireNonNull(user, "user example must not be null");
        Objects.requireNonNull(assistant, "assistant example must not be null");
    }

    public static FewShotExample of(String user, String assistant) {
        return new FewShotExample(user, assistant);
    }
}
