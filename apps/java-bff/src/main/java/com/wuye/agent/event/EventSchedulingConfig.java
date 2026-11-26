package com.wuye.agent.event;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 只为本项目的 Outbox relay 开启调度；不影响其他组件。
 */
@Configuration
@EnableScheduling
public class EventSchedulingConfig {
}
