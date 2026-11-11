package dev.ppmb.intellihub.rag;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ppmb.intellihub.prompt.render.SafePromptRenderer;
import dev.ppmb.intellihub.rag.engine.RagAnswer;
import dev.ppmb.intellihub.rag.engine.RagQueryEngine;
import dev.ppmb.intellihub.rag.ingest.DocumentIngestionPipeline;
import dev.ppmb.intellihub.rag.rerank.KeywordOverlapReranker;
import dev.ppmb.intellihub.rag.rerank.LlmListwiseReranker;
import dev.ppmb.intellihub.rag.retrieval.RagSearchMode;
import dev.ppmb.intellihub.rag.retrieval.RagSearchRequest;
import dev.ppmb.intellihub.rag.retrieval.ScoredDocument;
import dev.ppmb.intellihub.rag.splitter.SemanticMarkdownDocumentSplitter;
import dev.ppmb.intellihub.rag.store.HybridKnowledgeStore;
import dev.ppmb.intellihub.rag.store.LexicalSearchBackend;
import dev.ppmb.intellihub.rag.store.VectorSearchBackend;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

class RagModuleTest {

    @Test
    void shouldPreserveHeadingHierarchyCodeFencesAndDeduplicateDuringIngestion() {
        String markdown =
                """
                # IntelliHub Architecture
                ## High Availability Router
                RoutingChatModel supports FAILOVER, ROUND_ROBIN, and WEIGHTED strategies.

                ```java
                ChatModel router = new RoutingChatModel("ha", RoutingStrategy.FAILOVER, targets);
                router.call(new Prompt("Hello"));
                ```

                ## Structured Output
                StructuredOutputExecutor repairs truncated JSON and strips <think> blocks.
                """;

        SemanticMarkdownDocumentSplitter splitter =
                new SemanticMarkdownDocumentSplitter(300, 40, true);
        DocumentIngestionPipeline pipeline = new DocumentIngestionPipeline(splitter);
        HybridKnowledgeStore store = new HybridKnowledgeStore();

        Document rawDoc = Document.builder().id("arch-doc").text(markdown).build();
        DocumentIngestionPipeline.IngestionResult firstRun =
                pipeline.ingest(List.of(rawDoc), Map.of("tenant_id", "t-001"), store::addAll);

        assertThat(firstRun.sourceDocumentCount()).isEqualTo(1);
        assertThat(firstRun.ingestedChunkCount()).isEqualTo(2);
        assertThat(store.size()).isEqualTo(2);

        // 幂等重复摄取同一文档，去重后新增应为 0
        DocumentIngestionPipeline.IngestionResult secondRun =
                pipeline.ingest(List.of(rawDoc), Map.of("tenant_id", "t-001"), store::addAll);
        assertThat(secondRun.ingestedChunkCount()).isZero();
        assertThat(store.size()).isEqualTo(2);

        // 验证切分出的切片保留了完整的标题层级路径与代码块
        List<ScoredDocument> results =
                store.search(
                        RagSearchRequest.builder("RoutingChatModel FAILOVER")
                                .searchMode(RagSearchMode.BM25_ONLY)
                                .filterMetadata("tenant_id", "t-001")
                                .build());
        assertThat(results).isNotEmpty();
        Document topChunk = results.get(0).document();
        assertThat(topChunk.getMetadata().get(SemanticMarkdownDocumentSplitter.META_SECTION_PATH))
                .isEqualTo("IntelliHub Architecture > High Availability Router");
        assertThat(topChunk.getText()).contains("```java").contains("RoutingStrategy.FAILOVER");
    }

