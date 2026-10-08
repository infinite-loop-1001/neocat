package com.neocat.identity.domain.auth;
import org.springframework.modulith.NamedInterface;

/**
 * 口令哈希与校验。生产实现使用 BCrypt。
 */
@NamedInterface("identity")
public interface PasswordHasher {

    String hash(String rawPassword);

    boolean matches(String rawPassword, String hash);
}
