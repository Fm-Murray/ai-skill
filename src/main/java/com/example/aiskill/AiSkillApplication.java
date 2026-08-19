package com.example.aiskill;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * AI技能管理平台 Spring Boot 应用程序入口类。
 * <p>
 * 使用 @SpringBootApplication 注解标记，启用自动配置、组件扫描和Spring Boot配置。
 * 包含 main 方法作为应用程序的启动入口。
 * </p>
 *
 * @author example
 */
@SpringBootApplication
public class AiSkillApplication {

    /**
     * 应用程序主入口方法。
     * <p>
     * 通过 SpringApplication.run 启动Spring Boot应用，完成自动配置和内嵌服务器初始化。
     * </p>
     *
     * @param args 启动参数，可传递给Spring Boot应用
     */
    public static void main(String[] args) {
        SpringApplication.run(AiSkillApplication.class, args);
    }
}
