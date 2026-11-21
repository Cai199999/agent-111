package com.wuye.agent.api;

import com.wuye.agent.audit.AuditService;
import com.wuye.agent.auth.AuthContext;
import com.wuye.agent.auth.AuthUser;
import com.wuye.agent.chat.ChatService;
import com.wuye.agent.common.ApiException;
import com.wuye.agent.common.ApiResponse;
import com.wuye.agent.common.Ids;
import com.wuye.agent.store.ChatMessage;
import com.wuye.agent.store.SessionState;
import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 单对话框 API。登录态由 AuthFilter 的 JWT 解析并写入 AuthContext；
 * 历史 X-Actor-Id 仅作为兼容头保留，不再作为登录凭据。
 */
@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {

    private final ChatService chatService;
    private final AuditService auditService;
    private final Ids ids;

    public ChatController(ChatService chatService, AuditService auditService, Ids ids) {
        this.chatService = chatService;
        this.auditService = auditService;
        this.ids = ids;
    }

    @PostMapping("/sessions")
    public ApiResponse<SessionView> createSession(@RequestBody(required = false) CreateSessionRequest request,
                                                  @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        AuthUser user = AuthContext.require();
        SessionState session = chatService.createSession(user.id(), request == null ? null : request.title());
        return ApiResponse.ok(SessionView.from(session), trace(traceId));
    }

    @GetMapping("/sessions")
    public ApiResponse<List<SessionView>> listSessions(
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        return ApiResponse.ok(chatService.listSessions(AuthContext.require().id()).stream()
                .map(SessionView::from).toList(), trace(traceId));
    }

    @GetMapping("/sessions/{sessionNo}")
    public ApiResponse<SessionView> getSession(@PathVariable String sessionNo,
                                               @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        SessionState session = requireOwnedSession(sessionNo);
        return ApiResponse.ok(SessionView.from(session), trace(traceId));
    }

    @DeleteMapping("/sessions/{sessionNo}")
    public ApiResponse<Map<String, Object>> deleteSession(@PathVariable String sessionNo,
                                                         @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        requireOwnedSession(sessionNo);
        if (!chatService.deleteSession(sessionNo)) {
            throw ApiException.notFound("SESSION_NOT_FOUND", "会话不存在：" + sessionNo);
        }
        auditService.record("SESSION_DELETE", "SESSION", sessionNo, "SUCCESS", Map.of());
        return ApiResponse.ok(Map.of("sessionNo", sessionNo, "deleted", true), trace(traceId));
    }

    @GetMapping("/sessions/{sessionNo}/messages")
    public ApiResponse<List<ChatMessage>> messages(@PathVariable String sessionNo,
                                                   @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        requireOwnedSession(sessionNo);
        return ApiResponse.ok(chatService.messages(sessionNo), trace(traceId));
    }

    @PostMapping(value = "/sessions/{sessionNo}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter send(@PathVariable String sessionNo,
                           @RequestBody SendMessageRequest request,
                           @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        requireOwnedSession(sessionNo);
        SseEmitter emitter = new SseEmitter(60_000L);
        chatService.send(sessionNo, request.content(), request.clientMessageId(),
                AuthContext.require(), trace(traceId), emitter);
        return emitter;
    }

    private SessionState requireOwnedSession(String sessionNo) {
        AuthUser user = AuthContext.require();
        SessionState session = chatService.getSession(sessionNo);
        if (session == null) {
            throw ApiException.notFound("SESSION_NOT_FOUND", "会话不存在：" + sessionNo);
        }
        if (session.userId != user.id() && !user.isAdmin()) {
            throw new ApiException("AUTH_FORBIDDEN", 403, "无权访问该会话");
        }
        return session;
    }

    private String trace(String traceId) {
        return traceId == null || traceId.isBlank() ? ids.traceId() : traceId;
    }

    public record CreateSessionRequest(String title) {
    }

    public record SendMessageRequest(String content, String clientMessageId) {
    }
}
