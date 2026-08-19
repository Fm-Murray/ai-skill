package com.example.aiskill.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.util.List;

/**
 * 第一轮路由判断的多候选结果。
 *
 * <p>大模型在第一轮调用中分析用户意图，返回多个匹配的候选技能（按匹配分数从高到低排列），
 * 同时包含大模型的思考过程（reasoning_content），供前端展示 AI 推理过程。
 * </p>
 *
 * <p>用户可以从候选列表中选择一个技能，系统再进入第二轮内容生成。</p>
 *
 * @see RouteResult 单个候选技能的信息
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RouteCandidates {

    /** 用户的原始输入内容 */
    private String userInput;

    /** 候选技能列表（按匹配分数从高到低排列） */
    private List<RouteResult> candidates;

    /** 大模型的思考过程（reasoning_content），如果模型支持则非空 */
    private String thinkingProcess;

    /** 候选技能数量 */
    public int size() {
        return candidates != null ? candidates.size() : 0;
    }

    /** 是否只有一个候选（无需用户选择，可直接执行） */
    public boolean isSingleCandidate() {
        return size() == 1;
    }
}
