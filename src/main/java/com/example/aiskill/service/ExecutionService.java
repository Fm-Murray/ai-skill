package com.example.aiskill.service;

import com.example.aiskill.model.ExecutionRound;
import com.example.aiskill.model.ExecutionSession;
import com.example.aiskill.model.RouteCandidates;
import com.example.aiskill.model.RouteResult;
import com.example.aiskill.model.Skill;
import com.example.aiskill.model.SkillIndex;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 技能执行服务类
 * <p>
 * 该服务负责管理技能执行的完整生命周期，包括：
 * <ul>
 *   <li>创建执行会话（Session）并执行首个技能</li>
 *   <li>在会话中继续执行下一个技能（支持链式调用）</li>
 *   <li>回退到上一轮执行结果</li>
 *   <li>重新执行当前轮次</li>
 *   <li>查询会话状态</li>
 * </ul>
 * </p>
 * <p>
 * 会话数据存储在内存中的 ConcurrentHashMap 中，
 * 每个会话包含多轮执行记录（ExecutionRound），支持分支和回溯操作。
 * 会话间的数据流可以串联：上一轮的输出可作为下一轮的输入。
 * </p>
 */
@Slf4j
@Service
public class ExecutionService {

    /** 技能服务，用于获取技能定义和读取素材/脚本文件 */
    private final SkillService skillService;
    /** AI 服务，用于调用大语言模型执行技能 */
    private final AiService aiService;

    /** 内存会话存储：sessionId -> ExecutionSession，使用 ConcurrentHashMap 保证线程安全 */
    private final ConcurrentHashMap<String, ExecutionSession> sessions = new ConcurrentHashMap<>();

    /**
     * 构造函数，通过 Spring 依赖注入初始化技能服务和 AI 服务。
     *
     * @param skillService 技能服务
     * @param aiService    AI 服务
     */
    public ExecutionService(SkillService skillService, AiService aiService) {
        this.skillService = skillService;
        this.aiService = aiService;
    }

    /**
     * 启动新的执行会话，并执行第一个技能。
     * <p>
     * 创建一个新的会话，生成唯一的会话 ID，
     * 然后立即执行指定的技能，将结果作为第一轮执行记录存入会话。
     * </p>
     *
     * @param skillId 要执行的技能 ID
     * @param input   用户输入内容
     * @return 包含第一轮执行结果的新会话
     * @throws IllegalArgumentException 当指定的技能 ID 不存在时抛出
     */
    public ExecutionSession startSession(String skillId, String input) {
        // 根据技能 ID 获取技能定义
        Skill skill = skillService.getSkill(skillId);
        if (skill == null) {
            throw new IllegalArgumentException("Skill not found: " + skillId);
        }

        // 生成唯一的会话 ID（截取 UUID 前 8 位作为简短标识）
        String sessionId = "session_" + UUID.randomUUID().toString().substring(0, 8);
        // 构建新的执行会话
        ExecutionSession session = ExecutionSession.builder()
                .sessionId(sessionId)
                .rounds(new ArrayList<>())
                .currentRound(0)
                .createdAt(LocalDateTime.now())
                .build();

        // 执行第一轮技能
        ExecutionRound round = executeSkillInternal(skill, input, false);
        session.getRounds().add(round);
        session.setCurrentRound(1);

        // 将会话存入内存存储
        sessions.put(sessionId, session);
        log.info("Started session {} with skill '{}', round 1 completed", sessionId, skill.getName());

        return session;
    }

    /**
     * 继续执行下一轮技能。
     * <p>
     * 在当前会话中追加一轮新的技能执行。如果未提供自定义输入，
     * 则使用当前轮次的输出作为下一轮的输入（链式调用）。
     * 如果当前位置之后已有后续轮次记录，会被截断（实现分支效果）。
     * </p>
     *
     * @param sessionId 会话 ID
     * @param skillId   下一个要执行的技能 ID
     * @param input     可选的自定义输入；为 null 时使用当前轮次的输出
     * @return 更新后的会话
     * @throws IllegalArgumentException  当技能不存在时抛出
     * @throws IllegalStateException     当当前轮次无输出可用作输入时抛出
     */
    public ExecutionSession continueToNext(String sessionId, String skillId, String input) {
        // 获取会话
        ExecutionSession session = getSessionInternal(sessionId);

        // 获取技能定义
        Skill skill = skillService.getSkill(skillId);
        if (skill == null) {
            throw new IllegalArgumentException("Skill not found: " + skillId);
        }

        // 确定输入：使用提供的输入或当前轮次的输出
        String actualInput = input;
        boolean continuedFromPrev = false;
        if (actualInput == null || actualInput.trim().isEmpty()) {
            // 未提供输入，使用当前轮次的输出作为输入
            actualInput = session.getCurrentOutput();
            continuedFromPrev = true;
            if (actualInput == null || actualInput.trim().isEmpty()) {
                throw new IllegalStateException("No output from current round to use as input");
            }
        }

        // 截断当前轮次之后的记录（创建新分支）
        int currentRound = session.getCurrentRound();
        List<ExecutionRound> keptRounds = session.getRounds().stream()
                .filter(r -> r.getRound() <= currentRound)
                .collect(Collectors.toList());
        session.setRounds(new ArrayList<>(keptRounds));

        // 执行新一轮
        int newRoundNum = currentRound + 1;
        ExecutionRound round = executeSkillInternal(skill, actualInput, continuedFromPrev);
        round.setRound(newRoundNum);
        session.getRounds().add(round);
        session.setCurrentRound(newRoundNum);

        log.info("Session {} continued to round {} with skill '{}'", sessionId, newRoundNum, skill.getName());

        return session;
    }

    /**
     * 回退到上一轮执行结果。
     * <p>
     * 移除当前轮次的执行记录，并将当前轮次指针回退到上一轮。
     * 如果当前已经是第一轮，则无法回退。
     * </p>
     *
     * @param sessionId 会话 ID
     * @return 更新后的会话
     * @throws IllegalStateException 当已在第一轮无法回退时抛出
     */
    public ExecutionSession goBack(String sessionId) {
        ExecutionSession session = getSessionInternal(sessionId);

        // 检查是否可以回退（当前不能是第一轮）
        if (!session.canGoBack()) {
            throw new IllegalStateException("Cannot go back: already at the first round");
        }

        // 移除当前轮次及之后的记录，指针回退到上一轮
        int currentRound = session.getCurrentRound();
        session.getRounds().removeIf(r -> r.getRound() >= currentRound);
        session.setCurrentRound(currentRound - 1);

        log.info("Session {} went back to round {}", sessionId, session.getCurrentRound());

        return session;
    }

