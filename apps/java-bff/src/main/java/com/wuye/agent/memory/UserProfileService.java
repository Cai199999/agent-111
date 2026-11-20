package com.wuye.agent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuye.agent.routing.LlmRouterClient;
import com.wuye.agent.store.ChatMessage;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

/**
 * 全局用户画像：只保存稳定偏好（默认项目/楼栋/房间、常用工单类型/优先级、常用意图），
 * 不保存姓名/电话/身份证等敏感个人信息；会话原文不跨会话读取。
 */
@Service
public class UserProfileService {

    private final UserProfileStore store;
    private final LlmRouterClient llmRouterClient;
    private final ObjectMapper mapper;

    public UserProfileService(UserProfileStore store, LlmRouterClient llmRouterClient,
                              ObjectMapper mapper) {
        this.store = store;
        this.llmRouterClient = llmRouterClient;
        this.mapper = mapper;
    }

    public Map<String, Object> getProfile(long userId) {
        return store.get(userId).map(UserMemoryProfile::profile).orElse(Map.of());
    }

    public UserMemoryProfile getMeta(long userId) {
        return store.get(userId).orElse(null);
    }

    public void delete(long userId) {
        store.delete(userId);
    }

    public Map<String, Object> merge(long userId, Map<String, Object> updates) {
        Map<String, Object> merged = new LinkedHashMap<>(getProfile(userId));
        for (String key : List.of("preferredProjectId", "preferredBuildingId", "preferredRoomId",
                "preferredTicketTypeCode", "preferredPriorityCode", "commonIntents", "notes")) {
            Object value = updates == null ? null : updates.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                merged.put(key, value);
            }
        }
        store.save(new UserMemoryProfile(userId, merged, 1.0, "explicit", Instant.now()));
        return merged;
    }

    public String contextFor(long userId) {
        Map<String, Object> profile = getProfile(userId);
        if (profile.isEmpty()) {
            return null;
        }
        try {
            return mapper.writeValueAsString(profile);
        } catch (Exception ex) {
            return null;
        }
    }

    public void updateFromSession(long userId, String sessionNo, String summary,
                                  List<ChatMessage> messages) {
        if (messages == null || messages.size() < 2) {
            return;
        }
        Map<String, Object> current = getProfile(userId);
        List<Map<String, String>> turns = messages.subList(Math.max(0, messages.size() - 8), messages.size())
                .stream()
                .map(message -> {
                    Map<String, String> turn = new LinkedHashMap<>();
                    turn.put("role", message.role());
                    turn.put("content", message.content() == null ? "" : message.content());
                    return turn;
                })
                .toList();
        llmRouterClient.extractProfile(String.valueOf(userId), current, summary, turns)
                .filter(outcome -> outcome.changed() && outcome.confidence() >= 0.6)
                .ifPresent(outcome -> store.save(new UserMemoryProfile(
                        userId,
                        outcome.profile(),
                        outcome.confidence(),
                        sessionNo,
                        Instant.now())));
    }
}
