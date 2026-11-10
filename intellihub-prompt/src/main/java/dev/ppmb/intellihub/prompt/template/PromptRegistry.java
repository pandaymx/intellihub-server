package dev.ppmb.intellihub.prompt.template;

import dev.ppmb.intellihub.core.exception.GlobalErrorCodeConstants;
import dev.ppmb.intellihub.core.exception.IntelliHubExceptionUtil;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多版本 Prompt 模板注册中心（支持同 {@code id} 多版本共存、默认激活版本切换与按版本精确检索）。
 */
public class PromptRegistry {

    private final Map<String, Map<String, PromptTemplateSpec>> versionedStore =
            new ConcurrentHashMap<>();
    private final Map<String, String> activeVersions = new ConcurrentHashMap<>();

    /**
     * 注册一个 {@link PromptTemplateSpec} 并将其设为该 {@code id} 的当前激活版本。
     */
    public PromptRegistry register(PromptTemplateSpec spec) {
        return register(spec, true);
    }

    /**
     * 注册一个 {@link PromptTemplateSpec}，可指定是否立即切换为当前激活版本。
     */
    public PromptRegistry register(PromptTemplateSpec spec, boolean setAsActive) {
        Objects.requireNonNull(spec, "PromptTemplateSpec must not be null");
        versionedStore
                .computeIfAbsent(spec.id(), k -> new ConcurrentHashMap<>())
                .put(spec.version(), spec);
        if (setAsActive) {
            activeVersions.put(spec.id(), spec.version());
        } else {
            activeVersions.putIfAbsent(spec.id(), spec.version());
        }
        return this;
    }

    /**
     * 切换指定模板 {@code id} 的当前激活版本号（灰度切流或回滚时使用）。
     */
    public PromptRegistry activateVersion(String id, String version) {
        String cleanId = normalize(id, "id");
        String cleanVer = normalize(version, "version");
        Map<String, PromptTemplateSpec> versions = versionedStore.get(cleanId);
        if (versions == null || !versions.containsKey(cleanVer)) {
            throw IntelliHubExceptionUtil.exception(
                    GlobalErrorCodeConstants.NOT_FOUND,
                    "Prompt 模板 [" + cleanId + "@" + cleanVer + "]");
        }
        activeVersions.put(cleanId, cleanVer);
        return this;
    }

    /**
     * 获取指定 {@code id} 当前激活版本的 {@link PromptTemplateSpec}。
     */
    public PromptTemplateSpec get(String id) {
        String cleanId = normalize(id, "id");
        String activeVer = activeVersions.get(cleanId);
        if (activeVer == null) {
            throw IntelliHubExceptionUtil.exception(
                    GlobalErrorCodeConstants.NOT_FOUND, "Prompt 模板 [" + cleanId + "]");
        }
        return get(cleanId, activeVer);
    }

    /**
     * 获取指定 {@code id} 与特定 {@code version} 的 {@link PromptTemplateSpec}。
     */
    public PromptTemplateSpec get(String id, String version) {
        String cleanId = normalize(id, "id");
        String cleanVer = normalize(version, "version");
        Map<String, PromptTemplateSpec> versions = versionedStore.get(cleanId);
        if (versions != null) {
            PromptTemplateSpec spec = versions.get(cleanVer);
            if (spec != null) {
                return spec;
            }
        }
        throw IntelliHubExceptionUtil.exception(
                GlobalErrorCodeConstants.NOT_FOUND, "Prompt 模板 [" + cleanId + "@" + cleanVer + "]");
    }

    public Optional<PromptTemplateSpec> find(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        String cleanId = id.strip();
        String activeVer = activeVersions.get(cleanId);
        if (activeVer == null) {
            return Optional.empty();
        }
        Map<String, PromptTemplateSpec> versions = versionedStore.get(cleanId);
        return versions == null ? Optional.empty() : Optional.ofNullable(versions.get(activeVer));
    }

    public Set<String> listIds() {
        return Collections.unmodifiableSet(versionedStore.keySet());
    }

    public Set<String> listVersions(String id) {
        if (id == null || id.isBlank()) {
            return Set.of();
        }
        Map<String, PromptTemplateSpec> versions = versionedStore.get(id.strip());
        return versions == null ? Set.of() : Collections.unmodifiableSet(versions.keySet());
    }

    private static String normalize(String val, String label) {
        if (val == null || val.isBlank()) {
            throw IntelliHubExceptionUtil.invalidParamException("Prompt {} 不能为空", label);
        }
        return val.strip();
    }
}
