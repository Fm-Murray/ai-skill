package com.example.aiskill.service;

import com.example.aiskill.model.Skill;
import com.example.aiskill.model.SkillIndex;
import com.example.aiskill.storage.FileInfo;
import com.example.aiskill.storage.StorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.yaml.snakeyaml.Yaml;

import javax.annotation.PostConstruct;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 技能服务类，负责技能的完整生命周期管理。
 *
 * <p>该服务提供以下核心能力：</p>
 * <ul>
 *     <li>技能上传与注册：支持松散文件和压缩包（zip、tar.gz、tar、tgz）两种方式</li>
 *     <li>技能解析：解析 skill.md 中的 YAML front matter 和提示词模板</li>
 *     <li>技能检索：支持 AI 语义匹配和关键词匹配两种搜索方式，并支持中英文跨语言匹配</li>
 *     <li>技能文件管理：读取、列举、分类（脚本/素材/其他）技能目录下的文件</li>
 *     <li>技能持久化：通过 {@link StorageService} 抽象层将技能元数据和文件持久化到存储后端</li>
 * </ul>
 *
 * <p>技能在启动时通过 {@link #init()} 方法从存储后端加载到内存缓存 {@link #skillCache} 中，
 * 后续操作优先访问内存缓存以提高性能。</p>
 *
 * @see AiService
 * @see StorageService
 */
@Slf4j
@Service
public class SkillService {

    /** AI 服务，用于技能的语义搜索和自动选择 */
    private final AiService aiService;
    /** 存储服务，提供文件和目录的持久化能力 */
    private final StorageService storage;
    /** JSON 序列化/反序列化器，用于读写 skill 的 meta.json 元数据 */
    private final ObjectMapper objectMapper;

    /** 内存中的技能索引：skillId -> Skill 对象，使用并发安全 Map 保证线程安全 */
    private final Map<String, Skill> skillCache = new ConcurrentHashMap<>();

    /** 脚本文件扩展名集合，匹配这些扩展名的文件会被归类为"脚本"类型 */
    private static final Set<String> SCRIPT_EXTENSIONS = new HashSet<>(Arrays.asList(
            ".py", ".sh", ".js", ".rb", ".pl", ".go", ".java", ".bat"
    ));

    /** 素材文件扩展名集合，匹配这些扩展名的文件会被归类为"素材"类型 */
    private static final Set<String> MATERIAL_EXTENSIONS = new HashSet<>(Arrays.asList(
            ".txt", ".md", ".pdf", ".docx", ".doc", ".csv", ".json", ".xml", ".html", ".yaml", ".yml"
    ));

    /** 压缩包扩展名集合，匹配这些扩展名的文件会被识别为压缩包并解压处理 */
    private static final Set<String> ARCHIVE_EXTENSIONS = new HashSet<>(Arrays.asList(
            ".zip", ".tar.gz", ".tgz", ".tar"
    ));

    /**
     * 构造函数，通过 Spring 依赖注入初始化服务。
     *
     * @param aiService AI 服务实例
     * @param storage   存储服务实例
     */
    public SkillService(AiService aiService, StorageService storage) {
        this.aiService = aiService;
        this.storage = storage;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 初始化方法，在 Spring Bean 构造完成后由 {@link PostConstruct} 触发执行。
     *
     * <p>该方法负责从存储后端加载所有已存在的技能到内存缓存 {@link #skillCache} 中，
     * 以便后续操作可以直接访问内存缓存，提高响应速度。</p>
     *
     * <p>加载流程：</p>
     * <ol>
     *     <li>确保存储根目录存在</li>
     *     <li>列出根目录下的所有技能目录</li>
     *     <li>逐个加载每个技能（优先读取 meta.json，回退到解析 skill.md）</li>
     *     <li>将加载成功的技能放入内存缓存</li>
     * </ol>
     *
     * <p>单个技能加载失败不会影响其他技能的加载，只会记录警告日志。</p>
     */
    @PostConstruct
    public void init() {
        try {
            // 存储后端会通过自身的 @PostConstruct 初始化根目录，
            // 但这里再次确保根目录存在，以防初始化顺序问题。
            storage.createDirectory("");

            // 列出根目录下的所有技能目录（每个子目录代表一个技能）
            List<String> skillDirs = storage.listDirectories("");
            if (skillDirs != null) {
                for (String skillId : skillDirs) {
                    try {
                        // 尝试从存储中加载单个技能
                        Skill skill = loadSkillFromStorage(skillId);
                        if (skill != null) {
                            skillCache.put(skill.getId(), skill);
                            log.info("Loaded skill: {} ({})", skill.getName(), skill.getId());
                        }
                    } catch (Exception e) {
                        // 单个技能加载失败不影响其他技能，仅记录警告
                        log.warn("Failed to load skill from: {}", skillId, e);
                    }
                }
            }
            log.info("Loaded {} skills from storage", skillCache.size());
        } catch (Exception e) {
            // 整体初始化失败时记录错误日志，但不抛出异常以避免应用启动失败
            log.error("Failed to initialize skill storage", e);
        }
    }

    /**
     * 上传并注册一个新的技能。
     *
     * <p>支持两种上传方式：</p>
     * <ul>
     *     <li>松散文件：直接上传多个文件，必须包含 skill.md</li>
     *     <li>压缩包：上传 zip、tar.gz、tar、tgz 格式的压缩文件，
     *         系统会自动解压并验证是否包含 skill.md</li>
     * </ul>
     *
     * <p>该方法会根据上传文件中是否包含压缩包自动选择处理方式。</p>
     *
     * @param files 上传的文件数组
     * @return 注册成功的技能对象
     * @throws IOException          如果文件读写失败
     * @throws IllegalArgumentException 如果未提供文件或缺少必要的 skill.md 文件
     */
    public Skill uploadSkill(MultipartFile[] files) throws IOException {
        if (files == null || files.length == 0) {
            throw new IllegalArgumentException("未提供文件");
        }

        // 检查上传的文件中是否包含压缩包
        boolean hasArchive = false;
        for (MultipartFile f : files) {
            String name = f.getOriginalFilename();
            if (name != null && isArchive(name)) {
                hasArchive = true;
                break;
            }
        }

        // 根据是否包含压缩包选择不同的上传处理方式
        if (hasArchive) {
            return uploadSkillFromArchive(files);
        } else {
            return uploadSkillFromLooseFiles(files);
        }
    }

    /**
     * 通过松散文件上传技能（原始行为）。
     *
     * <p>处理流程：</p>
     * <ol>
     *     <li>在上传文件中查找 skill.md 文件（必需）</li>
     *     <li>生成技能 ID 并创建技能目录</li>
     *     <li>解析 skill.md 的 YAML front matter 和提示词模板</li>
     *     <li>将其他文件按类型分类存储到 scripts/ 或 materials/ 子目录</li>
     *     <li>保存技能元数据（meta.json）并更新内存缓存</li>
     * </ol>
     *
     * @param files 上传的文件数组，必须包含 skill.md
     * @return 注册成功的技能对象
     * @throws IOException          如果文件读写失败
     * @throws IllegalArgumentException 如果缺少 skill.md 文件
     */
    private Skill uploadSkillFromLooseFiles(MultipartFile[] files) throws IOException {
        // 在上传文件中查找 skill.md
        MultipartFile skillMdFile = null;
        for (MultipartFile f : files) {
            if ("skill.md".equalsIgnoreCase(f.getOriginalFilename())) {
                skillMdFile = f;
                break;
            }
        }

        if (skillMdFile == null) {
            throw new IllegalArgumentException("必须包含 skill.md 文件");
        }

        // 生成唯一的技能 ID（基于时间戳）
        String skillId = "skill_" + System.currentTimeMillis();
        String skillDir = skillId;
        storage.createDirectory(skillDir);

        // 解析 skill.md 内容，提取技能元数据和提示词模板（自动检测编码，兼容 UTF-8 和 GBK）
        String skillMdContent = readTextWithEncodingDetection(skillMdFile.getBytes());
        Skill skill = parseSkillMd(skillMdContent);
        skill.setId(skillId);
        skill.setStoragePath(skillDir);
        skill.setCreatedAt(LocalDateTime.now());

        // 保存 skill.md 到存储
        storage.store(skillDir + "/skill.md", skillMdContent.getBytes(StandardCharsets.UTF_8));

        // 保存并分类其他文件
        List<Skill.SkillFile> skillFiles = new ArrayList<>();
        // 创建 materials 和 scripts 子目录用于分类存储文件
        storage.createDirectory(skillDir + "/materials");
        storage.createDirectory(skillDir + "/scripts");

        for (MultipartFile file : files) {
            String filename = file.getOriginalFilename();
            // 跳过空文件名和 skill.md（已单独处理）
            if (filename == null || "skill.md".equalsIgnoreCase(filename)) {
                continue;
            }

            // 根据文件扩展名分类：脚本类放入 scripts/，其他放入 materials/
            String fileType = categorizeFile(filename);
            String relativePath;

            if ("script".equals(fileType)) {
                relativePath = "scripts/" + filename;
            } else {
                relativePath = "materials/" + filename;
            }

            // 使用流式上传，避免大文件全部加载到内存导致 OOM
            try (InputStream fis = file.getInputStream()) {
                storage.storeStream(skillDir + "/" + relativePath, fis);
            }

            skillFiles.add(new Skill.SkillFile(filename, fileType, file.getSize(), relativePath));
        }

        // 将 skill.md 添加到文件列表的首位
        skillFiles.add(0, new Skill.SkillFile("skill.md", "md", skillMdFile.getSize(), "skill.md"));
        skill.setFiles(skillFiles);

        // 保存技能元数据到 meta.json
        saveSkillMeta(skill);

        // 更新内存缓存
        skillCache.put(skillId, skill);
        log.info("Uploaded skill: {} ({}) with {} files", skill.getName(), skillId, skillFiles.size());

        return skill;
    }

    /**
     * 通过压缩包上传技能。解压压缩包，验证是否包含 skill.md，然后注册技能。
     *
     * <p>压缩包解压在本地临时目录中通过 java.nio.file 完成（仅用于临时处理），
     * 最终的文件会通过 {@link StorageService} 抽象层持久化到存储后端。</p>
     *
     * <p>处理流程：</p>
     * <ol>
     *     <li>创建本地临时目录</li>
     *     <li>遍历上传文件：压缩包先解压到临时目录，松散文件直接保存到临时目录</li>
     *     <li>验证解压结果中是否包含 skill.md（必需）</li>
     *     <li>解析 skill.md 并验证技能名称</li>
     *     <li>将所有文件分类后通过存储服务持久化</li>
     *     <li>清理临时目录</li>
     * </ol>
     *
     * @param files 上传的文件数组，至少包含一个压缩包
     * @return 注册成功的技能对象
     * @throws IOException          如果解压或文件读写失败
     * @throws IllegalArgumentException 如果压缩包无效或缺少 skill.md
     */
    private Skill uploadSkillFromArchive(MultipartFile[] files) throws IOException {
        // 创建本地临时目录用于解压压缩包（仅用于临时处理，最终会清理）
        Path tempDir = Files.createTempDirectory("skill-archive-");
        try {
            // 收集所有解压后的文件路径（绝对路径 + 文件名）
            List<ExtractedFile> extractedFiles = new ArrayList<>();

            for (MultipartFile file : files) {
                String filename = file.getOriginalFilename();
                if (filename == null) continue;

                if (isArchive(filename)) {
                    // 将压缩包保存到临时文件，然后解压
                    Path archivePath = tempDir.resolve(filename);
                    file.transferTo(archivePath.toFile());

                    // 为每个压缩包创建独立的解压目录
                    Path extractDir = tempDir.resolve("extract_" + System.currentTimeMillis());
                    Files.createDirectories(extractDir);

                    try {
                        extractArchive(archivePath.toFile(), extractDir);
                        log.info("Extracted archive: {} -> {}", filename, extractDir);
                    } catch (Exception e) {
                        throw new IOException("解压失败: " + filename + " - " + e.getMessage(), e);
                    }

                    // 递归收集解压目录中的所有文件
                    collectExtractedFiles(extractDir, extractedFiles);
                } else {
                    // 非压缩包文件直接保存到临时目录
                    Path savedPath = tempDir.resolve(filename);
                    file.transferTo(savedPath.toFile());
                    extractedFiles.add(new ExtractedFile(filename, savedPath, file.getSize()));
                }
            }

            if (extractedFiles.isEmpty()) {
                throw new IllegalArgumentException("压缩包解压后未找到任何文件");
            }

            // 在解压后的文件中查找 skill.md
            ExtractedFile skillMdExtracted = null;
            for (ExtractedFile ef : extractedFiles) {
                if (ef.filename.equalsIgnoreCase("skill.md")) {
                    skillMdExtracted = ef;
                    break;
                }
            }

            if (skillMdExtracted == null) {
                // 列出找到的文件以提供更有帮助的错误信息
                List<String> foundNames = new ArrayList<>();
                for (ExtractedFile ef : extractedFiles) {
                    foundNames.add(ef.filename);
                    // 最多列出 10 个文件名，避免错误信息过长
                    if (foundNames.size() >= 10) {
                        foundNames.add("...(更多文件省略)");
                        break;
                    }
                }
                throw new IllegalArgumentException(
                    "压缩包中未找到 skill.md 文件，不是有效的技能包。\n解压发现的文件: " + String.join(", ", foundNames)
                );
            }

            // 读取并解析 skill.md（自动检测编码，兼容 UTF-8 和 GBK）
            String skillMdContent = readTextWithEncodingDetection(Files.readAllBytes(skillMdExtracted.path));
            Skill skill = parseSkillMd(skillMdContent);

            // 验证技能至少包含名称字段
            if (skill.getName() == null || skill.getName().trim().isEmpty()) {
                throw new IllegalArgumentException("skill.md 中缺少 name 字段，不是有效的技能配置");
            }

            // 生成技能 ID 并创建技能目录
            String skillId = "skill_" + System.currentTimeMillis();
            String skillDir = skillId;
            storage.createDirectory(skillDir);

            skill.setId(skillId);
            skill.setStoragePath(skillDir);
            skill.setCreatedAt(LocalDateTime.now());

            // 保存 skill.md 到存储后端
            byte[] skillMdBytes = skillMdContent.getBytes(StandardCharsets.UTF_8);
            storage.store(skillDir + "/skill.md", skillMdBytes);

            // 保存并分类其他文件
            List<Skill.SkillFile> skillFiles = new ArrayList<>();
            // 创建 materials 和 scripts 子目录用于分类存储
            storage.createDirectory(skillDir + "/materials");
            storage.createDirectory(skillDir + "/scripts");

            // 将 skill.md 添加到文件列表首位
            skillFiles.add(new Skill.SkillFile("skill.md", "md",
                    (long) skillMdBytes.length, "skill.md"));

            for (ExtractedFile ef : extractedFiles) {
                // 跳过 skill.md（已单独处理）
                if (ef.filename.equalsIgnoreCase("skill.md")) {
                    continue;
                }

                // 根据文件扩展名分类
                String fileType = categorizeFile(ef.filename);
                String relativePath;

                if ("script".equals(fileType)) {
                    relativePath = "scripts/" + ef.filename;
                } else {
                    relativePath = "materials/" + ef.filename;
                }

                // 使用流式上传，避免大文件全部加载到内存导致 OOM
                try (InputStream fis = Files.newInputStream(ef.path)) {
                    storage.storeStream(skillDir + "/" + relativePath, fis);
                }

                skillFiles.add(new Skill.SkillFile(ef.filename, fileType, ef.size, relativePath));
            }

            skill.setFiles(skillFiles);
            // 保存技能元数据并更新内存缓存
            saveSkillMeta(skill);
            skillCache.put(skillId, skill);

            log.info("Uploaded skill from archive: {} ({}) with {} files", skill.getName(), skillId, skillFiles.size());
            return skill;

        } finally {
            // 清理临时目录（仅限本地文件系统操作）
            try {
                deleteDirectory(tempDir.toFile());
            } catch (Exception e) {
                log.warn("Failed to clean up temp directory: {}", tempDir, e);
            }
        }
    }

    /**
     * 列出所有技能（不包含提示词模板，以精简返回数据）。
     *
     * <p>结果按创建时间降序排列（最新的在前），null 值排在最后。</p>
     *
     * @return 所有技能的列表
     */
    public List<Skill> listSkills() {
        return skillCache.values().stream()
                .sorted(Comparator.comparing(Skill::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .collect(Collectors.toList());
    }

    /**
     * 根据技能 ID 获取技能对象。
     *
     * @param skillId 技能 ID
     * @return 技能对象，如果不存在则返回 null
     */
    public Skill getSkill(String skillId) {
        return skillCache.get(skillId);
    }

    /**
     * 根据技能 ID 删除技能。
     *
     * <p>删除操作包括：</p>
     * <ol>
     *     <li>从内存缓存中移除技能</li>
     *     <li>从存储后端删除技能目录及其所有文件</li>
     * </ol>
     *
     * @param skillId 技能 ID
     * @return 如果删除成功返回 true；如果技能不存在或删除失败返回 false
     */
    public boolean deleteSkill(String skillId) {
        // 先从内存缓存中移除
        Skill skill = skillCache.remove(skillId);
        if (skill == null) {
            return false;
        }

        try {
            // 删除存储后端中的技能目录
            String skillDir = skill.getStoragePath();
            if (storage.exists(skillDir)) {
                storage.delete(skillDir);
            }
            log.info("Deleted skill: {} ({})", skill.getName(), skillId);
            return true;
        } catch (Exception e) {
            log.error("Failed to delete skill directory: {}", skillId, e);
            return false;
        }
    }

    /**
     * 读取技能目录中某个文件的内容。
     *
     * @param skillId      技能 ID
     * @param relativePath 相对于技能目录的文件路径
     * @return 文件内容的字节数组
     * @throws IOException          如果文件无法读取
     * @throws IllegalArgumentException 如果技能不存在
     */
    public byte[] readSkillFile(String skillId, String relativePath) throws IOException {
        Skill skill = skillCache.get(skillId);
        if (skill == null) {
            throw new IllegalArgumentException("技能不存在: " + skillId);
        }
        String skillDir = skill.getStoragePath();
        // 拼接完整的存储路径
        String fullPath = skillDir + "/" + relativePath;
        return storage.read(fullPath);
    }

    /**
     * 递归列出技能目录中的所有文件，包括子目录中的文件
     * （这些文件可能未在 Skill.files 元数据中记录）。
     *
     * <p>返回的文件路径是相对于技能目录的相对路径。</p>
     *
     * @param skillId 技能 ID
     * @return 技能目录下所有文件的 FileInfo 列表
     * @throws IOException          如果无法列出目录
     * @throws IllegalArgumentException 如果技能不存在
     */
    public List<FileInfo> listAllSkillFiles(String skillId) throws IOException {
        Skill skill = skillCache.get(skillId);
        if (skill == null) {
            throw new IllegalArgumentException("技能不存在: " + skillId);
        }
        String skillDir = skill.getStoragePath();
        List<FileInfo> files = storage.listFiles(skillDir);
        // 去除技能目录前缀，使路径相对于技能目录
        String prefix = skillDir + "/";
        List<FileInfo> result = new ArrayList<>();
        for (FileInfo fi : files) {
            String relPath = fi.getPath();
            if (relPath.startsWith(prefix)) {
                relPath = relPath.substring(prefix.length());
            }
            result.add(new FileInfo(fi.getName(), relPath, fi.getSize(), fi.isDirectory()));
        }
        return result;
    }

    /**
     * 根据描述搜索技能。
     *
     * <p>如果启用了 AI 且 AI 服务可用，优先使用 AI 语义匹配；
     * 如果 AI 匹配失败或未启用，则回退到关键词匹配。</p>
     *
     * @param description 搜索描述文本
     * @param useAi       是否使用 AI 语义匹配
     * @return 匹配的技能列表，按相关度排序
     */
    public List<Skill> searchSkills(String description, boolean useAi) {
        List<Skill> allSkills = listSkills();
        if (allSkills.isEmpty()) {
            return Collections.emptyList();
        }

        // 优先尝试 AI 语义匹配
        if (useAi && aiService.isConfigured()) {
            try {
                List<String> rankedIds = aiService.searchSkillsByAi(description, allSkills);
                List<Skill> result = new ArrayList<>();
                // 根据 AI 返回的排序后的 ID 列表重建技能列表
                for (String id : rankedIds) {
                    Skill skill = skillCache.get(id);
                    if (skill != null) {
                        result.add(skill);
                    }
                }
                if (!result.isEmpty()) {
                    return result;
                }
            } catch (Exception e) {
                // AI 搜索失败时回退到关键词搜索
                log.warn("AI search failed, falling back to keyword search: {}", e.getMessage());
            }
        }

        // 回退方案：关键词匹配
        return keywordSearch(description, allSkills);
    }

    /**
     * 使用 AI 自动为给定输入选择最匹配的技能。
     *
     * <p>返回单个最佳匹配的技能，如果没有匹配的技能则返回 null。</p>
     *
     * <p>选择策略：</p>
     * <ol>
     *     <li>优先使用 AI 语义匹配，取排名第一的技能</li>
     *     <li>如果 AI 不可用或匹配失败，回退到关键词匹配，取排名第一的技能</li>
     * </ol>
     *
     * @param input 用户的输入内容/描述
     * @return 最匹配的技能，如果没有匹配则返回 null
     */
    public Skill autoSelectSkill(String input) {
        List<Skill> allSkills = listSkills();
        if (allSkills.isEmpty()) {
            return null;
        }

        // 优先尝试 AI 语义匹配
        if (aiService.isConfigured()) {
            try {
                List<String> rankedIds = aiService.searchSkillsByAi(input, allSkills);
                if (rankedIds != null && !rankedIds.isEmpty()) {
                    // 取排名第一的技能作为最佳匹配
                    Skill best = skillCache.get(rankedIds.get(0));
                    if (best != null) {
                        log.info("AI auto-selected skill: {} ({}) for input: {}",
                                best.getName(), best.getId(), input.substring(0, Math.min(input.length(), 80)));
                        return best;
                    }
                }
            } catch (Exception e) {
                // AI 自动选择失败时回退到关键词匹配
                log.warn("AI auto-select failed, falling back to keyword search: {}", e.getMessage());
            }
        }

        // 回退方案：关键词匹配，返回排名第一的匹配结果
        List<Skill> matched = keywordSearch(input, allSkills);
        if (!matched.isEmpty()) {
            Skill best = matched.get(0);
            log.info("Keyword auto-selected skill: {} ({}) for input: {}",
                    best.getName(), best.getId(), input.substring(0, Math.min(input.length(), 80)));
            return best;
        }

        return null;
    }

    /**
     * 加载技能索引：仅加载技能的 name + description，作为常驻上下文。
     *
     * <p>不加载完整技能文档（promptTemplate、files 等），控制 token 消耗。
     * 用于第一轮路由判断，快速判断要调用哪个 skill。</p>
     *
     * @return 技能索引列表（仅包含 id、name、description、tags）
     */
    public List<SkillIndex> loadSkillsIndex() {
        return skillCache.values().stream()
                .map(s -> new SkillIndex(
                        s.getId(),
                        s.getName(),
                        s.getDescription(),
                        s.getTags() != null ? String.join(", ", s.getTags()) : ""))
                .collect(Collectors.toList());
    }

    /**
     * 装配上下文：读取选中技能的完整 SKILL.md 正文，挂载引用材料，组装完整 prompt。
     *
     * <p>设计思路：</p>
     * <ol>
     *     <li>读取技能的 promptTemplate（来自 skill.md 的正文部分）</li>
     *     <li>读取所有素材文件内容（materials 目录下的文件）</li>
     *     <li>读取所有脚本文件内容（scripts 目录下的文件，作为上下文参考）</li>
     *     <li>替换 prompt 模板中的占位符：{input}、{materials}、{scripts}</li>
     * </ol>
     *
     * @param skill 选中的技能对象
     * @param input 用户的输入内容
     * @return 组装完成的完整 prompt
     */
    public String assembleContext(Skill skill, String input) {
        // 获取技能的 prompt 模板
        String template = skill.getPromptTemplate();
        if (template == null || template.trim().isEmpty()) {
            // 如果没有模板，使用默认模板
            template = "你是一个AI助手。请根据以下技能指令处理输入并返回结果。\n\n{input}";
        }

        // 读取素材文件内容
        String materials = readMaterials(skill);
        // 读取脚本文件内容（作为上下文参考，不执行）
        String scripts = readScripts(skill);

        // 替换 prompt 模板中的占位符
        String prompt = template;
        // 如果模板包含 {input} 占位符，直接替换；否则在末尾追加用户输入
        if (prompt.contains("{input}")) {
            prompt = prompt.replace("{input}", input != null ? input : "");
        } else {
            prompt += "\n\n--- 用户输入 ---\n" + (input != null ? input : "");
        }
        prompt = prompt.replace("{materials}", materials != null && !materials.isEmpty() ? materials : "（无参考资料）");
        prompt = prompt.replace("{scripts}", scripts != null && !scripts.isEmpty() ? scripts : "（无脚本文件）");

        // 检测是否有使用 argparse --input/--output 的脚本，如果有则追加 JSON 输出强制指令
        if (hasArgparseScriptPublic(skill)) {
            prompt += "\n\n" + buildJsonOutputInstruction(skill);
        }

        log.debug("上下文装配完成：技能={}, prompt长度={}", skill.getName(), prompt.length());
        return prompt;
    }

    /**
     * 检测技能是否包含使用 argparse --input/--output 参数的脚本。
     *
     * @param skill 技能对象
     * @return 如果包含 argparse 脚本返回 true，否则返回 false
     */
    public boolean hasArgparseScriptPublic(Skill skill) {
        if (skill.getFiles() == null) return false;
        for (Skill.SkillFile file : skill.getFiles()) {
            if (!"script".equals(file.getType())) continue;
            if (!file.getName().toLowerCase().endsWith(".py")) continue;
            try {
                String storagePath = skill.getStoragePath() + "/" + file.getRelativePath();
                // 自动检测编码，兼容 UTF-8 和 GBK 编码的脚本文件
                String content = readTextWithEncodingDetection(storage.read(storagePath));
                if (content.contains("argparse")
                        && (content.contains("--input") || content.contains("-i"))
                        && (content.contains("--output") || content.contains("-o"))) {
                    return true;
                }
            } catch (Exception e) {
                log.warn("读取脚本文件失败: {}", file.getName(), e);
            }
        }
        return false;
    }

    /**
     * 构建 JSON 输出强制指令。
     * 当技能包含 argparse 脚本时，追加此指令要求 AI 输出纯 JSON 格式结果。
     *
     * @param skill 技能对象
     * @return JSON 输出指令字符串
     */
    private String buildJsonOutputInstruction(Skill skill) {
        return "【重要输出格式要求】\n"
                + "你的输出将被传递给 Python 脚本进行处理，因此必须严格遵循以下规则：\n"
                + "1. 只输出纯 JSON 文本，不要包含任何 Markdown 代码块标记（不要使用 ```json 或 ```）\n"
                + "2. 不要输出任何解释性文字、问候语或额外说明\n"
                + "3. JSON 必须是合法的、可被 json.loads() 直接解析的格式\n"
                + "4. JSON 结构要求：\n"
                + "   {\n"
                + "     \"metadata\": { \"title\": \"标题\", \"author\": \"作者\", \"subject\": \"主题\", \"keywords\": \"关键词\" },\n"
                + "     \"slides\": [\n"
                + "       {\n"
                + "         \"layout\": \"TitleSlide|TitleAndContent|TwoColumnText|SectionHeader|ContentWithCaption|BulletList|BlankSlide\",\n"
                + "         \"title\": \"页面标题\",\n"
                + "         \"content\": [\"要点1\", \"要点2\", \"要点3\"],\n"
                + "         \"notes\": \"备注信息\"\n"
                + "       }\n"
                + "     ]\n"
                + "   }\n"
                + "5. slides 数组不能为空，每个 slide 必须包含 layout 和 title 字段\n"
                + "6. content 字段必须是字符串数组\n"
                + "7. 请根据用户需求生成完整的 PPT 内容（建议 8-15 页），不要只生成大纲\n";
    }

    /**
     * 读取技能中所有素材文件的内容。
     *
     * <p>将所有类型为 "material" 的文件内容拼接成一个字符串，
     * 每个文件内容以文件名标题分隔。</p>
     *
     * @param skill 技能对象
     * @return 所有素材文件内容的拼接字符串，如果没有素材则返回空字符串
     */
    public String readMaterials(Skill skill) {
        if (skill.getFiles() == null) return "";
        StringBuilder sb = new StringBuilder();
        for (Skill.SkillFile file : skill.getFiles()) {
            // 只读取素材类型的文件
            if ("material".equals(file.getType())) {
                try {
                    String storagePath = skill.getStoragePath() + "/" + file.getRelativePath();
                    // 自动检测编码，兼容 UTF-8 和 GBK 编码的素材文件
                    String content = readTextWithEncodingDetection(storage.read(storagePath));
                    // 以文件名作为标题分隔不同文件的内容
                    sb.append("=== ").append(file.getName()).append(" ===\n");
                    sb.append(content).append("\n\n");
                } catch (Exception e) {
                    log.warn("Failed to read material file: {}", file.getName(), e);
                }
            }
        }
        return sb.toString();
    }

    /**
     * 读取技能中所有脚本文件的内容（作为上下文信息，不执行）。
     *
     * <p>将所有类型为 "script" 的文件内容拼接成一个字符串，
     * 每个脚本内容以文件名标题和代码块标记（根据语言类型）分隔。</p>
     *
     * @param skill 技能对象
     * @return 所有脚本文件内容的拼接字符串，如果没有脚本则返回空字符串
     */
    public String readScripts(Skill skill) {
        if (skill.getFiles() == null) return "";
        StringBuilder sb = new StringBuilder();
        for (Skill.SkillFile file : skill.getFiles()) {
            // 只读取脚本类型的文件
            if ("script".equals(file.getType())) {
                try {
                    String storagePath = skill.getStoragePath() + "/" + file.getRelativePath();
                    // 自动检测编码，兼容 UTF-8 和 GBK 编码的脚本文件
                    String content = readTextWithEncodingDetection(storage.read(storagePath));
                    // 以 Markdown 代码块格式包裹脚本内容，并标注语言类型
                    sb.append("=== ").append(file.getName()).append(" ===\n");
                    sb.append("```").append(getLanguage(file.getName())).append("\n");
                    sb.append(content).append("\n```\n\n");
                } catch (Exception e) {
                    log.warn("Failed to read script file: {}", file.getName(), e);
                }
            }
        }
        return sb.toString();
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 智能检测字节数组的文本编码并转换为字符串。
     *
     * <p>编码检测策略：</p>
     * <ol>
     *     <li>检查 UTF-8 BOM（字节顺序标记 EF BB BF），如果存在则跳过 BOM 后按 UTF-8 解码</li>
     *     <li>尝试用 UTF-8 严格模式解码：如果成功则说明文件是 UTF-8 编码</li>
     *     <li>UTF-8 解码失败时回退到 GBK 编码（中文 Windows 系统常用的编码）</li>
     * </ol>
     *
     * <p>该方法可以有效处理 UTF-8 和 GBK/GB2312 编码的中文文本文件，
     * 解决上传的文档中文出现乱码的问题。</p>
     *
     * @param bytes 文件内容字节数组
     * @return 解码后的字符串；如果输入为空则返回空字符串
     */
    public static String readTextWithEncodingDetection(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }

        // 1. 检查 UTF-8 BOM (EF BB BF)
        if (bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }

        // 2. 尝试 UTF-8 严格解码（遇到非法字节序列会抛出异常）
        try {
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            CharBuffer result = decoder.decode(ByteBuffer.wrap(bytes));
            return result.toString();
        } catch (CharacterCodingException e) {
            // 3. UTF-8 解码失败，回退到 GBK 编码
            log.debug("UTF-8 decoding failed, falling back to GBK: {}", e.getMessage());
            try {
                return new String(bytes, Charset.forName("GBK"));
            } catch (Exception ex) {
                // GBK 也失败，使用系统默认编码作为最后手段
                return new String(bytes);
            }
        }
    }

    /** 内部类，用于保存解压后的文件信息 */
    private static class ExtractedFile {
        /** 文件名（不含路径） */
        String filename;
        /** 文件在本地临时目录中的绝对路径 */
        Path path;
        /** 文件大小（字节） */
        long size;

        ExtractedFile(String filename, Path path, long size) {
            this.filename = filename;
            this.path = path;
            this.size = size;
        }
    }

    /**
     * 检查文件名是否为支持的压缩包格式。
     *
     * @param filename 文件名
     * @return 如果是支持的压缩格式返回 true，否则返回 false
     */
    private boolean isArchive(String filename) {
        String lower = filename.toLowerCase();
        for (String ext : ARCHIVE_EXTENSIONS) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }

    /**
     * 解压压缩包到目标目录。支持 zip、tar.gz、tgz、tar 格式。
     *
     * @param archiveFile 压缩包文件
     * @param targetDir   解压目标目录
     * @throws IOException 如果解压失败或格式不支持
     */
    private void extractArchive(File archiveFile, Path targetDir) throws IOException {
        String name = archiveFile.getName().toLowerCase();
        if (name.endsWith(".zip")) {
            extractZip(archiveFile, targetDir);
        } else if (name.endsWith(".tar.gz") || name.endsWith(".tgz")) {
            // tar.gz 和 tgz 都需要 gzip 解压
            extractTarGz(archiveFile, targetDir, true);
        } else if (name.endsWith(".tar")) {
            // 纯 tar 不需要 gzip 解压
            extractTarGz(archiveFile, targetDir, false);
        } else {
            throw new IOException("不支持的压缩格式: " + archiveFile.getName());
        }
    }

    /**
     * 解压 ZIP 格式的压缩包到目标目录。
     *
     * <p>包含安全检查：防止路径穿越攻击（zip slip），确保解压路径不会超出目标目录。</p>
     *
     * <p><b>编码处理：</b>ZIP 文件的文件名编码在不同操作系统上不一致：
     * <ul>
     *   <li>Linux/macOS 创建的 ZIP 通常使用 UTF-8 编码文件名</li>
     *   <li>Windows 中文系统创建的 ZIP 通常使用 GBK/CP936 编码文件名</li>
     * </ul>
     * 本方法先尝试 UTF-8 编码，如果发现文件名包含替换字符（U+FFFD，表示解码失败），
     * 则自动切换到 GBK 编码重新解压，确保中文文件名不会乱码。</p>
     *
     * @param zipFile   ZIP 文件
     * @param targetDir 解压目标目录
     * @throws IOException 如果解压过程中发生 I/O 错误
     */
    private void extractZip(File zipFile, Path targetDir) throws IOException {
        // 先检测 ZIP 文件名使用的编码
        Charset zipCharset = detectZipCharset(zipFile);
        log.info("Detected ZIP charset: {} for file: {}", zipCharset.name(), zipFile.getName());

        try (ZipFile zf = new ZipFile(zipFile, zipCharset)) {
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                Path entryPath = targetDir.resolve(entry.getName()).normalize();
                // 安全检查：防止路径穿越攻击（zip slip），确保解压路径在目标目录内
                if (!entryPath.startsWith(targetDir)) {
                    continue;
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    // 确保父目录存在后再写入文件
                    Files.createDirectories(entryPath.getParent());
                    try (InputStream is = zf.getInputStream(entry)) {
                        Files.copy(is, entryPath, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
    }

    /**
     * 检测 ZIP 文件中文件名使用的字符编码。
     *
     * <p>检测策略：</p>
     * <ol>
     *     <li>先用 UTF-8 打开 ZIP 文件，遍历所有条目名称</li>
     *     <li>如果任何文件名包含替换字符 '\uFFFD'（表示 UTF-8 解码失败），说明文件名不是 UTF-8 编码</li>
     *     <li>回退到 GBK 编码（中文 Windows 系统的默认编码）</li>
     * </ol>
     *
     * @param zipFile ZIP 文件
     * @return 检测到的字符编码；UTF-8 或 GBK
     */
    private Charset detectZipCharset(File zipFile) {
        // 先尝试 UTF-8 编码
        try (ZipFile zf = new ZipFile(zipFile, StandardCharsets.UTF_8)) {
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                // 检查文件名是否包含替换字符（UTF-8 解码失败时 Java 会插入此字符）
                if (name != null && name.contains("\uFFFD")) {
                    log.debug("ZIP entry name contains replacement character, switching to GBK: {}", name);
                    return Charset.forName("GBK");
                }
            }
        } catch (IOException e) {
            // UTF-8 打开失败，直接使用 GBK
            log.debug("Failed to open ZIP with UTF-8, trying GBK: {}", e.getMessage());
            return Charset.forName("GBK");
        }
        return StandardCharsets.UTF_8;
    }

    /**
     * 解压 TAR 或 TAR.GZ 格式的压缩包到目标目录。
     *
     * <p>包含安全检查：防止路径穿越攻击，确保解压路径不会超出目标目录。</p>
     *
     * @param tarFile  TAR 文件
     * @param targetDir 解压目标目录
     * @param gzipped  是否启用了 gzip 压缩（true 表示 tar.gz/tgz，false 表示纯 tar）
     * @throws IOException 如果解压过程中发生 I/O 错误
     */
    private void extractTarGz(File tarFile, Path targetDir, boolean gzipped) throws IOException {
        InputStream fis = new FileInputStream(tarFile);
        // 如果是 gzip 压缩的（tar.gz/tgz），需要先进行 gzip 解压
        if (gzipped) {
            fis = new GzipCompressorInputStream(fis);
        }
        try (TarArchiveInputStream tis = new TarArchiveInputStream(fis)) {
            TarArchiveEntry entry;
            while ((entry = tis.getNextTarEntry()) != null) {
                Path entryPath = targetDir.resolve(entry.getName()).normalize();
                // 安全检查：防止路径穿越攻击，确保解压路径在目标目录内
                if (!entryPath.startsWith(targetDir)) {
                    continue;
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    // 确保父目录存在后再写入文件
                    Files.createDirectories(entryPath.getParent());
                    Files.copy(tis, entryPath, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /**
     * 递归收集目录中的所有文件到 extractedFiles 列表中。
     *
     * <p>会过滤掉 macOS 元数据文件（以 ._ 开头的文件、.DS_Store）和隐藏系统文件（如 __MACOSX 目录）。</p>
     *
     * @param dir       要扫描的目录
     * @param collected 收集到的文件列表
     * @throws IOException 如果扫描过程中发生 I/O 错误
     */
    private void collectExtractedFiles(Path dir, List<ExtractedFile> collected) throws IOException {
        File[] files = dir.toFile().listFiles();
        if (files == null) return;
        for (File file : files) {
            String name = file.getName();
            // 跳过 macOS 元数据文件和系统文件
            if (name.startsWith("._") || name.equals(".DS_Store") || name.equals("__MACOSX")) {
                continue;
            }
            if (file.isDirectory()) {
                // 递归处理子目录
                collectExtractedFiles(file.toPath(), collected);
            } else if (file.isFile()) {
                collected.add(new ExtractedFile(file.getName(), file.toPath(), file.length()));
            }
        }
    }

    /**
     * 解析 skill.md 文件内容，提取技能元数据和提示词模板。
     *
     * <p>skill.md 文件采用 YAML front matter 格式：</p>
     * <pre>
     * ---
     * name: 技能名称
     * description: 技能描述
     * input_type: text
     * output_type: text
     * model: gpt-4
     * temperature: 0.7
     * max_tokens: 2000
     * tags:
     *   - 标签1
     *   - 标签2
     * ---
     * 这里是提示词模板内容...
     * </pre>
     *
     * <p>如果文件没有 front matter，则将整个内容作为提示词模板，
     * 技能名称默认为 "Unnamed Skill"。</p>
     *
     * @param content skill.md 文件的文本内容
     * @return 解析后的 Skill 对象（通过 builder 构建）
     */
    private Skill parseSkillMd(String content) {
        Skill.SkillBuilder builder = Skill.builder();
        List<String> tags = new ArrayList<>();

        // 解析 YAML front matter（以 --- 开头和结尾的元数据块）
        if (content.startsWith("---")) {
            // 查找 front matter 的结束标记
            int endIdx = content.indexOf("\n---", 3);
            if (endIdx > 0) {
                // 提取 YAML 元数据部分和提示词模板部分
                String yamlContent = content.substring(3, endIdx).trim();
                String body = content.substring(endIdx + 4).trim();

                // 使用 SnakeYAML 解析 YAML 元数据
                Yaml yaml = new Yaml();
                Map<String, Object> meta = yaml.load(yamlContent);

                if (meta != null) {
                    // 解析基本字段：名称、描述、输入/输出类型
                    builder.name(getString(meta, "name", "Unnamed Skill"));
                    builder.description(getString(meta, "description", ""));
                    builder.inputType(getString(meta, "input_type", "text"));
                    builder.outputType(getString(meta, "output_type", "text"));

                    // 解析模型名称（可选）
                    Object modelVal = meta.get("model");
                    if (modelVal != null) builder.model(String.valueOf(modelVal));

                    // 解析温度参数（可选，控制生成随机性）
                    Object tempVal = meta.get("temperature");
                    if (tempVal instanceof Number) builder.temperature(((Number) tempVal).doubleValue());

                    // 解析最大 token 数（可选）
                    Object maxTokensVal = meta.get("max_tokens");
                    if (maxTokensVal instanceof Number) builder.maxTokens(((Number) maxTokensVal).intValue());

                    // 解析标签列表（支持 YAML 列表格式或逗号分隔的字符串格式）
                    Object tagsVal = meta.get("tags");
                    if (tagsVal instanceof List) {
                        // YAML 列表格式：tags: [tag1, tag2]
                        for (Object tag : (List<?>) tagsVal) {
                            tags.add(String.valueOf(tag));
                        }
                    } else if (tagsVal instanceof String) {
                        // 逗号分隔字符串格式：tags: tag1, tag2
                        for (String tag : ((String) tagsVal).split(",")) {
                            tags.add(tag.trim());
                        }
                    }
                }

                // 提示词模板为 front matter 之后的正文内容
                builder.promptTemplate(body);
            } else {
                // 没有 front matter 结束标记，将整个内容作为提示词模板
                builder.name("Unnamed Skill");
                builder.description("");
                builder.promptTemplate(content);
            }
        } else {
            // 没有 front matter，将整个内容作为提示词模板
            builder.name("Unnamed Skill");
            builder.description("");
            builder.promptTemplate(content);
        }

        builder.tags(tags);
        return builder.build();
    }

    /**
     * 根据文件扩展名对文件进行分类。
     *
     * <p>分类规则：</p>
     * <ul>
     *     <li>脚本类（script）：.py, .sh, .js, .rb, .pl, .go, .java, .bat</li>
     *     <li>素材类（material）：.txt, .md, .pdf, .docx, .doc, .csv, .json, .xml, .html, .yaml, .yml</li>
     *     <li>其他（other）：不属于以上两类的文件</li>
     * </ul>
     *
     * @param filename 文件名
     * @return 文件类型字符串："script"、"material" 或 "other"
     */
    private String categorizeFile(String filename) {
        String lower = filename.toLowerCase();
        // 先检查是否为脚本文件
        for (String ext : SCRIPT_EXTENSIONS) {
            if (lower.endsWith(ext)) return "script";
        }
        // 再检查是否为素材文件
        for (String ext : MATERIAL_EXTENSIONS) {
            if (lower.endsWith(ext)) return "material";
        }
        // 既不是脚本也不是素材，归类为其他
        return "other";
    }

    /**
     * 根据文件扩展名获取对应的编程语言标识（用于 Markdown 代码块语法高亮）。
     *
     * @param filename 文件名
     * @return 编程语言标识字符串（如 "python"、"java"），无法识别时返回 "text"
     */
    private String getLanguage(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".py")) return "python";
        if (lower.endsWith(".sh")) return "bash";
        if (lower.endsWith(".js")) return "javascript";
        if (lower.endsWith(".java")) return "java";
        if (lower.endsWith(".go")) return "go";
        return "text";
    }

    /**
     * 从存储后端加载技能。技能目录名（相对于存储根目录）作为技能 ID。
     *
     * <p>加载策略：</p>
     * <ol>
     *     <li>优先读取 {@code meta.json} 元数据文件（如果存在且有效）</li>
     *     <li>如果 meta.json 不存在或损坏，回退到解析 {@code skill.md} 并扫描目录</li>
     * </ol>
     *
     * @param skillId 技能 ID（即技能目录名）
     * @return 加载的技能对象，如果技能目录不存在 skill.md 则返回 null
     * @throws IOException 如果读取过程中发生 I/O 错误
     */
    private Skill loadSkillFromStorage(String skillId) throws IOException {
        // 优先尝试读取 meta.json 元数据文件
        String metaPath = skillId + "/meta.json";
        if (storage.exists(metaPath)) {
            try {
                byte[] metaBytes = storage.read(metaPath);
                return objectMapper.readValue(metaBytes, Skill.class);
            } catch (Exception e) {
                // meta.json 损坏时回退到解析 skill.md
                log.warn("Corrupted meta.json for skill {}, falling back to skill.md: {}", skillId, e.getMessage());
            }
        }

        // 回退方案：直接解析 skill.md
        String skillMdPath = skillId + "/skill.md";
        if (!storage.exists(skillMdPath)) {
            return null;
        }

        byte[] skillMdBytes = storage.read(skillMdPath);
        // 自动检测编码，兼容 UTF-8 和 GBK 编码的 skill.md 文件
        String content = readTextWithEncodingDetection(skillMdBytes);
        Skill skill = parseSkillMd(content);
        skill.setId(skillId);
        skill.setStoragePath(skillId);

        // 扫描技能目录，收集所有文件信息（通过存储抽象层递归列出）
        List<Skill.SkillFile> files = new ArrayList<>();
        // skill.md 始终在文件列表中
        files.add(new Skill.SkillFile("skill.md", "md", skillMdBytes.length, "skill.md"));

        List<FileInfo> allFiles = storage.listFiles(skillId);
        if (allFiles != null) {
            for (FileInfo fi : allFiles) {
                // 跳过目录
                if (fi.isDirectory()) {
                    continue;
                }
                String rel = fi.getPath();
                // 将存储相对路径转换为技能相对路径
                String skillRelative = rel.startsWith(skillId + "/")
                        ? rel.substring(skillId.length() + 1) : rel;
                // 跳过元数据文件和 skill.md（已单独处理）
                if ("skill.md".equals(skillRelative) || "meta.json".equals(skillRelative)) {
                    continue;
                }
                files.add(new Skill.SkillFile(
                        fi.getName(),
                        categorizeFile(fi.getName()),
                        fi.getSize(),
                        skillRelative
                ));
            }
        }

        skill.setFiles(files);
        // 回退加载后保存 meta.json，以便下次直接读取
        saveSkillMeta(skill);

        return skill;
    }

    /**
     * 保存技能元数据到 meta.json 文件。
     *
     * <p>将技能对象序列化为 JSON 并存储到技能目录下的 meta.json 文件中。
     * 保存失败仅记录警告日志，不抛出异常，以免影响主流程。</p>
     *
     * @param skill 要保存的技能对象
     */
    private void saveSkillMeta(Skill skill) {
        try {
            String metaPath = skill.getStoragePath() + "/meta.json";
            byte[] metaBytes = objectMapper.writeValueAsBytes(skill);
            storage.store(metaPath, metaBytes);
        } catch (Exception e) {
            // 元数据保存失败不影响主流程，仅记录警告
            log.warn("Failed to save skill meta: {}", e.getMessage());
        }
    }

    /**
     * 中文到英文的术语映射表，用于跨语言技能匹配。
     *
     * <p>当用户使用中文描述搜索技能时，系统会将中文术语映射为对应的英文关键词，
     * 以便与使用英文命名的技能进行匹配。映射涵盖以下领域：</p>
     * <ul>
     *     <li>通用业务术语（商业、模式、分析、生成等）</li>
     *     <li>演示文稿相关（幻灯片、演示文稿等）</li>
     *     <li>视频/动画相关（视频、动画、剪辑等）</li>
     *     <li>视觉艺术/设计相关（海报、艺术、图像等）</li>
     *     <li>Git/工作区相关（工作区、分支、版本等）</li>
     *     <li>通用创建术语（制作、创建、环境等）</li>
     * </ul>
     */
    private static final Map<String, String[]> CN_EN_TERMS = new HashMap<>();
    static {
        // 通用业务术语
        CN_EN_TERMS.put("商业", new String[]{"business", "commerce"});
        CN_EN_TERMS.put("模式", new String[]{"model", "pattern"});
        CN_EN_TERMS.put("画布", new String[]{"canvas"});
        CN_EN_TERMS.put("分析", new String[]{"analysis", "analyze"});
        CN_EN_TERMS.put("生成", new String[]{"generate", "create"});
        CN_EN_TERMS.put("翻译", new String[]{"translate", "translation"});
        CN_EN_TERMS.put("总结", new String[]{"summarize", "summary"});
        CN_EN_TERMS.put("摘要", new String[]{"summary", "abstract"});
        CN_EN_TERMS.put("代码", new String[]{"code", "coding"});
        CN_EN_TERMS.put("文档", new String[]{"document", "documentation"});
        CN_EN_TERMS.put("测试", new String[]{"test", "testing"});
        CN_EN_TERMS.put("部署", new String[]{"deploy", "deployment"});
        CN_EN_TERMS.put("数据", new String[]{"data"});
        CN_EN_TERMS.put("报告", new String[]{"report"});
        CN_EN_TERMS.put("邮件", new String[]{"email", "mail"});
        CN_EN_TERMS.put("计划", new String[]{"plan", "planning"});
        CN_EN_TERMS.put("优化", new String[]{"optimize", "optimization"});
        CN_EN_TERMS.put("转换", new String[]{"convert", "conversion", "transform"});
        CN_EN_TERMS.put("检查", new String[]{"check", "inspect"});
        CN_EN_TERMS.put("修复", new String[]{"fix", "repair"});
        CN_EN_TERMS.put("清理", new String[]{"clean", "cleanup"});
        CN_EN_TERMS.put("格式", new String[]{"format"});
        CN_EN_TERMS.put("提取", new String[]{"extract", "extraction"});
        CN_EN_TERMS.put("分类", new String[]{"classify", "classification"});
        CN_EN_TERMS.put("推荐", new String[]{"recommend", "recommendation"});
        CN_EN_TERMS.put("评估", new String[]{"evaluate", "assessment"});
        CN_EN_TERMS.put("设计", new String[]{"design"});
        CN_EN_TERMS.put("架构", new String[]{"architecture"});
        CN_EN_TERMS.put("安全", new String[]{"security", "safe"});
        CN_EN_TERMS.put("性能", new String[]{"performance"});
        CN_EN_TERMS.put("配置", new String[]{"config", "configuration"});
        // 演示文稿相关
        CN_EN_TERMS.put("幻灯片", new String[]{"slide", "slides", "deck", "ppt"});
        CN_EN_TERMS.put("幻灯", new String[]{"slide", "slides"});
        CN_EN_TERMS.put("演示文稿", new String[]{"presentation", "slides", "ppt"});
        CN_EN_TERMS.put("演示", new String[]{"presentation", "demo"});
        CN_EN_TERMS.put("演讲", new String[]{"presentation", "speech"});
        // 视频/动画相关
        CN_EN_TERMS.put("视频", new String[]{"video", "seedance"});
        CN_EN_TERMS.put("影片", new String[]{"video", "film"});
        CN_EN_TERMS.put("动画", new String[]{"animation", "video"});
        CN_EN_TERMS.put("录制", new String[]{"record", "recording"});
        CN_EN_TERMS.put("剪辑", new String[]{"edit", "clip", "video"});
        CN_EN_TERMS.put("配音", new String[]{"dub", "voice", "audio"});
        CN_EN_TERMS.put("字幕", new String[]{"subtitle"});
        // 视觉艺术/设计相关
        CN_EN_TERMS.put("海报", new String[]{"poster", "design"});
        CN_EN_TERMS.put("艺术", new String[]{"art", "design"});
        CN_EN_TERMS.put("视觉", new String[]{"visual", "design"});
        CN_EN_TERMS.put("图像", new String[]{"image"});
        CN_EN_TERMS.put("图片", new String[]{"image", "picture"});
        CN_EN_TERMS.put("绘画", new String[]{"paint", "draw", "art"});
        CN_EN_TERMS.put("画作", new String[]{"painting", "art"});
        // Git/工作区相关
        CN_EN_TERMS.put("工作区", new String[]{"workspace", "worktree"});
        CN_EN_TERMS.put("隔离", new String[]{"isolate", "isolation"});
        CN_EN_TERMS.put("分支", new String[]{"branch"});
        CN_EN_TERMS.put("版本", new String[]{"version"});
        CN_EN_TERMS.put("代码库", new String[]{"repository", "repo"});
        CN_EN_TERMS.put("协作", new String[]{"collaborate", "collaboration"});
        // 通用创建术语
        CN_EN_TERMS.put("制作", new String[]{"create", "make", "generate"});
        CN_EN_TERMS.put("创建", new String[]{"create", "make"});
        CN_EN_TERMS.put("环境", new String[]{"environment"});
    }

    /**
     * 基于关键词的技能搜索方法。
     *
     * <p>采用多维度的匹配策略来提高搜索准确率：</p>
     * <ol>
     *     <li>空白符分隔的关键词匹配（适用于英文输入）</li>
     *     <li>从输入中提取英文单词进行匹配（处理中英文混合输入）</li>
     *     <li>将中文术语映射为英文后进行匹配（跨语言匹配）</li>
     *     <li>提取 CJK 字符进行直接匹配（当技能包含中文描述时）</li>
     *     <li>技能名称分词匹配（按非字母数字字符拆分技能名）</li>
     * </ol>
     *
     * <p>评分规则（分数越高匹配度越高）：</p>
     * <ul>
     *     <li>技能名称匹配：+3 分</li>
     *     <li>技能描述匹配：+2 分</li>
     *     <li>技能标签匹配：+2 分</li>
     *     <li>中英文映射匹配：+4 分（跨语言匹配权重更高）</li>
     *     <li>CJK 字符匹配：+1 分（单个字符权重较低）</li>
     * </ul>
     *
     * @param description 搜索描述文本
     * @param allSkills   所有技能列表
     * @return 按匹配分数降序排列的技能列表（仅包含分数大于 0 的技能）
     */
    private List<Skill> keywordSearch(String description, List<Skill> allSkills) {
        String lowerDesc = description.toLowerCase();

        // 1. 按空白符分隔关键词（适用于英文输入）
        String[] keywords = lowerDesc.split("\\s+");

        // 2. 从输入中提取英文单词（处理中英文混合文本）
        List<String> englishWords = new ArrayList<>();
        java.util.regex.Matcher engMatcher = java.util.regex.Pattern.compile("[a-z]{2,}").matcher(lowerDesc);
        while (engMatcher.find()) {
            englishWords.add(engMatcher.group());
        }

        // 3. 将中文术语映射为英文等价词，用于跨语言匹配
        List<String> mappedEnglish = new ArrayList<>();
        for (Map.Entry<String, String[]> entry : CN_EN_TERMS.entrySet()) {
            if (lowerDesc.contains(entry.getKey())) {
                Collections.addAll(mappedEnglish, entry.getValue());
            }
        }

        // 4. 提取 CJK 字符用于直接匹配（当技能包含中文描述/标签时）
        List<String> cjkChars = new ArrayList<>();
        for (char c : lowerDesc.toCharArray()) {
            // CJK 统一表意文字范围：U+4E00 到 U+9FFF
            if (c >= '\u4e00' && c <= '\u9fff') {
                cjkChars.add(String.valueOf(c));
            }
        }

        return allSkills.stream()
                .map(skill -> {
                    int score = 0;
                    // 获取技能的名称、描述、标签的小写形式用于匹配
                    String name = skill.getName() != null ? skill.getName().toLowerCase() : "";
                    String desc = skill.getDescription() != null ? skill.getDescription().toLowerCase() : "";
                    String tags = skill.getTags() != null ? String.join(" ", skill.getTags()).toLowerCase() : "";
                    // 将所有文本合并用于统一匹配
                    String allText = name + " " + desc + " " + tags;

                    // 空白符分隔的关键词匹配（原始行为）
                    for (String keyword : keywords) {
                        // 跳过过短的关键词（单字符）
                        if (keyword.length() < 2) continue;
                        if (name.contains(keyword)) score += 3;
                        if (desc.contains(keyword)) score += 2;
                        if (tags.contains(keyword)) score += 2;
                    }

                    // 英文单词双向匹配
                    for (String word : englishWords) {
                        if (name.contains(word)) score += 3;
                        if (desc.contains(word)) score += 2;
                        if (tags.contains(word)) score += 2;
                    }

                    // 技能名称分词匹配（按非字母数字字符拆分技能名后与输入匹配）
                    String[] nameParts = name.split("[^a-z0-9]+");
                    for (String part : nameParts) {
                        if (part.length() >= 2 && lowerDesc.contains(part)) {
                            score += 3;
                        }
                    }

                    // 中英文映射术语匹配（跨语言匹配，权重较高）
                    for (String eng : mappedEnglish) {
                        if (allText.contains(eng)) {
                            score += 4;
                        }
                    }

                    // CJK 字符匹配（用于包含中文描述/标签的技能，单字符权重较低）
                    for (String cjk : cjkChars) {
                        if (allText.contains(cjk)) {
                            score += 1;
                        }
                    }

                    return new AbstractMap.SimpleEntry<>(skill, score);
                })
                // 过滤掉得分为 0 的技能（完全不匹配）
                .filter(e -> e.getValue() > 0)
                // 按匹配分数降序排列
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .map(AbstractMap.SimpleEntry::getKey)
                .collect(Collectors.toList());
    }

    /**
     * 从 Map 中安全地获取字符串值。
     *
     * <p>如果指定的 key 不存在或值为 null，则返回默认值。</p>
     *
     * @param map       元数据 Map
     * @param key       要获取的键
     * @param defaultVal 默认值（当键不存在或值为 null 时返回）
     * @return 键对应的字符串值，或默认值
     */
    private String getString(Map<String, Object> map, String key, String defaultVal) {
        Object val = map.get(key);
        return val != null ? String.valueOf(val) : defaultVal;
    }

    /**
     * 递归删除本地目录树（仅用于临时目录清理）。
     *
     * <p>该方法会先递归删除目录中的所有子目录和文件，最后删除目录本身。
     * 仅用于清理本地文件系统中的临时目录，不涉及存储后端。</p>
     *
     * @param dir 要删除的目录
     */
    private void deleteDirectory(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    // 递归删除子目录
                    deleteDirectory(file);
                } else {
                    file.delete();
                }
            }
        }
        // 删除空目录本身
        dir.delete();
    }
}
