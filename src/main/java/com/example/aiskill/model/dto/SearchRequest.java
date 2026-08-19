package com.example.aiskill.model.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * 技能搜索请求DTO（数据传输对象）。
 * <p>
 * 用于封装搜索技能时提交的参数，包括搜索描述和是否使用AI语义匹配的标志。
 * </p>
 *
 * @author example
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SearchRequest {

    /** 搜索描述文本，用户自然语言描述的需求 */
    private String description;

    /** 是否使用AI进行语义匹配（默认为 true） */
    private Boolean useAi;
}