    /**
     * 重新执行当前轮次（通常在回退后使用）。
     * <p>
     * 使用当前轮次原有的技能和输入（或新的输入）重新调用 AI 执行，
     * 用新的执行结果替换原有记录。
     * </p>
     *
     * @param sessionId 会话 ID
     * @param input     可选的新输入；为 null 时使用当前轮次的原始输入
     * @return 更新后的会话
     * @throws IllegalStateException    当当前轮次记录不存在时抛出
     * @throws IllegalArgumentException 当当前轮次对应的技能不存在时抛出
     */
    public ExecutionSession reExecute(String sessionId, String input) {
        ExecutionSession session = getSessionInternal(sessionId);

        // 获取当前轮次的执行记录
        int currentRound = session.getCurrentRound();
        ExecutionRound existingRound = session.getRounds().stream()
                .filter(r -> r.getRound() == currentRound)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Current round not found"));

        // 获取当前轮次使用的技能
        Skill skill = skillService.getSkill(existingRound.getSkillId());
        if (skill == null) {
            throw new IllegalArgumentException("Skill not found: " + existingRound.getSkillId());
        }

        // 确定输入：使用新输入或当前轮次的原始输入
        String actualInput = input != null ? input : existingRound.getInput();

        // 重新执行技能
        ExecutionRound newRound = executeSkillInternal(skill, actualInput, existingRound.isContinuedFromPrevious());
        newRound.setRound(currentRound);

        // 用新的执行结果替换原有记录
        session.getRounds().removeIf(r -> r.getRound() == currentRound);
        session.getRounds().add(newRound);
        // 按轮次编号重新排序
        session.getRounds().sort((a, b) -> Integer.compare(a.getRound(), b.getRound()));

        log.info("Session {} re-executed round {}", sessionId, currentRound);

        return session;
    }

    /**
     * 根据 ID 获取执行会话。
     *
     * @param sessionId 会话 ID
     * @return 对应的执行会话
     * @throws IllegalArgumentException 当会话不存在时抛出
     */
    public ExecutionSession getSession(String sessionId) {
        return getSessionInternal(sessionId);
    }

    /**
     * AI 自动选择技能并执行（两轮大模型调用架构）。
     *
     * <p>完整流转：</p>
     * <pre>
     * skills仓库(共享存储)
     * ├─→加载skills索引 → 第一轮路由判断 → 装配上下文 → 第二轮内容生成 → 后处理与交付
     * └───────────────────────────────────▶
     * </pre>
     *
     * <p>设计思路：两轮大模型调用分离职责</p>
     * <ol>
     *     <li>第一轮只做路由选择，仅传入技能的名称和简介，token 开销小，快速判断要调用哪个 skill</li>
     *     <li>第二轮才加载完整技能文档，执行实际业务生成</li>
     *     <li>中间层承担存储、索引、上下文组装、校验脚本执行，大模型只负责推理</li>
     *     <li>输出强结构化：全程以 JSON 流转，后处理再做文档渲染</li>
     * </ol>
     *
     * @param input 用户的输入内容（描述或文件内容拼接）
     * @return 两轮执行结果，包含路由信息和执行会话；如果路由失败则返回 null
     */
    public TwoRoundResult autoExecuteTwoRound(String input) {
        log.info("开始两轮执行：输入长度={}", input != null ? input.length() : 0);

        // ==================== 步骤1：加载 skills 索引 ====================
        // 仅加载 skill 的 name + description，作为常驻上下文，不加载完整技能文档，控制 token 消耗
        List<SkillIndex> skillIndexes = skillService.loadSkillsIndex();
        if (skillIndexes.isEmpty()) {
            log.warn("技能索引为空，无法执行两轮流程");
            return null;
        }
        log.info("步骤1完成：加载了 {} 个技能索引", skillIndexes.size());

        // ==================== 步骤2：第一轮 - 路由判断（标准 chat 调用） ====================
        // 调用大模型，只输出 JSON，不生成业务内容，完成意图识别，选出需要执行的 skill
        RouteResult routeResult = aiService.routeSkill(input, skillIndexes);
        if (routeResult == null || routeResult.getSkillId() == null) {
            log.warn("第一轮路由判断失败：未选中任何技能");
            return null;
        }

        // 验证选中的技能是否存在
        Skill selectedSkill = skillService.getSkill(routeResult.getSkillId());
        if (selectedSkill == null) {
            log.warn("路由判断选中的技能不存在: {}", routeResult.getSkillId());
            return null;
        }
        log.info("步骤2完成：第一轮路由判断选中技能 {} ({})", selectedSkill.getName(), selectedSkill.getId());

        // ==================== 步骤3：装配上下文（中间层自建） ====================
        // 读取上一步选中的 SKILL.md 完整正文，依据 when 规则挂载引用材料，组装完整 prompt
        String context = skillService.assembleContext(selectedSkill, input);
        log.info("步骤3完成：上下文装配完成，prompt 长度={}", context.length());

        // ==================== 步骤4：第二轮 - 内容生成（标准 chat 调用） ====================
        // 送入完整上下文给大模型，按照 skill 的业务规程输出结构化 JSON 结果
        String rawOutput;
        try {
            // 如果技能包含 argparse 脚本，使用更大的 max_tokens 和更明确的用户消息
            Integer maxTokens = selectedSkill.getMaxTokens();
            String userMessage = "请根据上述指令处理输入并返回结果。";
            if (skillService.hasArgparseScriptPublic(selectedSkill)) {
                userMessage = "请根据上述指令处理用户输入，直接输出完整的 JSON 格式结果（不要包含 Markdown 代码块标记），用于后续脚本处理。";
                if (maxTokens == null) {
                    maxTokens = 8192; // argparse 脚本通常需要更长的输出
                }
            }
            rawOutput = aiService.chat(
                    context,
                    userMessage,
                    selectedSkill.getModel(),
                    selectedSkill.getTemperature(),
                    maxTokens
            );
            log.info("步骤4完成：第二轮内容生成完成，输出长度={}", rawOutput != null ? rawOutput.length() : 0);
        } catch (Exception e) {
            log.error("第二轮内容生成失败", e);
            rawOutput = "执行失败: " + e.getMessage();
        }

        // ==================== 步骤5：后处理与交付（中间层自建） ====================
        // 做 schema 格式校验，执行脚本，渲染输出最终文档交付用户
        String processedOutput = postProcess(rawOutput, selectedSkill);
        log.info("步骤5完成：后处理完成，最终输出长度={}", processedOutput.length());

        // ==================== 创建会话和执行记录 ====================
        String sessionId = "session_" + UUID.randomUUID().toString().substring(0, 8);
        ExecutionSession session = ExecutionSession.builder()
                .sessionId(sessionId)
                .rounds(new ArrayList<>())
                .currentRound(0)
                .createdAt(LocalDateTime.now())
                .build();

        ExecutionRound round = ExecutionRound.builder()
                .round(1)
                .skillId(selectedSkill.getId())
                .skillName(selectedSkill.getName())
                .skillDescription(selectedSkill.getDescription())
                .input(input)
                .output(processedOutput)
                .continuedFromPrevious(false)
                .timestamp(LocalDateTime.now())
                .status("success")
                .build();

        session.getRounds().add(round);
        session.setCurrentRound(1);
        sessions.put(sessionId, session);

        log.info("两轮执行完成：会话={}, 技能={}", sessionId, selectedSkill.getName());

        return new TwoRoundResult(session, routeResult);
    }

