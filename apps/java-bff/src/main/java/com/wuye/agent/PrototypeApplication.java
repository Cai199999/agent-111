package com.wuye.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;

/**
 * 物业主 Agent BFF 原型入口。
 *
 * <p>原型阶段只依赖 spring-boot-starter-web：会话、任务、确认、事件全部内存存储，
 * 子 Agent 由 Mock Adapter 模拟两种协议方言。生产化时再接入 MySQL/Redis/MinIO
 * 与真实 HTTP Adapter，不改变控制器与 Agent 层接口。</p>
 */
@SpringBootApplication(exclude = {
        DataSourceAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class,
        JdbcTemplateAutoConfiguration.class,
        FlywayAutoConfiguration.class
})
public class PrototypeApplication {

    public static void main(String[] args) {
        SpringApplication.run(PrototypeApplication.class, args);
    }
}
