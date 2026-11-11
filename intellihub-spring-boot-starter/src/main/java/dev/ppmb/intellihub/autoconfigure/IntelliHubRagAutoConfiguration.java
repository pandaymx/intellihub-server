package dev.ppmb.intellihub.autoconfigure;

import dev.ppmb.intellihub.prompt.render.SafePromptRenderer;
import dev.ppmb.intellihub.rag.engine.RagQueryEngine;
import dev.ppmb.intellihub.rag.ingest.DocumentIngestionPipeline;
import dev.ppmb.intellihub.rag.rerank.DocumentReranker;
import dev.ppmb.intellihub.rag.rerank.KeywordOverlapReranker;
import dev.ppmb.intellihub.rag.splitter.SemanticMarkdownDocumentSplitter;
import dev.ppmb.intellihub.rag.store.HybridKnowledgeStore;
import dev.ppmb.intellihub.rag.store.LexicalSearchBackend;
import dev.ppmb.intellihub.rag.store.VectorSearchBackend;
import java.nio.file.Path;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * {@code intellihub-rag} 模块 Spring Boot 自动装配配置类。
 *
 * <p>当类路径下存在 {@link HybridKnowledgeStore} 时，自动注册：
 *
 * <ul>
 *   <li>{@link SemanticMarkdownDocumentSplitter}：语义感知型 Markdown 文档切分器；
 *   <li>{@link DocumentIngestionPipeline}：支持 SHA-256 幂等去重与租户标签注入的文档摄取流水线；
 *   <li>{@link HybridKnowledgeStore}：向量余弦相似度 + BM25 + RRF 混合知识检索引擎（优先注入外部下推的 {@link
 *       VectorSearchBackend} 与 {@link LexicalSearchBackend}，缺省回退至内置内存双路索引）；
 *   <li>{@link DocumentReranker}：默认 {@link KeywordOverlapReranker} 特征重排器；
 *   <li>{@link RagQueryEngine}：具备上下文窗口预算控制与角标溯源的 RAG 问答引擎。
 * </ul>
 */
@AutoConfiguration(after = IntelliHubPromptAutoConfiguration.class)
@ConditionalOnClass(HybridKnowledgeStore.class)
public class IntelliHubRagAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public SemanticMarkdownDocumentSplitter intelliHubMarkdownDocumentSplitter() {
        return new SemanticMarkdownDocumentSplitter();
    }

    @Bean
    @ConditionalOnMissingBean
    public DocumentIngestionPipeline intelliHubDocumentIngestionPipeline(
            SemanticMarkdownDocumentSplitter splitter) {
        return new DocumentIngestionPipeline(splitter);
    }

    @Bean
    @ConditionalOnMissingBean
    public HybridKnowledgeStore intelliHubHybridKnowledgeStore(
            ObjectProvider<VectorSearchBackend> vectorBackendProvider,
            ObjectProvider<LexicalSearchBackend> lexicalBackendProvider,
            ObjectProvider<EmbeddingModel> embeddingModelProvider,
            DocumentIngestionPipeline ingestionPipeline,
            @Value("${intellihub.rag.storage-path:}") String storagePath) {
        VectorSearchBackend vectorBackend = vectorBackendProvider.getIfUnique();
        LexicalSearchBackend lexicalBackend = lexicalBackendProvider.getIfUnique();
        if (vectorBackend != null && lexicalBackend != null) {
            return new HybridKnowledgeStore(vectorBackend, lexicalBackend);
        }
        Path persistencePath =
                (storagePath != null && !storagePath.isBlank())
                        ? Path.of(storagePath.strip())
                        : null;
        HybridKnowledgeStore store =
                new HybridKnowledgeStore(embeddingModelProvider.getIfUnique(), persistencePath);
        if (store.size() > 0 && ingestionPipeline != null) {
            ingestionPipeline.registerExistingDocuments(store.listDocuments());
        }
        return store;
    }

    @Bean
    @ConditionalOnMissingBean
    public DocumentReranker intelliHubDocumentReranker() {
        return new KeywordOverlapReranker();
    }

    @Bean
    @ConditionalOnMissingBean
    public RagQueryEngine intelliHubRagQueryEngine(
            HybridKnowledgeStore knowledgeStore,
            DocumentReranker reranker,
            ObjectProvider<SafePromptRenderer> promptRendererProvider) {
        SafePromptRenderer renderer =
                promptRendererProvider.getIfAvailable(SafePromptRenderer::new);
        return new RagQueryEngine(
                knowledgeStore, reranker, renderer, RagQueryEngine.DEFAULT_MAX_CONTEXT_CHARS);
    }
}
