package com.example.aiskill.service;

import com.example.aiskill.config.AiConfig;
import com.example.aiskill.model.RouteCandidates;
import com.example.aiskill.model.RouteResult;
import com.example.aiskill.model.Skill;
import com.example.aiskill.model.SkillIndex;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 服务类
 * <p>
 * 该服务封装了与大语言模型（LLM）聊天补全 API 的交互逻辑，
 * 负责构建请求体、发送 HTTP 请求、解析响应内容。
 * 同时提供了基于 AI 语义匹配的技能搜索能力，
 * 以及 AI 服务配置状态的检查功能。
 * </p>
 * <p>
 * 兼容 OpenAI 兼容接口（如 Ollama、GLM、Qwen3 等），
 * 支持 reasoning 模型的 think 参数以及 reasoning_content / reasoning 字段回退解析。
 * </p>
 */
@Slf4j
@Service
public class AiService {

    /** AI 配置对象，包含 API 密钥、基础 URL、默认模型、温度等参数 */
    private final AiConfig aiConfig;
    /** Spring HTTP 客户端，用于发送 REST 请求 */
    private final RestTemplate restTemplate;
    /** JSON 序列化/反序列化工具，用于构建请求体和解析响应 */
    private final ObjectMapper objectMapper;

    /**
     * 构造函数，通过 Spring 依赖注入初始化 AI 配置和 HTTP 客户端。
     *
     * @param aiConfig     AI 配置对象
     * @param restTemplate Spring REST 模板客户端
     */
    public AiService(AiConfig aiConfig, RestTemplate restTemplate) {
        this.aiConfig = aiConfig;
        this.restTemplate = restTemplate;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 调用 AI 聊天补全 API，传入系统提示词和用户消息，返回 AI 生成的文本。
     * <p>
     * 该方法构建符合 OpenAI 兼容格式的请求体，发送 POST 请求到
     * {baseUrl}/chat/completions 端点，并解析响应中的内容。
     * 支持模型、温度、最大 token 数等参数的覆盖配置，
     * 同时兼容 Ollama 的 think 参数以及 reasoning 模型的多种响应字段。
     * </p>
     *
     * @param systemPrompt 系统提示词（通常来自 skill.md 模板），可为空
     * @param userMessage  用户输入内容
     * @param model        可选的模型覆盖参数（为 null 时使用默认配置中的模型）
     * @param temperature  可选的温度覆盖参数（为 null 时使用默认配置）
     * @param maxTokens    可选的最大 token 数覆盖参数（为 null 时使用默认配置）
     * @return AI 返回的响应文本
     * @throws RuntimeException 当 API 调用失败、响应状态非 2xx 或响应中无有效内容时抛出
     */
    public String chat(String systemPrompt, String userMessage,
                       String model, Double temperature, Integer maxTokens) {
        try {
            // 拼接完整的 API 地址，去除 baseUrl 末尾多余的斜杠后追加 /chat/completions
            String url = aiConfig.getBaseUrl().replaceAll("/+$", "") + "/chat/completions";

            // 构建 JSON 请求体
            ObjectNode requestBody = objectMapper.createObjectNode();
            // 模型名称：优先使用方法参数，其次使用配置默认值
            requestBody.put("model", model != null ? model : aiConfig.getModel());
            // 温度参数：优先使用方法参数，其次使用配置默认值，最终回退到 0.7
            requestBody.put("temperature", temperature != null ? temperature :
                    (aiConfig.getTemperature() != null ? aiConfig.getTemperature() : 0.7));
            // 最大 token 数：优先使用方法参数，其次使用配置默认值
            if (maxTokens != null) {
                requestBody.put("max_tokens", maxTokens);
            } else if (aiConfig.getMaxTokens() != null) {
                requestBody.put("max_tokens", aiConfig.getMaxTokens());
            }

            // 支持 Ollama 的 "think" 参数，用于推理模型（如 Qwen3）
            // 当 think=false 时，禁用推理以获得更快的响应速度
            if (aiConfig.getThink() != null) {
                requestBody.put("think", aiConfig.getThink());
            }

            // 构建 messages 数组
            ArrayNode messages = requestBody.putArray("messages");

            // 如果系统提示词非空，则添加 system 角色消息
            if (systemPrompt != null && !systemPrompt.trim().isEmpty()) {
                ObjectNode sysMsg = messages.addObject();
                sysMsg.put("role", "system");
                sysMsg.put("content", systemPrompt);
            }

            // 添加 user 角色消息
            ObjectNode userMsg = messages.addObject();
            userMsg.put("role", "user");
            userMsg.put("content", userMessage);

            // 设置 HTTP 请求头
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(aiConfig.getApiKey());

            // 构建 HTTP 请求实体（请求体 + 请求头）
            HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(requestBody), headers);

            log.debug("Calling AI API: url={}, model={}", url, requestBody.get("model"));
            log.debug("Request body: {}", requestBody);

            // 发送 POST 请求到 AI API
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);

            // 校验响应状态码和响应体
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new RuntimeException("AI API returned status: " + response.getStatusCode());
            }

