package com.wuye.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;

/**
 * 物业主 Agent BFF 入口。
 *
 * <p>默认内存 profile，便于测试；mysql profile 接 MySQL + Flyway。
 * 子 Agent 支持 Mock / 真实 HTTP 方言 / 运行时安装的 Canonical v1 Agent；
 * 当前未接 Redis/MinIO，生产化时再补，不改变控制器与 Agent 层接口。</p>
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
