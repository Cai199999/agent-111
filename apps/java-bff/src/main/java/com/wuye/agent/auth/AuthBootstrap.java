package com.wuye.agent.auth;

import java.sql.Timestamp;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * MySQL profile 的演示账号初始化。生产应改为管理员创建/SSO，不保留默认密码。
 */
@Component
@Profile("mysql")
public class AuthBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AuthBootstrap.class);

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final String adminPassword;
    private final String workerPassword;
    private final String userPassword;

    public AuthBootstrap(JdbcTemplate jdbc, PasswordEncoder passwordEncoder,
                         @Value("${wuye.dev.admin-password:admin123}") String adminPassword,
                         @Value("${wuye.dev.worker-password:worker123}") String workerPassword,
                         @Value("${wuye.dev.user-password:user123}") String userPassword) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.adminPassword = adminPassword;
        this.workerPassword = workerPassword;
        this.userPassword = userPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        long adminId = 0;
        for (AuthSeed.DemoUser demo : AuthSeed.demoUsers()) {
            // 新增层级账号统一使用“用户名 + 123”的开发密码，生产必须改为 SSO/管理员创建。
            String password = switch (demo.username()) {
                case "admin" -> adminPassword;
                case "worker" -> workerPassword;
                case "user" -> userPassword;
                default -> demo.username() + "123";
            };
            Timestamp now = Timestamp.from(Instant.now());
            jdbc.update("""
                            INSERT IGNORE INTO wa_user
                                (user_no, username, password_hash, display_name, actor_id,
                                 tenant_id, status, created_at, updated_at)
                            VALUES (?, ?, ?, ?, ?, '0', 1, ?, ?)
                            """,
                    demo.userNo(), demo.username(), passwordEncoder.encode(password),
                    demo.displayName(), demo.actorId(), now, now);
            Long userId = jdbc.queryForObject(
                    "SELECT id FROM wa_user WHERE username = ?", Long.class, demo.username());
            if (userId != null) {
                jdbc.update("INSERT IGNORE INTO wa_user_role (user_id, role_code) VALUES (?, ?)",
                        userId, demo.role());
                if ("admin".equals(demo.username())) {
                    adminId = userId;
                }
            }
        }
        if (adminId > 0) {
            int migrated = jdbc.update(
                    "UPDATE wa_chat_session SET user_id = ? WHERE user_id IS NULL", adminId);
            if (migrated > 0) {
                log.info("已将 {} 个历史会话归属到管理员账号", migrated);
            }
        }
        log.warn("已确保演示账号 admin/worker/user 存在；默认密码仅限开发环境，生产必须修改或接 SSO");
    }
}
