package com.example.aiskill.model;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

/**
 * 统一API响应结果封装类。
 * <p>
 * 用于包装所有API接口的返回数据，包含状态码、消息和业务数据。
 * 提供了快速构建成功和失败响应的静态工厂方法。
 * </p>
 *
 * @param <T> 业务数据的泛型类型
 * @author example
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ApiResult<T> {

    /** 状态码，200表示成功，其他值表示不同类型的错误 */
    private int code;

    /** 响应消息，用于描述请求处理结果 */
    private String message;

    /** 业务数据 */
    private T data;

    /**
     * 构建一个成功的API响应结果（使用默认消息"success"）。
     *
     * @param data 业务数据
     * @param <T>  业务数据的泛型类型
     * @return 包含成功状态和数据的 ApiResult 实例
     */
    public static <T> ApiResult<T> success(T data) {
        return new ApiResult<>(200, "success", data);
    }

    /**
     * 构建一个成功的API响应结果（使用自定义消息）。
     *
     * @param message 自定义成功消息
     * @param data    业务数据
     * @param <T>     业务数据的泛型类型
     * @return 包含成功状态、自定义消息和数据的 ApiResult 实例
     */
    public static <T> ApiResult<T> success(String message, T data) {
        return new ApiResult<>(200, message, data);
    }

    /**
     * 构建一个失败的API响应结果（使用指定错误码和消息，数据为null）。
     *
     * @param code    错误码
     * @param message 错误消息
     * @param <T>     业务数据的泛型类型
     * @return 包含错误状态和消息的 ApiResult 实例
     */
    public static <T> ApiResult<T> error(int code, String message) {
        return new ApiResult<>(code, message, null);
    }

    /**
     * 构建一个失败的API响应结果（使用默认错误码500和指定消息，数据为null）。
     *
     * @param message 错误消息
     * @param <T>     业务数据的泛型类型
     * @return 包含500错误码和消息的 ApiResult 实例
     */
    public static <T> ApiResult<T> error(String message) {
        return new ApiResult<>(500, message, null);
    }
}
