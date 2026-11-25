package com.wuye.agent.notification;

import com.wuye.agent.auth.AuthContext;
import com.wuye.agent.common.ApiResponse;
import com.wuye.agent.common.Ids;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 登录用户的站内通知：列表、未读数、已读、SSE 实时流。
 */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationService notificationService;
    private final NotificationStreamService streamService;
    private final Ids ids;

    public NotificationController(NotificationService notificationService,
                                  NotificationStreamService streamService, Ids ids) {
        this.notificationService = notificationService;
        this.streamService = streamService;
        this.ids = ids;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> list(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "limit", defaultValue = "20") int limit,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        return ApiResponse.ok(notificationService.list(AuthContext.require().actorId(), status, limit),
                trace(traceId));
    }

    @GetMapping("/unread-count")
    public ApiResponse<Map<String, Object>> unreadCount(
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        return ApiResponse.ok(
                Map.of("unread", notificationService.unreadCount(AuthContext.require().actorId())),
                trace(traceId));
    }

    @PostMapping("/{notificationNo}/read")
    public ApiResponse<Map<String, Object>> markRead(
            @PathVariable String notificationNo,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        return ApiResponse.ok(
                notificationService.markRead(AuthContext.require().actorId(), notificationNo),
                trace(traceId));
    }

    @PostMapping("/read-all")
    public ApiResponse<Map<String, Object>> markAllRead(
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        return ApiResponse.ok(
                notificationService.markAllRead(AuthContext.require().actorId()), trace(traceId));
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return streamService.subscribe(AuthContext.require().actorId());
    }

    private String trace(String traceId) {
        return traceId == null || traceId.isBlank() ? ids.traceId() : traceId;
    }
}