    @Test
    void shouldFuseVectorAndBm25WithRrfAndEnforceMultiTenantIsolation() {
        EmbeddingModel fakeEmbeddingModel =
                new EmbeddingModel() {
                    @Override
                    public EmbeddingResponse call(EmbeddingRequest request) {
                        List<Embedding> list =
                                request.getInstructions().stream()
                                        .map(text -> new Embedding(embed(text), 0))
                                        .toList();
                        return new EmbeddingResponse(list);
                    }

                    @Override
                    public float[] embed(Document document) {
                        return embed(document.getText());
                    }

                    @Override
                    public float[] embed(String text) {
                        if (text.contains("内存") || text.contains("Heap") || text.contains("OOM")) {
                            return new float[] {0.95f, 0.10f, 0.05f};
                        }
                        if (text.contains("1001000005") || text.contains("结构化输出")) {
                            return new float[] {0.10f, 0.95f, 0.05f};
                        }
                        return new float[] {0.05f, 0.05f, 0.95f};
                    }
                };

        HybridKnowledgeStore store = new HybridKnowledgeStore(fakeEmbeddingModel);
        store.addAll(
                List.of(
                        Document.builder()
                                .id("doc-jvm")
                                .text("排查指南：当遇到 Java Heap Space 时，请调大 -Xmx 并检查本地模型权重缓存。")
                                .metadata(Map.of("tenant_id", "tenant-a"))
                                .build(),
                        Document.builder()
                                .id("doc-err-code")
                                .text(
                                        "错误码 1001000005 代表 AI_STRUCTURED_OUTPUT_PARSE_ERROR（结构化输出解析失败）。")
                                .metadata(Map.of("tenant_id", "tenant-a"))
                                .build(),
                        Document.builder()
                                .id("doc-secret-b")
                                .text("错误码 1001000005 绝密内部文档，仅限 tenant-b 可见。")
                                .metadata(Map.of("tenant_id", "tenant-b"))
                                .build()));

        // 1. 语义查询（字面不含 Heap，靠向量余弦相似度召回 doc-jvm）
        List<ScoredDocument> semanticHits =
                store.search(
                        RagSearchRequest.builder("服务器发生内存溢出怎么处理")
                                .filterMetadata("tenant_id", "tenant-a")
                                .topK(2)
                                .build());
        assertThat(semanticHits).isNotEmpty();
        assertThat(semanticHits.get(0).document().getId()).isEqualTo("doc-jvm");
        assertThat(semanticHits.get(0).vectorScore()).isGreaterThan(0.9);

        // 2. 精确错误码查询 + 多租户隔离校验（严禁召回 tenant-b 的 doc-secret-b）
        List<ScoredDocument> codeHits =
                store.search(
                        RagSearchRequest.builder("报错 1001000005 是什么原因")
                                .filterMetadata("tenant_id", "tenant-a")
                                .topK(5)
                                .build());
        assertThat(codeHits)
                .extracting(sd -> sd.document().getId())
                .contains("doc-err-code")
                .doesNotContain("doc-secret-b");
    }

    @Test
    void shouldNeverCrushPureSemanticMatchInKeywordOverlapRerankerAndSupportLlmListwiseReranker() {
        // 切片 A：字面与问法零重合，但向量语义余弦相似度极高（0.96）
        ScoredDocument semanticWinner =
                new ScoredDocument(
                        Document.builder()
                                .id("semantic-winner")
                                .text(
                                        "OutOfMemoryError: Java heap space can be mitigated by tuning -Xmx.")
                                .build(),
                        0.0098,
                        0.96,
                        null,
                        null);

        // 切片 B：向量相似度极低（0.08），但碰巧命中了问句中的普通词 "系统"
        ScoredDocument lexicalDistractor =
                new ScoredDocument(
                        Document.builder()
                                .id("lexical-distractor")
                                .text("系统设置页面支持修改界面主题颜色。")
                                .build(),
                        0.0065,
                        0.08,
                        0.40,
                        null);

        KeywordOverlapReranker overlapReranker = new KeywordOverlapReranker();
        List<ScoredDocument> reranked =
                overlapReranker.rerank(
                        "系统崩溃报内存不够用怎么办", List.of(lexicalDistractor, semanticWinner), 2);

        // 验证归一化语义锚定后，高向量相似度的 semantic-winner 稳居第一，绝不被字面噪音反超
        assertThat(reranked.get(0).document().getId()).isEqualTo("semantic-winner");
        assertThat(reranked.get(0).score()).isGreaterThan(reranked.get(1).score());

        // 验证 LlmListwiseReranker 深度语义重排
        ChatModel rerankLlm =
                prompt ->
                        new ChatResponse(
                                List.of(
                                        new Generation(
                                                new AssistantMessage(
                                                        "{\"items\":[{\"index\":0,\"relevanceScore\":0.05},{\"index\":1,\"relevanceScore\":0.98}]}"))));
        LlmListwiseReranker llmReranker = new LlmListwiseReranker(rerankLlm);
        List<ScoredDocument> llmRanked =
                llmReranker.rerank("系统崩溃报内存不够用怎么办", List.of(lexicalDistractor, semanticWinner), 1);
        assertThat(llmRanked).hasSize(1);
        assertThat(llmRanked.get(0).document().getId()).isEqualTo("semantic-winner");
        assertThat(llmRanked.get(0).rerankScore()).isEqualTo(0.98);
    }

