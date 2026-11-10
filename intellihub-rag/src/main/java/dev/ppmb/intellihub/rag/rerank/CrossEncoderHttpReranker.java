package dev.ppmb.intellihub.rag.rerank;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.ppmb.intellihub.llm.spi.support.SharedHttpClientSupport;
import dev.ppmb.intellihub.rag.retrieval.ScoredDocument;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * 标准 Cross-Encoder 深度语义精排客户端（支持 BGE-Reranker / vLLM / Infinity / Jina / Cohere {@code /v1/rerank}
 * 协议）。
 *
 * <p>通过交叉注意力模型（Cross-Encoder）对 {@code (query, document)} 文本对进行深度语义相关性打分，能够准确识别“字面零重合但语义强相关”的切片。
 *
 * <p>具备高可用降级保护：当远端 Rerank 服务超时或不可达时，自动无缝回退至 {@link #fallbackReranker}（默认 {@link
 * KeywordOverlapReranker}）。
 */
public class CrossEncoderHttpReranker implements DocumentReranker {

    private static final Logger log = LoggerFactory.getLogger(CrossEncoderHttpReranker.class);

    public static final String DEFAULT_RERANK_PATH = "/v1/rerank";
    public static final String DEFAULT_MODEL = "BAAI/bge-reranker-v2-m3";

    private final RestClient restClient;
    private final String rerankPath;
    private final String model;
    private final DocumentReranker fallbackReranker;

    public CrossEncoderHttpReranker(String baseUrl, String apiKey, String model) {
        this(
                buildRestClient(baseUrl, apiKey),
                DEFAULT_RERANK_PATH,
                model,
                new KeywordOverlapReranker());
    }

    public CrossEncoderHttpReranker(
            RestClient restClient,
            String rerankPath,
            String model,
            DocumentReranker fallbackReranker) {
        this.restClient = Objects.requireNonNull(restClient, "restClient 不能为空");
        this.rerankPath =
                rerankPath == null || rerankPath.isBlank()
                        ? DEFAULT_RERANK_PATH
                        : rerankPath.strip();
        this.model = model == null || model.isBlank() ? DEFAULT_MODEL : model.strip();
        this.fallbackReranker =
                fallbackReranker != null ? fallbackReranker : new KeywordOverlapReranker();
    }

    private static RestClient buildRestClient(String baseUrl, String apiKey) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("CrossEncoderHttpReranker baseUrl 不能为空");
        }
        RestClient.Builder builder =
                SharedHttpClientSupport.sharedRestClientBuilder().baseUrl(baseUrl.strip());
        if (apiKey != null && !apiKey.isBlank()) {
            builder.defaultHeader("Authorization", "Bearer " + apiKey.strip());
        }
        return builder.build();
    }

    @Override
    public List<ScoredDocument> rerank(String query, List<ScoredDocument> candidates, int topN) {
        if (candidates == null || candidates.isEmpty() || topN <= 0) {
            return List.of();
        }
        if (query == null || query.isBlank()) {
            return candidates.size() <= topN ? candidates : candidates.subList(0, topN);
        }

        List<String> docTexts =
                candidates.stream()
                        .map(c -> c.document().getText() == null ? "" : c.document().getText())
                        .toList();

        try {
            RerankApiRequest apiRequest =
                    new RerankApiRequest(model, query.strip(), docTexts, topN);
            RerankApiResponse response =
                    restClient
                            .post()
                            .uri(rerankPath)
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.APPLICATION_JSON)
                            .body(apiRequest)
                            .retrieve()
                            .body(RerankApiResponse.class);

            if (response == null || response.results() == null || response.results().isEmpty()) {
                log.warn("[CrossEncoderHttpReranker] 远端返回空结果，回退至本地精排器");
                return fallbackReranker.rerank(query, candidates, topN);
            }

            List<ScoredDocument> reranked = new ArrayList<>();
            for (RerankResultItem item : response.results()) {
                if (item != null && item.index() >= 0 && item.index() < candidates.size()) {
                    ScoredDocument orig = candidates.get(item.index());
                    reranked.add(orig.withRerankScore(item.relevanceScore()));
                }
            }
            if (reranked.isEmpty()) {
                return fallbackReranker.rerank(query, candidates, topN);
            }
            reranked.sort(Comparator.comparingDouble(ScoredDocument::score).reversed());
            return reranked.size() <= topN ? reranked : reranked.subList(0, topN);
        } catch (RuntimeException ex) {
            log.warn(
                    "[CrossEncoderHttpReranker] 调用 Cross-Encoder [{}] 异常，自动降级至兜底精排器: {}",
                    model,
                    ex.getMessage());
            return fallbackReranker.rerank(query, candidates, topN);
        }
    }

    public record RerankApiRequest(
            String model, String query, List<String> documents, @JsonProperty("top_n") int topN) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RerankApiResponse(List<RerankResultItem> results) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RerankResultItem(
            int index, @JsonProperty("relevance_score") double relevanceScore) {}
}
