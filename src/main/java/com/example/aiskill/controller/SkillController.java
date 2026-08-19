package com.example.aiskill.controller;

import com.example.aiskill.model.ApiResult;
import com.example.aiskill.model.Skill;
import com.example.aiskill.model.dto.SearchRequest;
import com.example.aiskill.service.AiService;
import com.example.aiskill.service.SkillService;
import com.example.aiskill.storage.FileInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 技能管理控制器
 * <p>
 * 该控制器是 AI 技能管理系统的核心 HTTP 接口入口，负责处理与"技能（Skill）"相关的所有
 * RESTful 请求，包括：
 * <ul>
 *   <li>技能的上传（包含 skill.md 描述文件、素材文件和脚本文件）</li>
 *   <li>技能列表查询与详情获取</li>
 *   <li>技能文件目录的递归列举与文件内容读取（支持文本与二进制）</li>
 *   <li>技能删除</li>
 *   <li>基于自然语言描述的技能搜索（可选用 AI 语义匹配）</li>
 *   <li>AI 服务配置状态查询</li>
 * </ul>
 * <p>
 * 所有接口统一以 {@link ApiResult} 作为返回封装，便于前端进行统一的成功/失败处理。
 * 接口根路径为 {@code /api/skills}。
 *
 * @see SkillService 技能业务服务层
 * @see AiService AI 能力服务层
 */
@Slf4j
@RestController
@RequestMapping("/api/skills")
public class SkillController {

    /** 技能业务服务，负责技能的上传、查询、删除、搜索等核心业务逻辑 */
    private final SkillService skillService;

    /** AI 能力服务，用于技能语义搜索及 AI 配置状态检测 */
    private final AiService aiService;

    /**
     * 构造函数，通过 Spring 依赖注入初始化技能服务与 AI 服务。
     *
     * @param skillService 技能业务服务实例
     * @param aiService    AI 能力服务实例
     */
    public SkillController(SkillService skillService, AiService aiService) {
        this.skillService = skillService;
        this.aiService = aiService;
    }

    /**
     * 上传新技能（包含 skill.md 描述文件、素材文件与脚本文件）。
     * <p>
     * 接口地址：{@code POST /api/skills/upload}
     * <p>
     * 请求格式为 multipart/form-data，通过 {@code files} 参数传入一个或多个文件。
     * 其中必须包含一个 {@code skill.md} 文件用于描述技能元信息。
     *
     * @param files 上传的文件数组，至少包含 skill.md
     * @return 包含已创建技能对象的统一响应结果
     */
    @PostMapping("/upload")
    public ApiResult<Skill> uploadSkill(@RequestParam("files") MultipartFile[] files) {
        try {
            // 委托技能服务完成文件解析与持久化
            Skill skill = skillService.uploadSkill(files);
            return ApiResult.success("技能上传成功", skill);
        } catch (IllegalArgumentException e) {
            // 参数非法（如缺少 skill.md），返回 400
            return ApiResult.error(400, e.getMessage());
        } catch (IOException e) {
            // 文件 IO 异常，记录日志并返回错误信息
            log.error("Upload failed", e);
            return ApiResult.error("文件上传失败: " + e.getMessage());
        }
    }

    /**
     * 获取所有技能列表。
     * <p>
     * 接口地址：{@code GET /api/skills}
     * <p>
     * 返回所有已上传技能的摘要信息（不包含完整文件内容），便于前端展示技能列表。
     *
     * @return 包含技能摘要信息列表的统一响应结果
     */
    @GetMapping
    public ApiResult<List<Map<String, Object>>> listSkills() {
        // 从服务层获取全部技能实体
        List<Skill> skills = skillService.listSkills();
        // 将完整技能对象转换为精简的摘要 Map，减少响应体大小
        List<Map<String, Object>> result = skills.stream()
                .map(this::toSummary)
                .collect(Collectors.toList());
        return ApiResult.success(result);
    }

    /**
     * 根据技能 ID 获取技能详情。
     * <p>
     * 接口地址：{@code GET /api/skills/{skillId}}
     *
     * @param skillId 技能唯一标识
     * @return 包含技能完整信息的统一响应结果；若技能不存在则返回 404 错误
     */
    @GetMapping("/{skillId}")
    public ApiResult<Skill> getSkill(@PathVariable String skillId) {
        // 根据 ID 查询技能实体
        Skill skill = skillService.getSkill(skillId);
        if (skill == null) {
            // 技能不存在，返回 404
            return ApiResult.error(404, "技能不存在: " + skillId);
        }
        return ApiResult.success(skill);
    }

