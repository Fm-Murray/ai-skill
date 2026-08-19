package com.example.aiskill.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;

/**
 * AI服务配置类。
 * <p>
 * 从配置文件中读取以 "ai" 为前缀的配置项，包括AI服务的基础URL、API密钥、
 * 默认模型名称、采样温度、最大Token数、请求超时时间以及是否启用思考模式等。
 * 同时提供 RestTemplate Bean 用于发起HTTP请求。
 * </p>
 *
 * @author example
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "ai")
public class AiConfig {

    /** AI服务的基础URL地址 */
    private String baseUrl;

    /** AI服务的API密钥 */
    private String apiKey;

    /** 默认使用的AI模型名称 */
    private String model;

    /** 默认采样温度，控制生成结果的随机性 */
    private Double temperature;

    /** 默认最大生成Token数量 */
    private Integer maxTokens;

    /** 请求超时时间（毫秒） */
    private Integer timeout;

    /** 是否启用思考模式（深度推理模式） */
    private Boolean think;

    /**
     * 创建并配置 RestTemplate Bean。
     * <p>
     * 设置连接超时和读取超时时间，如果配置文件中未指定超时时间，
     * 则连接超时默认为30秒，读取超时默认为60秒。
     * </p>
     * <p>
     * 同时配置 StringHttpMessageConverter 使用 UTF-8 编码，
     * 解决 AI API 响应中中文乱码的问题。
     * RestTemplate 默认的 StringHttpMessageConverter 使用 ISO-8859-1 编码，
     * 当 AI API 响应的 Content-Type 头中未显式指定 charset 时，
     * 会导致中文内容被错误解码为 ISO-8859-1，出现乱码。
     * </p>
     *
     * @return 配置好超时参数和 UTF-8 编码的 RestTemplate 实例
     */
    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout != null ? timeout : 30000);
        factory.setReadTimeout(timeout != null ? timeout : 60000);

        RestTemplate restTemplate = new RestTemplate(factory);

        // 将 StringHttpMessageConverter 的默认编码设置为 UTF-8，
        // 确保 AI API 返回的中文内容能被正确解码
        restTemplate.getMessageConverters().stream()
                .filter(converter -> converter instanceof StringHttpMessageConverter)
                .map(converter -> (StringHttpMessageConverter) converter)
                .forEach(converter -> converter.setDefaultCharset(StandardCharsets.UTF_8));

        return restTemplate;
    }
}
