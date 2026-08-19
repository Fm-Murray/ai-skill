package com.example.aiskill.controller;

import com.example.aiskill.model.ApiResult;
import com.example.aiskill.model.ExecutionSession;
import com.example.aiskill.model.RouteCandidates;
import com.example.aiskill.model.Skill;
import com.example.aiskill.model.dto.ContinueRequest;
import com.example.aiskill.model.dto.ExecuteRequest;
import com.example.aiskill.service.ExecutionService;
import com.example.aiskill.service.SkillService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * 技能执行会话控制器
 * <p>
 * 该控制器负责管理技能执行的生命周期，提供基于"会话（Session）"的执行流程控制。
 * 一个会话由一个或多个"轮次（Round）"组成，支持多技能串联编排。
 * <p>
 * 主要功能包括：
 * <ul>
 *   <li>启动新会话：支持纯文本输入、带文件上传输入以及 AI 自动选择技能执行</li>
 *   <li>继续下一轮：在当前会话基础上接入新技能，传递上一轮输出作为输入</li>
 *   <li>返回上一轮：回退到会话中的前一个轮次</li>
 *   <li>重新执行：使用相同或新的输入重新执行当前轮次</li>
 *   <li>查询会话状态：获取会话的完整状态信息</li>
 * </ul>
 * <p>
 * 所有接口统一以 {@link ApiResult} 作为返回封装。接口根路径为 {@code /api/sessions}。
 *
 * @see ExecutionService 执行会话业务服务层
 * @see SkillService 技能业务服务层（用于 AI 自动选择技能）
 */
@Slf4j
@RestController
@RequestMapping("/api/sessions")
public class ExecutionController {

    /** 执行会话服务，负责会话的创建、轮次推进、回退及重新执行等业务逻辑 */
    private final ExecutionService executionService;

    /** 技能服务，用于 AI 自动选择技能场景下根据输入匹配最合适的技能 */
    private final SkillService skillService;

    /**
     * 构造函数，通过 Spring 依赖注入初始化执行会话服务与技能服务。
     *
     * @param executionService 执行会话服务实例
     * @param skillService     技能服务实例
     */
    public ExecutionController(ExecutionService executionService, SkillService skillService) {
        this.executionService = executionService;
        this.skillService = skillService;
    }

    /**
     * 启动新的执行会话（首个技能）。
     * <p>
     * 接口地址：{@code POST /api/sessions}
     * <p>
     * 请求体 JSON 格式：{@code { "skillId": "技能ID", "input": "输入文本" }}
     * <p>
     * 调用方需显式指定要执行的技能 ID 及输入内容。
     *
     * @param request 执行请求对象，包含技能 ID 和输入文本
     * @return 包含会话状态信息的统一响应结果
     */
    @PostMapping
    public ApiResult<Map<String, Object>> startSession(@RequestBody ExecuteRequest request) {
        try {
            // 校验技能 ID 非空
            if (request.getSkillId() == null || request.getSkillId().trim().isEmpty()) {
                return ApiResult.error(400, "技能ID不能为空");
            }
            // 校验输入内容非空
            if (request.getInput() == null || request.getInput().trim().isEmpty()) {
                return ApiResult.error(400, "输入内容不能为空");
            }

            // 创建并启动执行会话
            ExecutionSession session = executionService.startSession(request.getSkillId(), request.getInput());
            return ApiResult.success("执行成功", toResponse(session));
        } catch (IllegalArgumentException e) {
            // 参数非法（如技能不存在），返回 400
            return ApiResult.error(400, e.getMessage());
        } catch (Exception e) {
            log.error("Session start failed", e);
            return ApiResult.error("执行失败: " + e.getMessage());
        }
    }

