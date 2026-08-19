package com.example.aiskill.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;

/**
 * 执行轮次实体类，表示会话中单次AI技能执行的详细信息。
 * <p>
 * 记录了轮次编号、所用技能信息、输入文本、AI输出文本、是否基于上一轮输出、
 * 执行状态以及可能的错误信息等。
 * </p>
 *
 * @author example
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionRound {

    /** 轮次编号，从1开始递增 */
    private int round;

    /** 执行所用的技能ID */
    private String skillId;

    /** 执行所用的技能名称 */
    private String skillName;

    /** 执行所用的技能描述 */
    private String skillDescription;

    /** 本轮次的输入文本 */
    private String input;

    /** 本轮次AI返回的输出文本 */
    private String output;

    /** 标记本轮次是否使用了上一轮次的输出作为输入 */
    private boolean continuedFromPrevious;

    /** 本轮次执行的时间戳 */
    private LocalDateTime timestamp;

    /** 执行状态：running（执行中）| success（成功）| error（失败） */
    private String status;

    /** 当状态为 error 时的错误信息 */
    private String errorMessage;
}
