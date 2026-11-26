package com.wuye.agent.flywheel;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * JSONL 飞轮样本存储：一行一个样本，追加写；内存保留最近 N 条供管理端查询。
 *
 * <p>写盘失败只记录日志，不影响任务主流程。文件目录默认 {@code data/flywheel}（已 gitignore）。</p>
 */
@Component
@Profile("!mysql")
public class JsonlFlywheelSampleStore implements FlywheelSampleStore {

    private static final Logger log = LoggerFactory.getLogger(JsonlFlywheelSampleStore.class);

    private final ObjectMapper mapper;
    private final Path file;
    private final int recentSize;
    private final Deque<FlywheelSample> recent = new ArrayDeque<>();
    private long appended;
    private long failed;

    public JsonlFlywheelSampleStore(
            ObjectMapper mapper,
            @Value("${wuye.flywheel.root:data/flywheel}") String root,
            @Value("${wuye.flywheel.recent-size:200}") int recentSize) {
        this.mapper = mapper;
        this.file = Path.of(root == null || root.isBlank() ? "data/flywheel" : root)
                .resolve("samples.jsonl");
        this.recentSize = Math.max(1, recentSize);
    }

    @Override
    public synchronized void append(FlywheelSample sample) {
        if (sample == null) {
            return;
        }
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String line = mapper.writeValueAsString(sample) + System.lineSeparator();
            Files.writeString(file, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            appended++;
            recent.addLast(sample);
            while (recent.size() > recentSize) {
                recent.removeFirst();
            }
        } catch (IOException ex) {
            failed++;
            // 飞轮是旁路数据，不能因为磁盘问题影响任务。
            log.warn("flywheel sample append failed sampleId={} file={}",
                    sample.sampleId(), file, ex);
        }
    }

    @Override
    public synchronized List<FlywheelSample> recent(int limit) {
        int size = Math.max(0, Math.min(limit, recentSize));
        int skip = Math.max(0, recent.size() - size);
        List<FlywheelSample> list = new ArrayList<>(size);
        int index = 0;
        for (FlywheelSample sample : recent) {
            if (index++ >= skip) {
                list.add(sample);
            }
        }
        return list;
    }

    @Override
    public synchronized Map<String, Object> stats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("file", file.toString());
        stats.put("appended", appended);
        stats.put("failed", failed);
        stats.put("recent", recent.size());
        return stats;
    }
}