    /**
     * 递归列举技能目录下的所有文件。
     * <p>
     * 接口地址：{@code GET /api/skills/{skillId}/files/list}
     * <p>
     * 该接口会递归扫描技能目录（包括子目录），返回其中所有文件的信息。
     * 部分文件可能未被记录在 Skill.files 元数据中，但通过此接口仍可获取。
     *
     * @param skillId 技能唯一标识
     * @return 包含文件信息列表（名称、路径、大小、是否为二进制）的统一响应结果
     */
    @GetMapping("/{skillId}/files/list")
    public ApiResult<List<Map<String, Object>>> listSkillFiles(@PathVariable String skillId) {
        try {
            // 递归获取技能目录下的全部文件信息（含目录）
            List<FileInfo> files = skillService.listAllSkillFiles(skillId);
            List<Map<String, Object>> result = new ArrayList<>();
            for (FileInfo fi : files) {
                // 跳过目录，仅返回文件
                if (fi.isDirectory()) continue;
                Map<String, Object> fileMap = new HashMap<>();
                fileMap.put("name", fi.getName());
                fileMap.put("path", fi.getPath());
                fileMap.put("size", fi.getSize());
                // 根据扩展名判断是否为二进制文件，前端可据此决定展示方式
                fileMap.put("isBinary", isBinaryFile(fi.getName()));
                result.add(fileMap);
            }
            return ApiResult.success(result);
        } catch (IllegalArgumentException e) {
            // 技能 ID 非法或不存在
            return ApiResult.error(404, e.getMessage());
        } catch (Exception e) {
            log.error("Failed to list files for skill: {}", skillId, e);
            return ApiResult.error("获取文件列表失败: " + e.getMessage());
        }
    }

    /**
     * 读取技能目录下指定文件的内容。
     * <p>
     * 接口地址：{@code GET /api/skills/{skillId}/files/content?path=xxx}
     * <p>
     * 对于文本类文件，直接返回 UTF-8 编码的字符串内容；
     * 对于二进制文件（如图片、PDF、压缩包等），返回 Base64 编码后的字符串，
     * 以便前端进行解码展示或下载。
     *
     * @param skillId      技能唯一标识
     * @param relativePath 文件相对于技能根目录的相对路径
     * @return 包含文件名、大小、是否二进制及内容的统一响应结果
     */
    @GetMapping("/{skillId}/files/content")
    public ApiResult<Map<String, Object>> readSkillFile(
            @PathVariable String skillId,
            @RequestParam("path") String relativePath) {
        try {
            // 以字节数组形式读取文件内容
            byte[] content = skillService.readSkillFile(skillId, relativePath);
            Map<String, Object> result = new HashMap<>();
            result.put("name", relativePath);
            result.put("size", content.length);

            if (isBinaryFile(relativePath)) {
                // 二进制文件：使用 Base64 编码后返回，避免传输中的字符集问题
                result.put("isBinary", true);
                result.put("content", java.util.Base64.getEncoder().encodeToString(content));
            } else {
                // 文本文件：自动检测编码（兼容 UTF-8 和 GBK），以字符串形式返回
                result.put("isBinary", false);
                result.put("content", SkillService.readTextWithEncodingDetection(content));
            }
            return ApiResult.success(result);
        } catch (IllegalArgumentException e) {
            // 技能或文件路径非法
            return ApiResult.error(404, e.getMessage());
        } catch (Exception e) {
            log.error("Failed to read file: {}/{}", skillId, relativePath, e);
            return ApiResult.error("读取文件失败: " + e.getMessage());
        }
    }

