package com.neocat.platform.domain.init;
import org.springframework.modulith.NamedInterface;

/**
 * 超级管理员创建钩子。
 *
 * <p>平台初始化需要建立第一个超级管理员，但账号能力属于 identity 模块；
 * platform 通过该抽象单向调用 identity，避免反向依赖。
 */
@FunctionalInterface
@NamedInterface("platform")
public interface SuperAdminProvisioner {

    /**
     * 创建第一个超级管理员。
     *
     * @return 新建账号 ID
     */
    long createSuperAdmin(String username, String rawPassword);
}
