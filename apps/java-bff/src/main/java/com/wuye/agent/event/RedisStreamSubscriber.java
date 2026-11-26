package com.wuye.agent.event;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis Stream 消费者：本实例作为 consumer group 成员，读取事件后投递给本地 EventBus。
 *
 * <p>多实例部署时同一 group 内竞争消费；消费端（飞轮）按 eventId 幂等。
 * 启动时先补读本 consumer 的 PENDING，再读新消息；坏消息记录后 ACK，避免毒消息阻塞。</p>
 */
@Component
@ConditionalOnProperty(name = "wuye.event.backend", havingValue = "redis")
public class RedisStreamSubscriber implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(RedisStreamSubscriber.class);

    private final StringRedisTemplate redis;
    private final RedisEventCodec codec;
    private final EventBus eventBus;
    private final RedisStreamHealth health;
    private final String stream;
    private final String group;
    private final String consumer;
    private final String dlq;
    private final long dlqMaxLen;
    private final AtomicBoolean started = new AtomicBoolean();
    private volatile boolean running;
    private Thread worker;

    public RedisStreamSubscriber(
            StringRedisTemplate redis,
            RedisEventCodec codec,
            EventBus eventBus,
            RedisStreamHealth health,
            @Value("${wuye.event.redis.stream:wuye_agent:events}") String stream,
            @Value("${wuye.event.redis.group:wuye_agent-bff}") String group,
            @Value("${wuye.event.redis.consumer:bff-1}") String consumer,
            @Value("${wuye.event.redis.dlq:wuye_agent:events:dlq}") String dlq,
            @Value("${wuye.event.redis.dlq-maxlen:5000}") long dlqMaxLen) {
        this.redis = redis;
        this.codec = codec;
        this.eventBus = eventBus;
        this.health = health;
        this.stream = stream;
        this.group = group;
        this.consumer = consumer;
        this.dlq = dlq;
        this.dlqMaxLen = Math.max(100, dlqMaxLen);
    }

    @Override
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        running = true;
        worker = new Thread(this::loop, "redis-event-subscriber");
        worker.setDaemon(true);
        worker.start();
        log.info("redis stream subscriber started stream={} group={} consumer={}",
                stream, group, consumer);
    }

    @Override
    public void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
        }
        started.set(false);
        log.info("redis stream subscriber stopped stream={} consumer={}", stream, consumer);
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void loop() {
        boolean groupReady = ensureGroup();
        if (groupReady) {
            drainPending();
        }
        while (running) {
            if (!groupReady) {
                groupReady = ensureGroup();
                if (!groupReady) {
                    sleep();
                    continue;
                }
                drainPending();
            }
            try {
                StreamOperations<String, String, String> ops = redis.opsForStream();
                List<MapRecord<String, String, String>> records = ops.read(
                        Consumer.from(group, consumer),
                        StreamReadOptions.empty().count(50).block(Duration.ofSeconds(2)),
                        StreamOffset.create(stream, ReadOffset.lastConsumed()));
                if (records != null) {
                    records.forEach(this::handle);
                }
            } catch (Exception ex) {
                if (!running) {
                    break;
                }
                log.warn("redis stream read failed stream={} consumer={} error={}",
                        stream, consumer, ex.getMessage());
                // 只有 group/stream 丢失才重建；普通超时保持 groupReady，直接重试。
                if (fullMessage(ex).contains("NOGROUP")) {
                    groupReady = false;
                }
                sleep();
            }
        }
    }

    /** 进程重启后，先处理本 consumer 上次未 ACK 的消息。 */
    private void drainPending() {
        try {
            StreamOperations<String, String, String> ops = redis.opsForStream();
            List<MapRecord<String, String, String>> records = ops.read(
                    Consumer.from(group, consumer),
                    StreamReadOptions.empty().count(100),
                    StreamOffset.create(stream, ReadOffset.from("0")));
            if (records != null) {
                log.info("redis stream replay pending count={} consumer={}",
                        records.size(), consumer);
                records.forEach(this::handle);
            }
        } catch (Exception ex) {
            log.warn("redis stream pending replay failed consumer={} error={}",
                    consumer, ex.getMessage());
        }
    }

    private void handle(MapRecord<String, String, String> record) {
        String json = record.getValue().get("event");
        boolean shouldAck;
        if (json == null || json.isBlank()) {
            health.recordFailure("redis stream record without event field");
            shouldAck = deadLetter(record, null, "missing event field");
        } else {
            try {
                EventEnvelope event = codec.decode(json);
                eventBus.publish(event);
                health.recordDelivered(1);
                shouldAck = true;
                log.debug("redis stream event delivered id={} type={} eventId={}",
                        record.getId(), event.eventType(), event.eventId());
            } catch (Exception ex) {
                health.recordFailure(ex.getMessage());
                // 坏消息进 DLQ；DLQ 写失败则不 ACK，留待重启补读。
                shouldAck = deadLetter(record, json, ex.getMessage());
            }
        }
        if (!shouldAck) {
            return;
        }
        try {
            redis.opsForStream().acknowledge(stream, group, record.getId());
        } catch (Exception ex) {
            health.recordFailure("ack failed: " + ex.getMessage());
            log.warn("redis stream ack failed id={} error={}",
                    record.getId(), ex.getMessage());
        }
    }

    private boolean deadLetter(MapRecord<String, String, String> record, String json, String error) {
        try {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("originalId", record.getId().getValue());
            fields.put("error", error == null ? "unknown" : error);
            fields.put("at", Instant.now().toString());
            if (json != null) {
                fields.put("event", json);
            }
            redis.opsForStream().add(
                    StreamRecords.mapBacked(fields).withStreamKey(dlq));
            try {
                redis.opsForStream().trim(dlq, dlqMaxLen, true);
            } catch (Exception trimEx) {
                log.warn("redis stream dlq trim failed dlq={} error={}",
                        dlq, trimEx.getMessage());
            }
            health.recordDeadLetter();
            log.warn("redis stream dead letter id={} dlq={} error={}",
                    record.getId(), dlq, error);
            return true;
        } catch (Exception ex) {
            log.warn("redis stream dead letter failed id={} error={}",
                    record.getId(), ex.getMessage());
            return false;
        }
    }

    private boolean ensureGroup() {
        try {
            redis.execute((RedisConnection connection) -> connection.streamCommands()
                    .xGroupCreate(stream.getBytes(StandardCharsets.UTF_8), group,
                            ReadOffset.from("0"), true));
            return true;
        } catch (Exception ex) {
            String message = fullMessage(ex);
            if (message.contains("BUSYGROUP")) {
                // group 已存在，属于正常启动路径。
                return true;
            }
            log.warn("redis stream group create failed stream={} group={} error={}",
                    stream, group, message);
            return false;
        }
    }

    private static String fullMessage(Throwable ex) {
        StringBuilder builder = new StringBuilder();
        Throwable current = ex;
        while (current != null) {
            if (current.getMessage() != null) {
                builder.append(current.getMessage()).append(' ');
            }
            current = current.getCause();
        }
        return builder.toString();
    }

    private void sleep() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }
}
