package com.wuye.agent.metrics;

import com.wuye.agent.agent.model.TaskStatus;
import com.wuye.agent.store.Store;
import com.wuye.agent.store.TaskRecord;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

/**
 * 主 Agent 任务度量：成功率、耗时、能力/Agent 分布、工单闭环漏斗、人工介入率。
 *
 * <p>数据来自主 Agent 自己的任务表，不直连子 Agent 数据库。</p>
 */
@Service
public class MetricsService {

    private final Store store;

    public MetricsService(Store store) {
        this.store = store;
    }

    public MetricsSummary summary(Instant from, Instant to) {
        List<TaskRecord> tasks = store.listTasks(null).stream()
                .filter(task -> task.createdAt != null)
                .filter(task -> from == null || !task.createdAt.isBefore(from))
                .filter(task -> to == null || !task.createdAt.isAfter(to))
                .toList();

        long succeeded = count(tasks, TaskStatus.SUCCEEDED);
        long failed = count(tasks, TaskStatus.FAILED);
        long rejected = count(tasks, TaskStatus.REJECTED);
        long cancelled = count(tasks, TaskStatus.CANCELLED);
        long waiting = count(tasks, TaskStatus.WAITING_CONFIRM);
        long active = tasks.stream().filter(task -> task.status.active()).count();

        List<Long> latencies = tasks.stream()
                .filter(task -> task.finishedAt != null)
                .map(task -> {
                    Instant start = task.startedAt != null ? task.startedAt : task.createdAt;
                    return Math.max(0, Duration.between(start, task.finishedAt).toMillis());
                })
                .sorted()
                .toList();

        double successRate = tasks.isEmpty() ? 0 : round((double) succeeded / tasks.size());
        TaskMetrics taskMetrics = new TaskMetrics(
                tasks.size(), succeeded, failed, rejected, cancelled, waiting, active,
                successRate, average(latencies), percentile95(latencies));

        return new MetricsSummary(from, to, taskMetrics,
                byCapability(tasks), byAgent(tasks), funnel(tasks), humanIntervention(tasks));
    }

    private List<CapabilityMetric> byCapability(List<TaskRecord> tasks) {
        Map<String, List<TaskRecord>> groups = new LinkedHashMap<>();
        for (TaskRecord task : tasks) {
            groups.computeIfAbsent(task.capabilityId, key -> new ArrayList<>()).add(task);
        }
        return groups.entrySet().stream()
                .map(entry -> new CapabilityMetric(
                        entry.getKey(), entry.getValue().size(),
                        count(entry.getValue(), TaskStatus.SUCCEEDED),
                        count(entry.getValue(), TaskStatus.FAILED)
                                + count(entry.getValue(), TaskStatus.REJECTED)))
                .sorted(Comparator.comparingLong(CapabilityMetric::total).reversed())
                .toList();
    }

    private List<AgentMetric> byAgent(List<TaskRecord> tasks) {
        Map<String, List<TaskRecord>> groups = new LinkedHashMap<>();
        for (TaskRecord task : tasks) {
            groups.computeIfAbsent(task.agentKey, key -> new ArrayList<>()).add(task);
        }
        return groups.entrySet().stream()
                .map(entry -> new AgentMetric(
                        entry.getKey(), entry.getValue().size(),
                        count(entry.getValue(), TaskStatus.SUCCEEDED),
                        count(entry.getValue(), TaskStatus.FAILED)
                                + count(entry.getValue(), TaskStatus.REJECTED)))
                .sorted(Comparator.comparingLong(AgentMetric::total).reversed())
                .toList();
    }

    private Map<String, Long> funnel(List<TaskRecord> tasks) {
        Map<String, Long> funnel = new LinkedHashMap<>();
        funnel.put("create", succeededCapability(tasks, "property.ticket.create"));
        funnel.put("assign", succeededCapability(tasks, "property.ticket.assign"));
        funnel.put("accept", succeededCapability(tasks, "property.ticket.accept"));
        funnel.put("resolve", succeededCapability(tasks, "property.ticket.resolve"));
        funnel.put("visit", succeededCapability(tasks, "property.ticket.visit.add"));
        return funnel;
    }

    private HumanIntervention humanIntervention(List<TaskRecord> tasks) {
        long writeTasks = tasks.stream()
                .filter(task -> task.confirmationStatus != null
                        && !"NONE".equals(task.confirmationStatus))
                .count();
        long approved = tasks.stream()
                .filter(task -> "APPROVED".equals(task.confirmationStatus)).count();
        long rejected = tasks.stream()
                .filter(task -> "REJECTED".equals(task.confirmationStatus)).count();
        long pending = tasks.stream()
                .filter(task -> "PENDING".equals(task.confirmationStatus)).count();
        double rate = writeTasks == 0 ? 0 : round((double) (approved + rejected) / writeTasks);
        return new HumanIntervention(writeTasks, approved, rejected, pending, rate);
    }

    private long succeededCapability(List<TaskRecord> tasks, String capabilityId) {
        return tasks.stream()
                .filter(task -> capabilityId.equals(task.capabilityId))
                .filter(task -> task.status == TaskStatus.SUCCEEDED)
                .count();
    }

    private long count(List<TaskRecord> tasks, TaskStatus status) {
        return tasks.stream().filter(task -> task.status == status).count();
    }

    private long average(List<Long> values) {
        return values.isEmpty() ? 0 : Math.round(values.stream().mapToLong(Long::longValue).average().orElse(0));
    }

    private long percentile95(List<Long> sorted) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(sorted.size() * 0.95) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    public record MetricsSummary(
            Instant from,
            Instant to,
            TaskMetrics tasks,
            List<CapabilityMetric> byCapability,
            List<AgentMetric> byAgent,
            Map<String, Long> funnel,
            HumanIntervention humanIntervention
    ) {
    }

    public record TaskMetrics(
            long total,
            long succeeded,
            long failed,
            long rejected,
            long cancelled,
            long waitingConfirm,
            long active,
            double successRate,
            long avgLatencyMs,
            long p95LatencyMs
    ) {
    }

    public record CapabilityMetric(String capabilityId, long total, long succeeded, long failed) {
    }

    public record AgentMetric(String agentKey, long total, long succeeded, long failed) {
    }

    public record HumanIntervention(long writeTasks, long approved, long rejected, long pending, double rate) {
    }
}