    /**
     * 启动新的执行会话（支持文件上传）。
     * <p>
     * 接口地址：{@code POST /api/sessions/with-files}
     * <p>
     * 请求格式为 multipart/form-data，包含以下部分：
     * <ul>
     *   <li>{@code skillId}：技能 ID（必填，表单字段）</li>
     *   <li>{@code input}：文本输入内容（选填，表单字段）</li>
     *   <li>{@code files}：上传的文件数组（选填，文件部分）</li>
     * </ul>
     * 文件内容会以带标签的形式拼接到文本输入后，作为完整的输入内容传给技能。
     *
     * @param skillId 技能 ID
     * @param input   文本输入内容（可为空）
     * @param files   上传的文件数组（可为空）
     * @return 包含会话状态信息的统一响应结果
     */
    @PostMapping("/with-files")
    public ApiResult<Map<String, Object>> startSessionWithFiles(
            @RequestParam("skillId") String skillId,
            @RequestParam(value = "input", required = false, defaultValue = "") String input,
            @RequestParam(value = "files", required = false) MultipartFile[] files) {
        try {
            // 校验技能 ID 非空
            if (skillId == null || skillId.trim().isEmpty()) {
                return ApiResult.error(400, "技能ID不能为空");
            }

            // 将文本输入与文件内容合并为完整的输入字符串
            String combinedInput = buildInputWithFiles(input, files);
            // 合并后仍为空则报错
            if (combinedInput.trim().isEmpty()) {
                return ApiResult.error(400, "输入内容不能为空");
            }

            // 创建并启动执行会话
            ExecutionSession session = executionService.startSession(skillId, combinedInput);
            return ApiResult.success("执行成功", toResponse(session));
        } catch (IllegalArgumentException e) {
            return ApiResult.error(400, e.getMessage());
        } catch (Exception e) {
            log.error("Session start with files failed", e);
            return ApiResult.error("执行失败: " + e.getMessage());
        }
    }

    /**
     * AI 自动选择技能并一步执行（仅文本输入）。
     * <p>
     * 接口地址：{@code POST /api/sessions/auto-execute}
     * <p>
     * 请求体 JSON 格式：{@code { "input": "输入文本" }}
     * <p>
     * 系统会根据输入文本通过 AI 自动匹配最合适的技能，然后立即启动执行会话。
     * 响应中会额外返回所选中技能的名称、ID 和描述，便于前端展示。
     *
     * @param request 执行请求对象，包含输入文本
     * @return 包含会话状态信息及自动选中技能详情的统一响应结果
     */
    @PostMapping("/auto-execute")
    public ApiResult<Map<String, Object>> autoExecute(@RequestBody ExecuteRequest request) {
        try {
            // 校验输入内容非空
            if (request.getInput() == null || request.getInput().trim().isEmpty()) {
                return ApiResult.error(400, "输入内容不能为空");
            }

            // 两轮大模型调用：第一轮路由判断 → 装配上下文 → 第二轮内容生成 → 后处理
            ExecutionService.TwoRoundResult result = executionService.autoExecuteTwoRound(request.getInput());
            if (result == null) {
                return ApiResult.error(404, "未找到匹配的技能，请先上传技能或手动选择");
            }

            // 构建响应
            Map<String, Object> response = toResponse(result.getSession());
            response.put("autoSelectedSkill", true);
            response.put("selectedSkillName", result.getRouteResult().getSkillName());
            response.put("selectedSkillId", result.getRouteResult().getSkillId());
            response.put("selectedSkillDescription", result.getRouteResult().getReason());
            response.put("routeReason", result.getRouteResult().getReason());
            response.put("twoRoundExecution", true);
            return ApiResult.success("AI 自动选择技能: " + result.getRouteResult().getSkillName(), response);
        } catch (IllegalArgumentException e) {
            return ApiResult.error(400, e.getMessage());
        } catch (Exception e) {
            log.error("Auto-execute failed", e);
            return ApiResult.error("自动执行失败: " + e.getMessage());
        }
    }

