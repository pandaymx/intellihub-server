package dev.ppmb.intellihub.app.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ppmb.intellihub.app.common.ApiResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 对话工作台多轮会话历史持久化控制器（将用户会话、思考链、工具轨迹、知识引用及挂起审批状态落盘存储）。
 */
@RestController
@RequestMapping("/api/v1/chat/sessions")
public class ChatSessionController {

    private static final Logger log = LoggerFactory.getLogger(ChatSessionController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path sessionsFilePath;
    private final Object fileLock = new Object();

    public ChatSessionController(
            @Value("${intellihub.chat.sessions-path:.intellihub-data/chat-sessions.json}")
                    String sessionsPath) {
        this.sessionsFilePath =
                (sessionsPath != null && !sessionsPath.isBlank())
                        ? Path.of(sessionsPath.strip())
                        : null;
    }

    /**
     * 读取已持久化的全部聊天会话列表。
     */
    @GetMapping
    public ApiResponse<List<Map<String, Object>>> getSessions() {
        return ApiResponse.success(loadSessionsFromDisk());
    }

    /**
     * 覆盖保存最新的聊天会话列表。
     */
    @PutMapping
    public ApiResponse<Integer> saveSessions(@RequestBody List<Map<String, Object>> sessions) {
        List<Map<String, Object>> safeList = sessions != null ? sessions : List.of();
        saveSessionsToDisk(safeList);
        return ApiResponse.success(safeList.size());
    }

    /**
     * 删除指定 ID 的聊天会话。
     */
    @DeleteMapping("/{sessionId}")
    public ApiResponse<Boolean> deleteSession(@PathVariable("sessionId") String sessionId) {
        synchronized (fileLock) {
            List<Map<String, Object>> current = new ArrayList<>(loadSessionsFromDisk());
            boolean removed =
                    current.removeIf(s -> Objects.equals(String.valueOf(s.get("id")), sessionId));
            if (removed) {
                saveSessionsToDisk(current);
            }
            return ApiResponse.success(removed);
        }
    }

    private List<Map<String, Object>> loadSessionsFromDisk() {
        if (sessionsFilePath == null || !Files.exists(sessionsFilePath)) {
            return List.of();
        }
        synchronized (fileLock) {
            try {
                byte[] bytes = Files.readAllBytes(sessionsFilePath);
                if (bytes.length == 0) {
                    return List.of();
                }
                return MAPPER.readValue(bytes, new TypeReference<List<Map<String, Object>>>() {});
            } catch (Exception ex) {
                log.warn(
                        "[ChatSessionController] 读取会话持久化文件 [{}] 失败: {}",
                        sessionsFilePath,
                        ex.getMessage());
                return List.of();
            }
        }
    }

    private void saveSessionsToDisk(List<Map<String, Object>> sessions) {
        if (sessionsFilePath == null) {
            return;
        }
        synchronized (fileLock) {
            try {
                Path parent = sessionsFilePath.toAbsolutePath().getParent();
                if (parent != null && !Files.exists(parent)) {
                    Files.createDirectories(parent);
                }
                Path tmp =
                        sessionsFilePath.resolveSibling(
                                sessionsFilePath.getFileName().toString() + ".tmp");
                MAPPER.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), sessions);
                Files.move(
                        tmp,
                        sessionsFilePath,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException ex) {
                log.warn(
                        "[ChatSessionController] 写入会话持久化文件 [{}] 失败: {}",
                        sessionsFilePath,
                        ex.getMessage());
            }
        }
    }
}