    /**
     * 仅执行第一轮路由判断（不执行第二轮内容生成）。
     * <p>
     * 加载 skills 索引 → 调用大模型进行路由判断 → 返回多候选结果和思考过程。
     * 用户可以在前端查看候选技能列表和 AI 思考过程，然后选择一个技能进入第二轮。
     * </p>
     *
     * @param input 用户的输入内容（描述或文件内容拼接）
     * @return 多候选路由结果，包含候选列表和思考过程；如果路由失败则返回 null
     */
    public RouteCandidates routeOnly(String input) {
        log.info("开始第一轮路由判断（仅路由）：输入长度={}", input != null ? input.length() : 0);

        // 步骤1：加载 skills 索引
        List<SkillIndex> skillIndexes = skillService.loadSkillsIndex();
        if (skillIndexes.isEmpty()) {
            log.warn("技能索引为空，无法执行路由判断");
            return null;
        }
        log.info("加载了 {} 个技能索引", skillIndexes.size());

        // 步骤2：第一轮 - 路由判断（多候选版）
        RouteCandidates candidates = aiService.routeSkillMultiple(input, skillIndexes);
        if (candidates == null || candidates.size() == 0) {
            log.warn("路由判断未返回任何候选技能");
            return null;
        }

        log.info("路由判断完成：返回 {} 个候选技能", candidates.size());
        return candidates;
    }

    /**
     * 执行第二轮内容生成（用户已选择技能后调用）。
     * <p>
     * 装配上下文 → 第二轮内容生成 → 后处理与交付 → 创建会话。
     * 完整流转中的步骤 3-5。
     * </p>
     *
     * @param input           用户的输入内容
     * @param skillId         用户选中的技能 ID
     * @param routeReason     路由选择理由（用于前端展示）
     * @param thinkingProcess AI 思考过程（用于前端展示）
     * @return 两轮执行结果，包含路由信息和执行会话；如果失败则返回 null
     */
    public TwoRoundResult executeRouted(String input, String skillId, String routeReason, String thinkingProcess) {
        log.info("开始第二轮内容生成：技能ID={}", skillId);

        // 验证选中的技能是否存在
        Skill selectedSkill = skillService.getSkill(skillId);
        if (selectedSkill == null) {
            log.warn("选中的技能不存在: {}", skillId);
            return null;
        }
        log.info("选中技能: {} ({})", selectedSkill.getName(), selectedSkill.getId());

        // 步骤3：装配上下文
        String context = skillService.assembleContext(selectedSkill, input);
        log.info("上下文装配完成，prompt 长度={}", context.length());

        // 步骤4：第二轮 - 内容生成
        String rawOutput;
        try {
            // 如果技能包含 argparse 脚本，使用更大的 max_tokens 和更明确的用户消息
            Integer maxTokens = selectedSkill.getMaxTokens();
            String userMessage = "请根据上述指令处理输入并返回结果。";
            if (skillService.hasArgparseScriptPublic(selectedSkill)) {
                userMessage = "请根据上述指令处理用户输入，直接输出完整的 JSON 格式结果（不要包含 Markdown 代码块标记），用于后续脚本处理。";
                if (maxTokens == null) {
                    maxTokens = 8192;
                }
            }
            rawOutput = aiService.chat(
                    context,
                    userMessage,
                    selectedSkill.getModel(),
                    selectedSkill.getTemperature(),
                    maxTokens
            );
            log.info("第二轮内容生成完成，输出长度={}", rawOutput != null ? rawOutput.length() : 0);
        } catch (Exception e) {
            log.error("第二轮内容生成失败", e);
            rawOutput = "执行失败: " + e.getMessage();
        }

        // 步骤5：后处理与交付
        String processedOutput = postProcess(rawOutput, selectedSkill);
        log.info("后处理完成，最终输出长度={}", processedOutput.length());

        // 创建会话和执行记录
        String sessionId = "session_" + UUID.randomUUID().toString().substring(0, 8);
        ExecutionSession session = ExecutionSession.builder()
                .sessionId(sessionId)
                .rounds(new ArrayList<>())
                .currentRound(0)
                .createdAt(LocalDateTime.now())
                .build();

        ExecutionRound round = ExecutionRound.builder()
                .round(1)
                .skillId(selectedSkill.getId())
                .skillName(selectedSkill.getName())
                .skillDescription(selectedSkill.getDescription())
                .input(input)
                .output(processedOutput)
                .continuedFromPrevious(false)
                .timestamp(LocalDateTime.now())
                .status("success")
                .build();

        session.getRounds().add(round);
        session.setCurrentRound(1);
        sessions.put(sessionId, session);

        // 构建路由结果
        RouteResult routeResult = new RouteResult(selectedSkill.getId(), selectedSkill.getName(),
                routeReason != null ? routeReason : "", null);

        log.info("第二轮执行完成：会话={}, 技能={}", sessionId, selectedSkill.getName());
        return new TwoRoundResult(session, routeResult);
    }

