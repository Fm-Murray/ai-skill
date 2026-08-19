package com.example.aiskill.storage;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;

import javax.annotation.PostConstruct;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于 FTP 协议实现的 {@link StorageService} 存储服务。
 *
 * <p>连接配置从 {@code storage.ftp.host/port/username/password/base-dir} 配置项中读取。
 * 为了保证实现简单且对连接断开具有更好的容错能力，每个操作都会开启一个短生命周期的
 * FTP 连接（即每次操作都执行连接/断开循环），而非维持长连接。</p>
 *
 * <p>所有对外暴露的路径均为相对于 {@code base-dir} 的相对路径，并统一使用 {@code /}
 * 作为路径分隔符。内部会负责将相对路径转换为 FTP 服务器上的绝对路径。</p>
 *
 * <p>该类由 {@link StorageConfig} 作为 Spring Bean 进行实例化，因此<b>故意</b>没有
 * 添加 {@code @Component} 注解。</p>
 *
 * <p><b>连接管理说明：</b>采用"每次操作新建连接"的策略，每次操作完成后都会在
 * {@code finally} 块中安全关闭连接，避免连接泄漏。这种方式虽然在性能上有一定开销，
 * 但能有效避免 FTP 服务器因空闲连接超时而断开连接导致的问题。</p>
 *
 * @see StorageService
 * @see StorageConfig
 */
@Slf4j
public class FtpStorageService implements StorageService {

    /** FTP 服务器主机地址。 */
    private final String host;
    /** FTP 服务器端口。 */
    private final int port;
    /** FTP 登录用户名。 */
    private final String username;
    /** FTP 登录密码。 */
    private final String password;
    /** 存储根目录在 FTP 服务器上的绝对路径（已规范化，以 {@code /} 开头且无尾部斜杠）。 */
    private final String baseDir;