    /**
     * 根据文件扩展名判断文件是否为二进制文件。
     * <p>
     * 该方法通过简单的扩展名匹配来判断，覆盖常见的图片、文档、压缩包、
     * 可执行文件及类文件等二进制格式。
     *
     * @param filename 文件名（含扩展名）
     * @return 如果判定为二进制文件返回 {@code true}，否则返回 {@code false}
     */
    private boolean isBinaryFile(String filename) {
        if (filename == null) return false;
        // 统一转换为小写进行匹配，避免大小写差异
        String lower = filename.toLowerCase();
        // 图片格式
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".ico")
                // 文档与压缩包格式
                || lower.endsWith(".pdf") || lower.endsWith(".zip") || lower.endsWith(".tar")
                || lower.endsWith(".gz") || lower.endsWith(".tgz") || lower.endsWith(".jar")
                // 可执行与类文件格式
                || lower.endsWith(".class") || lower.endsWith(".so") || lower.endsWith(".dll")
                || lower.endsWith(".exe") || lower.endsWith(".bin") || lower.endsWith(".dat");
    }

    /**
     * 根据技能 ID 删除技能。
     * <p>
     * 接口地址：{@code DELETE /api/skills/{skillId}}
     * <p>
     * 删除操作会移除技能的元数据以及存储在磁盘上的全部相关文件。
     *
     * @param skillId 技能唯一标识
     * @return 操作结果；若技能不存在则返回 404 错误
     */
    @DeleteMapping("/{skillId}")
    public ApiResult<Void> deleteSkill(@PathVariable String skillId) {
        // 执行删除操作
        boolean deleted = skillService.deleteSkill(skillId);
        if (!deleted) {
            // 删除失败通常意味着技能不存在
            return ApiResult.error(404, "技能不存在: " + skillId);
        }
        return ApiResult.success("技能已删除", null);
    }

    /**
     * 根据自然语言描述搜索匹配的技能。
     * <p>
     * 接口地址：{@code POST /api/skills/search}
     * <p>
     * 请求体为 {@link SearchRequest}，包含搜索描述以及是否使用 AI 语义匹配的开关。
     * 当 {@code useAi} 为 {@code true} 或未指定时，将借助 AI 进行语义匹配；
     * 否则采用关键词匹配方式。
     *
     * @param request 搜索请求对象，包含描述文本和是否使用 AI 的标志
     * @return 包含匹配技能摘要列表的统一响应结果
     */
    @PostMapping("/search")
    public ApiResult<List<Map<String, Object>>> searchSkills(@RequestBody SearchRequest request) {
        // 校验搜索描述非空
        if (request.getDescription() == null || request.getDescription().trim().isEmpty()) {
            return ApiResult.error(400, "搜索描述不能为空");
        }

        // 当 useAi 为 null 或 true 时启用 AI 语义匹配
        boolean useAi = request.getUseAi() == null || request.getUseAi();
        List<Skill> skills = skillService.searchSkills(request.getDescription(), useAi);

        // 将匹配结果转换为摘要列表
        List<Map<String, Object>> result = skills.stream()
                .map(this::toSummary)
                .collect(Collectors.toList());

        return ApiResult.success(result);
    }

    /**
     * 查询 AI 服务配置状态。
     * <p>
     * 接口地址：{@code GET /api/skills/ai-status}
     * <p>
     * 用于前端判断当前系统是否已正确配置 AI 相关参数（如 API Key），
     * 从而决定是否展示 AI 搜索、自动选择等功能入口。
     *
     * @return 包含 {@code configured} 字段的统一响应结果，true 表示已配置
     */
    @GetMapping("/ai-status")
    public ApiResult<Map<String, Object>> getAiStatus() {
        Map<String, Object> status = new HashMap<>();
        // 检测 AI 服务是否已配置
        status.put("configured", aiService.isConfigured());
        return ApiResult.success(status);
    }

    /**
     * 将技能实体转换为摘要信息 Map。
     * <p>
     * 摘要信息包含技能的核心字段（ID、名称、描述、标签、输入输出类型、模型、
     * 文件数量、创建时间），不包含完整的文件内容，用于列表展示场景，
     * 可有效减少网络传输的数据量。
     *
     * @param skill 技能实体对象
     * @return 技能摘要信息的 Map
     */
    private Map<String, Object> toSummary(Skill skill) {
        Map<String, Object> summary = new HashMap<>();
        summary.put("id", skill.getId());
        summary.put("name", skill.getName());
        summary.put("description", skill.getDescription());
        summary.put("tags", skill.getTags());
        summary.put("inputType", skill.getInputType());
        summary.put("outputType", skill.getOutputType());
        summary.put("model", skill.getModel());
        // 文件数量为空时返回 0
        summary.put("fileCount", skill.getFiles() != null ? skill.getFiles().size() : 0);
        summary.put("createdAt", skill.getCreatedAt());
        return summary;
    }
}