    /**
     * AI 自动选择技能并一步执行（支持文件上传）。
     * <p>
     * 接口地址：{@code POST /api/sessions/auto-execute-with-files}
     * <p>
     * 请求格式为 multipart/form-data，包含以下部分：
     * <ul>
     *   <li>{@code input}：文本输入内容（选填，表单字段）</li>
     *   <li>{@code files}：上传的文件数组（选填，文件部分）</li>
     * </ul>
     * 系统会根据合并后的输入内容通过 AI 自动匹配最合适的技能，然后立即启动执行会话。
     * 响应中会额外返回所选中技能的名称、ID 和描述。
     *
     * @param input 文本输入内容（可为空）
     * @param files 上传的文件数组（可为空）
     * @return 包含会话状态信息及自动选中技能详情的统一响应结果
     */
    @PostMapping("/auto-execute-with-files")
    public ApiResult<Map<String, Object>> autoExecuteWithFiles(
            @RequestParam(value = "input", required = false, defaultValue = "") String input,
            @RequestParam(value = "files", required = false) MultipartFile[] files) {
        try {
            // 将文本输入与文件内容合并为完整输入
            String combinedInput = buildInputWithFiles(input, files);
            if (combinedInput.trim().isEmpty()) {
                return ApiResult.error(400, "输入内容不能为空");
            }

            // 两轮大模型调用：第一轮路由判断 → 装配上下文 → 第二轮内容生成 → 后处理
            ExecutionService.TwoRoundResult result = executionService.autoExecuteTwoRound(combinedInput);
            if (result == null) {
                return ApiResult.error(404, "未找到匹配的技能，请先上传技能或手动选择");
            }

            // 构建响应
            Map<String, Object> response = toResponse(result.getSession());
            response.put("autoSelectedSkill", true);
            response.put("selectedSkillName", result.getRouteResult().getSkillName());
            response.put("selectedSkillId", result.getRouteResult().getSkillId());
            response.put("selectedSkillDescription", result.getRouteResult().getReason());
            response.put("routeReason", result.getRouteResult().getReason());
            response.put("twoRoundExecution", true);
            return ApiResult.success("AI 自动选择技能: " + result.getRouteResult().getSkillName(), response);
        } catch (IllegalArgumentException e) {
            return ApiResult.error(400, e.getMessage());
        } catch (Exception e) {
            log.error("Auto-execute with files failed", e);
            return ApiResult.error("自动执行失败: " + e.getMessage());
        }
    }

    /**
     * 第一轮路由判断（仅路由，不执行内容生成）。
     * <p>
     * 接口地址：{@code POST /api/sessions/route}
     * <p>
     * 请求体 JSON 格式：{@code { "input": "输入文本" }}
     * <p>
     * 系统根据输入内容通过 AI 进行路由判断，返回多个匹配的候选技能列表和 AI 思考过程。
     * 用户可以在前端查看候选列表并选择一个技能，然后调用 {@code /api/sessions/execute-routed} 执行第二轮。
     *
     * @param request 执行请求对象，包含输入文本
     * @return 包含候选技能列表和思考过程的统一响应结果
     */
    @PostMapping("/route")
    public ApiResult<Map<String, Object>> routeOnly(@RequestBody ExecuteRequest request) {
        try {
            if (request.getInput() == null || request.getInput().trim().isEmpty()) {
                return ApiResult.error(400, "输入内容不能为空");
            }

            // 第一轮：路由判断（多候选版）
            RouteCandidates candidates = executionService.routeOnly(request.getInput());
            if (candidates == null) {
                return ApiResult.error(404, "未找到匹配的技能，请先上传技能或手动选择");
            }

            // 构建响应
            Map<String, Object> response = new HashMap<>();
            response.put("candidates", candidates.getCandidates());
            response.put("thinkingProcess", candidates.getThinkingProcess());
            response.put("userInput", candidates.getUserInput());
            response.put("candidateCount", candidates.size());
            return ApiResult.success("路由判断完成，返回 " + candidates.size() + " 个候选技能", response);
        } catch (Exception e) {
            log.error("Route only failed", e);
            return ApiResult.error("路由判断失败: " + e.getMessage());
        }
    }

