package dev.ppmb.intellihub.core.clean;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AiOutputUtilTest {

    @Test
    void stripThink_removesClosedAndUnclosedThinkBlocks() {
        String raw =
                """
                <think>
                用户想要一个 JSON 格式的订单摘要。
                先分析字段：orderId, amount。
                </think>
                好的，这是最终回答。
                """;

        assertThat(AiOutputUtil.stripThink(raw)).isEqualTo("好的，这是最终回答。");
        assertThat(AiOutputUtil.extractThink(raw))
                .contains("用户想要一个 JSON 格式的订单摘要。")
                .contains("先分析字段：orderId, amount。");

        String truncatedThink = "先输出一点提示<thinking>正在思考但被 max_tokens 截断了...";
        assertThat(AiOutputUtil.stripThink(truncatedThink)).isEqualTo("先输出一点提示");
        assertThat(AiOutputUtil.extractThink(truncatedThink)).isEqualTo("正在思考但被 max_tokens 截断了...");
    }

    @Test
    void extractJson_stripsThinkAndMarkdownFenceAndTrailingCommas() {
        String raw =
                """
                <think>先思考一下怎么返回 JSON</think>
                为您生成结果如下：
                ```json
                {
                  "name": "测试项目",
                  "tags": ["ai", "sdk",],
                  "meta": {
                    "braceInStr": "包含 } 和 ] 的字符串",
                  },
                }
                ```
                希望以上内容对您有帮助！
                """;

        String json = AiOutputUtil.extractJson(raw);

        assertThat(json)
                .isEqualTo(
                        """
                        {
                          "name": "测试项目",
                          "tags": ["ai", "sdk"],
                          "meta": {
                            "braceInStr": "包含 } 和 ] 的字符串"
                          }
                        }\
                        """);
    }

    @Test
    void repairTruncatedJson_closesUnfinishedStringAndNestedBrackets() {
        String truncatedInValue =
                "<think>思考中</think>```json\n{\"title\":\"Java AI 工具包\",\"items\":[{\"id\":1,\"desc\":\"半截字符";
        assertThat(AiOutputUtil.repairTruncatedJson(truncatedInValue))
                .isEqualTo("{\"title\":\"Java AI 工具包\",\"items\":[{\"id\":1,\"desc\":\"半截字符\"}]}");

        String truncatedAfterColon = "{\"status\":\"ok\",\"count\":";
        assertThat(AiOutputUtil.repairTruncatedJson(truncatedAfterColon))
                .isEqualTo("{\"status\":\"ok\",\"count\":null}");

        String truncatedAtDanglingKey = "{\"status\":\"ok\", \"unfinishedKey";
        assertThat(AiOutputUtil.repairTruncatedJson(truncatedAtDanglingKey))
                .isEqualTo("{\"status\":\"ok\", \"unfinishedKey\":null}");

        String truncatedAtBoolean = "{\"active\": tru";
        assertThat(AiOutputUtil.repairTruncatedJson(truncatedAtBoolean))
                .isEqualTo("{\"active\": true}");
    }
}
