package com.wuye.agent.store;

import com.wuye.agent.agent.model.AgentError;
import com.wuye.agent.agent.model.Confirmation;
import com.wuye.agent.agent.model.TaskEvent;
import com.wuye.agent.agent.model.TaskStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 原型任务状态。生产化对应 wa_task_binding + wa_task_event。
 */
public final class TaskRecord {

    public final String taskNo;
    public final String sessionNo;
    public final String agentKey;
    public final String capabilityId;
    public final String clientTaskId;
    public final String actorId;
    public final String executionMode;
    public final Map<String, Object> input;
    public final Instant createdAt;
    public final AtomicLong eventSeq = new AtomicLong();
    public final AtomicLong childEventSeq = new AtomicLong();
    public final List<TaskEvent> events = new CopyOnWriteArrayList<>();

    public volatile String childTaskId;
    /** 创建任务的链路 ID；事件信封与排障统一使用。 */
    public volatile String traceId;
    /** 租户隔离标识；原型阶段来自登录用户，缺省为 "0"。 */
    public volatile String tenantId = "0";
    public volatile TaskStatus status = TaskStatus.PENDING;
    public volatile Object output;
    public volatile AgentError error;
    public volatile Confirmation confirmation;
    public volatile String confirmationStatus = "NONE";
    public volatile int progress;
    public volatile String currentStep;
    public volatile boolean cancelSupported = true;
    public volatile String cancelReason;
    public volatile Instant startedAt;
    public volatile Instant finishedAt;

    public TaskRecord(String taskNo, String sessionNo, String agentKey, String capabilityId,
                      String clientTaskId, String actorId, String executionMode,
                      Map<String, Object> input, Instant createdAt) {
        this.taskNo = taskNo;
        this.sessionNo = sessionNo;
        this.agentKey = agentKey;
        this.capabilityId = capabilityId;
        this.clientTaskId = clientTaskId;
        this.actorId = actorId;
        this.executionMode = executionMode;
        this.input = input;
        this.createdAt = createdAt;
    }

    public boolean terminal() {
        return status.terminal();
    }

    public boolean cancelable() {
        return cancelSupported && status.active();
    }
}