    @Test
    void shouldPushdownToExternalBackendsAndEnforceWiderCoarseRecallWindow() {
        AtomicInteger capturedVectorLimit = new AtomicInteger();
        AtomicInteger capturedLexicalLimit = new AtomicInteger();

        VectorSearchBackend customVectorBackend =
                new VectorSearchBackend() {
                    @Override
                    public void upsert(Collection<Document> documents) {}

                    @Override
                    public boolean delete(String documentId) {
                        return false;
                    }

                    @Override
                    public void clear() {}

                    @Override
                    public boolean isAvailable() {
                        return true;
                    }

                    @Override
                    public List<ScoredDocument> searchSimilar(
                            String query, int topK, Map<String, Object> metadataFilters) {
                        capturedVectorLimit.set(topK);
                        return List.of(
                                new ScoredDocument(
                                        Document.builder()
                                                .id("pgvector-1")
                                                .text("pgvector HNSW index recalled chunk")
                                                .metadata(metadataFilters)
                                                .build(),
                                        0.93,
                                        0.93,
                                        null,
                                        null));
                    }
                };

        LexicalSearchBackend customLexicalBackend =
                new LexicalSearchBackend() {
                    @Override
                    public void upsert(Collection<Document> documents) {}

                    @Override
                    public boolean delete(String documentId) {
                        return false;
                    }

                    @Override
                    public void clear() {}

                    @Override
                    public int size() {
                        return 100_000;
                    }

                    @Override
                    public List<ScoredDocument> searchLexical(
                            String query, int topK, Map<String, Object> metadataFilters) {
                        capturedLexicalLimit.set(topK);
                        return List.of(
                                new ScoredDocument(
                                        Document.builder()
                                                .id("pgsearch-1")
                                                .text("PostgreSQL GIN BM25 recalled chunk")
                                                .metadata(metadataFilters)
                                                .build(),
                                        4.2,
                                        null,
                                        4.2,
                                        null));
                    }
                };

        HybridKnowledgeStore pushdownStore =
                new HybridKnowledgeStore(customVectorBackend, customLexicalBackend);
        RagQueryEngine engine = new RagQueryEngine(pushdownStore);

        // 验证 topK=3 时，Stage 1 粗召回窗口自动按 Math.max(3 * 4, 20) = 20 放大，且下推后端收到宽窗口请求
        assertThat(engine.computeCoarseRecallLimit(3)).isEqualTo(20);
        assertThat(engine.computeCoarseRecallLimit(8)).isEqualTo(32);

        List<ScoredDocument> results =
                engine.retrieveAndRerank(
                        RagSearchRequest.builder("HNSW index")
                                .topK(3)
                                .filterMetadata("tenant_id", "prod-tenant")
                                .build());

        assertThat(results).hasSize(2);
        assertThat(capturedVectorLimit.get()).isGreaterThanOrEqualTo(20);
        assertThat(capturedLexicalLimit.get()).isGreaterThanOrEqualTo(20);
    }

