package com.example.aiskill.storage;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * 文件存储服务抽象层，定义了对 FTP 存储后端进行文件操作的统一接口。
 *
 * <p>所有路径均为相对于存储根目录的相对路径，并统一使用 {@code /} 作为路径分隔符，
 * 以保证跨平台兼容性。例如：{@code "skill_123/skill.md"}、
 * {@code "skill_123/materials/file.txt"}。</p>
 *
 * <p>该接口屏蔽了底层存储实现细节，便于后续扩展其他存储后端（如本地文件系统、
 * 对象存储等）。</p>
 *
 * @see FileInfo
 */
public interface StorageService {

    /**
     * 将字节数据存储到指定的相对路径。如果父目录不存在，将自动创建。
     *
     * @param path    相对路径（使用 {@code /} 作为分隔符）
     * @param content 待存储的字节数据
     * @throws IOException 如果写入内容失败
     */
    void store(String path, byte[] content) throws IOException;

    /**
     * 将输入流中的数据存储到指定的相对路径。适用于大文件上传，避免将整个文件加载到内存。
     * 如果父目录不存在，将自动创建。
     *
     * @param path       相对路径（使用 {@code /} 作为分隔符）
     * @param inputStream 待存储的数据输入流
     * @throws IOException 如果写入内容失败
     */
    void storeStream(String path, InputStream inputStream) throws IOException;

    /**
     * 读取指定相对路径下文件的字节内容。
     *
     * @param path 相对路径（使用 {@code /} 作为分隔符）
     * @return 文件内容（字节数组）
     * @throws IOException 如果读取文件失败
     */
    byte[] read(String path) throws IOException;

    /**
     * 判断指定相对路径下是否存在文件或目录。
     *
     * @param path 相对路径（使用 {@code /} 作为分隔符）
     * @return 如果路径存在则返回 {@code true}，否则返回 {@code false}
     */
    boolean exists(String path);

    /**
     * 删除指定相对路径下的文件或目录。当路径指向目录时，
     * 将递归删除整个子目录树。
     *
     * @param path 相对路径（使用 {@code /} 作为分隔符）
     * @throws IOException 如果删除路径失败
     */
    void delete(String path) throws IOException;

    /**
     * 在指定相对路径创建目录，包括所有缺失的父目录。
     * 如果目录已存在，则不执行任何操作。
     *
     * @param path 相对路径（使用 {@code /} 作为分隔符）
     * @throws IOException 如果创建目录失败
     */
    void createDirectory(String path) throws IOException;

    /**
     * 列出指定目录路径下所有直接子目录的名称。
     *
     * @param dirPath 待扫描目录的相对路径（使用 {@code /} 作为分隔符）
     * @return 子目录名称列表（仅名称，不包含完整路径）
     * @throws IOException 如果无法列出目录内容
     */
    List<String> listDirectories(String dirPath) throws IOException;

    /**
     * 列出指定目录路径下包含的所有文件。该列表为递归列表，
     * 包含所有嵌套子目录中的文件。
     *
     * @param dirPath 待扫描目录的相对路径（使用 {@code /} 作为分隔符）
     * @return 描述每个文件的 {@link FileInfo} 列表
     * @throws IOException 如果无法列出目录内容
     */
    List<FileInfo> listFiles(String dirPath) throws IOException;

    /**
     * 初始化存储后端，例如创建根目录或验证连接可用性。
     * 该方法在 Bean 初始化期间被调用一次。
     *
     * @throws IOException 如果初始化失败
     */
    void init() throws IOException;
}
