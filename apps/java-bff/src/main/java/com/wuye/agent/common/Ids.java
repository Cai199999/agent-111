package com.wuye.agent.common;

import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

/**
 * 原型 ID 生成器：时间戳 + 进程内自增，保证单机唯一且可读。
 * 生产化建议替换为 ULID/雪花，并落库唯一约束兜底。
 */
@Component
public class Ids {

    private final AtomicLong seq = new AtomicLong();

    public String next(String prefix) {
        return prefix + System.currentTimeMillis() + "_" + seq.incrementAndGet();
    }

    public String traceId() {
        return next("trc_");
    }
}
