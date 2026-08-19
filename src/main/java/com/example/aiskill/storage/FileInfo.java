package com.example.aiskill.storage;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文件信息描述类，表示位于 {@link StorageService} 存储后端中某个文件或目录的轻量级描述。
 *
 * <p>该类主要用于 {@link StorageService#listFiles(String)} 方法的返回结果，
 * 提供文件的基本元信息。所有路径均为相对于存储根目录的相对路径，
 * 并统一使用 {@code /} 作为路径分隔符。</p>
 *
 * <p>使用 Lombok 的 {@link Data}、{@link NoArgsConstructor}、{@link AllArgsConstructor}
 * 注解自动生成 getter/setter、无参构造方法和全参构造方法。</p>
 *
 * @see StorageService#listFiles(String)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FileInfo {

    /** 文件或目录的名称（路径的最后一段，不含分隔符）。 */
    private String name;

    /** 相对于存储根目录的相对路径，使用 {@code /} 作为分隔符。 */
    private String path;

    /** 文件大小（字节数）。对于目录，该值通常为 0。 */
    private long size;

    /** 该条目是否表示一个目录。{@code true} 表示目录，{@code false} 表示文件。 */
    private boolean isDirectory;
}
