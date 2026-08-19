package com.example.aiskill.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 技能实体类，表示一个AI技能的完整定义。
 * <p>
 * 包含技能的基本信息（名称、描述、标签）、模型配置（模型名称、温度、最大Token数）、
 * Prompt模板内容、附属文件列表以及本地存储路径等信息。
 * </p>
 *
 * @author example
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Skill {

    /** 技能唯一标识符 */
    private String id;

    /** 技能名称 */
    private String name;

    /** 技能描述，用于说明技能的用途和功能 */
    private String description;

    /** 技能标签列表，用于分类和检索 */
    private List<String> tags;

    /** 输入类型，描述技能接受的输入数据格式 */
    private String inputType;

    /** 输出类型，描述技能产生的输出数据格式 */
    private String outputType;

    /** AI模型名称，如 gpt-4、qwen 等 */
    private String model;

    /** 采样温度，控制生成结果的随机性，值越高随机性越大 */
    private Double temperature;

    /** 最大生成Token数量限制 */
    private Integer maxTokens;

    /** Prompt模板正文内容（来自 skill.md 文件中 YAML 前置元数据之后的部分） */
    private String promptTemplate;

    /** 属于该技能的文件列表 */
    private List<SkillFile> files;

    /** 技能目录的本地存储路径 */
    private String storagePath;

    /** 技能创建时间 */
    private LocalDateTime createdAt;

    /**
     * 技能附属文件实体类，表示技能目录下的单个文件信息。
     * <p>
     * 记录文件的名称、类型、大小和相对路径等元数据信息。
     * </p>
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SkillFile {

        /** 文件名称 */
        private String name;

        /** 文件类型：material（素材）| script（脚本）| md（Markdown）| other（其他） */
        private String type;

        /** 文件大小（字节） */
        private long size;

        /** 文件相对于技能目录的相对路径 */
        private String relativePath;
    }
}
