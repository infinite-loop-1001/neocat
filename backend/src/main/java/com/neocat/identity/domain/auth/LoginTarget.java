package com.neocat.identity.domain.auth;

/**
 * 登录后的落点决策（PRD 01 §4.1）。
 *
 * @param targetService 最近访问服务名；为 null 表示进入服务列表
 */
@org.springframework.modulith.NamedInterface("identity")
@lombok.Getter
@lombok.EqualsAndHashCode
@lombok.ToString
public class LoginTarget {
    private final String targetService;

    public LoginTarget(String targetService) {
        this.targetService = targetService;
    }

    public static final LoginTarget SERVICE_LIST = new LoginTarget(null);

    public boolean isServiceList() {
        return targetService == null;
    }
}
