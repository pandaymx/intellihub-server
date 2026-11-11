package dev.ppmb.intellihub.llm.embedding;

import com.google.genai.Client;
import com.google.genai.types.ClientOptions;
import com.google.genai.types.ContentEmbedding;
import com.google.genai.types.EmbedContentConfig;
import com.google.genai.types.EmbedContentResponse;
import com.google.genai.types.HttpOptions;
import dev.ppmb.intellihub.core.exception.GlobalErrorCodeConstants;
import dev.ppmb.intellihub.core.exception.IntelliHubException;
import dev.ppmb.intellihub.core.exception.IntelliHubExceptionUtil;
import dev.ppmb.intellihub.llm.config.ModelConfig;
import dev.ppmb.intellihub.llm.config.ModelProviderType;
import dev.ppmb.intellihub.llm.router.ApiKeyPool;
import dev.ppmb.intellihub.llm.spi.provider.GoogleGenAiChatModelBuilderSpi;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * 基于 Google GenAI 原生 SDK（{@link Client}）与 {@link ApiKeyPool} 的生产级向量嵌入模型（{@code
 * gemini-embedding-001}）。
 *
 * <ul>
 *   <li><b>多 Key 故障转移与死钥隔离</b>：支持传入 {@link ApiKeyPool}，当某个 Key 返回 401/402/403 时自动标记为 {@code DEAD}
 *       并无缝切换至池内健康 Key；
 *   <li><b>代理自动穿透</b>：复用 {@link GoogleGenAiChatModelBuilderSpi#resolveProxyOptions(ModelConfig)}
 *       自动识别环境变量或扩展属性中的代理配置；
 *   <li><b>可变维度与 L2 归一化</b>：支持配置 {@code outputDimensionality}（默认 768 维，最高 3072 维）并自动执行 L2 单位向量归一化；
 *   <li><b>内存 LRU 向量缓存</b>：对相同文本片段自动命中本地向量缓存，降低重复检索与重索引的 API 延迟与配额消耗。
 * </ul>
 */
public class GoogleGenAiEmbeddingModel implements EmbeddingModel, AutoCloseable {

    public static final String DEFAULT_EMBEDDING_MODEL = "gemini-embedding-001";
    public static final int DEFAULT_DIMENSIONS = 768;
    private static final int DEFAULT_MAX_CACHE_ENTRIES = 2048;

    private static final Logger log = LoggerFactory.getLogger(GoogleGenAiEmbeddingModel.class);

    private final ModelConfig baseConfig;
    private final ApiKeyPool keyPool;
    private final String modelName;
    private final int dimensions;
    private final Map<String, Client> clientByKey = new ConcurrentHashMap<>();
    private final Map<String, float[]> embeddingCache;

    public GoogleGenAiEmbeddingModel(ModelConfig baseConfig, ApiKeyPool keyPool, int dimensions) {
        this.baseConfig = Objects.requireNonNull(baseConfig, "baseConfig 不能为空");
        this.keyPool = Objects.requireNonNull(keyPool, "keyPool 不能为空");
        this.modelName =
                (baseConfig.model() != null && !baseConfig.model().isBlank())
                        ? baseConfig.model().strip()
                        : DEFAULT_EMBEDDING_MODEL;
        this.dimensions = dimensions > 0 ? dimensions : DEFAULT_DIMENSIONS;
        this.embeddingCache =
                Collections.synchronizedMap(
                        new LinkedHashMap<>(256, 0.75f, true) {
                            @Override
                            protected boolean removeEldestEntry(Map.Entry<String, float[]> eldest) {
                                return size() > DEFAULT_MAX_CACHE_ENTRIES;
                            }
                        });
    }

    public String getModelName() {
        return modelName;
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public float[] embed(String text) {
        if (text == null || text.isBlank()) {
            return new float[dimensions];
        }
        String normalized = text.strip();
        float[] cached = embeddingCache.get(normalized);
        if (cached != null) {
            return cached.clone();
        }
        EmbeddingResponse response = call(new EmbeddingRequest(List.of(normalized), null));
        if (response.getResults() == null || response.getResults().isEmpty()) {
            throw IntelliHubExceptionUtil.exception(
                    GlobalErrorCodeConstants.AI_MODEL_CALL_FAILED, "Gemini Embedding 返回结果为空");
        }
        float[] vector = response.getResult().getOutput();
        embeddingCache.put(normalized, vector.clone());
        return vector;
    }

    @Override
    public float[] embed(Document document) {
        if (document == null || document.getText() == null) {
            return new float[dimensions];
        }
        return embed(document.getText());
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        if (request == null
                || request.getInstructions() == null
                || request.getInstructions().isEmpty()) {
            return new EmbeddingResponse(List.of());
        }
        List<String> texts = request.getInstructions();
        List<float[]> resolvedVectors = new ArrayList<>(Collections.nCopies(texts.size(), null));
        List<String> missingTexts = new ArrayList<>();
        List<Integer> missingIndices = new ArrayList<>();

        for (int i = 0; i < texts.size(); i++) {
            String raw = texts.get(i);
            String cleaned = raw == null ? "" : raw.strip();
            float[] cached = embeddingCache.get(cleaned);
            if (cached != null) {
                resolvedVectors.set(i, cached.clone());
            } else {
                missingTexts.add(cleaned.isEmpty() ? " " : cleaned);
                missingIndices.add(i);
            }
        }

        if (!missingTexts.isEmpty()) {
            List<float[]> fetched = embedBatchWithFailover(missingTexts);
            for (int j = 0; j < missingIndices.size(); j++) {
                int targetIdx = missingIndices.get(j);
                float[] vec = j < fetched.size() ? fetched.get(j) : new float[dimensions];
                resolvedVectors.set(targetIdx, vec);
                String cacheKey = texts.get(targetIdx) == null ? "" : texts.get(targetIdx).strip();
                if (!cacheKey.isEmpty()) {
                    embeddingCache.put(cacheKey, vec.clone());
                }
            }
        }

        List<Embedding> embeddings = new ArrayList<>(resolvedVectors.size());
        for (int i = 0; i < resolvedVectors.size(); i++) {
            embeddings.add(new Embedding(resolvedVectors.get(i), i));
        }
        return new EmbeddingResponse(embeddings);
    }

    private List<float[]> embedBatchWithFailover(List<String> texts) {
        int maxAttempts = Math.max(1, keyPool.totalKeyCount());
        Throwable lastError = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            String apiKey = keyPool.acquireKey();
            try {
                Client client = clientByKey.computeIfAbsent(apiKey, this::buildGenAiClient);
                EmbedContentConfig config =
                        EmbedContentConfig.builder().outputDimensionality(dimensions).build();
                EmbedContentResponse response =
                        client.models.embedContent(modelName, texts, config);
                List<ContentEmbedding> contentEmbeddings = response.embeddings().orElse(List.of());
                if (contentEmbeddings.isEmpty()) {
                    throw IntelliHubExceptionUtil.exception(
                            GlobalErrorCodeConstants.AI_MODEL_CALL_FAILED,
                            "Gemini Embedding API 未返回有效向量");
                }
                List<float[]> results = new ArrayList<>(contentEmbeddings.size());
                for (ContentEmbedding ce : contentEmbeddings) {
                    List<Float> vals = ce.values().orElse(List.of());
                    float[] arr = new float[vals.size()];
                    for (int i = 0; i < vals.size(); i++) {
                        Float v = vals.get(i);
                        arr[i] = v != null ? v : 0.0f;
                    }
                    results.add(normalizeL2(arr));
                }
                keyPool.recordSuccess(apiKey);
                return results;
            } catch (Throwable ex) {
                lastError = ex;
                keyPool.recordFailure(apiKey, ex);
                IntelliHubException wrapped =
                        IntelliHubExceptionUtil.wrap(ModelProviderType.GOOGLE_GENAI.getCode(), ex);
                if (!wrapped.isRetryable() && attempt >= maxAttempts) {
                    throw wrapped;
                }
                log.warn(
                        "[GoogleGenAiEmbeddingModel] Embedding 调用异常 (attempt {}/{}): {}",
                        attempt,
                        maxAttempts,
                        wrapped.getMessage());
            }
        }
        throw IntelliHubExceptionUtil.wrap(ModelProviderType.GOOGLE_GENAI.getCode(), lastError);
    }

    private Client buildGenAiClient(String apiKey) {
        ModelConfig keySpecific = baseConfig.toBuilder().apiKey(apiKey).build();
        Client.Builder clientBuilder = Client.builder().apiKey(apiKey);

        if ((keySpecific.baseUrl() != null && !keySpecific.baseUrl().isBlank())
                || keySpecific.timeout() != null
                || !keySpecific.customHeaders().isEmpty()) {
            HttpOptions.Builder httpBuilder = HttpOptions.builder();
            if (keySpecific.baseUrl() != null && !keySpecific.baseUrl().isBlank()) {
                httpBuilder.baseUrl(keySpecific.baseUrl().strip());
            }
            if (keySpecific.timeout() != null) {
                httpBuilder.timeout((int) keySpecific.timeout().toMillis());
            }
            if (!keySpecific.customHeaders().isEmpty()) {
                httpBuilder.headers(keySpecific.customHeaders());
            }
            clientBuilder.httpOptions(httpBuilder.build());
        }

        GoogleGenAiChatModelBuilderSpi.resolveProxyOptions(keySpecific)
                .ifPresent(proxyOpts ->
                        clientBuilder.clientOptions(
                                ClientOptions.builder().proxyOptions(proxyOpts).build()));
        return clientBuilder.build();
    }

    private static float[] normalizeL2(float[] vector) {
        if (vector == null || vector.length == 0) {
            return vector;
        }
        double sumSq = 0.0;
        for (float v : vector) {
            sumSq += (double) v * v;
        }
        if (sumSq <= 0.0) {
            return vector;
        }
        float invNorm = (float) (1.0 / Math.sqrt(sumSq));
        float[] out = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            out[i] = vector[i] * invNorm;
        }
        return out;
    }

    @Override
    public void close() {
        for (Client client : clientByKey.values()) {
            try {
                client.close();
            } catch (Exception ignored) {
                // ignore
            }
        }
        clientByKey.clear();
    }
}