    /**
     * 后处理：对大模型输出做 schema 格式校验，执行脚本，渲染输出最终文档。
     *
     * <p>处理步骤：</p>
     * <ol>
     *     <li>Schema 格式校验：尝试解析 AI 输出为 JSON，验证格式有效性</li>
     *     <li>脚本执行：如果技能包含脚本文件，执行脚本对输出进行后处理</li>
     *     <li>如果任何步骤失败，返回原始输出（不阻断流程）</li>
     * </ol>
     *
     * @param rawOutput 大模型的原始输出
     * @param skill     执行的技能
     * @return 后处理后的输出（如果处理失败则返回原始输出）
     */
    private String postProcess(String rawOutput, Skill skill) {
        if (rawOutput == null || rawOutput.isEmpty()) {
            return rawOutput;
        }

        String result = rawOutput;

        // ==================== 1. JSON 输出清理（argparse 脚本专用） ====================
        // 如果技能包含 argparse 脚本，AI 输出必须是纯 JSON，需要清理 Markdown 代码块等干扰内容
        if (skillService.hasArgparseScriptPublic(skill)) {
            String cleaned = cleanJsonOutput(result);
            if (cleaned != null && !cleaned.isEmpty()) {
                log.info("JSON 输出清理完成：原始长度={}, 清理后长度={}", result.length(), cleaned.length());
                result = cleaned;
            }
        }

        // ==================== 2. Schema 格式校验 ====================
        try {
            ObjectMapper mapper = new ObjectMapper();
            // 尝试解析为 JSON，验证格式有效性
            // 提取可能的 JSON 部分
            String jsonStr = result.trim();
            if (jsonStr.startsWith("{") || jsonStr.startsWith("[")) {
                mapper.readTree(jsonStr);
                log.debug("Schema 校验通过：输出是有效的 JSON");
            } else if (jsonStr.contains("```json")) {
                // 从代码块中提取 JSON
                int start = jsonStr.indexOf("```json") + 7;
                int end = jsonStr.indexOf("```", start);
                if (end > start) {
                    String extracted = jsonStr.substring(start, end).trim();
                    mapper.readTree(extracted);
                    log.debug("Schema 校验通过：从代码块中提取的 JSON 有效");
                }
            }
        } catch (Exception e) {
            log.debug("Schema 校验：输出不是 JSON 格式（可能是纯文本/Markdown），跳过校验");
        }

        // ==================== 3. 脚本执行 ====================
        // 如果技能包含脚本文件，尝试执行脚本对输出进行后处理
        if (skill.getFiles() != null) {
            for (Skill.SkillFile file : skill.getFiles()) {
                if (!"script".equals(file.getType())) {
                    continue;
                }

                String filename = file.getName();
                String lower = filename.toLowerCase();

                // 只执行 .py 和 .sh 脚本
                if (!lower.endsWith(".py") && !lower.endsWith(".sh")) {
                    continue;
                }

                log.info("尝试执行后处理脚本: {}", filename);
                String scriptOutput = executeScript(skill, file, result);
                if (scriptOutput != null && !scriptOutput.isEmpty()) {
                    // 检查脚本输出是否包含错误信息
                    boolean hasError = scriptOutput.contains("--- 脚本错误输出 ---");
                    if (hasError) {
                        log.warn("后处理脚本 {} 执行完成（含错误信息），输出长度={}", filename, scriptOutput.length());
                    } else {
                        log.info("后处理脚本 {} 执行成功，输出长度={}", filename, scriptOutput.length());
                    }
                    result = scriptOutput;
                }
            }
        }

        return result;
    }

    /**
     * 清理 AI 输出中的非 JSON 内容，提取纯 JSON 文本。
     * <p>
     * 处理步骤：
     * <ol>
     *   <li>去除首尾空白</li>
     *   <li>如果包含 Markdown 代码块（```json ... ``` 或 ``` ... ```），提取代码块内容</li>
     *   <li>如果包含非 JSON 前缀文本，找到第一个 { 或 [ 并截取到对应的最后一个 } 或 ]</li>
     *   <li>去除 BOM 头（如果有）</li>
     * </ol>
     * </p>
     *
     * @param output AI 的原始输出
     * @return 清理后的纯 JSON 字符串；如果无法提取有效 JSON 则返回原始输出
     */
    private String cleanJsonOutput(String output) {
        if (output == null || output.isEmpty()) {
            return output;
        }

        String cleaned = output.trim();

        // 去除 BOM 头
        if (cleaned.startsWith("\uFEFF")) {
            cleaned = cleaned.substring(1);
        }

        // 情况1：Markdown 代码块 ```json ... ```
        if (cleaned.contains("```json")) {
            int start = cleaned.indexOf("```json") + 7;
            int end = cleaned.indexOf("```", start);
            if (end > start) {
                cleaned = cleaned.substring(start, end).trim();
                log.debug("从 ```json 代码块中提取 JSON，长度={}", cleaned.length());
                return cleaned;
            }
        }

        // 情况2：Markdown 代码块 ``` ... ```（没有 json 标记）
        if (cleaned.startsWith("```") && cleaned.endsWith("```")) {
            // 去除首尾的 ```
            String inner = cleaned.substring(3);
            if (inner.endsWith("```")) {
                inner = inner.substring(0, inner.length() - 3);
            }
            // 去除可能的语言标识（如 json、python 等）
            int firstNewline = inner.indexOf('\n');
            if (firstNewline > 0 && firstNewline < 20) {
                String possibleLang = inner.substring(0, firstNewline).trim();
                if (possibleLang.matches("[a-zA-Z]+")) {
                    inner = inner.substring(firstNewline + 1);
                }
            }
            cleaned = inner.trim();
            log.debug("从 ``` 代码块中提取 JSON，长度={}", cleaned.length());
            return cleaned;
        }

        // 情况3：输出前后有非 JSON 文本，提取第一个 { 到最后一个 }
        if (!cleaned.startsWith("{") && !cleaned.startsWith("[")) {
            int firstBrace = cleaned.indexOf('{');
            int firstBracket = cleaned.indexOf('[');
            int startPos = -1;
            if (firstBrace >= 0 && firstBracket >= 0) {
                startPos = Math.min(firstBrace, firstBracket);
            } else if (firstBrace >= 0) {
                startPos = firstBrace;
            } else if (firstBracket >= 0) {
                startPos = firstBracket;
            }

            if (startPos >= 0) {
                char openChar = cleaned.charAt(startPos);
                char closeChar = (openChar == '{') ? '}' : ']';
                int lastClose = cleaned.lastIndexOf(closeChar);
                if (lastClose > startPos) {
                    cleaned = cleaned.substring(startPos, lastClose + 1).trim();
                    log.debug("从非 JSON 前缀中提取 JSON，长度={}", cleaned.length());
                    return cleaned;
                }
            }
        }

        // 情况4：已经是纯 JSON，直接返回
        return cleaned;
    }