    /**
     * 构造方法，初始化 FTP 存储服务的连接参数。
     *
     * @param host     FTP 服务器主机地址
     * @param port     FTP 服务器端口
     * @param username FTP 登录用户名
     * @param password FTP 登录密码
     * @param baseDir  存储根目录的绝对路径，会被规范化处理
     */
    public FtpStorageService(String host, int port, String username, String password, String baseDir) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.baseDir = normalizeDir(baseDir); // 对根目录路径进行规范化
    }

    /**
     * 规范化目录路径：确保始终以 {@code /} 开头，且不包含尾部斜杠。
     *
     * <p>同时会将 Windows 风格的反斜杠 {@code \} 转换为正斜杠 {@code /}。
     * 空路径或 {@code null} 会被视为根目录 {@code /}。</p>
     *
     * @param dir 待规范化的目录路径
     * @return 规范化后的目录路径
     */
    private static String normalizeDir(String dir) {
        if (dir == null || dir.isEmpty()) {
            return "/";
        }
        String d = dir.replace("\\", "/"); // 统一使用正斜杠
        if (!d.startsWith("/")) {
            d = "/" + d; // 确保以 / 开头
        }
        while (d.length() > 1 && d.endsWith("/")) {
            d = d.substring(0, d.length() - 1); // 移除尾部斜杠
        }
        return d;
    }

    /**
     * 规范化相对路径：使用 {@code /} 作为分隔符，并移除前导斜杠。
     *
     * <p>空路径、{@code null} 或根路径 {@code /} 会被视为空字符串（即根目录）。
     * 同时会移除尾部的斜杠。</p>
     *
     * @param path 待规范化的相对路径
     * @return 规范化后的相对路径（无前导/尾部斜杠），根目录返回空字符串
     */
    private static String normalizeRelative(String path) {
        if (path == null || path.isEmpty() || path.equals("/")) {
            return "";
        }
        String p = path.replace("\\", "/"); // 统一使用正斜杠
        while (p.startsWith("/")) {
            p = p.substring(1); // 移除前导斜杠
        }
        while (p.endsWith("/") && p.length() > 0) {
            p = p.substring(0, p.length() - 1); // 移除尾部斜杠
        }
        return p;
    }

    /**
     * 将相对存储路径转换为 FTP 服务器上的绝对路径。
     *
     * <p>拼接规则为 {@code baseDir + "/" + 相对路径}。如果相对路径为空（即根目录），
     * 则直接返回 {@code baseDir}。</p>
     *
     * @param relativePath 相对存储路径
     * @return FTP 服务器上的绝对路径
     */
    private String toAbsolute(String relativePath) {
        String rel = normalizeRelative(relativePath);
        if (rel.isEmpty()) {
            return baseDir; // 相对路径为空，直接返回根目录
        }
        return baseDir + "/" + rel;
    }

    /**
     * 创建并连接一个新的 FTP 客户端实例。
     *
     * <p>连接成功后会进行如下设置：</p>
     * <ul>
     *     <li>设置文件传输类型为二进制（{@link FTP#BINARY_FILE_TYPE}），避免文本文件被错误转换</li>
     *     <li>进入本地被动模式（{@code enterLocalPassiveMode}），适用于大部分位于防火墙/NAT 后的客户端</li>
     *     <li>设置控制连接保活超时为 60 秒，防止长时间传输时控制连接被关闭</li>
     * </ul>
     *
     * <p><b>注意：</b>调用方负责在操作完成后调用 {@link #disconnectQuietly(FTPClient)} 断开连接。</p>
     *
     * @return 已连接并登录的 FTPClient 实例
     * @throws IOException 如果连接或登录失败
     */
    private FTPClient connect() throws IOException {
        FTPClient client = new FTPClient();
        try {
            // 连接超时：30 秒内无法建立连接则失败
            client.setConnectTimeout(30000);
            client.connect(host, port); // 建立 TCP 连接
            client.login(username, password); // 登录 FTP 服务器
            client.setFileType(FTP.BINARY_FILE_TYPE); // 使用二进制模式传输，保证文件内容不被篡改
            client.enterLocalPassiveMode(); // 使用被动模式，便于穿越防火墙/NAT
            // 设置大数据传输缓冲区（默认仅 1024 字节，大文件传输极慢且易超时）
            client.setBufferSize(1024 * 1024); // 1MB 缓冲区
            // 数据传输超时：5 分钟，保证大文件有足够传输时间
            client.setDataTimeout(300000);
            // 控制连接保活：传输期间每 60 秒发送一次 NOOP 命令，防止连接被关闭
            client.setControlKeepAliveTimeout(60);
            log.debug("Connected to FTP {}:{} as {}", host, port, username);
            return client;
        } catch (IOException e) {
            // 连接失败时，尝试断开以释放资源（忽略断开时的异常）
            try {
                client.disconnect();
            } catch (IOException ignored) {
                // 忽略关闭时的错误
            }
            throw new IOException("Failed to connect to FTP " + host + ":" + port, e);
        }
    }

    /**
     * 安全断开 FTP 客户端连接，仅记录日志而不抛出异常。
     *
     * <p>如果客户端为 {@code null} 则直接返回。如果客户端仍处于连接状态，
     * 会先执行登出操作再断开连接。任何断开过程中的异常都会被捕获并记录为调试日志，
     * 不会影响主流程。</p>
     *
     * @param client 待断开的 FTP 客户端，可为 {@code null}
     */
    private void disconnectQuietly(FTPClient client) {
        if (client == null) {
            return;
        }
        try {
            if (client.isConnected()) {
                client.logout();     // 发送 QUIT 命令，优雅登出
                client.disconnect(); // 断开 TCP 连接
            }
        } catch (IOException e) {
            log.debug("Error while disconnecting FTP client", e);
        }
    }

    /**
     * 在 FTP 服务器上递归创建目录路径（类似 Unix 的 {@code mkdir -p} 命令）。
     *
     * <p>首先检查目标目录是否已存在，若存在则直接返回。否则将路径按 {@code /} 拆分为
     * 多级目录，逐级创建。</p>
     *
     * @param client      已连接的 FTP 客户端
     * @param absoluteDir 要创建的目录的绝对路径
     * @throws IOException 如果创建目录失败
     */
    private void makeDirs(FTPClient client, String absoluteDir) throws IOException {
        String normalized = normalizeDir(absoluteDir);
        if (normalized.equals("/") || normalized.isEmpty()) {
            return; // 根目录无需创建
        }
        // 先检查目录是否已存在
        if (client.changeWorkingDirectory(normalized)) {
            client.changeWorkingDirectory("/"); // 切回根目录，避免影响后续操作
            return;
        }
        String[] parts = normalized.substring(1).split("/"); // 移除开头的 / 后按 / 拆分
        StringBuilder current = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue; // 跳过空段（连续斜杠的情况）
            current.append("/").append(part);
            String dir = current.toString();
            // 尝试切换到该目录，如果切换失败则尝试创建
            if (!client.changeWorkingDirectory(dir)) {
                if (!client.makeDirectory(dir)) {
                    throw new IOException("Failed to create FTP directory: " + dir
                            + " (reply: " + client.getReplyString() + ")");
                }
            }
        }
    }

    /**
     * 初始化 FTP 存储后端。
     *
     * <p>由 Spring 的 {@link PostConstruct} 注解触发，在 Bean 创建后自动执行。
     * 主要工作是连接 FTP 服务器并确保存储根目录 {@code baseDir} 存在（不存在则创建）。</p>
     *
     * <p>如果初始化失败（如无法连接 FTP 或创建目录失败），将抛出
     * {@link IllegalStateException} 阻止应用启动。</p>
     */
    @PostConstruct
    @Override
    public void init() {
        FTPClient client = null;
        try {
            client = connect(); // 建立 FTP 连接
            makeDirs(client, baseDir); // 确保根目录存在
            log.info("FTP storage initialized: {}:{}{}", host, port, baseDir);
        } catch (IOException e) {
            log.error("Failed to initialize FTP storage: {}", e.getMessage(), e);
            throw new IllegalStateException("Failed to initialize FTP storage", e);
        } finally {
            disconnectQuietly(client); // 无论成功与否，都安全关闭连接
        }
    }

    /**
     * 将字节数据存储到指定的相对路径。
     *
     * <p>实现流程：</p>
     * <ol>
     *     <li>将相对路径转换为 FTP 绝对路径</li>
     *     <li>确保父目录存在（不存在则递归创建）</li>
     *     <li>通过 {@code storeFile} 上传文件内容</li>
     * </ol>
     *
     * @param path    相对路径（使用 {@code /} 作为分隔符）
     * @param content 待存储的字节数据
     * @throws IOException 如果存储文件失败
     */
    @Override
    public void store(String path, byte[] content) throws IOException {
        String absolute = toAbsolute(path); // 转换为绝对路径
        String parent = parentDir(absolute); // 获取父目录路径
        FTPClient client = null;
        try {
            client = connect();
            if (parent != null) {
                makeDirs(client, parent); // 确保父目录存在
            }
            try (InputStream in = new ByteArrayInputStream(content)) {
                boolean ok = client.storeFile(absolute, in); // 上传文件
                if (!ok) {
                    throw new IOException("Failed to store file: " + absolute
                            + " (reply: " + client.getReplyString() + ")");
                }
            }
        } finally {
            disconnectQuietly(client);
        }
    }

    /**
     * 将输入流中的数据流式存储到指定的相对路径。
     *
     * <p>与 {@link #store(String, byte[])} 不同，此方法不会将整个文件加载到内存中，
     * 而是直接将输入流传输到 FTP 服务器，适用于大文件上传场景。</p>
     *
     * @param path        相对路径（使用 {@code /} 作为分隔符）
     * @param inputStream 待存储的数据输入流（调用方负责关闭）
     * @throws IOException 如果存储文件失败
     */
    @Override
    public void storeStream(String path, InputStream inputStream) throws IOException {
        String absolute = toAbsolute(path);
        String parent = parentDir(absolute);
        FTPClient client = null;
        try {
            client = connect();
            if (parent != null) {
                makeDirs(client, parent);
            }
            // 直接将输入流传输到 FTP，不在内存中缓存整个文件
            boolean ok = client.storeFile(absolute, inputStream);
            if (!ok) {
                throw new IOException("Failed to store file (stream): " + absolute
                        + " (reply: " + client.getReplyString() + ")");
            }
        } finally {
            disconnectQuietly(client);
        }
    }

    /**
     * 读取指定相对路径下文件的字节内容。
     *
     * <p>使用 {@code retrieveFile} 将文件内容下载到内存中的
     * {@link ByteArrayOutputStream}，然后转换为字节数组返回。</p>
     *
     * @param path 相对路径（使用 {@code /} 作为分隔符）
     * @return 文件内容（字节数组）
     * @throws IOException 如果读取文件失败
     */
    @Override
    public byte[] read(String path) throws IOException {
        String absolute = toAbsolute(path); // 转换为绝对路径
        FTPClient client = null;
        try {
            client = connect();
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                boolean ok = client.retrieveFile(absolute, out); // 下载文件到内存
                if (!ok) {
                    throw new IOException("Failed to read file: " + absolute
                            + " (reply: " + client.getReplyString() + ")");
                }
                return out.toByteArray();
            }
        } finally {
            disconnectQuietly(client);
        }
    }

    /**
     * 判断指定相对路径下是否存在文件或目录。
     *
     * <p>判断逻辑如下：</p>
     * <ol>
     *     <li>如果路径为根目录，则通过 {@code changeWorkingDirectory} 验证根目录是否存在</li>
     *     <li>对于非根路径，先尝试作为文件判断：切换到父目录后用 {@code listFiles} 查找同名文件</li>
     *     <li>若文件查找失败，再尝试作为目录判断：通过 {@code changeWorkingDirectory} 切换到该路径</li>
     * </ol>
     *
     * @param path 相对路径（使用 {@code /} 作为分隔符）
     * @return 如果路径存在则返回 {@code true}，否则返回 {@code false}
     */
    @Override
    public boolean exists(String path) {
        String rel = normalizeRelative(path);
        if (rel.isEmpty()) {
            // 根目录：通过切换工作目录来验证其是否存在
            FTPClient client = null;
            try {
                client = connect();
                return client.changeWorkingDirectory(baseDir);
            } catch (IOException e) {
                log.warn("Failed to check existence of FTP root", e);
                return false;
            } finally {
                disconnectQuietly(client);
            }
        }
        String absolute = toAbsolute(path);
        FTPClient client = null;
        try {
            client = connect();
            // 首先尝试作为文件判断：通过精确文件名在父目录中查找
            String parent = parentDir(absolute);
            String name = lastSegment(absolute);
            if (parent != null && client.changeWorkingDirectory(parent)) {
                FTPFile[] files = client.listFiles(name);
                if (files != null && files.length > 0) {
                    return true; // 找到同名文件，确认存在
                }
            }
            // 文件查找失败，尝试作为目录判断
            return client.changeWorkingDirectory(absolute);
        } catch (IOException e) {
            log.warn("Failed to check existence of FTP path: {}", path, e);
            return false;
        } finally {
            disconnectQuietly(client);
        }
    }

    /**
     * 删除指定相对路径下的文件或目录。
     *
     * <p>删除逻辑：</p>
     * <ol>
     *     <li>尝试切换到该路径，若切换成功则视为目录，递归删除整个子目录树</li>
     *     <li>若切换失败则视为文件，调用 {@code deleteFile} 删除</li>
     *     <li>如果文件删除失败但路径实际上不存在，则视为无操作（幂等）</li>
     * </ol>
     *
     * @param path 相对路径（使用 {@code /} 作为分隔符）
     * @throws IOException 如果删除路径失败
     */
    @Override
    public void delete(String path) throws IOException {
        String absolute = toAbsolute(path);
        FTPClient client = null;
        try {
            client = connect();
            // 先判断路径是目录还是文件
            if (client.changeWorkingDirectory(absolute)) {
                // 切换成功，说明是目录
                client.changeWorkingDirectory("/"); // 切回根目录，避免影响递归删除
                deleteDirectoryRecursive(client, absolute);
            } else {
                // 切换失败，视为文件进行删除
                boolean ok = client.deleteFile(absolute);
                if (!ok) {
                    // 删除失败，可能路径不存在；如果确实不存在则视为无操作
                    FTPFile[] files = client.listFiles(absolute);
                    if (files != null && files.length == 0) {
                        log.debug("FTP path does not exist, skip delete: {}", absolute);
                        return;
                    }
                    throw new IOException("Failed to delete FTP file: " + absolute
                            + " (reply: " + client.getReplyString() + ")");
                }
            }
        } finally {
            disconnectQuietly(client);
        }
    }

    /**
     * 递归删除 FTP 服务器上的目录及其所有子内容。
     *
     * <p>实现步骤：</p>
     * <ol>
     *     <li>切换到目标目录</li>
     *     <li>列出目录下所有子项</li>
     *     <li>对每个子项：如果是目录则递归删除，如果是文件则直接删除</li>
     *     <li>最后切换回根目录并删除空目录本身</li>
     * </ol>
     *
     * @param client      已连接的 FTP 客户端
     * @param absoluteDir 要删除的目录的绝对路径
     * @throws IOException 如果删除过程中发生 I/O 错误
     */
    private void deleteDirectoryRecursive(FTPClient client, String absoluteDir) throws IOException {
        if (!client.changeWorkingDirectory(absoluteDir)) {
            return; // 目录不存在或无法切换，直接返回
        }
        FTPFile[] children = client.listFiles(); // 列出当前目录下所有子项
        if (children != null) {
            for (FTPFile child : children) {
                String name = child.getName();
                // 跳过当前目录和上级目录的特殊标记
                if (name.equals(".") || name.equals("..")) {
                    continue;
                }
                String childPath = absoluteDir + "/" + name;
                if (child.isDirectory()) {
                    deleteDirectoryRecursive(client, childPath); // 递归删除子目录
                } else {
                    client.deleteFile(childPath); // 删除文件
                }
            }
        }
        client.changeWorkingDirectory("/"); // 切回根目录
        client.removeDirectory(absoluteDir); // 删除已清空的目录本身
    }

    /**
     * 在指定相对路径创建目录，包括所有缺失的父目录。
     *
     * <p>内部委托给 {@link #makeDirs(FTPClient, String)} 实现递归创建。
     * 如果目录已存在，则不执行任何操作。</p>
     *
     * @param path 相对路径（使用 {@code /} 作为分隔符）
     * @throws IOException 如果创建目录失败
     */
    @Override
    public void createDirectory(String path) throws IOException {
        String absolute = toAbsolute(path);
        FTPClient client = null;
        try {
            client = connect();
            makeDirs(client, absolute);
        } finally {
            disconnectQuietly(client);
        }
    }

    /**
     * 列出指定目录路径下所有直接子目录的名称。
     *
     * <p>仅返回直接子目录的名称（不包含完整路径），不进行递归。
     * 会自动过滤掉 {@code .} 和 {@code ..} 特殊目录标记。</p>
     *
     * @param dirPath 待扫描目录的相对路径（使用 {@code /} 作为分隔符）
     * @return 子目录名称列表（仅名称，不包含完整路径）；若目录不存在则返回空列表
     * @throws IOException 如果无法列出目录内容
     */
    @Override
    public List<String> listDirectories(String dirPath) throws IOException {
        String absolute = toAbsolute(dirPath);
        List<String> result = new ArrayList<>();
        FTPClient client = null;
        try {
            client = connect();
            if (!client.changeWorkingDirectory(absolute)) {
                return result; // 目录不存在，返回空列表
            }
            FTPFile[] files = client.listFiles();
            if (files == null) {
                return result;
            }
            for (FTPFile f : files) {
                // 仅收集目录，并排除 . 和 .. 特殊标记
                if (f.isDirectory() && !".".equals(f.getName()) && !"..".equals(f.getName())) {
                    result.add(f.getName());
                }
            }
        } finally {
            disconnectQuietly(client);
        }
        return result;
    }

    /**
     * 递归列出指定目录路径下包含的所有文件。
     *
     * <p>该列表为递归列表，包含所有嵌套子目录中的文件和目录。
     * 内部委托给 {@link #listFilesRecursive(FTPClient, String, String, List)} 实现。</p>
     *
     * @param dirPath 待扫描目录的相对路径（使用 {@code /} 作为分隔符）
     * @return 描述每个文件的 {@link FileInfo} 列表
     * @throws IOException 如果无法列出目录内容
     */
    @Override
    public List<FileInfo> listFiles(String dirPath) throws IOException {
        String absolute = toAbsolute(dirPath);
        List<FileInfo> result = new ArrayList<>();
        String relativeRoot = normalizeRelative(dirPath); // 保留相对路径根，用于构建相对路径
        FTPClient client = null;
        try {
            client = connect();
            listFilesRecursive(client, absolute, relativeRoot, result);
        } finally {
            disconnectQuietly(client);
        }
        return result;
    }

    /**
     * 递归遍历 FTP 目录，收集所有文件和目录信息到结果列表中。
     *
     * <p>对每个子项：</p>
     * <ul>
     *     <li>如果是目录，添加一条目录信息（大小为 0），然后递归进入该子目录</li>
     *     <li>如果是文件，添加一条文件信息（包含实际文件大小）</li>
     * </ul>
     *
     * <p>所有收集的相对路径都基于传入的 {@code relativeDir} 进行构建。</p>
     *
     * @param client      已连接的 FTP 客户端
     * @param absoluteDir 当前正在遍历的目录的绝对路径
     * @param relativeDir 当前目录对应的相对路径（相对于存储根目录）
     * @param result      用于收集结果的列表
     * @throws IOException 如果遍历过程中发生 I/O 错误
     */
    private void listFilesRecursive(FTPClient client, String absoluteDir, String relativeDir,
                                    List<FileInfo> result) throws IOException {
        if (!client.changeWorkingDirectory(absoluteDir)) {
            return; // 目录不存在或无法切换，直接返回
        }
        FTPFile[] files = client.listFiles();
        if (files == null) {
            return;
        }
        for (FTPFile f : files) {
            String name = f.getName();
            // 跳过当前目录和上级目录的特殊标记
            if (name.equals(".") || name.equals("..")) {
                continue;
            }
            // 构建相对于存储根目录的路径
            String relative = relativeDir.isEmpty() ? name : relativeDir + "/" + name;
            if (f.isDirectory()) {
                result.add(new FileInfo(name, relative, 0L, true));
                listFilesRecursive(client, absoluteDir + "/" + name, relative, result); // 递归进入子目录
            } else {
                result.add(new FileInfo(name, relative, f.getSize(), false));
            }
        }
    }

    /**
     * 返回绝对路径的父目录路径。
     *
     * <p>先移除路径末尾的所有斜杠，然后取最后一个 {@code /} 之前的部分。
     * 如果路径没有父目录（如根路径 {@code /}），则返回 {@code null}。</p>
     *
     * @param absolutePath 绝对路径
     * @return 父目录路径，若无父目录则返回 {@code null}
     */
    private static String parentDir(String absolutePath) {
        if (absolutePath == null) return null;
        String p = absolutePath;
        while (p.endsWith("/") && p.length() > 1) {
            p = p.substring(0, p.length() - 1); // 移除尾部斜杠
        }
        int idx = p.lastIndexOf('/');
        if (idx <= 0) {
            return null; // 无父目录
        }
        return p.substring(0, idx);
    }

    /**
     * 返回绝对路径的最后一段（即文件名或目录名）。
     *
     * <p>先移除路径末尾的所有斜杠，然后取最后一个 {@code /} 之后的部分。</p>
     *
     * @param absolutePath 绝对路径
     * @return 路径的最后一段；如果路径为 {@code null} 则返回空字符串
     */
    private static String lastSegment(String absolutePath) {
        if (absolutePath == null) return "";
        String p = absolutePath;
        while (p.endsWith("/") && p.length() > 1) {
            p = p.substring(0, p.length() - 1); // 移除尾部斜杠
        }
        int idx = p.lastIndexOf('/');
        return idx >= 0 ? p.substring(idx + 1) : p;
    }

    /**
     * 返回该服务实例的字符串表示，主要用于调试和测试。
     *
     * @return 包含 host、port、username、baseDir 信息的字符串
     */
    @Override
    public String toString() {
        return "FtpStorageService{host='" + host + "', port=" + port
                + ", username='" + username + "', baseDir='" + baseDir + "'}";
    }
}
