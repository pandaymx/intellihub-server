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
        assertThat(
                        dev.ppmb.intellihub.rag.store.pg.PgHybridSearchBackend.toVectorLiteral(
                                new float[] {0.25f, -0.5f, 1.0f}))
                .isEqualTo("[0.25,-0.5,1.0]");
    }

    @Test
    void shouldParseAndIngestMultiFormatDocumentsIntoStructuredMarkdown() throws Exception {
        DocumentIngestionPipeline pipeline = new DocumentIngestionPipeline();
        HybridKnowledgeStore store = new HybridKnowledgeStore();

        // 1. PDF 解析验证（生成 2 页真实 PDF 字节流）
        byte[] pdfBytes;
        try (org.apache.pdfbox.pdmodel.PDDocument pdf = new org.apache.pdfbox.pdmodel.PDDocument();
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {
            org.apache.pdfbox.pdmodel.PDPage page1 = new org.apache.pdfbox.pdmodel.PDPage();
            pdf.addPage(page1);
            try (org.apache.pdfbox.pdmodel.PDPageContentStream cs =
                    new org.apache.pdfbox.pdmodel.PDPageContentStream(pdf, page1)) {
                cs.beginText();
                cs.setFont(
                        new org.apache.pdfbox.pdmodel.font.PDType1Font(
                                org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA),
                        12);
                cs.newLineAtOffset(50, 700);
                cs.showText("IntelliHub Enterprise SLA Guide Page 1");
                cs.endText();
            }
            org.apache.pdfbox.pdmodel.PDPage page2 = new org.apache.pdfbox.pdmodel.PDPage();
            pdf.addPage(page2);
            try (org.apache.pdfbox.pdmodel.PDPageContentStream cs =
                    new org.apache.pdfbox.pdmodel.PDPageContentStream(pdf, page2)) {
                cs.beginText();
                cs.setFont(
                        new org.apache.pdfbox.pdmodel.font.PDType1Font(
                                org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA),
                        12);
                cs.newLineAtOffset(50, 700);
                cs.showText("Refund Policy and Approval Workflow Page 2");
                cs.endText();
            }
            pdf.save(bos);
            pdfBytes = bos.toByteArray();
        }

        var pdfResult =
                pipeline.ingestFile(
                        "sla-manual.pdf",
                        "application/pdf",
                        pdfBytes,
                        Map.of("tenant_id", "default"),
                        store::addAll);
        assertThat(pdfResult.parsedDocument().fileFormat()).isEqualTo("PDF");
        assertThat(pdfResult.parsedDocument().metadata().get("page_count")).isEqualTo(2);
        assertThat(pdfResult.parsedDocument().markdownContent())
                .contains("## Page 1")
                .contains("## Page 2")
                .contains("Refund Policy and Approval Workflow Page 2");
        assertThat(pdfResult.chunks()).isNotEmpty();

        // 2. CSV 表格解析验证
        String csvContent = "订单号,客户姓名,退款金额,审批状态\nORD-001,张三,199.00,自动通过\nORD-002,李四,899.00,人工复核\n";
        var csvParsed =
                pipeline.parseFile(
                        "orders.csv",
                        "text/csv",
                        csvContent.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(csvParsed.fileFormat()).isEqualTo("CSV");
        assertThat(csvParsed.markdownContent())
                .contains("| 订单号 | 客户姓名 | 退款金额 | 审批状态 |")
                .contains("| ORD-002 | 李四 | 899.00 | 人工复核 |");

        // 3. Word (.docx) 压缩包解析验证（含标题样式与表格）
        byte[] docxBytes;
        try (java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(bos)) {
            zos.putNextEntry(new java.util.zip.ZipEntry("word/document.xml"));
            String docXml =
                    """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                      <w:body>
                        <w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>架构设计总览</w:t></w:r></w:p>
                        <w:p><w:r><w:t>本系统采用多模型高可用路由架构。</w:t></w:r></w:p>
                        <w:tbl>
                          <w:tr><w:tc><w:p><w:r><w:t>模块</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>职责</w:t></w:r></w:p></w:tc></w:tr>
                          <w:tr><w:tc><w:p><w:r><w:t>intellihub-rag</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>多格式解析与混合检索</w:t></w:r></w:p></w:tc></w:tr>
                        </w:tbl>
                      </w:body>
                    </w:document>
                    """;
            zos.write(docXml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.finish();
            docxBytes = bos.toByteArray();
        }
        var docxParsed =
                pipeline.parseFile(
                        "arch-spec.docx",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        docxBytes);
        assertThat(docxParsed.fileFormat()).isEqualTo("DOCX");
        assertThat(docxParsed.markdownContent())
                .contains("# 架构设计总览")
                .contains("| 模块 | 职责 |")
                .contains("| intellihub-rag | 多格式解析与混合检索 |");

        // 4. HTML 与 JSON 解析验证
        String html =
                "<html><head><title>API 手册</title><script>alert(1)</script></head><body><h2>鉴权说明</h2><p>请在 Header 携带 Bearer Token。</p></body></html>";
        var htmlParsed =
                pipeline.parseFile(
                        "api.html",
                        "text/html",
                        html.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(htmlParsed.title()).isEqualTo("API 手册");
        assertThat(htmlParsed.markdownContent()).contains("## 鉴权说明").doesNotContain("alert(1)");
    }
}
