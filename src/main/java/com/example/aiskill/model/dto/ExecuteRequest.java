package com.example.aiskill.model.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * 执行技能请求DTO（数据传输对象）。
 * <p>
 * 用于封装前端发起技能执行请求时提交的参数，包括要执行的技能ID和用户输入文本。
 * </p>
 *
 * @author example
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExecuteRequest {

    /** 要执行的技能ID */
    private String skillId;

    /** 用户输入的文本内容 */
    private String input;
}
