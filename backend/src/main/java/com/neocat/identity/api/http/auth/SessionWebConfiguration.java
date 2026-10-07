package com.neocat.identity.api.http.auth;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class SessionWebConfiguration implements WebMvcConfigurer {
    private final SessionInterceptor interceptor;

    public SessionWebConfiguration(SessionInterceptor interceptor) {
        this.interceptor = interceptor;
    }
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns("/api/**");
    }
}