            // 解析 JSON 响应
            JsonNode responseJson = objectMapper.readTree(response.getBody());
            JsonNode choices = responseJson.path("choices");
            if (choices.isArray() && choices.size() > 0) {
                // 提取第一个 choice 的 message.content
                String content = choices.get(0).path("message").path("content").asText("");
                // 回退逻辑：推理模型（GLM、Qwen3）在 think 模式激活时，
                // 可能将输出放在 "reasoning_content" 或 "reasoning" 字段中（content 为空）
                if (content == null || content.trim().isEmpty()) {
                    content = choices.get(0).path("message").path("reasoning_content").asText("");
                }
                if (content == null || content.trim().isEmpty()) {
                    content = choices.get(0).path("message").path("reasoning").asText("");
                }
                log.debug("AI response: {}", content.substring(0, Math.min(content.length(), 200)));
                return content;
            }

            throw new RuntimeException("AI API response has no choices");

        } catch (Exception e) {
            log.error("AI API call failed", e);
            throw new RuntimeException("AI调用失败: " + e.getMessage(), e);
        }
    }

    /**
     * 聊天结果封装类，同时包含正文内容和推理过程（思考过程）。
     * 用于需要展示 AI 思考过程的场景（如路由判断）。
     */
    public static class ChatResult {
        /** AI 生成的正文内容 */
        private String content;
        /** AI 的思考过程（reasoning_content），如果模型支持则非空 */
        private String reasoningContent;

        public ChatResult(String content, String reasoningContent) {
            this.content = content;
            this.reasoningContent = reasoningContent;
        }

        public String getContent() { return content; }
        public String getReasoningContent() { return reasoningContent; }
    }

    /**
     * 调用 AI 聊天补全 API，同时返回正文内容和推理过程（思考过程）。
     * <p>
     * 与 {@link #chat} 方法不同，此方法会分别提取 message.content 和 message.reasoning_content，
     * 便于前端展示 AI 的思考过程。
     * </p>
     *
     * @param systemPrompt 系统提示词
     * @param userMessage  用户输入内容
     * @param model        可选的模型覆盖参数
     * @param temperature  可选的温度覆盖参数
     * @param maxTokens    可选的最大 token 数覆盖参数
     * @return 包含正文内容和推理过程的 ChatResult 对象
     */
    public ChatResult chatWithReasoning(String systemPrompt, String userMessage,
                                        String model, Double temperature, Integer maxTokens) {
        try {
            String url = aiConfig.getBaseUrl().replaceAll("/+$", "") + "/chat/completions";

            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", model != null ? model : aiConfig.getModel());
            requestBody.put("temperature", temperature != null ? temperature :
                    (aiConfig.getTemperature() != null ? aiConfig.getTemperature() : 0.7));
            if (maxTokens != null) {
                requestBody.put("max_tokens", maxTokens);
            } else if (aiConfig.getMaxTokens() != null) {
                requestBody.put("max_tokens", aiConfig.getMaxTokens());
            }
            if (aiConfig.getThink() != null) {
                requestBody.put("think", aiConfig.getThink());
            }

            ArrayNode messages = requestBody.putArray("messages");
            if (systemPrompt != null && !systemPrompt.trim().isEmpty()) {
                ObjectNode sysMsg = messages.addObject();
                sysMsg.put("role", "system");
                sysMsg.put("content", systemPrompt);
            }
            ObjectNode userMsg = messages.addObject();
            userMsg.put("role", "user");
            userMsg.put("content", userMessage);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(aiConfig.getApiKey());

            HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(requestBody), headers);
            log.debug("Calling AI API (with reasoning): url={}, model={}", url, requestBody.get("model"));

            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new RuntimeException("AI API returned status: " + response.getStatusCode());
            }

            JsonNode responseJson = objectMapper.readTree(response.getBody());
            JsonNode choices = responseJson.path("choices");
            if (choices.isArray() && choices.size() > 0) {
                // 分别提取 content 和 reasoning_content
                String content = choices.get(0).path("message").path("content").asText("");
                String reasoningContent = choices.get(0).path("message").path("reasoning_content").asText("");
                if (reasoningContent == null || reasoningContent.trim().isEmpty()) {
                    reasoningContent = choices.get(0).path("message").path("reasoning").asText("");
                }

                log.debug("AI response content length: {}, reasoning length: {}",
                        content.length(), reasoningContent != null ? reasoningContent.length() : 0);
                return new ChatResult(content, reasoningContent);
            }

            throw new RuntimeException("AI API response has no choices");

        } catch (Exception e) {
            log.error("AI API call (with reasoning) failed", e);
            throw new RuntimeException("AI调用失败: " + e.getMessage(), e);
        }
    }

    /**
     * 使用 AI 语义匹配搜索技能。
     * <p>
     * 将所有可用技能的列表（包含 ID、名称、描述、标签）发送给 AI，
     * 让 AI 根据用户描述的需求按匹配度从高到低排列，
     * 返回排序后的技能 ID 列表。
     * </p>
     *
     * @param description 用户描述的需求内容
     * @param allSkills   所有可用技能的列表
     * @return 按匹配度从高到低排序的技能 ID 列表（最匹配的排在最前）
     */
    public List<String> searchSkillsByAi(String description, List<Skill> allSkills) {
        // 如果技能列表为空，直接返回空列表
        if (allSkills == null || allSkills.isEmpty()) {
            return new ArrayList<>();
        }

        // 构建技能列表文本，格式化为每行一个技能的描述
        StringBuilder skillList = new StringBuilder();
        for (Skill skill : allSkills) {
            skillList.append(String.format("- ID: %s | 名称: %s | 描述: %s | 标签: %s\n",
                    skill.getId(),
                    skill.getName(),
                    skill.getDescription(),
                    skill.getTags() != null ? String.join(", ", skill.getTags()) : ""));
        }

        // 构建系统提示词，指导 AI 进行技能匹配
        String systemPrompt = "你是一个技能匹配助手。用户会描述他们的需求，你需要从可用技能列表中找出最匹配的技能。\n" +
                "请按照匹配度从高到低排列，只返回技能ID列表，每行一个ID，不要返回其他内容。\n" +
                "如果没有匹配的技能，返回空。\n\n" +
                "可用技能列表:\n" + skillList;

        // 构建用户消息
        String userMessage = "用户需求: " + description;

        // 调用 AI 进行匹配，使用较低温度（0.3）以获得更确定性的结果
        String response = chat(systemPrompt, userMessage, null, 0.3, 1000);

        // 解析 AI 返回的技能 ID 列表
        List<String> result = new ArrayList<>();
        if (response != null) {
            for (String line : response.trim().split("\n")) {
                // 去除行首的列表标记符号（如 -、*、数字、点号等）和空白字符
                String id = line.trim().replaceAll("^[\\-\\*\\d\\.\\s]+", "").trim();
                if (!id.isEmpty()) {
                    result.add(id);
                }
            }
        }

        log.info("AI search for '{}' returned {} matches", description, result.size());
        return result;
    }

    /**
     * 第一轮路由判断：调用大模型进行意图识别，选出需要执行的技能。
     * <p>
     * 仅传入技能的名称和简介（SkillIndex），token 开销小，快速判断要调用哪个 skill。
     * 大模型只输出 JSON，不生成业务内容。
     * </p>
     * <p>
     * 输出 JSON 格式：
     * <pre>
     * {
     *   "skillId": "skill_xxx",
     *   "skillName": "技能名称",
     *   "reason": "选择该技能的理由"
     * }
     * </pre>
     * </p>
     *
     * @param userInput    用户的输入内容（描述或文件内容）
     * @param skillIndexes 技能索引列表（仅包含 id、name、description、tags）
     * @return 路由判断结果，包含选中的技能 ID、名称和选择理由；如果未匹配则返回 null
     */
    public RouteResult routeSkill(String userInput, List<SkillIndex> skillIndexes) {
        if (skillIndexes == null || skillIndexes.isEmpty()) {
            log.warn("路由判断失败：技能索引列表为空");
            return null;
        }

        // 构建技能索引列表文本（仅 name + description，控制 token 消耗）
        StringBuilder skillList = new StringBuilder();
        for (SkillIndex idx : skillIndexes) {
            skillList.append(String.format("- ID: %s | 名称: %s | 描述: %s | 标签: %s\n",
                    idx.getId(),
                    idx.getName(),
                    idx.getDescription(),
                    idx.getTags() != null ? idx.getTags() : ""));
        }

        // 构建系统提示词：要求大模型只输出 JSON，不生成业务内容
        String systemPrompt = "你是一个技能路由助手。用户会描述他们的需求，你需要从可用技能列表中选出最匹配的一个技能。\n" +
                "你只负责路由选择，不生成任何业务内容。\n" +
                "请只输出 JSON 格式结果，不要输出任何其他内容。JSON 格式如下：\n" +
                "{\"skillId\": \"技能ID\", \"skillName\": \"技能名称\", \"reason\": \"选择理由\"}\n\n" +
                "可用技能列表:\n" + skillList;

        String userMessage = "用户需求: " + userInput;

        // 调用大模型进行路由判断，使用低温度以获得确定性结果
        // max_tokens 设为 1000 确保 JSON 响应不会被截断
        String response = chat(systemPrompt, userMessage, null, 0.1, 1000);

        if (response == null || response.trim().isEmpty()) {
            log.warn("路由判断返回空结果");
            return null;
        }

        // 解析 JSON 响应
        try {
            // 提取 JSON 部分（大模型可能在 JSON 前后添加了说明文字）
            String jsonStr = extractJson(response);
            if (jsonStr == null) {
                log.warn("无法从路由判断响应中提取 JSON: {}", response.substring(0, Math.min(response.length(), 200)));
                return null;
            }

            JsonNode json = objectMapper.readTree(jsonStr);
            String skillId = json.path("skillId").asText("");
            String skillName = json.path("skillName").asText("");
            String reason = json.path("reason").asText("");

            if (skillId.isEmpty()) {
                log.warn("路由判断结果中缺少 skillId");
                return null;
            }

            log.info("路由判断完成：选中技能 {} ({})", skillName, skillId);
            return new RouteResult(skillId, skillName, reason, null);

        } catch (Exception e) {
            log.error("解析路由判断 JSON 失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 第一轮路由判断（多候选版）：调用大模型进行意图识别，返回多个匹配的候选技能。
     * <p>
     * 仅传入技能的名称和简介（SkillIndex），token 开销小。
     * 大模型输出 JSON 数组格式，包含多个候选技能及其匹配分数和理由。
     * 同时捕获大模型的思考过程（reasoning_content）供前端展示。
     * </p>
     * <p>
     * 输出 JSON 格式：
     * <pre>
     * {
     *   "candidates": [
     *     {"skillId": "skill_xxx", "skillName": "技能名称", "reason": "匹配理由", "score": 95},
     *     {"skillId": "skill_yyy", "skillName": "技能名称", "reason": "匹配理由", "score": 80}
     *   ]
     * }
     * </pre>
     * </p>
     *
     * @param userInput    用户的输入内容（描述或文件内容）
     * @param skillIndexes 技能索引列表（仅包含 id、name、description、tags）
     * @return 多候选路由结果，包含候选列表和思考过程；如果未匹配则返回 null
     */
    public RouteCandidates routeSkillMultiple(String userInput, List<SkillIndex> skillIndexes) {
        if (skillIndexes == null || skillIndexes.isEmpty()) {
            log.warn("多候选路由判断失败：技能索引列表为空");
            return null;
        }

        // 构建技能索引列表文本
        StringBuilder skillList = new StringBuilder();
        for (SkillIndex idx : skillIndexes) {
            skillList.append(String.format("- ID: %s | 名称: %s | 描述: %s | 标签: %s\n",
                    idx.getId(),
                    idx.getName(),
                    idx.getDescription(),
                    idx.getTags() != null ? idx.getTags() : ""));
        }

        // 构建系统提示词：要求大模型返回多个候选技能，按匹配分数排序
        String systemPrompt = "你是一个技能路由助手。用户会描述他们的需求，你需要从可用技能列表中选出所有可能匹配的技能（最多3个）。\n" +
                "你只负责路由选择，不生成任何业务内容。\n" +
                "请按匹配度从高到低排列候选技能，并为每个技能给出匹配分数（0-100）和选择理由。\n" +
                "请只输出 JSON 格式结果，不要输出任何其他内容。JSON 格式如下：\n" +
                "{\n  \"candidates\": [\n" +
                "    {\"skillId\": \"技能ID\", \"skillName\": \"技能名称\", \"reason\": \"匹配理由\", \"score\": 95},\n" +
                "    {\"skillId\": \"技能ID\", \"skillName\": \"技能名称\", \"reason\": \"匹配理由\", \"score\": 80}\n" +
                "  ]\n}\n\n" +
                "可用技能列表:\n" + skillList;

        String userMessage = "用户需求: " + userInput;

        // 使用 chatWithReasoning 调用大模型，同时获取正文和思考过程
        ChatResult chatResult = chatWithReasoning(systemPrompt, userMessage, null, 0.1, 2000);

        String response = chatResult.getContent();
        String thinkingProcess = chatResult.getReasoningContent();

        if (response == null || response.trim().isEmpty()) {
            log.warn("多候选路由判断返回空结果");
            return null;
        }

        // 解析 JSON 响应
        try {
            String jsonStr = extractJson(response);
            if (jsonStr == null) {
                log.warn("无法从多候选路由判断响应中提取 JSON: {}", response.substring(0, Math.min(response.length(), 200)));
                return null;
            }

            JsonNode json = objectMapper.readTree(jsonStr);
            JsonNode candidatesNode = json.path("candidates");

            if (!candidatesNode.isArray() || candidatesNode.size() == 0) {
                log.warn("多候选路由判断结果中没有 candidates 数组");
                return null;
            }

            List<RouteResult> candidates = new ArrayList<>();
            for (JsonNode candidateNode : candidatesNode) {
                String skillId = candidateNode.path("skillId").asText("");
                String skillName = candidateNode.path("skillName").asText("");
                String reason = candidateNode.path("reason").asText("");
                double score = candidateNode.path("score").asDouble(0);

                if (!skillId.isEmpty()) {
                    candidates.add(new RouteResult(skillId, skillName, reason, score));
                }
            }

            if (candidates.isEmpty()) {
                log.warn("多候选路由判断结果中没有有效的候选技能");
                return null;
            }

            log.info("多候选路由判断完成：返回 {} 个候选技能", candidates.size());
            return RouteCandidates.builder()
                    .userInput(userInput)
                    .candidates(candidates)
                    .thinkingProcess(thinkingProcess)
                    .build();

        } catch (Exception e) {
            log.error("解析多候选路由判断 JSON 失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 从大模型响应文本中提取 JSON 字符串。
     * 支持直接 JSON、```json 代码块包裹、以及前后有说明文字的情况。
     *
     * @param text 大模型响应文本
     * @return 提取出的 JSON 字符串，如果无法提取则返回 null
     */
    private String extractJson(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }

        String trimmed = text.trim();

        // 尝试直接解析
        if (trimmed.startsWith("{")) {
            return trimmed;
        }

        // 尝试从 ```json ... ``` 代码块中提取
        int jsonStart = trimmed.indexOf("```json");
        if (jsonStart >= 0) {
            int contentStart = jsonStart + 7;
            int jsonEnd = trimmed.indexOf("```", contentStart);
            if (jsonEnd > contentStart) {
                return trimmed.substring(contentStart, jsonEnd).trim();
            }
        }

        // 尝试从 ``` ... ``` 代码块中提取
        jsonStart = trimmed.indexOf("```");
        if (jsonStart >= 0) {
            int contentStart = trimmed.indexOf("\n", jsonStart);
            if (contentStart >= 0) {
                contentStart++;
                int jsonEnd = trimmed.indexOf("```", contentStart);
                if (jsonEnd > contentStart) {
                    String content = trimmed.substring(contentStart, jsonEnd).trim();
                    if (content.startsWith("{")) {
                        return content;
                    }
                }
            }
        }

        // 尝试找到第一个 { 和最后一个 } 之间的内容
        int braceStart = trimmed.indexOf('{');
        int braceEnd = trimmed.lastIndexOf('}');
        if (braceStart >= 0 && braceEnd > braceStart) {
            return trimmed.substring(braceStart, braceEnd + 1);
        }

        return null;
    }

    /**
     * 检查 AI 服务是否已正确配置。
     * <p>
     * 通过验证 API 密钥是否为空且不为默认占位符来判断配置状态。
     * </p>
     *
     * @return 如果 API 密钥已配置且非默认占位符则返回 true，否则返回 false
     */
    public boolean isConfigured() {
        return aiConfig.getApiKey() != null
                && !aiConfig.getApiKey().trim().isEmpty()
                && !aiConfig.getApiKey().equals("sk-your-api-key-here");
    }
}