    /**
     * 第一轮路由判断（仅路由，支持文件上传）。
     * <p>
     * 接口地址：{@code POST /api/sessions/route-with-files}
     * <p>
     * 请求格式为 multipart/form-data，包含 input 和 files 字段。
     *
     * @param input 文本输入内容（可为空）
     * @param files 上传的文件数组（可为空）
     * @return 包含候选技能列表和思考过程的统一响应结果
     */
    @PostMapping("/route-with-files")
    public ApiResult<Map<String, Object>> routeWithFiles(
            @RequestParam(value = "input", required = false, defaultValue = "") String input,
            @RequestParam(value = "files", required = false) MultipartFile[] files) {
        try {
            String combinedInput = buildInputWithFiles(input, files);
            if (combinedInput.trim().isEmpty()) {
                return ApiResult.error(400, "输入内容不能为空");
            }

            RouteCandidates candidates = executionService.routeOnly(combinedInput);
            if (candidates == null) {
                return ApiResult.error(404, "未找到匹配的技能，请先上传技能或手动选择");
            }

            Map<String, Object> response = new HashMap<>();
            response.put("candidates", candidates.getCandidates());
            response.put("thinkingProcess", candidates.getThinkingProcess());
            response.put("userInput", candidates.getUserInput());
            response.put("candidateCount", candidates.size());
            return ApiResult.success("路由判断完成，返回 " + candidates.size() + " 个候选技能", response);
        } catch (Exception e) {
            log.error("Route with files failed", e);
            return ApiResult.error("路由判断失败: " + e.getMessage());
        }
    }

    /**
     * 第二轮内容生成（用户选择技能后执行）。
     * <p>
     * 接口地址：{@code POST /api/sessions/execute-routed}
     * <p>
     * 请求体 JSON 格式：{@code { "input": "输入文本", "skillId": "技能ID", "routeReason": "选择理由", "thinkingProcess": "思考过程" }}
     * <p>
     * 用户从第一轮路由判断的候选列表中选择一个技能后，调用此接口执行第二轮内容生成。
     *
     * @param request 执行请求对象
     * @return 包含会话状态信息及选中技能详情的统一响应结果
     */
    @PostMapping("/execute-routed")
    public ApiResult<Map<String, Object>> executeRouted(@RequestBody Map<String, Object> body) {
        try {
            String input = (String) body.get("input");
            String skillId = (String) body.get("skillId");
            String routeReason = (String) body.get("routeReason");
            String thinkingProcess = (String) body.get("thinkingProcess");

            if (skillId == null || skillId.trim().isEmpty()) {
                return ApiResult.error(400, "技能ID不能为空");
            }
            if (input == null || input.trim().isEmpty()) {
                return ApiResult.error(400, "输入内容不能为空");
            }

            // 第二轮：装配上下文 → 内容生成 → 后处理
            ExecutionService.TwoRoundResult result = executionService.executeRouted(
                    input, skillId, routeReason, thinkingProcess);
            if (result == null) {
                return ApiResult.error(404, "技能不存在或执行失败");
            }

            // 构建响应
            Map<String, Object> response = toResponse(result.getSession());
            response.put("autoSelectedSkill", true);
            response.put("selectedSkillName", result.getRouteResult().getSkillName());
            response.put("selectedSkillId", result.getRouteResult().getSkillId());
            response.put("routeReason", result.getRouteResult().getReason());
            response.put("twoRoundExecution", true);
            response.put("userSelected", true);
            return ApiResult.success("执行技能: " + result.getRouteResult().getSkillName(), response);
        } catch (Exception e) {
            log.error("Execute routed failed", e);
            return ApiResult.error("执行失败: " + e.getMessage());
        }
    }

