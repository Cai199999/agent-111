package com.wuye.agent.audit;

import com.wuye.agent.auth.AuthContext;
import com.wuye.agent.auth.AuthUser;
import com.wuye.agent.common.Ids;
import java.time.Instant;
import java.util.Map;

import org.springframework.stereotype.Service;

/**
 * 审计写入：只记录动作、资源、结果和脱敏摘要，不记录密码/令牌。
 */
@Service
public class AuditService {

    private final AuditStore store;
    private final Ids ids;

    public AuditService(AuditStore store, Ids ids) {
        this.store = store;
        this.ids = ids;
    }

    public void record(String action, String resource, String resourceId,
                       String status, Map<String, Object> detail) {
        record(action, resource, resourceId, null, null, status, detail);
    }

    public void record(String action, String resource, String resourceId,
                       String agentKey, String capabilityId,
                       String status, Map<String, Object> detail) {
        AuthUser user = AuthContext.get();
        recordAs(user == null ? "anonymous" : user.username(),
                user == null ? null : user.id(),
                action, resource, resourceId, agentKey, capabilityId, status, detail);
    }

    /**
     * 登录时 AuthContext 尚未建立，需要显式传入用户名/用户 ID。
     */
    public void recordAs(String username, Long userId,
                         String action, String resource, String resourceId,
                         String agentKey, String capabilityId,
                         String status, Map<String, Object> detail) {
        store.record(new AuditEvent(
                ids.traceId(),
                userId,
                username,
                action,
                resource,
                resourceId,
                agentKey,
                capabilityId,
                status,
                detail == null ? Map.of() : detail,
                Instant.now()));
    }
}
