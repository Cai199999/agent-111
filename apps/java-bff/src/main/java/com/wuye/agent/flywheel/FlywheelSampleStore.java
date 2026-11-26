package com.wuye.agent.flywheel;

import java.util.List;
import java.util.Map;

/**
 * 飞轮样本存储。当前实现为 JSONL 文件；未来可替换为数据库/对象存储，不改消费逻辑。
 */
public interface FlywheelSampleStore {

    void append(FlywheelSample sample);

    /** 最近样本，时间升序。 */
    List<FlywheelSample> recent(int limit);

    Map<String, Object> stats();
}
