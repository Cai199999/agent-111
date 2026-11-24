package com.wuye.agent.agent.install;

import java.util.List;
import java.util.Optional;

/**
 * 运行时安装 Agent 的注册表存储。
 *
 * <p>默认内存实现用于 mock/测试；mysql profile 使用数据库实现，
 * 由启动加载器恢复。实现负责内部令牌的落库加密（MySQL）。</p>
 */
public interface AgentRegistryStore {

    /** 新增或覆盖同 key 的记录；安装时调用。 */
    void save(AgentRegistryEntry entry);

    /** 查询单条（含已移除记录）。 */
    Optional<AgentRegistryEntry> find(String agentKey);

    /** 查询所有 ACTIVE 记录；启动加载使用。 */
    List<AgentRegistryEntry> listActive();

    /** 标记移除；不物理删除，保留审计线索。 */
    void remove(String agentKey);
}
