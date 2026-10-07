package com.neocat.common.http.context;

import com.neocat.common.error.exception.AuthenticationException;
import com.neocat.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.springframework.modulith.NamedInterface;

import java.util.Objects;

/** 身份模块完成认证后写入的最小 HTTP 请求上下文，不暴露身份领域对象。 */
@NamedInterface("http")
@Getter
@EqualsAndHashCode
@ToString
public class RequestActor {
    private final long id;

    private final String role;

    public RequestActor(long id, String role) {
        this.id = id;
        this.role = role;
    }
    public static final String ATTRIBUTE = "neocat.actor";

    public static RequestActor current(HttpServletRequest request) {
        Object actor = request.getAttribute(ATTRIBUTE);
        if (actor instanceof RequestActor value) {
            return value;
        }
        throw new AuthenticationException(ErrorCode.UNAUTHENTICATED);
    }
    public boolean isAdmin() {
        return Objects.equals("ADMIN", role) || Objects.equals("SUPER_ADMIN", role);
    }
}