    /**
     * 使用新技能继续执行下一轮。
     * <p>
     * 接口地址：{@code POST /api/sessions/{sessionId}/continue}
     * <p>
     * 请求体 JSON 格式：{@code { "skillId": "技能ID", "input": "可选输入" }}
     * <p>
     * 在现有会话基础上接入新的技能。如果未提供 input，则默认使用上一轮的输出作为输入。
     *
     * @param sessionId 会话 ID
     * @param request   继续执行请求对象，包含技能 ID 和可选输入
     * @return 包含更新后会话状态信息的统一响应结果
     */
    @PostMapping("/{sessionId}/continue")
    public ApiResult<Map<String, Object>> continueToNext(
            @PathVariable String sessionId,
            @RequestBody ContinueRequest request) {
        try {
            // 校验技能 ID 非空
            if (request.getSkillId() == null || request.getSkillId().trim().isEmpty()) {
                return ApiResult.error(400, "技能ID不能为空");
            }

            // 继续下一轮执行，input 为空时由服务层使用上一轮输出
            ExecutionSession session = executionService.continueToNext(
                    sessionId, request.getSkillId(), request.getInput());
            return ApiResult.success("继续执行成功", toResponse(session));
        } catch (IllegalArgumentException e) {
            // 参数非法（如会话或技能不存在）
            return ApiResult.error(400, e.getMessage());
        } catch (IllegalStateException e) {
            // 状态非法（如会话已结束）
            return ApiResult.error(400, e.getMessage());
        } catch (Exception e) {
            log.error("Continue failed", e);
            return ApiResult.error("继续执行失败: " + e.getMessage());
        }
    }

    /**
     * 使用新技能继续执行下一轮（支持文件上传）。
     * <p>
     * 接口地址：{@code POST /api/sessions/{sessionId}/continue-with-files}
     * <p>
     * 请求格式为 multipart/form-data，包含以下部分：
     * <ul>
     *   <li>{@code skillId}：技能 ID（必填，表单字段）</li>
     *   <li>{@code input}：文本输入内容（选填，表单字段）</li>
     *   <li>{@code files}：上传的文件数组（选填，文件部分）</li>
     * </ul>
     * 如果未提供任何输入和文件，则默认使用上一轮的输出作为输入。
     *
     * @param sessionId 会话 ID
     * @param skillId   技能 ID
     * @param input     文本输入内容（可为空）
     * @param files     上传的文件数组（可为空）
     * @return 包含更新后会话状态信息的统一响应结果
     */
    @PostMapping("/{sessionId}/continue-with-files")
    public ApiResult<Map<String, Object>> continueWithFiles(
            @PathVariable String sessionId,
            @RequestParam("skillId") String skillId,
            @RequestParam(value = "input", required = false, defaultValue = "") String input,
            @RequestParam(value = "files", required = false) MultipartFile[] files) {
        try {
            // 校验技能 ID 非空
            if (skillId == null || skillId.trim().isEmpty()) {
                return ApiResult.error(400, "技能ID不能为空");
            }

            // 合并文本输入与文件内容
            String combinedInput = buildInputWithFiles(input, files);
            // 如果没有输入也没有文件，传入 null，由服务层使用上一轮输出
            if (combinedInput.trim().isEmpty()) {
                combinedInput = null;
            }

            // 继续下一轮执行
            ExecutionSession session = executionService.continueToNext(sessionId, skillId, combinedInput);
            return ApiResult.success("继续执行成功", toResponse(session));
        } catch (IllegalArgumentException e) {
            return ApiResult.error(400, e.getMessage());
        } catch (IllegalStateException e) {
            return ApiResult.error(400, e.getMessage());
        } catch (Exception e) {
            log.error("Continue with files failed", e);
            return ApiResult.error("继续执行失败: " + e.getMessage());
        }
    }