    @Test
    void shouldExecuteEndToEndRagQueryWithContextBudgetAndCitations() {
        HybridKnowledgeStore store = new HybridKnowledgeStore();
        store.addAll(
                List.of(
                        Document.builder()
                                .id("kb-ha-1")
                                .text("401/403 鉴权失败的 API Key 会被标记为 DEAD 永久隔离，不会每 60 秒重复放行。")
                                .metadata(
                                        Map.of(
                                                SemanticMarkdownDocumentSplitter.META_SOURCE_DOC_ID,
                                                "ha-guide.md",
                                                SemanticMarkdownDocumentSplitter.META_SECTION_PATH,
                                                "Key Pool > Dead Isolation"))
                                .build(),
                        Document.builder()
                                .id("kb-ha-2")
                                .text("Half-Open 半开恢复采用 AtomicBoolean CAS 单请求探活，防止并发惊群效应。")
                                .metadata(
                                        Map.of(
                                                SemanticMarkdownDocumentSplitter.META_SOURCE_DOC_ID,
                                                "ha-guide.md",
                                                SemanticMarkdownDocumentSplitter.META_SECTION_PATH,
                                                "Key Pool > Half-Open Probe"))
                                .build()));

        // 设置较小的上下文预算（仅够容纳 Top-1 切片），验证预算裁剪与引用溯源
        RagQueryEngine engine =
                new RagQueryEngine(
                        store, new KeywordOverlapReranker(), new SafePromptRenderer(), 260);

        ChatModel mockChatModel =
                prompt -> {
                    String userPromptText =
                            prompt.getInstructions()
                                    .get(prompt.getInstructions().size() - 1)
                                    .getText();
                    assertThat(userPromptText).contains("[#1 | source=ha-guide.md");
                    return new ChatResponse(
                            List.of(
                                    new Generation(
                                            new AssistantMessage(
                                                    "<think>Checking citations...</think>当遇到 401/403 时，Key 会直接进入 DEAD 状态永久隔离 [#1]。"))));
                };

        RagAnswer answer =
                engine.ask(mockChatModel, RagSearchRequest.of("401 鉴权失败的 Key 会怎么处理？", 2));

        assertThat(answer.hasCitations()).isTrue();
        assertThat(answer.citations()).hasSize(1);
        assertThat(answer.citations().get(0).document().getId()).isEqualTo("kb-ha-1");
        assertThat(answer.answer()).isEqualTo("当遇到 401/403 时，Key 会直接进入 DEAD 状态永久隔离 [#1]。");
    }

    @Test
    void shouldPersistKnowledgeAndVectorsToDiskAndReloadWithoutRecomputingEmbeddings(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) {
        java.nio.file.Path storageFile = tempDir.resolve("kb-store.json");
        AtomicInteger embedCallCount = new AtomicInteger(0);

        EmbeddingModel countingEmbeddingModel =
                new EmbeddingModel() {
                    @Override
                    public EmbeddingResponse call(EmbeddingRequest request) {
                        List<Embedding> list =
                                request.getInstructions().stream()
                                        .map(text -> new Embedding(embed(text), 0))
                                        .toList();
                        return new EmbeddingResponse(list);
                    }

                    @Override
                    public float[] embed(Document document) {
                        return embed(document.getText());
                    }

                    @Override
                    public float[] embed(String text) {
                        embedCallCount.incrementAndGet();
                        return new float[] {0.6f, 0.8f};
                    }
                };

        // 1. 首次创建并写入文档，自动计算向量并持久化落盘
        HybridKnowledgeStore store1 = new HybridKnowledgeStore(countingEmbeddingModel, storageFile);
        store1.add(
                Document.builder()
                        .id("persisted-chunk-1")
                        .text("Gemini Embedding 768 维向量与本地 JSON 持久化测试")
                        .metadata(Map.of("tenant_id", "default", "kb_id", "builtin"))
                        .build());

        assertThat(store1.size()).isEqualTo(1);
        assertThat(store1.getVectorizedCount()).isEqualTo(1);
        assertThat(store1.getVectorDimensions()).isEqualTo(2);
        assertThat(embedCallCount.get()).isEqualTo(1);
        assertThat(java.nio.file.Files.exists(storageFile)).isTrue();

        // 2. 模拟服务重启，从同一文件恢复：已有向量无需重新调用 EmbeddingModel
        HybridKnowledgeStore store2 = new HybridKnowledgeStore(countingEmbeddingModel, storageFile);
        assertThat(store2.size()).isEqualTo(1);
        assertThat(store2.getVectorizedCount()).isEqualTo(1);
        assertThat(embedCallCount.get()).isEqualTo(1);

        List<ScoredDocument> listed = store2.listAll(10, Map.of("tenant_id", "default"));
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).document().getId()).isEqualTo("persisted-chunk-1");
    }
}
