package com.example.aiskill.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 执行会话实体类，表示一次完整的AI技能执行会话。
 * <p>
 * 一个会话可以包含多个执行轮次（ExecutionRound），支持多轮对话和结果链式传递。
 * 会话记录了所有轮次信息以及当前所处轮次，并提供轮次导航的辅助方法。
 * </p>
 *
 * @author example
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionSession {

    /** 会话唯一标识符 */
    private String sessionId;

    /** 执行轮次列表，按执行顺序排列 */
    private List<ExecutionRound> rounds;

    /** 当前轮次编号 */
    private int currentRound;

    /** 会话创建时间 */
    private LocalDateTime createdAt;

    /**
     * 检查用户是否可以回退到上一个轮次。
     * <p>
     * 当当前轮次编号大于1时（即至少已完成一个轮次），允许回退。
     * </p>
     *
     * @return 如果可以回退返回 true，否则返回 false
     */
    public boolean canGoBack() {
        return currentRound > 1;
    }

    /**
     * 检查用户是否可以继续执行下一轮次。
     * <p>
     * 当存在至少一个已完成且产生了输出内容的轮次时，允许继续。
     * </p>
     *
     * @return 如果可以继续返回 true，否则返回 false
     */
    public boolean canContinue() {
        if (rounds == null || rounds.isEmpty()) {
            return false;
        }
        ExecutionRound latest = rounds.get(rounds.size() - 1);
        return latest.getOutput() != null && !latest.getOutput().isEmpty();
    }

    /**
     * 获取当前轮次的输出内容（用于链式传递到下一轮次作为输入）。
     *
     * @return 当前轮次的输出文本，如果不存在则返回 null
     */
    public String getCurrentOutput() {
        if (rounds == null || rounds.isEmpty()) {
            return null;
        }
        // 在轮次列表中查找与当前轮次编号匹配的轮次并返回其输出
        for (ExecutionRound r : rounds) {
            if (r.getRound() == currentRound) {
                return r.getOutput();
            }
        }
        return null;
    }

    /**
     * 获取当前轮次的输入内容。
     *
     * @return 当前轮次的输入文本，如果不存在则返回 null
     */
    public String getCurrentInput() {
        if (rounds == null || rounds.isEmpty()) {
            return null;
        }
        for (ExecutionRound r : rounds) {
            if (r.getRound() == currentRound) {
                return r.getInput();
            }
        }
        return null;
    }
}
