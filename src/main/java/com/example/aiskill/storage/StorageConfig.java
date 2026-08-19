package com.example.aiskill.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring 配置类，负责创建 {@link StorageService} Bean。
 *
 * <p>当前仅支持 FTP 存储后端。连接相关配置从 {@code storage.ftp.*} 配置属性中读取，
 * 各属性及默认值说明如下：</p>
 * <ul>
 *     <li>{@code storage.ftp.host}：FTP 服务器主机地址，默认 {@code localhost}</li>
 *     <li>{@code storage.ftp.port}：FTP 服务器端口，默认 {@code 21}</li>
 *     <li>{@code storage.ftp.username}：FTP 登录用户名，默认为空</li>
 *     <li>{@code storage.ftp.password}：FTP 登录密码，默认为空</li>
 *     <li>{@code storage.ftp.base-dir}：存储根目录的绝对路径，默认 {@code /skills}</li>
 * </ul>
 *
 * <p>这些配置项通常在 {@code application.yml} 或 {@code application.properties} 中定义。</p>
 *
 * @see FtpStorageService
 */
@Slf4j
@Configuration
public class StorageConfig {

    /**
     * 创建并配置 {@link StorageService} Bean（具体实现为 {@link FtpStorageService}）。
     *
     * <p>从 Spring 配置属性中注入 FTP 连接参数，并传入 {@link FtpStorageService} 构造方法。
     * Bean 创建后会触发 {@link FtpStorageService#init()} 方法进行存储后端初始化。</p>
     *
     * @param host     FTP 服务器主机地址（默认 {@code localhost}）
     * @param port     FTP 服务器端口（默认 {@code 21}）
     * @param username FTP 登录用户名（默认为空）
     * @param password FTP 登录密码（默认为空）
     * @param baseDir  存储根目录的绝对路径（默认 {@code /skills}）
     * @return 配置完成的 {@link StorageService} 实例
     */
    @Bean
    public StorageService storageService(
            @Value("${storage.ftp.host:localhost}") String host,
            @Value("${storage.ftp.port:21}") int port,
            @Value("${storage.ftp.username:}") String username,
            @Value("${storage.ftp.password:}") String password,
            @Value("${storage.ftp.base-dir:/skills}") String baseDir) {
        log.info("Creating FtpStorageService (host={}, port={}, base-dir={})", host, port, baseDir);
        return new FtpStorageService(host, port, username, password, baseDir);
    }
}