    /**
     * 返回上一轮执行。
     * <p>
     * 接口地址：{@code POST /api/sessions/{sessionId}/back}
     * <p>
     * 将会话指针回退到前一个轮次，丢弃当前轮次及之后的所有轮次。
     * 如果当前已经是第一轮，则无法回退。
     *
     * @param sessionId 会话 ID
     * @return 包含回退后会话状态信息的统一响应结果
     */
    @PostMapping("/{sessionId}/back")
    public ApiResult<Map<String, Object>> goBack(@PathVariable String sessionId) {
        try {
            // 执行回退操作
            ExecutionSession session = executionService.goBack(sessionId);
            return ApiResult.success("已返回上一轮", toResponse(session));
        } catch (IllegalArgumentException e) {
            // 会话不存在
            return ApiResult.error(404, e.getMessage());
        } catch (IllegalStateException e) {
            // 当前无法回退（如已经是第一轮）
            return ApiResult.error(400, e.getMessage());
        } catch (Exception e) {
            log.error("Go back failed", e);
            return ApiResult.error("返回上一轮失败: " + e.getMessage());
        }
    }

    /**
     * 使用相同或新的输入重新执行当前轮次。
     * <p>
     * 接口地址：{@code POST /api/sessions/{sessionId}/reexecute}
     * <p>
     * 请求体 JSON 格式（可选）：{@code { "input": "可选新输入" }}
     * <p>
     * 如果请求体中提供了 input，则使用新输入重新执行；否则使用当前轮次的原始输入重新执行。
     *
     * @param sessionId 会话 ID
     * @param body      请求体 Map，可包含 input 字段；请求体本身可为空
     * @return 包含重新执行后会话状态信息的统一响应结果
     */
    @PostMapping("/{sessionId}/reexecute")
    public ApiResult<Map<String, Object>> reExecute(
            @PathVariable String sessionId,
            @RequestBody(required = false) Map<String, String> body) {
        try {
            // 从请求体中提取输入，请求体或字段为空时使用 null（由服务层处理）
            String input = body != null ? body.get("input") : null;
            // 重新执行当前轮次
            ExecutionSession session = executionService.reExecute(sessionId, input);
            return ApiResult.success("重新执行成功", toResponse(session));
        } catch (IllegalArgumentException e) {
            // 参数非法
            return ApiResult.error(400, e.getMessage());
        } catch (IllegalStateException e) {
            // 状态非法
            return ApiResult.error(400, e.getMessage());
        } catch (Exception e) {
            log.error("Re-execute failed", e);
            return ApiResult.error("重新执行失败: " + e.getMessage());
        }
    }

    /**
     * 获取当前会话状态。
     * <p>
     * 接口地址：{@code GET /api/sessions/{sessionId}}
     * <p>
     * 返回会话的完整状态信息，包括当前轮次、所有轮次记录、当前输入输出、
     * 是否可回退/继续以及创建时间等。
     *
     * @param sessionId 会话 ID
     * @return 包含会话状态信息的统一响应结果；若会话不存在则返回 404 错误
     */
    @GetMapping("/{sessionId}")
    public ApiResult<Map<String, Object>> getSession(@PathVariable String sessionId) {
        try {
            // 查询会话状态
            ExecutionSession session = executionService.getSession(sessionId);
            return ApiResult.success(toResponse(session));
        } catch (IllegalArgumentException e) {
            // 会话不存在
            return ApiResult.error(404, e.getMessage());
        }
    }

