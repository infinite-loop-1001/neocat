package com.neocat.identity.domain.auth;

/**
 * 口令哈希与校验。生产实现使用 BCrypt。
 */
@org.springframework.modulith.NamedInterface("identity")
public interface PasswordHasher {

    String hash(String rawPassword);

    boolean matches(String rawPassword, String hash);
}
