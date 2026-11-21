package com.wuye.agent.metrics;

import com.wuye.agent.human.HumanInterventionService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

/**
 * 粗粒度运行告警。阈值先固化，后续可移到配置/数据库。
 */
@Service
public class AlertService {

    private final MetricsService metricsService;
    private final HumanInterventionService humanService;

    public AlertService(MetricsService metricsService, HumanInterventionService humanService) {
        this.metricsService = metricsService;
        this.humanService = humanService;
    }

    public List<Alert> evaluate(Instant from, Instant to) {
        MetricsService.MetricsSummary summary = metricsService.summary(from, to);
        List<Alert> alerts = new ArrayList<>();

        if (summary.tasks().total() == 0) {
            alerts.add(new Alert("INFO", "NO_TRAFFIC", "当前时间范围暂无任务"));
            return alerts;
        }
        if (summary.tasks().total() >= 5 && summary.tasks().successRate() < 0.8) {
            alerts.add(new Alert("WARN", "LOW_SUCCESS_RATE",
                    "任务成功率 " + Math.round(summary.tasks().successRate() * 100) + "%，低于 80%"));
        }
        if (summary.tasks().waitingConfirm() >= 5) {
            alerts.add(new Alert("WARN", "CONFIRM_BACKLOG",
                    "待确认任务 " + summary.tasks().waitingConfirm() + " 个，人工确认积压"));
        }
        long humanPending = humanService.list("PENDING", 200).size();
        if (humanPending >= 5) {
            alerts.add(new Alert("WARN", "HUMAN_BACKLOG",
                    "人工介入队列待处理 " + humanPending + " 个"));
        }
        if (summary.tasks().p95LatencyMs() >= 30_000) {
            alerts.add(new Alert("WARN", "HIGH_P95",
                    "任务 P95 耗时 " + summary.tasks().p95LatencyMs() + "ms"));
        }
        if (alerts.isEmpty()) {
            alerts.add(new Alert("OK", "HEALTHY", "运行指标正常"));
        }
        return alerts;
    }

    public record Alert(String level, String code, String message) {
    }
}
