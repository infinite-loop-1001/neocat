package com.neocat.identity.infra.adapter;

import com.neocat.identity.domain.auth.PasswordHasher;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * BCrypt 口令哈希适配器。
 *
 * <p>只使用 spring-security-crypto 的编码器，不引入 Spring Security 过滤器链——
 * 会话机制由 {@code SessionInterceptor} 自行实现（技术方案 01 §1.1 的取舍）。
 */
@Component
public class BCryptPasswordHasher implements PasswordHasher {

    private final BCryptPasswordEncoder encoder;

    public BCryptPasswordHasher() {
        this.encoder = new BCryptPasswordEncoder();
    }

    @Override
    public String hash(String rawPassword) {
        return encoder.encode(rawPassword);
    }
    @Override
    public boolean matches(String rawPassword, String hash) {
        if (Objects.isNull(rawPassword) || Objects.isNull(hash)) {
            return false;
        }
        return encoder.matches(rawPassword, hash);
    }
}