    /**
     * 检测字节数组是否为二进制内容。
     * <p>
     * 通过检查是否包含 NULL 字节以及文件头魔数来判断。
     * 文本文件通常不包含 NULL 字节。
     * </p>
     *
     * @param bytes 文件内容字节数组
     * @return 如果是二进制内容返回 true，否则返回 false
     */
    private boolean isBinaryContent(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return false;
        }

        // 检查常见二进制文件魔数
        // ZIP/PPTX/DOCX/XLSX: PK\x03\x04
        if (bytes.length >= 4 && bytes[0] == 'P' && bytes[1] == 'K'
                && bytes[2] == 0x03 && bytes[3] == 0x04) {
            return true;
        }
        // PDF: %PDF
        if (bytes.length >= 4 && bytes[0] == '%' && bytes[1] == 'P'
                && bytes[2] == 'D' && bytes[3] == 'F') {
            return true;
        }
        // PNG: \x89PNG
        if (bytes.length >= 4 && bytes[0] == (byte) 0x89 && bytes[1] == 'P'
                && bytes[2] == 'N' && bytes[3] == 'G') {
            return true;
        }
        // JPEG: \xFF\xD8\xFF
        if (bytes.length >= 3 && bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xD8
                && bytes[2] == (byte) 0xFF) {
            return true;
        }
        // GIF: GIF8
        if (bytes.length >= 4 && bytes[0] == 'G' && bytes[1] == 'I'
                && bytes[2] == 'F' && bytes[3] == '8') {
            return true;
        }

        // 检查前 1024 字节是否包含 NULL 字节（文本文件通常不包含 NULL）
        int checkLen = Math.min(bytes.length, 1024);
        for (int i = 0; i < checkLen; i++) {
            if (bytes[i] == 0) {
                return true;
            }
        }

