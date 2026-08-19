package com.example.aiskill.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 跨域资源共享（CORS）配置类。
 * <p>
 * 实现 WebMvcConfigurer 接口，配置全局CORS策略，允许前端跨域访问所有 /api/** 路径的接口。
 * 支持所有来源、常用的HTTP方法和请求头，并允许携带凭证（如Cookie）。
 * </p>
 *
 * @author example
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    /**
     * 配置CORS跨域映射规则。
     * <p>
     * 对所有 /api/** 路径的请求进行跨域配置：
     * <ul>
     *   <li>允许所有来源模式（allowedOriginPatterns）</li>
     *   <li>允许 GET、POST、PUT、DELETE、OPTIONS 方法</li>
     *   <li>允许所有请求头</li>
     *   <li>允许携带凭证</li>
     *   <li>预检请求缓存时间为3600秒（1小时）</li>
     * </ul>
     * </p>
     *
     * @param registry CORS注册器，用于注册跨域映射规则
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
