package com.neocat.platform.domain.init;

import java.time.ZoneId;

/**
 * 平台初始化的输入（PRD 01 §2.1）。
 *
 * @param timezone       平台时区
 * @param adminUsername  第一个超级管理员用户名
 * @param adminPassword  第一个超级管理员初始口令
 */
@org.springframework.modulith.NamedInterface("platform")
@lombok.Getter
@lombok.EqualsAndHashCode
@lombok.ToString
public class InitRequest {
    private final ZoneId timezone;

    private final String adminUsername;

    private final String adminPassword;

    public InitRequest(ZoneId timezone, String adminUsername, String adminPassword) {
        this.timezone = timezone;
        this.adminUsername = adminUsername;
        this.adminPassword = adminPassword;
    }

}