        return false;
    }

    /**
     * 根据文件内容和脚本名检测文件扩展名。
     *
     * @param bytes       文件内容字节数组
     * @param scriptName  脚本文件名（用于推断输出类型）
     * @return 文件扩展名（不含点号，如 "pptx"）
     */
    private String detectFileExtension(byte[] bytes, String scriptName) {
        // 通过魔数检测
        if (bytes.length >= 4 && bytes[0] == 'P' && bytes[1] == 'K'
                && bytes[2] == 0x03 && bytes[3] == 0x04) {
            // ZIP 格式：根据脚本名推断具体类型
            if (scriptName != null) {
                String lower = scriptName.toLowerCase();
                if (lower.contains("ppt") || lower.contains("slide") || lower.contains("presentation")) {
                    return "pptx";
                }
                if (lower.contains("doc") || lower.contains("word")) {
                    return "docx";
                }
                if (lower.contains("xls") || lower.contains("excel") || lower.contains("sheet")) {
                    return "xlsx";
                }
            }
            return "zip";
        }
        if (bytes.length >= 4 && bytes[0] == '%' && bytes[1] == 'P'
                && bytes[2] == 'D' && bytes[3] == 'F') {
            return "pdf";
        }
        if (bytes.length >= 4 && bytes[0] == (byte) 0x89 && bytes[1] == 'P'
                && bytes[2] == 'N' && bytes[3] == 'G') {
            return "png";
        }
        if (bytes.length >= 3 && bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xD8
                && bytes[2] == (byte) 0xFF) {
            return "jpg";
        }
        if (bytes.length >= 4 && bytes[0] == 'G' && bytes[1] == 'I'
                && bytes[2] == 'F' && bytes[3] == '8') {
            return "gif";
        }

        // 默认返回 bin
        return "bin";
    }

    /**
     * 根据文件扩展名获取 MIME 类型。
     *
     * @param ext 文件扩展名（不含点号）
     * @return 对应的 MIME 类型字符串
     */
    private String getMimeType(String ext) {
        if (ext == null) return "application/octet-stream";
        switch (ext.toLowerCase()) {
            case "pptx":
                return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "docx":
                return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xlsx":
                return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "pdf":
                return "application/pdf";
            case "png":
                return "image/png";
            case "jpg":
            case "jpeg":
                return "image/jpeg";
            case "gif":
                return "image/gif";
            case "zip":
                return "application/zip";
            default:
                return "application/octet-stream";
        }
    }

    /**
     * 执行单个脚本文件，将当前输出作为脚本的输入，返回脚本输出。
     * <p>
     * 支持两种输入方式：
     * <ul>
     *   <li>argparse 模式：脚本使用 argparse 定义 --input/-i 和 --output/-o 参数，
     *       系统将输入写入临时文件并通过 --input 传入，执行后读取 --output 指定的输出文件</li>
     *   <li>stdin 模式（默认）：将输入通过 stdin 传入脚本，收集 stdout 作为输出</li>
     * </ul>
     * </p>
     * <p>
     * 对于 Python 脚本，会自动检测并安装缺失的第三方依赖（通过 pip install）。
     * 同时捕获 stderr 输出，在脚本执行失败时将错误信息附加到返回结果中。
     * </p>
     *
     * @param skill 技能对象
     * @param file  脚本文件信息
     * @param input 传入脚本的输入内容（当前输出）
     * @return 脚本的输出；如果执行失败则返回 null
     */
    private String executeScript(Skill skill, Skill.SkillFile file, String input) {
        String filename = file.getName();
        String lower = filename.toLowerCase();

        // 构建命令
        List<String> command = new ArrayList<>();
        boolean isPython = lower.endsWith(".py");
        if (isPython) {
            command.add("python3");
        } else if (lower.endsWith(".sh")) {
            command.add("bash");
        } else {
            return null;
        }

        // 将脚本内容写入临时文件
        File tempScript = null;
        File tempDir = null;
        File tempInputFile = null;
        File tempOutputFile = null;
        try {
            // 从存储读取脚本内容（自动检测编码，兼容 UTF-8 和 GBK）
            byte[] scriptContent = skillService.readSkillFile(skill.getId(), file.getRelativePath());
            String scriptText = SkillService.readTextWithEncodingDetection(scriptContent);

            // 创建临时目录（用于存放脚本及其依赖文件）
            tempDir = Files.createTempDirectory("skill_exec_").toFile();

            // 创建临时脚本文件
            tempScript = new File(tempDir, filename);
            try (FileOutputStream fos = new FileOutputStream(tempScript)) {
                fos.write(scriptContent);
            }
            tempScript.setExecutable(true);

            // Python 脚本：自动检测并安装缺失的第三方依赖
            if (isPython) {
                installPythonDependencies(skill, scriptText, tempDir);
            }

            command.add(tempScript.getAbsolutePath());

            // 检测脚本是否使用 argparse 的 --input/--output 参数模式
            boolean useArgparse = isPython && scriptText.contains("argparse")
                    && (scriptText.contains("--input") || scriptText.contains("-i"))
                    && (scriptText.contains("--output") || scriptText.contains("-o"));

            Process process;
            String stdout;

            if (useArgparse) {
                // ===== argparse 模式：通过命令行参数传输入/输出文件 =====
                log.info("检测到脚本使用 argparse --input/--output 参数模式: {}", filename);

                // 将输入内容写入临时文件
                tempInputFile = new File(tempDir, "input.txt");
                try (FileOutputStream fos = new FileOutputStream(tempInputFile)) {
                    fos.write(input.getBytes(StandardCharsets.UTF_8));
                }

                // 设置输出文件路径
                tempOutputFile = new File(tempDir, "output.txt");

                // 添加命令行参数
                command.add("--input");
                command.add(tempInputFile.getAbsolutePath());
                command.add("--output");
                command.add(tempOutputFile.getAbsolutePath());

                ProcessBuilder pb = new ProcessBuilder(command);
                pb.redirectErrorStream(false);
                pb.directory(tempDir);
                process = pb.start();

                // 关闭 stdin（argparse 模式不通过 stdin 传输入）
                process.getOutputStream().close();

                // 设置超时（120秒，文件处理可能需要更长时间）
                boolean finished = process.waitFor(120, TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    log.warn("脚本执行超时（120秒）: {}", filename);
                    return "[脚本执行超时（120秒），已终止]";
                }

                int exitCode = process.exitValue();
                String stderr = readStream(process.getErrorStream());

                // 优先读取输出文件内容
                if (tempOutputFile.exists() && tempOutputFile.length() > 0) {
                    byte[] outputBytes = Files.readAllBytes(tempOutputFile.toPath());
                    // 检测输出文件是否为二进制文件（如 PPTX、PDF、图片等）
                    if (isBinaryContent(outputBytes)) {
                        // 二进制文件：使用 Base64 编码并包装为 JSON 标记，供前端解码下载
                        String fileExt = detectFileExtension(outputBytes, filename);
                        String base64Data = java.util.Base64.getEncoder().encodeToString(outputBytes);
                        String downloadName = skill.getName() != null ? skill.getName() : "output";
                        downloadName = downloadName.replaceAll("[^a-zA-Z0-9_-]", "_") + "." + fileExt;
                        stdout = "{\"type\":\"binary_file\",\"filename\":\"" + downloadName
                                + "\",\"mime\":\"" + getMimeType(fileExt)
                                + "\",\"size\":" + outputBytes.length
                                + ",\"data\":\"" + base64Data + "\"}";
                        log.info("argparse 脚本输出二进制文件：{} ({} bytes, base64 长度={})",
                                downloadName, outputBytes.length, base64Data.length());
                    } else {
                        // 文本文件：自动检测编码读取（兼容 UTF-8 和 GBK）
                        stdout = SkillService.readTextWithEncodingDetection(outputBytes);
                        log.info("argparse 脚本输出文件读取成功，长度={}", stdout.length());
                    }
                } else {
                    // 输出文件不存在或为空，使用 stdout
                    stdout = readStream(process.getInputStream());
                }

                if (exitCode != 0) {
                    log.warn("脚本执行返回非零退出码 {}: {}", exitCode, filename);
                    log.warn("脚本 stderr: {}", stderr);
                    if (stderr != null && !stderr.trim().isEmpty()) {
                        return stdout + "\n\n--- 脚本错误输出 ---\n" + stderr;
                    }
                }

            } else {
                // ===== stdin 模式（默认）：通过 stdin 传输入，收集 stdout =====
                ProcessBuilder pb = new ProcessBuilder(command);
                pb.redirectErrorStream(false);
                pb.directory(tempDir);
                process = pb.start();

                // 写入 stdin
                try (OutputStream os = process.getOutputStream()) {
                    os.write(input.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }

                // 设置超时（60秒）
                boolean finished = process.waitFor(60, TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    log.warn("脚本执行超时（60秒）: {}", filename);
                    return "[脚本执行超时（60秒），已终止]";
                }

                int exitCode = process.exitValue();
                stdout = readStream(process.getInputStream());
                String stderr = readStream(process.getErrorStream());

                if (exitCode != 0) {
                    log.warn("脚本执行返回非零退出码 {}: {}", exitCode, filename);
                    log.warn("脚本 stderr: {}", stderr);
                    if (stderr != null && !stderr.trim().isEmpty()) {
                        return stdout + "\n\n--- 脚本错误输出 ---\n" + stderr;
                    }
                }
            }

            return stdout;

        } catch (Exception e) {
            log.error("脚本执行失败: {}", filename, e);
            return null;
        } finally {
            // 清理临时文件和目录
            if (tempScript != null) {
                tempScript.delete();
            }
            if (tempInputFile != null) {
                tempInputFile.delete();
            }
            if (tempOutputFile != null) {
                tempOutputFile.delete();
            }
            if (tempDir != null) {
                deleteDirectory(tempDir);
            }
        }
    }

    /**
     * 自动检测并安装 Python 脚本中引用的第三方依赖。
     * <p>
     * 1. 检查技能是否包含 requirements.txt，如果有则直接 pip install -r
     * 2. 扫描脚本中的 import 语句，提取第三方包名
     * 3. 逐个尝试 import，对失败的包执行 pip install
     * </p>
     *
     * @param skill       技能对象
     * @param scriptText  脚本文本内容
     * @param workDir     临时工作目录
     */
    private void installPythonDependencies(Skill skill, String scriptText, File workDir) {
        // 1. 检查是否有 requirements.txt
        if (skill.getFiles() != null) {
            for (Skill.SkillFile f : skill.getFiles()) {
                if ("requirements.txt".equalsIgnoreCase(f.getName())) {
                    try {
                        byte[] reqContent = skillService.readSkillFile(skill.getId(), f.getRelativePath());
                        File reqFile = new File(workDir, "requirements.txt");
                        try (FileOutputStream fos = new FileOutputStream(reqFile)) {
                            fos.write(reqContent);
                        }
                        log.info("发现 requirements.txt，正在安装依赖...");
                        runPipInstall("install", "-r", reqFile.getAbsolutePath());
                        return; // 如果有 requirements.txt，安装后直接返回
                    } catch (Exception e) {
                        log.warn("安装 requirements.txt 依赖失败: {}", e.getMessage());
                    }
                }
            }
        }

        // 2. 从脚本中提取 import 语句，识别第三方包
        List<String> thirdPartyModules = extractThirdPartyImports(scriptText);
        if (thirdPartyModules.isEmpty()) {
            log.debug("脚本未引用第三方包，跳过依赖安装");
            return;
        }

        // 3. 逐个检查并安装缺失的包
        for (String module : thirdPartyModules) {
            // 用 python3 -c "import xxx" 检查是否已安装
            if (!checkPythonModuleInstalled(module)) {
                log.info("检测到缺失的 Python 依赖: {}，正在安装...", module);
                // pip 包名映射（import 名 → pip 包名）
                String pipName = mapImportToPipName(module);
                runPipInstall("install", pipName);
            }
        }
    }

    /**
     * 从 Python 脚本文本中提取第三方 import 的模块名。
     * 支持 `import xxx`、`import xxx.yyy`（取 xxx）、`from xxx import yyy` 三种语法。
     * 排除 Python 标准库模块。
     *
     * @param scriptText 脚本文本
     * @return 第三方模块名列表（去重）
     */
    private List<String> extractThirdPartyImports(String scriptText) {
        List<String> modules = new ArrayList<>();
        // Python 标准库模块集合（常见模块）
        List<String> stdLib = java.util.Arrays.asList(
                "os", "sys", "re", "json", "io", "time", "datetime", "math", "random",
                "collections", "itertools", "functools", "pathlib", "shutil", "subprocess",
                "argparse", "logging", "unittest", "traceback", "typing", "enum",
                "dataclasses", "abc", "copy", "csv", "hashlib", "base64", "urllib",
                "http", "socket", "threading", "multiprocessing", "queue", "struct",
                "codecs", "string", "textwrap", "pprint", "inspect", "importlib",
                "configparser", "sqlite3", "xml", "html", "email", "calendar",
                "decimal", "fractions", "statistics", "array", "bisect", "heapq",
                "tempfile", "glob", "fnmatch", "errno", "signal", "mmap", "ctypes",
                "platform", "getpass", "shelve", "pickle", "json", "warnings"
        );

        // 匹配 import xxx 和 from xxx import yyy
        Pattern importPattern = Pattern.compile(
                "^\\s*(?:import\\s+(\\w+)|from\\s+(\\w+)\\s+import)", Pattern.MULTILINE);
        Matcher matcher = importPattern.matcher(scriptText);
        while (matcher.find()) {
            String module = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            if (module != null && !stdLib.contains(module) && !modules.contains(module)) {
                modules.add(module);
            }
        }

        return modules;
    }

    /**
     * 检查 Python 模块是否已安装。
     * 通过执行 `python3 -c "import xxx"` 来判断。
     *
     * @param module 模块名
     * @return true 如果已安装，false 如果未安装
     */
    private boolean checkPythonModuleInstalled(String module) {
        try {
            ProcessBuilder pb = new ProcessBuilder("python3", "-c", "import " + module);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            process.getOutputStream().close();
            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 将 Python import 名映射为 pip 包名。
     * 大多数包名与 import 名相同，但有一些例外。
     *
     * @param importName import 语句中的模块名
     * @return pip install 时使用的包名
     */
    private String mapImportToPipName(String importName) {
        switch (importName) {
            case "PIL": return "Pillow";
            case "cv2": return "opencv-python";
            case "docx": return "python-docx";
            case "pptx": return "python-pptx";
            case "xlrd": return "xlrd";
            case "xlwt": return "xlwt";
            case "openpyxl": return "openpyxl";
            case "yaml": return "PyYAML";
            case "bs4": return "beautifulsoup4";
            case "sklearn": return "scikit-learn";
            case "tensorflow": return "tensorflow";
            case "torch": return "torch";
            case "dateutil": return "python-dateutil";
            case "jwt": return "PyJWT";
            case "magic": return "python-magic";
            case "dotenv": return "python-dotenv";
            case "lxml": return "lxml";
            case "reportlab": return "reportlab";
            case "requests": return "requests";
            case "numpy": return "numpy";
            case "pandas": return "pandas";
            case "matplotlib": return "matplotlib";
            default: return importName;
        }
    }

    /**
     * 执行 pip install 命令安装 Python 包。
     * 使用 --break-system-packages 标志以兼容系统 Python 的限制。
     *
     * @param args pip 命令参数（如 "install", "requests"）
     */
    private void runPipInstall(String... args) {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add("python3");
            cmd.add("-m");
            cmd.add("pip");
            for (String arg : args) {
                cmd.add(arg);
            }
            cmd.add("--break-system-packages");
            cmd.add("--quiet");

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            process.getOutputStream().close();
            String output = readStream(process.getInputStream());
            boolean finished = process.waitFor(120, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("pip install 超时（120秒）");
                return;
            }
            if (process.exitValue() == 0) {
                log.info("pip install 成功: {}", String.join(" ", args));
            } else {
                log.warn("pip install 返回非零退出码: {}, 输出: {}", process.exitValue(),
                        output.substring(0, Math.min(output.length(), 500)));
            }
        } catch (Exception e) {
            log.warn("pip install 失败: {}", e.getMessage());
        }
    }

    /**
     * 递归删除目录及其内容。
     *
     * @param dir 要删除的目录
     */
    private void deleteDirectory(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory()) {
                    deleteDirectory(child);
                } else {
                    child.delete();
                }
            }
        }
        dir.delete();
    }

    /** 读取输入流为字符串（自动检测编码，兼容 UTF-8 和 GBK） */
    private String readStream(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int len;
        while ((len = is.read(buffer)) != -1) {
            baos.write(buffer, 0, len);
        }
        return SkillService.readTextWithEncodingDetection(baos.toByteArray());
    }

    // ==================== 私有方法 ====================

    /**
     * 内部方法：根据 ID 获取执行会话。
     * <p>
     * 从内存存储中查找会话，不存在时抛出异常。
     * </p>
     *
     * @param sessionId 会话 ID
     * @return 对应的执行会话
     * @throws IllegalArgumentException 当会话不存在时抛出
     */
    private ExecutionSession getSessionInternal(String sessionId) {
        ExecutionSession session = sessions.get(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("Session not found: " + sessionId);
        }
        return session;
    }

    /**
     * 内部方法：执行单个技能并生成执行记录。
     * <p>
     * 该方法构建技能提示词，调用 AI 服务执行技能，
     * 根据执行结果（成功或失败）构建对应的 ExecutionRound 对象。
     * </p>
     *
     * @param skill             要执行的技能
     * @param input             输入内容
     * @param continuedFromPrev 是否从上一轮输出继续（链式调用标识）
     * @return 包含执行结果的单轮执行记录
     */
    private ExecutionRound executeSkillInternal(Skill skill, String input, boolean continuedFromPrev) {
        log.info("Executing skill '{}' with input length {}", skill.getName(), input != null ? input.length() : 0);

        // 构建执行记录的 Builder，设置初始状态为 running
        ExecutionRound.ExecutionRoundBuilder roundBuilder = ExecutionRound.builder()
                .skillId(skill.getId())
                .skillName(skill.getName())
                .skillDescription(skill.getDescription())
                .input(input)
                .continuedFromPrevious(continuedFromPrev)
                .timestamp(LocalDateTime.now())
                .status("running");

        try {
            // 使用 SkillService 装配上下文（包含 argparse 脚本的 JSON 输出指令）
            String systemPrompt = skillService.assembleContext(skill, input);

            // 如果技能包含 argparse 脚本，使用更大的 max_tokens 和更明确的用户消息
            Integer maxTokens = skill.getMaxTokens();
            String userMessage = "请根据上述指令处理输入并返回结果。";
            if (skillService.hasArgparseScriptPublic(skill)) {
                userMessage = "请根据上述指令处理用户输入，直接输出完整的 JSON 格式结果（不要包含 Markdown 代码块标记），用于后续脚本处理。";
                if (maxTokens == null) {
                    maxTokens = 8192;
                }
            }

            log.debug("Built prompt for skill '{}':\n{}", skill.getName(),
                    systemPrompt.substring(0, Math.min(systemPrompt.length(), 500)));

            // 调用 AI 服务执行技能
            String output = aiService.chat(
                    systemPrompt,
                    userMessage,
                    skill.getModel(),
                    skill.getTemperature(),
                    maxTokens
            );

            // 后处理（包括 JSON 清理和脚本执行）
            output = postProcess(output, skill);

            // 设置输出和成功状态
            roundBuilder.output(output);
            roundBuilder.status("success");

            log.info("Skill '{}' executed successfully, output length {}", skill.getName(),
                    output != null ? output.length() : 0);

        } catch (Exception e) {
            // 执行失败，记录错误信息
            log.error("Skill '{}' execution failed", skill.getName(), e);
            roundBuilder.status("error");
            roundBuilder.errorMessage(e.getMessage());
            roundBuilder.output("执行失败: " + e.getMessage());
        }

        return roundBuilder.build();
    }

    /**
     * 根据技能模板构建完整的提示词。
     * <p>
     * 将技能模板中的占位符替换为实际内容：
     * <ul>
     *   <li>{input} - 替换为用户输入</li>
     *   <li>{materials} - 替换为所有素材文件的内容</li>
     *   <li>{scripts} - 替换为所有脚本文件的内容</li>
     * </ul>
     * 如果技能没有定义模板，则使用默认模板。
     * </p>
     *
     * @param skill 技能定义
     * @param input 用户输入
     * @return 替换占位符后的完整提示词
     */
    private String buildPrompt(Skill skill, String input) {
        // 获取技能的提示词模板，若为空则使用默认模板
        String template = skill.getPromptTemplate();
        if (template == null || template.trim().isEmpty()) {
            template = "你是一个AI助手。请处理以下输入：\n\n{input}";
        }

        // 读取技能关联的素材文件和脚本文件内容
        String materials = skillService.readMaterials(skill);
        String scripts = skillService.readScripts(skill);

        // 替换模板中的占位符为实际内容
        String prompt = template;
        prompt = prompt.replace("{input}", input != null ? input : "");
        prompt = prompt.replace("{materials}", materials != null ? materials : "（无参考资料）");
        prompt = prompt.replace("{scripts}", scripts != null ? scripts : "（无脚本文件）");

        return prompt;
    }

    /**
     * 两轮执行结果封装类。
     * 包含执行会话和第一轮路由判断的结果信息。
     */
    public static class TwoRoundResult {
        /** 执行会话 */
        private final ExecutionSession session;
        /** 第一轮路由判断结果 */
        private final RouteResult routeResult;

        public TwoRoundResult(ExecutionSession session, RouteResult routeResult) {
            this.session = session;
            this.routeResult = routeResult;
        }

        public ExecutionSession getSession() {
            return session;
        }

        public RouteResult getRouteResult() {
            return routeResult;
        }
    }
}