    /**
     * 自动寻找下一轮技能（基于当前输出的路由判断）。
     * <p>
     * 接口地址：{@code POST /api/sessions/{sessionId}/auto-continue-route}
     * <p>
     * 系统获取当前会话最新轮次的输出，通过 AI 路由判断找到最适合的下一个技能。
     * 返回候选技能列表和 AI 思考过程，前端据此决定是否显示"继续下一轮"按钮。
     *
     * @param sessionId 会话 ID
     * @return 包含候选技能列表和思考过程的统一响应结果；如果没有匹配技能返回 404
     */
    @PostMapping("/{sessionId}/auto-continue-route")
    public ApiResult<Map<String, Object>> autoContinueRoute(@PathVariable String sessionId) {
        try {
            // 获取会话
            ExecutionSession session = executionService.getSession(sessionId);
            // 获取当前轮次的输出作为下一轮的输入
            String currentOutput = session.getCurrentOutput();
            if (currentOutput == null || currentOutput.trim().isEmpty()) {
                return ApiResult.error(400, "当前轮次无输出，无法寻找下一轮技能");
            }

            // 使用 AI 路由判断寻找下一轮技能
            RouteCandidates candidates = executionService.routeOnly(currentOutput);
            if (candidates == null || candidates.size() == 0) {
                // 没有找到匹配的下一轮技能
                Map<String, Object> response = new HashMap<>();
                response.put("hasNextSkill", false);
                response.put("message", "未找到适合的下一轮技能");
                return ApiResult.success("无下一轮技能", response);
            }

            // 返回候选技能列表
            Map<String, Object> response = new HashMap<>();
            response.put("hasNextSkill", true);
            response.put("candidates", candidates.getCandidates());
            response.put("thinkingProcess", candidates.getThinkingProcess());
            response.put("candidateCount", candidates.size());
            return ApiResult.success("找到 " + candidates.size() + " 个候选下一轮技能", response);
        } catch (Exception e) {
            log.error("Auto continue route failed", e);
            return ApiResult.error("寻找下一轮技能失败: " + e.getMessage());
        }
    }

    /**
     * 将文本输入与上传文件的内容合并为完整的输入字符串。
     * <p>
     * 文件内容以带标签的分隔区块形式追加到文本输入之后，格式如下：
     * <pre>
     * 文本输入内容
     *
     * === 上传文件: 文件名 ===
     * 文件内容
     * </pre>
     * 为避免超出 AI 上下文长度限制，单个文件内容超过 50000 字符时会被截断。
     *
     * @param input 文本输入内容（可为空）
     * @param files 上传的文件数组（可为空）
     * @return 合并后的完整输入字符串
     * @throws IOException 读取文件内容时发生 IO 异常
     */
    private String buildInputWithFiles(String input, MultipartFile[] files) throws IOException {
        StringBuilder sb = new StringBuilder();
        // 先追加文本输入部分
        if (input != null && !input.trim().isEmpty()) {
            sb.append(input.trim());
        }

        // 再追加文件内容部分
        if (files != null && files.length > 0) {
            for (MultipartFile file : files) {
                // 跳过空文件
                if (file == null || file.isEmpty()) continue;
                String filename = file.getOriginalFilename();
                if (filename == null) filename = "unknown";

                // 以分隔标签标注文件内容区块
                sb.append("\n\n=== 上传文件: ").append(filename).append(" ===\n");
                try {
                    // 自动检测编码读取文件内容（兼容 UTF-8 和 GBK）
                    String content = SkillService.readTextWithEncodingDetection(file.getBytes());
                    // 超长内容截断，防止超出 AI 上下文长度限制
                    if (content.length() > 50000) {
                        content = content.substring(0, 50000) + "\n\n... (文件内容过长，已截断)";
                    }
                    sb.append(content);
                } catch (Exception e) {
                    // 文件内容读取失败时，记录错误信息但不中断流程
                    sb.append("(无法读取文件内容: ").append(e.getMessage()).append(")");
                }
            }
        }

        return sb.toString();
    }

    /**
     * 将执行会话实体转换为响应 Map。
     * <p>
     * 提取会话的关键状态字段，包括会话 ID、当前轮次索引、全部轮次记录、
     * 是否可回退/继续、当前输入输出及创建时间，用于接口响应。
     *
     * @param session 执行会话实体对象
     * @return 会话状态信息的 Map
     */
    private Map<String, Object> toResponse(ExecutionSession session) {
        Map<String, Object> response = new HashMap<>();
        response.put("sessionId", session.getSessionId());
        response.put("currentRound", session.getCurrentRound());
        response.put("rounds", session.getRounds());
        response.put("canGoBack", session.canGoBack());
        response.put("canContinue", session.canContinue());
        response.put("currentOutput", session.getCurrentOutput());
        response.put("currentInput", session.getCurrentInput());
        response.put("createdAt", session.getCreatedAt());
        return response;
    }
}
