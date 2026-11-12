package com.campus.homework.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 密码编码器配置：提供全局 BCryptPasswordEncoder Bean。
 * <p>Service 层保存/重置密码时注入本 Bean 加密，保证 init.sql 中的
 * $2a$10$ 密文与运行时生成的密文互相可校验；Batch 3 的 Spring Security
 * 认证流程复用同一个 Bean，避免多处 new 造成 strength 不一致。</p>
 *
 * @author campus-homework
 */
@Configuration
public class PasswordConfig {

    /**
     * BCrypt 密码编码器（默认 strength=10，与 init.sql 测试数据密文强度一致）。
     *
     * @return PasswordEncoder 单例
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
