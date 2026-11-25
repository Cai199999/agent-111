package com.wuye.agent.notification;

import com.wuye.agent.common.ApiException;
import com.wuye.agent.common.ApiResponse;
import com.wuye.agent.common.Ids;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 外部 Agent → 底座的通知写入接口。
 *
 * <p>只认内部令牌，不认用户 JWT；接收人仍是 actorId，底座不解释业务角色。</p>
 */
@RestController
@RequestMapping("/api/v1/internal/notifications")
public class InternalNotificationController {

    private final NotificationService notificationService;
    private final Ids ids;
    private final String internalToken;

    public InternalNotificationController(
            NotificationService notificationService,
            Ids ids,
            @Value("${wuye.notify.token:}") String internalToken) {
        this.notificationService = notificationService;
        this.ids = ids;
        this.internalToken = internalToken;
    }

    @PostMapping
    public ApiResponse<NotificationRecord> create(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestBody CreateNotificationRequest request,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        requireToken(token);
        NotificationRecord record = notificationService.create(
                new NotificationService.CreateCommand(
                        request.recipientActorId(),
                        request.tenantId(),
                        request.sourceAgent(),
                        request.category(),
                        request.title(),
                        request.content(),
                        request.linkUrl(),
                        request.bizType(),
                        request.bizId(),
                        request.dedupKey()));
        return ApiResponse.ok(record, trace(traceId));
    }

    private void requireToken(String token) {
        if (internalToken == null || internalToken.isBlank()) {
            throw new ApiException("NOTIFY_TOKEN_NOT_CONFIGURED", 503,
                    "底座未配置 wuye.notify.token，拒绝写入通知");
        }
        if (token == null || !MessageDigest.isEqual(
                internalToken.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException("AUTH_FORBIDDEN", 401, "内部通知令牌无效");
        }
    }

    private String trace(String traceId) {
        return traceId == null || traceId.isBlank() ? ids.traceId() : traceId;
    }

    public record CreateNotificationRequest(
            String recipientActorId,
            String tenantId,
            String sourceAgent,
            String category,
            String title,
            String content,
            String linkUrl,
            String bizType,
            String bizId,
            String dedupKey
    ) {
    }
}
