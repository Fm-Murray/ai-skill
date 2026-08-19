package com.example.aiskill.model.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * 继续执行请求DTO（数据传输对象）。
 * <p>
 * 用于封装在多轮执行中继续下一轮次时提交的参数。
 * 可以指定新的技能和自定义输入，若输入为空则自动使用上一轮次的输出作为输入。
 * </p>
 *
 * @author example
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ContinueRequest {

    /** 要继续执行的技能ID */
    private String skillId;

    /** 可选的自定义输入文本；如果为 null，则使用上一轮次的输出作为输入 */
    private String input;
}
