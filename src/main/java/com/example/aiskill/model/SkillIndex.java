package com.example.aiskill.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * 轻量级技能索引，仅包含技能的基本信息（name + description）。
 *
 * <p>用于第一轮路由判断，作为常驻上下文传入大模型，
 * 不加载完整技能文档（promptTemplate、files 等），控制 token 消耗。
 *
 * <p>设计思路：第一轮只做路由选择，仅传入技能的名称和简介，
 * token 开销小，快速判断要调用哪个 skill。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SkillIndex {

    /** 技能 ID */
    private String id;

    /** 技能名称 */
    private String name;

    /** 技能描述 */
    private String description;

    /** 标签（逗号分隔的字符串） */
    private String tags;
}
