package com.example.aiskill.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * 第一轮路由判断的结果。
 *
 * <p>大模型在第一轮调用中只输出 JSON，完成意图识别，选出需要执行的 skill。
 * 此类封装了路由判断的输出结果，包括选中的技能 ID、技能名称和选择理由。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RouteResult {

    /** 选中的技能 ID */
    private String skillId;

    /** 选中的技能名称 */
    private String skillName;

    /** 选择理由（大模型给出的匹配原因） */
    private String reason;

    /** 匹配分数（0-100，由大模型评估给出，用于多候选排序） */
    private Double score;
}
