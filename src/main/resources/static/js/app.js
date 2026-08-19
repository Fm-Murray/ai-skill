/**
 * ============================================================
 * AI 技能编排系统 - 前端核心逻辑 (app.js)
 * ============================================================
 * 本文件负责整个单页应用的前端交互逻辑，包括：
 * 1. 技能上传（拖拽 + 点击选择）
 * 2. 技能管理（列表展示、搜索、删除、详情查看）
 * 3. 技能执行（AI 自动选择 / 手动搜索选择）
 * 4. 会话链式编排（返回上一轮、重新执行、继续下一轮）
 * 5. 执行结果文件检测与下载
 * ============================================================
 */

// ==================== 全局状态管理 (State) ====================

/** 待上传的技能文件列表（技能上传页使用） */
let pendingFiles = [];

/** 执行页中用户手动选择的技能详情对象 */
let selectedSkillForExec = null;

/** 当前活跃的会话对象，包含所有执行轮次信息 */
let currentSession = null;

/** 当前查看的轮次号（与 currentSession.currentRound 分离，用于纯前端切换查看任意轮次） */
let viewingRound = null;

/** 执行页中待上传的输入文件列表 */
let execPendingFiles = [];

/** 两轮执行的路由信息（AI 自动选择技能时保存，用于前端展示执行流程） */
let currentRouteInfo = null;

/** 第一轮路由判断的完整结果（候选列表 + 思考过程 + 用户输入） */
let routeResultData = null;

/** 下一轮自动路由的候选技能列表（null 表示未检测，空数组表示无匹配技能） */
let nextRoundCandidates = null;

// ==================== API 请求封装 (API Helper) ====================

/**
 * 统一的 API 请求封装函数
 * 自动处理请求头设置（JSON / FormData），并返回解析后的 JSON 结果
 *
 * @param {string} url - 请求的 API 地址
 * @param {Object} options - fetch 的配置选项（method、body、headers 等）
 * @returns {Promise<Object>} 返回后端响应的 JSON 对象（通常包含 code、message、data 字段）
 */
async function api(url, options = {}) {
    const defaultHeaders = {};
    // 如果请求体不是 FormData（即不是文件上传），则默认设置 JSON 内容类型
    if (!(options.body instanceof FormData)) {
        defaultHeaders['Content-Type'] = 'application/json';
    }
    // 合并默认请求头与用户自定义请求头，发起 fetch 请求并解析 JSON 响应
    const response = await fetch(url, {
        ...options,
        headers: { ...defaultHeaders, ...options.headers }
    });
    return response.json();
}

// ==================== 消息提示组件 (Toast) ====================

/**
 * 显示一个轻量级的消息提示（Toast 通知）
 * 提示会在 3 秒后自动消失
 *
 * @param {string} message - 要显示的提示文本
 * @param {string} type - 提示类型：'info'（信息）、'success'（成功）、'error'（错误）
 */
function showToast(message, type = 'info') {
    const toast = document.getElementById('toast');
    toast.textContent = message;
    // 设置提示类型对应的样式类并显示
    toast.className = `toast toast-${type} show`;
    // 3 秒后自动隐藏提示
    setTimeout(() => toast.classList.remove('show'), 3000);
}

// ==================== 加载遮罩组件 (Loading) ====================

/**
 * 显示全屏加载遮罩层，阻止用户操作
 *
 * @param {string} text - 加载提示文本，默认为"正在执行..."
 */
function showLoading(text = '正在执行...') {
    document.getElementById('loading-text').textContent = text;
    document.getElementById('loading-overlay').style.display = 'flex';
}

/**
 * 隐藏全屏加载遮罩层
 */
function hideLoading() {
    document.getElementById('loading-overlay').style.display = 'none';
}

// ==================== 标签页导航 (Tab Navigation) ====================

/**
 * 为所有标签页按钮绑定点击事件
 * 点击时切换激活的标签页面板，并在切换到"技能管理"时自动加载技能列表
 */
document.querySelectorAll('.tab-btn').forEach(btn => {
    btn.addEventListener('click', () => {
        // 获取目标标签页标识
        const tab = btn.dataset.tab;
        // 移除所有标签按钮和面板的激活状态
        document.querySelectorAll('.tab-btn').forEach(b => b.classList.remove('active'));
        document.querySelectorAll('.tab-panel').forEach(p => p.classList.remove('active'));
        // 激活当前按钮和对应面板
        btn.classList.add('active');
        document.getElementById(`tab-${tab}`).classList.add('active');

        // 切换到技能管理页时自动加载技能列表
        if (tab === 'skills') loadSkills();
    });
});

// ==================== AI 状态检测 (AI Status) ====================

/**
 * 检查后端 AI 服务的连接状态
 * 调用 /api/skills/ai-status 接口，根据返回结果更新页面顶部的状态徽章
 */
async function checkAiStatus() {
    try {
        const result = await api('/api/skills/ai-status');
        const badge = document.getElementById('ai-status-badge');
        const text = document.getElementById('ai-status-text');
        // 根据后端返回的 configured 字段判断 AI 是否已配置
        if (result.data && result.data.configured) {
            badge.className = 'status-badge status-ok';
            text.textContent = 'AI 已连接';
        } else {
            badge.className = 'status-badge status-error';
            text.textContent = 'AI 未配置';
        }
    } catch (e) {
        // 请求失败时显示连接异常状态
        document.getElementById('ai-status-badge').className = 'status-badge status-error';
        document.getElementById('ai-status-text').textContent = 'AI 连接失败';
    }
}

// ==================== 技能上传功能 (Upload Functionality) ====================

// 获取上传区域和文件输入元素的 DOM 引用
const uploadZone = document.getElementById('upload-zone');
const fileInput = document.getElementById('file-input');

// 点击上传区域时触发文件选择对话框（排除点击 label 的情况，避免重复触发）
uploadZone.addEventListener('click', (e) => {
    if (e.target.tagName !== 'LABEL') fileInput.click();
});

// 文件选择框内容变化时，处理选中的文件并重置输入框
fileInput.addEventListener('change', (e) => {
    handleFiles(e.target.files);
    fileInput.value = '';
});

// 拖拽文件进入上传区域时，阻止默认行为并添加高亮样式
uploadZone.addEventListener('dragover', (e) => {
    e.preventDefault();
    uploadZone.classList.add('dragover');
});

// 拖拽文件离开上传区域时，移除高亮样式
uploadZone.addEventListener('dragleave', () => {
    uploadZone.classList.remove('dragover');
});

// 拖拽文件释放到上传区域时，阻止默认行为并处理文件
uploadZone.addEventListener('drop', (e) => {
    e.preventDefault();
    uploadZone.classList.remove('dragover');
    handleFiles(e.dataTransfer.files);
});

/**
 * 处理用户选择的文件列表，将文件添加到待上传列表并刷新展示
 *
 * @param {FileList} fileList - 用户选择的文件列表
 */
function handleFiles(fileList) {
    for (const file of fileList) {
        pendingFiles.push(file);
    }
    renderFileList();
}

/**
 * 根据文件名判断文件类型分类
 * 分类规则：skill.md 文件 -> md；压缩包 -> archive；脚本文件 -> script；资料文件 -> material；其他 -> other
 *
 * @param {string} filename - 文件名
 * @returns {string} 文件类型标识：'md' | 'archive' | 'script' | 'material' | 'other'
 */
function getFileType(filename) {
    const lower = filename.toLowerCase();
    // skill.md 是技能定义文件
    if (lower === 'skill.md') return 'md';
    // 压缩包格式：zip、tar.gz、tgz、tar
    if (/\.(zip|tar\.gz|tgz|tar)$/.test(lower)) return 'archive';
    // 脚本文件格式：py、sh、js、rb、pl、go、java、bat
    if (/\.(py|sh|js|rb|pl|go|java|bat)$/.test(lower)) return 'script';
    // 资料文件格式：txt、md、pdf、doc/docx、csv、json、xml、html、yaml/yml
    if (/\.(txt|md|pdf|docx?|csv|json|xml|html|ya?ml)$/.test(lower)) return 'material';
    return 'other';
}

/**
 * 根据文件类型返回对应的图标 emoji
 *
 * @param {string} type - 文件类型标识（由 getFileType 返回）
 * @returns {string} 对应的 emoji 图标
 */
function getFileIcon(type) {
    const icons = { md: '📝', material: '📄', script: '📜', archive: '📦', other: '📎' };
    return icons[type] || '📎';
}

/**
 * 将文件大小（字节）格式化为易读的字符串
 *
 * @param {number} bytes - 文件大小（字节数）
 * @returns {string} 格式化后的大小字符串，如 "1.5 KB"、"2.3 MB"
 */
function formatSize(bytes) {
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    return (bytes / 1024 / 1024).toFixed(1) + ' MB';
}

/**
 * 渲染待上传文件列表
 * 将 pendingFiles 数组中的文件渲染为可移除的列表项，并控制操作按钮的显示
 */
function renderFileList() {
    const container = document.getElementById('upload-file-list');
    const actions = document.getElementById('upload-actions');

    // 文件列表为空时清空内容并隐藏操作按钮
    if (pendingFiles.length === 0) {
        container.innerHTML = '';
        actions.style.display = 'none';
        return;
    }

    // 有文件时显示操作按钮
    actions.style.display = 'flex';
    // 遍历文件列表生成文件项 HTML
    container.innerHTML = pendingFiles.map((file, index) => {
        const type = getFileType(file.name);
        return `
            <div class="file-item">
                <span class="file-icon">${getFileIcon(type)}</span>
                <span class="file-name">${file.name}</span>
                <span class="file-size">${formatSize(file.size)}</span>
                <span class="file-type file-type-${type}">${type}</span>
                <button class="file-remove" onclick="removeFile(${index})">×</button>
            </div>
        `;
    }).join('');
}

/**
 * 从待上传列表中移除指定索引的文件
 *
 * @param {number} index - 要移除的文件在 pendingFiles 数组中的索引
 */
function removeFile(index) {
    pendingFiles.splice(index, 1);
    renderFileList();
}

/**
 * 上传按钮点击事件处理
 * 校验文件列表后，通过 XMLHttpRequest 上传到后端 /api/skills/upload 接口
 * 支持上传进度显示
 */
document.getElementById('upload-btn').addEventListener('click', async () => {
    // 校验：至少选择一个文件
    if (pendingFiles.length === 0) {
        showToast('请先选择文件', 'error');
        return;
    }

    // 校验：必须包含 skill.md 文件或压缩包（压缩包内需含 skill.md）
    const hasSkillMd = pendingFiles.some(f => f.name.toLowerCase() === 'skill.md');
    const hasArchive = pendingFiles.some(f => /\.(zip|tar\.gz|tgz|tar)$/i.test(f.name));

    if (!hasSkillMd && !hasArchive) {
        showToast('必须包含 skill.md 文件或压缩包（含 skill.md）', 'error');
        return;
    }

    // 构建 FormData 表单数据
    const formData = new FormData();
    pendingFiles.forEach(file => formData.append('files', file));

    // 计算总文件大小
    const totalSize = pendingFiles.reduce((sum, f) => sum + f.size, 0);
    const totalMB = (totalSize / 1024 / 1024).toFixed(1);

    // 显示上传进度条
    showUploadProgress(totalMB);

    try {
        // 使用 XMLHttpRequest 上传以支持进度监控
        const result = await new Promise((resolve, reject) => {
            const xhr = new XMLHttpRequest();
            xhr.open('POST', '/api/skills/upload');

            // 上传进度回调
            xhr.upload.addEventListener('progress', (e) => {
                if (e.lengthComputable) {
                    const percent = Math.round((e.loaded / e.total) * 100);
                    const loadedMB = (e.loaded / 1024 / 1024).toFixed(1);
                    updateUploadProgress(percent, `${loadedMB} / ${totalMB} MB`);
                }
            });

            // 上传完成回调
            xhr.addEventListener('load', () => {
                if (xhr.status >= 200 && xhr.status < 300) {
                    try {
                        resolve(JSON.parse(xhr.responseText));
                    } catch (e) {
                        reject(new Error('服务器返回数据解析失败'));
                    }
                } else {
                    let msg = `上传失败 (HTTP ${xhr.status})`;
                    try {
                        const resp = JSON.parse(xhr.responseText);
                        if (resp.message) msg = resp.message;
                    } catch (e) {}
                    reject(new Error(msg));
                }
            });

            // 上传错误回调
            xhr.addEventListener('error', () => reject(new Error('网络错误，上传失败')));
            xhr.addEventListener('abort', () => reject(new Error('上传已取消')));

            xhr.send(formData);
        });

        if (result.code === 200) {
            showToast('技能上传成功: ' + result.data.name, 'success');
            pendingFiles = [];
            renderFileList();
            loadSkills();
            document.querySelector('.tab-btn[data-tab="skills"]').click();
        } else {
            showToast(result.message || '上传失败', 'error');
        }
    } catch (e) {
        showToast('上传失败: ' + e.message, 'error');
    }
    hideUploadProgress();
});

/**
 * 显示上传进度条覆盖层
 * @param {string} totalMB - 总大小文本（如 "236.5"）
 */
function showUploadProgress(totalMB) {
    let overlay = document.getElementById('upload-progress-overlay');
    if (!overlay) {
        overlay = document.createElement('div');
        overlay.id = 'upload-progress-overlay';
        overlay.className = 'upload-progress-overlay';
        overlay.innerHTML = `
            <div class="upload-progress-card">
                <div class="upload-progress-title">
                    <span class="upload-progress-icon">📦</span>
                    <span>正在上传技能文件</span>
                </div>
                <div class="upload-progress-bar-container">
                    <div class="upload-progress-bar" id="upload-progress-bar" style="width: 0%"></div>
                </div>
                <div class="upload-progress-info">
                    <span id="upload-progress-percent">0%</span>
                    <span id="upload-progress-detail">0.0 / ${totalMB} MB</span>
                </div>
            </div>
        `;
        document.body.appendChild(overlay);
    } else {
        overlay.querySelector('#upload-progress-detail').textContent = `0.0 / ${totalMB} MB`;
        overlay.querySelector('#upload-progress-bar').style.width = '0%';
        overlay.querySelector('#upload-progress-percent').textContent = '0%';
        overlay.style.display = 'flex';
    }
}

/**
 * 更新上传进度
 * @param {number} percent - 进度百分比 (0-100)
 * @param {string} detail - 详细信息文本
 */
function updateUploadProgress(percent, detail) {
    const bar = document.getElementById('upload-progress-bar');
    const pct = document.getElementById('upload-progress-percent');
    const det = document.getElementById('upload-progress-detail');
    if (bar) bar.style.width = percent + '%';
    if (pct) pct.textContent = percent + '%';
    if (det) det.textContent = detail;
}

/**
 * 隐藏上传进度条
 */
function hideUploadProgress() {
    const overlay = document.getElementById('upload-progress-overlay');
    if (overlay) {
        setTimeout(() => { overlay.style.display = 'none'; }, 800);
    }
}

/**
 * 清空按钮点击事件处理
 * 清空待上传文件列表并刷新展示
 */
document.getElementById('clear-btn').addEventListener('click', () => {
    pendingFiles = [];
    renderFileList();
});

// ==================== 技能管理 (Skills Management) ====================

/**
 * 加载所有技能列表并渲染为卡片
 * 调用 /api/skills 获取全部技能，渲染卡片并为每个卡片绑定点击和删除事件
 */
async function loadSkills() {
    const container = document.getElementById('skill-cards');
    // 显示加载中状态
    container.innerHTML = '<div class="empty-state"><p>加载中...</p></div>';

    try {
        const result = await api('/api/skills');
        if (result.code === 200 && result.data.length > 0) {
            // 有数据时渲染技能卡片
            container.innerHTML = result.data.map(skill => renderSkillCard(skill)).join('');
            // 为每个卡片绑定点击事件（点击卡片查看详情）
            container.querySelectorAll('.skill-card').forEach(card => {
                card.addEventListener('click', (e) => {
                    // 点击删除按钮时不触发详情查看
                    if (e.target.classList.contains('skill-delete')) return;
                    showSkillDetail(card.dataset.skillId);
                });
            });
            // 为每个删除按钮绑定点击事件
            container.querySelectorAll('.skill-delete').forEach(btn => {
                btn.addEventListener('click', (e) => {
                    // 阻止事件冒泡，避免触发卡片点击
                    e.stopPropagation();
                    deleteSkill(btn.dataset.skillId);
                });
            });
        } else {
            // 无数据时显示空状态
            container.innerHTML = '<div class="empty-state"><p>暂无技能，请先上传</p></div>';
        }
    } catch (e) {
        container.innerHTML = '<div class="empty-state"><p>加载失败</p></div>';
    }
}

/**
 * 渲染单个技能卡片的 HTML
 *
 * @param {Object} skill - 技能对象，包含 id、name、description、tags、createdAt、fileCount 等字段
 * @returns {string} 技能卡片的 HTML 字符串
 */
function renderSkillCard(skill) {
    // 渲染标签列表
    const tags = (skill.tags || []).map(t => `<span class="skill-tag">${t}</span>`).join('');
    // 格式化创建时间
    const time = skill.createdAt ? new Date(skill.createdAt).toLocaleString('zh-CN') : '';
    return `
        <div class="skill-card" data-skill-id="${skill.id}">
            <div class="skill-card-header">
                <h3>${escapeHtml(skill.name)}</h3>
                <button class="skill-delete" data-skill-id="${skill.id}" title="删除">🗑</button>
            </div>
            <p class="skill-card-desc">${escapeHtml(skill.description || '暂无描述')}</p>
            <div class="skill-card-tags">${tags}</div>
            <div class="skill-card-footer">
                <span class="file-count">📄 ${skill.fileCount || 0} 个文件</span>
                <span>${time}</span>
            </div>
        </div>
    `;
}

/**
 * 删除指定技能
 * 弹出确认对话框，确认后调用 DELETE 接口删除技能
 *
 * @param {string} skillId - 要删除的技能 ID
 */
async function deleteSkill(skillId) {
    if (!confirm('确定要删除这个技能吗？')) return;
    try {
        const result = await api(`/api/skills/${skillId}`, { method: 'DELETE' });
        if (result.code === 200) {
            showToast('技能已删除', 'success');
            loadSkills();
        } else {
            showToast(result.message || '删除失败', 'error');
        }
    } catch (e) {
        showToast('删除失败: ' + e.message, 'error');
    }
}

/**
 * 技能搜索按钮点击事件
 * 根据输入框内容执行搜索；输入为空时加载全部技能
 */
document.getElementById('skill-search-btn').addEventListener('click', () => {
    const desc = document.getElementById('skill-search-input').value.trim();
    if (desc) {
        searchSkills(desc, 'skill-cards');
    } else {
        loadSkills();
    }
});

/**
 * 技能搜索输入框回车事件
 * 按下回车键时触发搜索按钮点击
 */
document.getElementById('skill-search-input').addEventListener('keypress', (e) => {
    if (e.key === 'Enter') document.getElementById('skill-search-btn').click();
});

/**
 * 技能刷新按钮点击事件
 * 清空搜索输入框并重新加载全部技能
 */
document.getElementById('skill-refresh-btn').addEventListener('click', () => {
    document.getElementById('skill-search-input').value = '';
    loadSkills();
});

/**
 * 搜索技能
 * 根据描述调用 /api/skills/search 接口搜索技能，可选择是否使用 AI 语义搜索
 * 搜索结果根据容器 ID 渲染为卡片或选择项
 *
 * @param {string} description - 搜索描述关键词
 * @param {string} containerId - 结果渲染的容器元素 ID
 */
async function searchSkills(description, containerId) {
    const container = document.getElementById(containerId);
    // 读取 AI 语义搜索复选框状态
    const useAi = document.getElementById('use-ai-search').checked;
    container.innerHTML = '<div class="empty-state"><p>搜索中...</p></div>';

    try {
        const result = await api('/api/skills/search', {
            method: 'POST',
            body: JSON.stringify({ description, useAi })
        });
        if (result.code === 200 && result.data.length > 0) {
            if (containerId === 'skill-cards') {
                // 技能管理页：渲染为可点击查看详情和删除的卡片
                container.innerHTML = result.data.map(skill => renderSkillCard(skill)).join('');
                container.querySelectorAll('.skill-card').forEach(card => {
                    card.addEventListener('click', (e) => {
                        if (e.target.classList.contains('skill-delete')) return;
                        showSkillDetail(card.dataset.skillId);
                    });
                });
                container.querySelectorAll('.skill-delete').forEach(btn => {
                    btn.addEventListener('click', (e) => {
                        e.stopPropagation();
                        deleteSkill(btn.dataset.skillId);
                    });
                });
            } else {
                // 执行/继续页：渲染为可选择项
                container.innerHTML = result.data.map(skill =>
                    renderSkillSelectItem(skill, containerId)
                ).join('');
                bindSkillSelectEvents(containerId);
            }
        } else {
            container.innerHTML = '<div class="empty-state"><p>未找到匹配的技能</p></div>';
        }
    } catch (e) {
        container.innerHTML = '<div class="empty-state"><p>搜索失败</p></div>';
    }
}

// ==================== 执行页文件上传 (Execute File Upload) ====================

// 获取执行页上传区域和文件输入元素的 DOM 引用
const execUploadZone = document.getElementById('exec-upload-zone');
const execFileInput = document.getElementById('exec-file-input');

// 点击执行页上传区域时触发文件选择对话框
execUploadZone.addEventListener('click', (e) => {
    if (e.target.tagName !== 'LABEL') execFileInput.click();
});

// 执行页文件选择框内容变化时，处理选中的文件并重置
execFileInput.addEventListener('change', (e) => {
    handleExecFiles(e.target.files);
    execFileInput.value = '';
});

// 执行页拖拽相关事件
execUploadZone.addEventListener('dragover', (e) => {
    e.preventDefault();
    execUploadZone.classList.add('dragover');
});

execUploadZone.addEventListener('dragleave', () => {
    execUploadZone.classList.remove('dragover');
});

execUploadZone.addEventListener('drop', (e) => {
    e.preventDefault();
    execUploadZone.classList.remove('dragover');
    handleExecFiles(e.dataTransfer.files);
});

/**
 * 处理执行页选择的文件列表，添加到待执行文件列表并刷新展示
 *
 * @param {FileList} fileList - 用户选择的文件列表
 */
function handleExecFiles(fileList) {
    for (const file of fileList) {
        execPendingFiles.push(file);
    }
    renderExecFileList();
}

/**
 * 渲染执行页待上传文件列表
 */
function renderExecFileList() {
    const container = document.getElementById('exec-file-list');
    if (execPendingFiles.length === 0) {
        container.innerHTML = '';
        return;
    }
    container.innerHTML = execPendingFiles.map((file, index) => `
        <div class="file-item">
            <span class="file-icon">📎</span>
            <span class="file-name">${file.name}</span>
            <span class="file-size">${formatSize(file.size)}</span>
            <button class="file-remove" onclick="removeExecFile(${index})">×</button>
        </div>
    `).join('');
}

/**
 * 从执行页待上传列表中移除指定索引的文件
 *
 * @param {number} index - 要移除的文件索引
 */
function removeExecFile(index) {
    execPendingFiles.splice(index, 1);
    renderExecFileList();
}

// ==================== 技能执行功能 (Execute Functionality) ====================

/**
 * 执行页搜索按钮点击事件
 * 根据输入描述搜索要执行的技能
 */
document.getElementById('exec-search-btn').addEventListener('click', () => {
    const desc = document.getElementById('exec-search-input').value.trim();
    if (desc) {
        searchSkillsForExec(desc);
    }
});

/**
 * 执行页搜索输入框回车事件
 */
document.getElementById('exec-search-input').addEventListener('keypress', (e) => {
    if (e.key === 'Enter') document.getElementById('exec-search-btn').click();
});

/**
 * 为执行页搜索技能（使用 AI 语义搜索）
 * 调用 /api/skills/search 接口并渲染可选择项
 *
 * @param {string} description - 搜索描述关键词
 */
async function searchSkillsForExec(description) {
    const container = document.getElementById('exec-skill-results');
    container.innerHTML = '<div class="empty-state"><p>搜索中...</p></div>';

    try {
        const result = await api('/api/skills/search', {
            method: 'POST',
            body: JSON.stringify({ description, useAi: true })
        });
        if (result.code === 200 && result.data.length > 0) {
            container.innerHTML = result.data.map(skill =>
                renderSkillSelectItem(skill, 'exec-skill-results')
            ).join('');
            bindSkillSelectEvents('exec-skill-results');
        } else {
            container.innerHTML = '<div class="empty-state"><p>未找到匹配的技能</p></div>';
        }
    } catch (e) {
        container.innerHTML = '<div class="empty-state"><p>搜索失败</p></div>';
    }
}

/**
 * 渲染技能选择项（用于执行页和继续执行页）
 * 与技能管理卡片不同，选择项没有删除按钮，点击后用于选择
 *
 * @param {Object} skill - 技能对象
 * @param {string} containerId - 所属容器 ID，用于区分执行页和继续执行页
 * @returns {string} 技能选择项的 HTML 字符串
 */
function renderSkillSelectItem(skill, containerId) {
    const tags = (skill.tags || []).map(t => `<span class="skill-tag">${t}</span>`).join('');
    return `
        <div class="skill-card" data-skill-id="${skill.id}" data-container="${containerId}">
            <div class="skill-card-header">
                <h3>${escapeHtml(skill.name)}</h3>
            </div>
            <p class="skill-card-desc">${escapeHtml(skill.description || '暂无描述')}</p>
            <div class="skill-card-tags">${tags}</div>
        </div>
    `;
}

/**
 * 为技能选择项绑定点击事件
 * 根据容器 ID 判断是执行页，调用对应的选择函数
 *
 * @param {string} containerId - 容器元素 ID
 */
function bindSkillSelectEvents(containerId) {
    const container = document.getElementById(containerId);
    container.querySelectorAll('.skill-card').forEach(card => {
        card.addEventListener('click', () => {
            const skillId = card.dataset.skillId;
            if (containerId === 'exec-skill-results') {
                selectSkillForExec(skillId, card);
            }
        });
    });
}

/**
 * 选择要执行的技能（执行页）
 * 高亮选中的卡片，获取技能详情并显示在选中信息区域
 *
 * @param {string} skillId - 技能 ID
 * @param {HTMLElement} card - 被点击的卡片元素
 */
async function selectSkillForExec(skillId, card) {
    // 移除所有卡片的高亮状态，高亮当前选中卡片
    document.querySelectorAll('#exec-skill-results .skill-card').forEach(c => c.classList.remove('selected'));
    card.classList.add('selected');

    try {
        // 获取技能详情
        const result = await api(`/api/skills/${skillId}`);
        if (result.code === 200) {
            selectedSkillForExec = result.data;
            // 渲染选中技能的信息
            const info = document.getElementById('selected-skill-info');
            info.innerHTML = `
                <span class="skill-badge">已选择</span>
                <div>
                    <div class="skill-name">${escapeHtml(selectedSkillForExec.name)}</div>
                    <div class="skill-desc">${escapeHtml(selectedSkillForExec.description || '')}</div>
                </div>
            `;
            // 显示手动执行操作区
            document.getElementById('exec-manual-input-area').style.display = 'block';
        }
    } catch (e) {
        showToast('加载技能详情失败', 'error');
    }
}

/**
 * AI 自动选择并执行按钮点击事件
 * 第一阶段：调用路由 API 获取候选技能列表和 AI 思考过程
 * 第二阶段：用户选择技能后调用执行 API
 */
document.getElementById('auto-exec-btn').addEventListener('click', async () => {
    const input = document.getElementById('exec-input-text').value.trim();
    // 校验：必须输入内容或上传文件
    if (!input && execPendingFiles.length === 0) {
        showToast('请输入内容或上传文件', 'error');
        return;
    }

    showLoading('AI 正在进行路由判断...');
    try {
        let result;
        if (execPendingFiles.length > 0) {
            // 有文件时使用 multipart 接口
            const formData = new FormData();
            formData.append('input', input);
            execPendingFiles.forEach(file => formData.append('files', file));
            result = await api('/api/sessions/route-with-files', { method: 'POST', body: formData });
        } else {
            // 无文件时使用 JSON 接口
            result = await api('/api/sessions/route', {
                method: 'POST',
                body: JSON.stringify({ input })
            });
        }
        if (result.code === 200) {
            // 保存路由结果数据
            routeResultData = {
                candidates: result.data.candidates || [],
                thinkingProcess: result.data.thinkingProcess || '',
                userInput: result.data.userInput || input
            };
            // 渲染路由判断结果
            renderRouteResult(routeResultData);
            showToast('路由判断完成，返回 ' + routeResultData.candidates.length + ' 个候选技能', 'success');
        } else {
            showToast(result.message || '路由判断失败', 'error');
        }
    } catch (e) {
        showToast('路由判断失败: ' + (e.response?.data?.message || e.message), 'error');
    }
    hideLoading();
});

/**
 * 手动搜索技能按钮点击事件
 * 切换显示/隐藏手动搜索区域
 */
document.getElementById('show-search-btn').addEventListener('click', () => {
    const section = document.getElementById('manual-search-section');
    if (section.style.display === 'none') {
        section.style.display = 'block';
        section.scrollIntoView({ behavior: 'smooth', block: 'start' });
    } else {
        section.style.display = 'none';
    }
});

/**
 * 手动执行选中技能按钮点击事件
 * 使用用户手动选择的技能执行任务，支持带文件或纯文本输入
 */
document.getElementById('exec-run-btn').addEventListener('click', async () => {
    // 校验：必须先选择一个技能
    if (!selectedSkillForExec) {
        showToast('请先选择一个技能', 'error');
        return;
    }
    const input = document.getElementById('exec-input-text').value.trim();
    // 校验：必须输入内容或上传文件
    if (!input && execPendingFiles.length === 0) {
        showToast('请输入内容或上传文件', 'error');
        return;
    }

    showLoading('正在执行技能: ' + selectedSkillForExec.name);
    try {
        let result;
        if (execPendingFiles.length > 0) {
            // 有文件时使用 multipart 接口
            const formData = new FormData();
            formData.append('skillId', selectedSkillForExec.id);
            formData.append('input', input);
            execPendingFiles.forEach(file => formData.append('files', file));
            result = await api('/api/sessions/with-files', { method: 'POST', body: formData });
        } else {
            // 无文件时使用 JSON 接口
            result = await api('/api/sessions', {
                method: 'POST',
                body: JSON.stringify({ skillId: selectedSkillForExec.id, input })
            });
        }
        if (result.code === 200) {
            currentSession = result.data;
            viewingRound = null;
            showToast('执行成功', 'success');
            renderSession();
        } else {
            showToast(result.message || '执行失败', 'error');
        }
    } catch (e) {
        showToast('执行失败: ' + e.message, 'error');
    }
    hideLoading();
});

// ==================== 路由判断结果渲染 (Route Result Rendering) ====================

/**
 * 渲染第一轮路由判断的结果
 * 展示 AI 思考过程（可折叠）和候选技能列表，用户可选择一个技能执行第二轮
 *
 * @param {Object} data - 路由判断结果，包含 candidates、thinkingProcess、userInput
 */
function renderRouteResult(data) {
    const section = document.getElementById('route-result-section');
    const thinkingArea = document.getElementById('thinking-process-area');
    const thinkingContent = document.getElementById('thinking-process-content');
    const candidateList = document.getElementById('candidate-list');
    const candidateCount = document.getElementById('candidate-count');

    // 显示路由结果区域
    section.style.display = 'block';

    // 渲染 AI 思考过程（如果有）
    if (data.thinkingProcess && data.thinkingProcess.trim()) {
        thinkingArea.style.display = 'block';
        thinkingContent.textContent = data.thinkingProcess;
        // 默认折叠思考过程
        thinkingContent.style.display = 'none';
        document.getElementById('thinking-toggle').textContent = '▼';
    } else {
        thinkingArea.style.display = 'none';
    }

    // 渲染候选技能列表
    const candidates = data.candidates || [];
    candidateCount.textContent = candidates.length;
    candidateList.innerHTML = candidates.map((c, idx) => {
        const score = c.score != null ? Math.round(c.score) : '--';
        // 分数对应的颜色等级
        let scoreClass = 'score-medium';
        if (score !== '--') {
            if (score >= 85) scoreClass = 'score-high';
            else if (score < 60) scoreClass = 'score-low';
        }
        // 第一个候选标记为"最佳匹配"
        const bestBadge = idx === 0 ? '<span class="candidate-badge-best">最佳匹配</span>' : '';

        return `
            <div class="candidate-card" data-skill-id="${escapeHtml(c.skillId)}">
                <div class="candidate-card-header">
                    <div class="candidate-card-title">
                        ${bestBadge}
                        <span class="candidate-name">${escapeHtml(c.skillName || c.skillId)}</span>
                    </div>
                    <div class="candidate-score ${scoreClass}">
                        <span class="score-value">${score}</span>
                        <span class="score-label">匹配度</span>
                    </div>
                </div>
                <div class="candidate-reason">${escapeHtml(c.reason || '未提供匹配理由')}</div>
                <div class="candidate-actions">
                    <button class="btn btn-primary btn-sm candidate-exec-btn"
                            data-skill-id="${escapeHtml(c.skillId)}"
                            data-skill-name="${escapeHtml(c.skillName || c.skillId)}"
                            data-reason="${escapeHtml(c.reason || '')}">
                        <span class="btn-icon">▶</span> 执行此技能
                    </button>
                </div>
            </div>
        `;
    }).join('');

    // 为每个候选技能的执行按钮绑定事件
    candidateList.querySelectorAll('.candidate-exec-btn').forEach(btn => {
        btn.addEventListener('click', () => {
            const skillId = btn.dataset.skillId;
            const skillName = btn.dataset.skillName;
            const reason = btn.dataset.reason;
            executeCandidate(skillId, skillName, reason);
        });
    });

    // 滚动到路由结果区域
    section.scrollIntoView({ behavior: 'smooth', block: 'start' });
}

/**
 * 切换 AI 思考过程的显示/隐藏状态
 * 点击思考过程标题栏时调用
 */
function toggleThinkingProcess() {
    const content = document.getElementById('thinking-process-content');
    const toggle = document.getElementById('thinking-toggle');
    if (content.style.display === 'none') {
        content.style.display = 'block';
        toggle.textContent = '▲';
    } else {
        content.style.display = 'none';
        toggle.textContent = '▼';
    }
}

/**
 * 执行用户选中的候选技能（第二轮内容生成）
 * 调用 /api/sessions/execute-routed 接口，传入用户选择的技能 ID 和路由信息
 *
 * @param {string} skillId    - 用户选中的技能 ID
 * @param {string} skillName  - 技能名称
 * @param {string} reason     - 路由选择理由
 */
async function executeCandidate(skillId, skillName, reason) {
    if (!routeResultData) {
        showToast('路由数据丢失，请重新进行路由判断', 'error');
        return;
    }

    // 获取用户原始输入（路由判断时保存的）
    const input = routeResultData.userInput || document.getElementById('exec-input-text').value.trim();
    const thinkingProcess = routeResultData.thinkingProcess || '';

    showLoading('正在执行技能: ' + skillName + ' ...');
    try {
        const result = await api('/api/sessions/execute-routed', {
            method: 'POST',
            body: JSON.stringify({
                input: input,
                skillId: skillId,
                routeReason: reason,
                thinkingProcess: thinkingProcess
            })
        });

        if (result.code === 200) {
            // 保存路由信息，用于在会话详情中展示两轮执行流程
            currentRouteInfo = {
                selectedSkillName: result.data.selectedSkillName || skillName,
                selectedSkillId: result.data.selectedSkillId || skillId,
                routeReason: reason,
                thinkingProcess: thinkingProcess
            };

            // 保存会话数据并切换到会话视图
            currentSession = result.data;
            viewingRound = null;
            // 隐藏路由结果区域
            document.getElementById('route-result-section').style.display = 'none';
            renderSession();
            showToast('执行成功: ' + skillName, 'success');
        } else {
            showToast(result.message || '执行失败', 'error');
        }
    } catch (e) {
        showToast('执行失败: ' + (e.response?.data?.message || e.message), 'error');
    }
    hideLoading();
}

// ==================== 会话渲染 (Session Rendering) ====================

/**
 * 检测技能输出是否包含需要用户回答的提问
 * 通过问号、常见提问句式等特征判断输出是否在等待用户补充信息
 *
 * @param {string} output - 技能输出文本
 * @returns {boolean} 输出中是否包含提问
 */
function outputContainsQuestion(output) {
    if (!output) return false;
    const text = output.trim();

    // 1. 包含中文或英文问号
    if (/[？?]/.test(text)) return true;

    // 2. 包含常见提问句式
    const questionPatterns = [
        /请(?:告诉|提供|回答|输入|描述|说明)/,
        /请问/,
        /你的.{0,10}是(?:什么|谁|哪)/,
        /(?:受众|用途|调性|风格|目标|需求).{0,6}(?:是什么|是啥|是哪个)/,
        /请回复/,
        /go ahead/i,
        /waiting for/i,
        /please (?:answer|provide|tell|describe|specify)/i,
    ];
    for (const pattern of questionPatterns) {
        if (pattern.test(text)) return true;
    }

    return false;
}

/**
 * 渲染当前会话的全部内容
 * 切换页面到会话视图，显示会话 ID、执行链、当前轮次详情和导航提示
 */
function renderSession() {
    if (!currentSession) return;

    // 初始化查看轮次为当前轮次
    if (viewingRound === null || !currentSession.rounds.some(r => r.round === viewingRound)) {
        viewingRound = currentSession.currentRound;
    }

    // 隐藏初始选择界面，显示会话界面，隐藏继续面板
    document.getElementById('execute-init').style.display = 'none';
    document.getElementById('execute-session').style.display = 'block';
    document.getElementById('continue-panel').style.display = 'none';

    // 检测当前轮次输出是否包含提问，决定是否显示回复区域（仅在查看最新轮次时显示）
    const current = (currentSession.rounds || []).find(r => r.round === currentSession.currentRound);
    const isViewingLatest = viewingRound === currentSession.currentRound;
    const hasQuestion = isViewingLatest && current && current.output && current.status !== 'error'
        ? outputContainsQuestion(current.output)
        : false;

    const replySection = document.getElementById('reply-section');
    const floatingBtn = document.getElementById('floating-reply-btn');
    if (hasQuestion) {
        if (replySection) replySection.style.display = 'block';
        if (floatingBtn) floatingBtn.style.display = 'flex';
    } else {
        if (replySection) replySection.style.display = 'none';
        if (floatingBtn) floatingBtn.style.display = 'none';
    }

    // 显示会话 ID
    document.getElementById('session-id-display').textContent = currentSession.sessionId;

    // 渲染执行链、当前轮次详情和导航提示
    renderChain();
    renderCurrentRound();
    renderNavPrompt();

    // 异步检测是否有下一轮可用技能
    checkNextRoundSkill();

    // 如果输出包含提问，自动滚动到回复区域
    if (hasQuestion) {
        setTimeout(() => {
            if (replySection) {
                replySection.scrollIntoView({ behavior: 'smooth', block: 'center' });
            }
        }, 500);
    }
}

/**
 * 异步检测当前会话是否有可用的下一轮技能
 * 调用 /api/sessions/{sessionId}/auto-continue-route 接口，
 * 根据当前输出通过 AI 路由判断找到候选技能。
 * 如果找到候选技能，显示"继续下一轮"按钮；否则隐藏。
 */
async function checkNextRoundSkill() {
    if (!currentSession) return;

    // 重置状态
    nextRoundCandidates = null;
    // 先隐藏"继续下一轮"按钮，等待检测结果
    document.getElementById('nav-continue-btn').style.display = 'none';

    try {
        const result = await api(`/api/sessions/${currentSession.sessionId}/auto-continue-route`, {
            method: 'POST'
        });
        if (result.code === 200 && result.data.hasNextSkill) {
            // 找到候选技能，保存列表并显示按钮
            nextRoundCandidates = result.data.candidates || [];
            document.getElementById('nav-continue-btn').style.display = 'inline-flex';
        } else {
            // 没有找到候选技能，保持隐藏
            nextRoundCandidates = [];
        }
    } catch (e) {
        // 检测失败，保持隐藏
        nextRoundCandidates = [];
    }
}

/**
 * 渲染执行链时间线
 * 将会话中所有执行轮次渲染为链式节点，节点间用箭头连接
 */
function renderChain() {
    const container = document.getElementById('exec-chain');
    const rounds = currentSession.rounds || [];

    container.innerHTML = rounds.map((round, i) => {
        // 判断是否为正在查看的轮次
        const isViewing = round.round === viewingRound;
        // 判断是否为后端当前轮次（最新轮次）
        const isCurrent = round.round === currentSession.currentRound;
        // 获取轮次状态样式
        const statusClass = round.status || 'success';
        // 生成预览文本（优先使用输出，无输出时使用输入）
        const preview = round.output ? round.output.substring(0, 100) : (round.input || '').substring(0, 100);
        // 判断是否为回复轮次（输入中包含"用户补充回复"标记）
        const isReplyRound = (round.input || '').includes('用户补充回复');
        // 回复轮次标签
        const replyBadge = isReplyRound ? '<span class="chain-badge-reply">回复</span>' : '';

        let html = `
            <div class="chain-node ${statusClass} ${isViewing ? 'current' : ''} ${isCurrent ? 'latest' : ''}" data-round="${round.round}" style="cursor: pointer;">
                <span class="round-status"></span>
                <span class="round-num">第 ${round.round} 轮${replyBadge}${isCurrent && !isViewing ? ' <em>(最新)</em>' : ''}</span>
                <div class="round-skill-name">${escapeHtml(round.skillName)}</div>
                <div class="round-preview">${escapeHtml(preview)}...</div>
            </div>
        `;
        // 非最后一个节点时添加箭头连接符
        if (i < rounds.length - 1) {
            html += '<div class="chain-arrow">→</div>';
        }
        return html;
    }).join('');

    // 为所有链节点绑定点击事件，纯前端切换查看轮次
    container.querySelectorAll('.chain-node').forEach(node => {
        node.addEventListener('click', () => {
            const roundNum = parseInt(node.dataset.round);
            if (roundNum !== viewingRound) {
                viewingRound = roundNum;
                renderSession();
            }
        });
    });
}

/**
 * 导航到指定的轮次
 * 计算需要返回的步数，调用 performGoBack 逐步返回
 *
 * @param {number} roundNum - 目标轮次号
 */
function navigateToRound(roundNum) {
    // 计算需要返回的步数
    const goBackSteps = currentSession.currentRound - roundNum;
    performGoBack(goBackSteps);
}

/**
 * 执行返回上一轮操作
 * 循环调用后端 /api/sessions/{sessionId}/back 接口，逐步返回指定步数
 *
 * @param {number} steps - 要返回的步数，默认为 1
 */
async function performGoBack(steps = 1) {
    showLoading('正在返回上一轮...');
    try {
        // 循环调用返回接口，逐步回退
        for (let i = 0; i < steps; i++) {
            const result = await api(`/api/sessions/${currentSession.sessionId}/back`, { method: 'POST' });
            if (result.code === 200) {
                currentSession = result.data;
                viewingRound = null;
            } else {
                showToast(result.message || '返回失败', 'error');
                break;
            }
        }
        renderSession();
        showToast('已返回', 'info');
    } catch (e) {
        showToast('返回失败: ' + e.message, 'error');
    }
    hideLoading();
}

/**
 * 渲染两轮大模型调用执行流程的可视化展示
 * 当 currentRouteInfo 存在且当前为第一轮时，展示完整的五步执行流程：
 * 1. 加载 skills 索引  2. 第一轮路由判断  3. 装配上下文  4. 第二轮内容生成  5. 后处理与交付
 *
 * @param {Object} currentRound - 当前轮次对象
 * @returns {string} 两轮流程的 HTML 字符串；如果不是两轮执行则返回空字符串
 */
function renderTwoRoundFlow(currentRound) {
    // 仅在存在路由信息且当前为第一轮时展示
    if (!currentRouteInfo || currentRound.round !== 1) {
        return '';
    }

    const routeInfo = currentRouteInfo;
    const isError = currentRound.status === 'error';

    return `
        <div class="two-round-flow">
            <div class="two-round-flow-title">
                <span class="flow-icon">🔄</span>
                <span>两轮大模型调用执行流程</span>
            </div>
            <div class="flow-steps">
                <div class="flow-step completed">
                    <div class="flow-step-icon">1</div>
                    <div class="flow-step-body">
                        <div class="flow-step-name">加载 skills 索引</div>
                        <div class="flow-step-desc">仅加载 name + description，控制 token 消耗</div>
                    </div>
                </div>
                <div class="flow-connector"></div>
                <div class="flow-step completed">
                    <div class="flow-step-icon">2</div>
                    <div class="flow-step-body">
                        <div class="flow-step-name">第一轮：路由判断</div>
                        <div class="flow-step-desc">大模型只输出 JSON，完成意图识别</div>
                        <div class="flow-step-result">
                            <span class="flow-tag">选中技能</span>
                            <span class="flow-skill-name">${escapeHtml(routeInfo.selectedSkillName || '')}</span>
                        </div>
                        ${routeInfo.routeReason ? `
                        <div class="flow-step-reason">
                            <span class="flow-tag">选择理由</span>
                            <span>${escapeHtml(routeInfo.routeReason)}</span>
                        </div>
                        ` : ''}
                        ${routeInfo.thinkingProcess ? `
                        <div class="flow-step-thinking" onclick="this.querySelector('.thinking-detail').style.display = this.querySelector('.thinking-detail').style.display === 'none' ? 'block' : 'none';">
                            <span class="flow-tag">🧠 AI 思考过程（点击展开/折叠）</span>
                            <div class="thinking-detail" style="display:none; margin-top:8px; font-size:12px; color:var(--gray-600); white-space:pre-wrap; word-break:break-word; max-height:300px; overflow-y:auto;">${escapeHtml(routeInfo.thinkingProcess)}</div>
                        </div>
                        ` : ''}
                    </div>
                </div>
                <div class="flow-connector"></div>
                <div class="flow-step completed">
                    <div class="flow-step-icon">3</div>
                    <div class="flow-step-body">
                        <div class="flow-step-name">装配上下文</div>
                        <div class="flow-step-desc">读取 SKILL.md 完整正文，挂载引用材料</div>
                    </div>
                </div>
                <div class="flow-connector"></div>
                <div class="flow-step ${isError ? 'failed' : 'completed'}">
                    <div class="flow-step-icon">4</div>
                    <div class="flow-step-body">
                        <div class="flow-step-name">第二轮：内容生成</div>
                        <div class="flow-step-desc">送入完整上下文，输出结构化结果</div>
                    </div>
                </div>
                <div class="flow-connector"></div>
                <div class="flow-step ${isError ? 'failed' : 'completed'}">
                    <div class="flow-step-icon">5</div>
                    <div class="flow-step-body">
                        <div class="flow-step-name">后处理与交付</div>
                        <div class="flow-step-desc">Schema 校验 → 脚本执行 → 文档渲染</div>
                    </div>
                </div>
            </div>
        </div>
    `;
}

/**
 * 渲染当前轮次的详细信息
 * 显示当前轮次的技能名称、时间、输入内容、输出内容，以及错误信息（如有）
 */
function renderCurrentRound() {
    const container = document.getElementById('current-round-detail');
    const rounds = currentSession.rounds || [];
    // 使用 viewingRound 查找正在查看的轮次
    const current = rounds.find(r => r.round === viewingRound);

    if (!current) {
        container.innerHTML = '<p>无轮次数据</p>';
        return;
    }

    // 如果查看的不是最新轮次，显示提示栏
    const isViewingHistory = viewingRound !== currentSession.currentRound;
    const historyBanner = isViewingHistory ? `
        <div class="viewing-history-banner">
            <span>正在查看第 ${viewingRound} 轮历史记录</span>
            <button class="back-to-latest-btn" onclick="viewingRound = currentSession.currentRound; renderSession();">返回最新轮次 →</button>
        </div>
    ` : '';

    // 格式化时间戳
    const time = current.timestamp ? new Date(current.timestamp).toLocaleString('zh-CN') : '';
    // 判断是否来自上一轮输出（继续执行场景）
    const continuedBadge = current.continuedFromPrevious
        ? '<span class="badge continued">来自上一轮输出</span>' : '';

    // 状态文本（执行出错时显示）
    const statusText = current.status === 'error' ? ' (执行出错)' : '';

    container.innerHTML = `
        ${historyBanner}
        <div class="round-detail-header">
            <h4>第 ${current.round} 轮: ${escapeHtml(current.skillName)}${statusText}</h4>
            <span class="round-meta">${time}</span>
        </div>
        ${renderTwoRoundFlow(current)}
        <div class="round-section">
            <div class="round-section-label">
                输入 ${continuedBadge}
            </div>
            <div class="round-section-content">${escapeHtml(current.input || '')}</div>
        </div>
        <div class="round-section">
            <div class="round-section-label">输出</div>
            <div class="round-section-content ${current.status === 'error' ? 'error-content' : 'output-content'}">${
                (current.output || '').trim().startsWith('{"type":"binary_file"')
                    ? '<div class="binary-file-notice">📎 二进制文件已生成，请点击下方按钮下载。</div>'
                    : escapeHtml(current.output || '')
            }</div>
        </div>
        ${current.errorMessage ? `
        <div class="round-section">
            <div class="round-section-label">错误信息</div>
            <div class="round-section-content error-content">${escapeHtml(current.errorMessage)}</div>
        </div>
        ` : ''}
    `;

    // 如果输出包含可识别的文件内容，检测并渲染下载文件区域
    if (current.output && current.status !== 'error') {
        const files = detectOutputFiles(current.output, current.skillName);
        if (files.length > 0) {
            container.insertAdjacentHTML('beforeend', renderOutputFiles(files));
        }
    }

    // 如果输出包含完整 HTML 文档，渲染内联预览
    if (current.output && current.status !== 'error') {
        const trimmedOutput = (current.output || '').trim();
        if (/<!DOCTYPE\s+html>|<html[\s>]/i.test(trimmedOutput)) {
            container.insertAdjacentHTML('beforeend', renderHtmlPreview(trimmedOutput));
        }
    }

    // 所有 DOM 内容追加完成后再统一绑定事件，避免 innerHTML += 销毁已有事件监听器
    if (current.output && current.status !== 'error') {
        bindDownloadButtons();
        bindPreviewButtons();
    }
}

/**
 * 渲染 HTML 内联预览区域
 * 生成包含预览/源码切换按钮和 iframe 预览的区域
 *
 * @param {string} htmlContent - 完整的 HTML 文档内容
 * @returns {string} 预览区域的 HTML 字符串
 */
function renderHtmlPreview(htmlContent) {
    // 将 HTML 内容编码为 base64，避免转义问题
    const encoded = btoa(unescape(encodeURIComponent(htmlContent)));
    return `
        <div class="round-section html-preview-section">
            <div class="round-section-label">
                HTML 预览
                <div class="preview-toolbar">
                    <button class="btn btn-sm btn-primary preview-toggle-btn" data-mode="preview">
                        <span>👁</span> 预览模式
                    </button>
                    <button class="btn btn-sm btn-secondary preview-newtab-btn">
                        <span>↗</span> 新窗口打开
                    </button>
                </div>
            </div>
            <div class="html-preview-wrapper">
                <iframe class="html-preview-iframe" sandbox="allow-same-origin allow-scripts allow-popups allow-forms allow-modals"
                        src="data:text/html;charset=utf-8,${encodeURIComponent(htmlContent)}"></iframe>
            </div>
            <div class="html-source-wrapper" style="display:none;">
                <pre class="html-source-content"><code>${escapeHtml(htmlContent)}</code></pre>
            </div>
        </div>
    `;
}

/**
 * 绑定预览区域的按钮事件
 */
function bindPreviewButtons() {
    // 预览/源码切换按钮
    const toggleBtn = document.querySelector('.preview-toggle-btn');
    if (toggleBtn) {
        toggleBtn.addEventListener('click', function() {
            const section = this.closest('.html-preview-section');
            const previewWrapper = section.querySelector('.html-preview-wrapper');
            const sourceWrapper = section.querySelector('.html-source-wrapper');
            if (this.dataset.mode === 'preview') {
                // 切换到源码模式
                previewWrapper.style.display = 'none';
                sourceWrapper.style.display = 'block';
                this.dataset.mode = 'source';
                this.innerHTML = '<span>📝</span> 源码模式';
            } else {
                // 切换到预览模式
                previewWrapper.style.display = 'block';
                sourceWrapper.style.display = 'none';
                this.dataset.mode = 'preview';
                this.innerHTML = '<span>👁</span> 预览模式';
            }
        });
    }

    // 新窗口打开按钮
    const newTabBtn = document.querySelector('.preview-newtab-btn');
    if (newTabBtn) {
        newTabBtn.addEventListener('click', function() {
            const iframe = document.querySelector('.html-preview-iframe');
            if (iframe) {
                // 获取 iframe 的 src（data URI）并在新窗口打开
                const src = iframe.src;
                const newWin = window.open('', '_blank');
                if (newWin) {
                    newWin.document.write(decodeURIComponent(src.split(',')[1] || ''));
                    newWin.document.close();
                }
            }
        });
    }
}

// ==================== 输出文件检测与下载 (Output File Detection & Download) ====================

/**
 * 从技能输出文本中检测并提取可识别的文件内容
 * 支持检测：HTML 文档、JSON、CSV、代码块、Markdown，以及兜底的纯文本文件
 *
 * @param {string} output - 技能输出的文本内容
 * @param {string} skillName - 技能名称（用于生成安全的文件名）
 * @returns {Array<Object>} 检测到的文件对象数组，每个对象包含 filename、label、icon、content、mime 字段
 */
function detectOutputFiles(output, skillName) {
    const files = [];
    const trimmed = output.trim();
    // 生成安全的文件名（仅保留字母数字和下划线、连字符）
    const safeName = (skillName || 'output').replace(/[^a-zA-Z0-9_-]/g, '_');

    // 0. 检测二进制文件标记（后端脚本生成的 PPTX/PDF/图片等二进制文件）
    if (trimmed.startsWith('{') && trimmed.includes('"type":"binary_file"')) {
        try {
            const binaryInfo = JSON.parse(trimmed);
            if (binaryInfo.type === 'binary_file' && binaryInfo.data) {
                files.push({
                    filename: binaryInfo.filename || `${safeName}.bin`,
                    label: getFileLabel(binaryInfo.filename || ''),
                    icon: getFileIconByExt(binaryInfo.filename || ''),
                    content: binaryInfo.data,  // Base64 编码的数据
                    mime: binaryInfo.mime || 'application/octet-stream',
                    isBinary: true,
                    size: binaryInfo.size || 0
                });
                return files;  // 二进制文件标记优先，直接返回
            }
        } catch (e) { /* JSON 解析失败，继续其他检测 */ }
    }

    // 1. 检测完整 HTML 文档（包含 DOCTYPE 或 html 标签）
    if (/<!DOCTYPE\s+html>|<html[\s>]/i.test(trimmed)) {
        files.push({
            filename: `${safeName}.html`,
            label: 'HTML 文件',
            icon: '🌐',
            content: trimmed,
            mime: 'text/html'
        });
    }

    // 2. 检测 JSON（以 { 或 [ 开头并以 } 或 ] 结尾，且能被 JSON.parse 解析）
    if ((trimmed.startsWith('{') && trimmed.endsWith('}')) ||
        (trimmed.startsWith('[') && trimmed.endsWith(']'))) {
        try {
            JSON.parse(trimmed);
            files.push({
                filename: `${safeName}.json`,
                label: 'JSON 文件',
                icon: '📊',
                // 格式化 JSON 输出（2 空格缩进）
                content: JSON.stringify(JSON.parse(trimmed), null, 2),
                mime: 'application/json'
            });
        } catch (e) { /* 不是有效的 JSON，忽略 */ }
    }

    // 3. 检测 CSV（多行且每行逗号数量一致）
    const lines = trimmed.split('\n').filter(l => l.trim());
    if (lines.length >= 2) {
        // 检查前 5 行的逗号数量是否一致
        const commaCounts = lines.slice(0, 5).map(l => (l.match(/,/g) || []).length);
        if (commaCounts.every(c => c > 0 && c === commaCounts[0])) {
            files.push({
                filename: `${safeName}.csv`,
                label: 'CSV 文件',
                icon: '📋',
                content: trimmed,
                mime: 'text/csv'
            });
        }
    }

    // 4. 提取代码块（```lang ... ``` 格式）
    const codeBlockRegex = /```(\w*)\n([\s\S]*?)```/g;
    let match;
    let codeIdx = 0;
    while ((match = codeBlockRegex.exec(output)) !== null) {
        codeIdx++;
        const lang = match[1] || 'txt';
        const code = match[2].trim();
        // 语言标识到文件扩展名的映射表
        const extMap = {
            'javascript': 'js', 'js': 'js', 'typescript': 'ts', 'ts': 'ts',
            'python': 'py', 'py': 'py', 'java': 'java', 'html': 'html',
            'css': 'css', 'json': 'json', 'bash': 'sh', 'sh': 'sh',
            'sql': 'sql', 'xml': 'xml', 'yaml': 'yml', 'yml': 'yml',
            'markdown': 'md', 'md': 'md', 'go': 'go', 'rust': 'rs',
            'c': 'c', 'cpp': 'cpp', 'php': 'php', 'ruby': 'rb'
        };
        const ext = extMap[lang.toLowerCase()] || lang || 'txt';
        files.push({
            filename: `${safeName}_code${codeIdx}.${ext}`,
            label: `代码文件 (${lang || 'text'})`,
            icon: '💻',
            content: code,
            mime: 'text/plain'
        });
    }

    // 5. 检测 Markdown（包含标题、引用、列表等语法，但不是完整 HTML）
    if (!files.some(f => f.filename.endsWith('.html')) &&
        /^(#{1,6}\s|>\s|\*\s|-\s|\d+\.\s)/m.test(trimmed) && trimmed.includes('\n')) {
        files.push({
            filename: `${safeName}.md`,
            label: 'Markdown 文件',
            icon: '📝',
            content: trimmed,
            mime: 'text/markdown'
        });
    }

    // 6. 兜底：如果未检测到任何文件类型，将完整输出作为 txt 文件
    if (files.length === 0) {
        files.push({
            filename: `${safeName}.txt`,
            label: '文本文件',
            icon: '📄',
            content: trimmed,
            mime: 'text/plain'
        });
    }

    return files;
}

/**
 * 渲染输出文件列表的 HTML
 * 生成包含文件图标、名称、下载和复制按钮的文件项列表
 *
 * @param {Array<Object>} files - 文件对象数组
 * @returns {string} 输出文件区域的 HTML 字符串
 */
function renderOutputFiles(files) {
    const items = files.map((f, i) => `
        <div class="output-file-item">
            <span class="output-file-icon">${f.icon}</span>
            <div class="output-file-info">
                <span class="output-file-label">${escapeHtml(f.label)}</span>
                <span class="output-file-name">${escapeHtml(f.filename)}</span>
                ${f.size ? `<span class="output-file-size">${formatSize(f.size)}</span>` : ''}
            </div>
            <div class="output-file-actions">
                <button class="btn btn-sm btn-primary download-btn" data-idx="${i}">
                    <span>⬇</span> 下载
                </button>
                ${f.isBinary ? '' : `
                <button class="btn btn-sm btn-secondary copy-btn" data-idx="${i}">
                    <span>📋</span> 复制
                </button>
                `}
            </div>
        </div>
    `).join('');

    return `
        <div class="output-files-section">
            <div class="output-files-header">
                <span>📎 生成文件</span>
                <span class="output-files-count">${files.length} 个文件</span>
            </div>
            <div class="output-files-list">${items}</div>
        </div>
    `;
}

/** 当前轮次检测到的输出文件列表缓存（供下载/复制按钮使用） */
let currentOutputFiles = [];

/**
 * 为下载和复制按钮绑定点击事件
 * 同时缓存当前轮次的输出文件列表，供按钮回调使用
 */
function bindDownloadButtons() {
    // 缓存当前轮次的输出文件列表
    const current = (currentSession.rounds || []).find(r => r.round === currentSession.currentRound);
    if (current && current.output) {
        currentOutputFiles = detectOutputFiles(current.output, current.skillName);
    }

    // 为所有下载按钮绑定点击事件
    document.querySelectorAll('.download-btn').forEach(btn => {
        btn.addEventListener('click', () => {
            const idx = parseInt(btn.dataset.idx);
            const file = currentOutputFiles[idx];
            if (file) downloadFile(file);
        });
    });

    // 为所有复制按钮绑定点击事件
    document.querySelectorAll('.copy-btn').forEach(btn => {
        btn.addEventListener('click', () => {
            const idx = parseInt(btn.dataset.idx);
            const file = currentOutputFiles[idx];
            if (file) copyToClipboard(file.content);
        });
    });
}

/**
 * 下载文件
 * 通过创建 Blob 对象和临时 <a> 标签触发浏览器下载
 * 支持文本文件和二进制文件（Base64 编码）
 *
 * @param {Object} file - 文件对象，包含 filename、content、mime、isBinary 字段
 */
function downloadFile(file) {
    let blob;
    if (file.isBinary && file.content) {
        // 二进制文件：将 Base64 字符串解码为二进制数据
        const byteCharacters = atob(file.content);
        const byteNumbers = new Array(byteCharacters.length);
        for (let i = 0; i < byteCharacters.length; i++) {
            byteNumbers[i] = byteCharacters.charCodeAt(i);
        }
        const byteArray = new Uint8Array(byteNumbers);
        blob = new Blob([byteArray], { type: file.mime });
    } else {
        // 文本文件：直接使用字符串内容
        blob = new Blob([file.content], { type: file.mime + ';charset=utf-8' });
    }
    const url = URL.createObjectURL(blob);
    // 创建临时 <a> 标签触发下载
    const a = document.createElement('a');
    a.href = url;
    a.download = file.filename;
    document.body.appendChild(a);
    a.click();
    // 清理临时元素和 URL
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
    showToast(`已下载: ${file.filename}`, 'success');
}

/**
 * 根据文件名获取文件标签文本
 *
 * @param {string} filename 文件名
 * @returns {string} 文件标签
 */
function getFileLabel(filename) {
    const ext = filename.split('.').pop().toLowerCase();
    const labelMap = {
        'pptx': 'PowerPoint 文件',
        'docx': 'Word 文件',
        'xlsx': 'Excel 文件',
        'pdf': 'PDF 文件',
        'png': 'PNG 图片',
        'jpg': 'JPEG 图片',
        'jpeg': 'JPEG 图片',
        'gif': 'GIF 图片',
        'zip': 'ZIP 压缩包',
        'html': 'HTML 文件',
        'json': 'JSON 文件',
        'csv': 'CSV 文件',
        'md': 'Markdown 文件',
        'txt': '文本文件'
    };
    return labelMap[ext] || '文件';
}

/**
 * 根据文件名获取文件图标
 *
 * @param {string} filename 文件名
 * @returns {string} 图标 emoji
 */
function getFileIconByExt(filename) {
    const ext = filename.split('.').pop().toLowerCase();
    const iconMap = {
        'pptx': '📊',
        'docx': '📄',
        'xlsx': '📈',
        'pdf': '📕',
        'png': '🖼️',
        'jpg': '🖼️',
        'jpeg': '🖼️',
        'gif': '🖼️',
        'zip': '📦',
        'html': '🌐',
        'json': '📋',
        'csv': '📋',
        'md': '📝',
        'txt': '📄'
    };
    return iconMap[ext] || '📎';
}

/**
 * 复制文本到剪贴板
 * 使用 Clipboard API，并给出成功/失败提示
 *
 * @param {string} text - 要复制的文本内容
 */
function copyToClipboard(text) {
    navigator.clipboard.writeText(text).then(() => {
        showToast('已复制到剪贴板', 'success');
    }).catch(() => {
        showToast('复制失败', 'error');
    });
}

/**
 * 渲染导航提示区域
 * 根据当前会话状态控制返回、继续按钮的可用性
 */
function renderNavPrompt() {
    const prompt = document.getElementById('nav-prompt');
    const backBtn = document.getElementById('nav-back-btn');
    const continueBtn = document.getElementById('nav-continue-btn');

    // 查看历史轮次时隐藏导航按钮
    const isViewingLatest = viewingRound === currentSession.currentRound;

    // 根据会话能力控制按钮可用状态（仅在查看最新轮次时可用）
    backBtn.disabled = !isViewingLatest || !currentSession.canGoBack;
    continueBtn.disabled = !isViewingLatest || !currentSession.canContinue;

    // 至少有一个执行轮次时才显示导航提示
    prompt.style.display = currentSession.rounds.length > 0 ? 'block' : 'none';
}

/**
 * 导航：返回上一轮按钮点击事件
 */
document.getElementById('nav-back-btn').addEventListener('click', () => {
    performGoBack(1);
});

/**
 * 导航：重新执行按钮点击事件
 * 调用后端 /api/sessions/{sessionId}/reexecute 接口重新执行当前轮次
 */
document.getElementById('nav-reexecute-btn').addEventListener('click', async () => {
    if (!currentSession) return;
    showLoading('正在重新执行...');
    try {
        const result = await api(`/api/sessions/${currentSession.sessionId}/reexecute`, {
            method: 'POST',
            body: JSON.stringify({})
        });
        if (result.code === 200) {
            currentSession = result.data;
            viewingRound = null;
            renderSession();
            showToast('重新执行完成', 'success');
        } else {
            showToast(result.message || '重新执行失败', 'error');
        }
    } catch (e) {
        showToast('重新执行失败: ' + e.message, 'error');
    }
    hideLoading();
});

/**
 * 回复提交按钮点击事件
 * 将用户回复与当前轮次的原始输入和上一轮输出合并，调用 continue 接口创建新一轮
 * 适用于技能提出问题后用户补充信息的场景
 * 使用 continue 而非 reexecute，确保每轮回复都在执行链中显示为独立节点
 */
document.getElementById('reply-submit-btn').addEventListener('click', async () => {
    if (!currentSession) return;
    const replyText = document.getElementById('reply-input').value.trim();
    if (!replyText) {
        showToast('请输入回复内容', 'error');
        return;
    }

    // 获取当前轮次信息
    const rounds = currentSession.rounds || [];
    const current = rounds.find(r => r.round === currentSession.currentRound);
    if (!current) {
        showToast('无法获取当前轮次信息', 'error');
        return;
    }

    // 合并原始输入、上一轮输出与用户回复，让技能看到完整的对话上下文
    const originalInput = current.input || '';
    const previousOutput = current.output || '';
    const combinedInput = originalInput
        + '\n\n上一轮技能输出:\n' + previousOutput
        + '\n\n用户补充回复:\n' + replyText;

    showLoading('正在合并回复并执行新一轮...');
    try {
        // 使用 continue 接口创建新一轮，而非 reexecute 替换当前轮次
        const result = await api(`/api/sessions/${currentSession.sessionId}/continue`, {
            method: 'POST',
            body: JSON.stringify({
                skillId: current.skillId,
                input: combinedInput
            })
        });
        if (result.code === 200) {
            currentSession = result.data;
            viewingRound = null;
            // 清空回复输入框
            document.getElementById('reply-input').value = '';
            renderSession();
            showToast('已合并回复并执行新一轮', 'success');
        } else {
            showToast(result.message || '执行失败', 'error');
        }
    } catch (e) {
        showToast('执行失败: ' + e.message, 'error');
    }
    hideLoading();
});

/**
 * 导航：继续下一轮按钮点击事件
 * 自动使用 AI 路由找到的候选技能，无需手动搜索。
 * 如果只有一个候选技能，直接执行；如果有多个，显示候选列表供用户选择。
 */
document.getElementById('nav-continue-btn').addEventListener('click', () => {
    if (!nextRoundCandidates || nextRoundCandidates.length === 0) {
        showToast('未找到可用的下一轮技能', 'error');
        return;
    }

    // 如果只有一个候选技能，直接执行
    if (nextRoundCandidates.length === 1) {
        const c = nextRoundCandidates[0];
        executeContinue(c.skillId, c.skillName, c.reason);
        return;
    }

    // 多个候选技能，显示候选列表供用户选择
    const panel = document.getElementById('continue-panel');
    panel.style.display = 'block';

    // 构建候选技能列表 HTML（复用路由结果的候选卡片样式）
    const candidateListHtml = nextRoundCandidates.map((c, idx) => {
        const score = c.score != null ? Math.round(c.score) : '--';
        let scoreClass = 'score-medium';
        if (score !== '--') {
            if (score >= 85) scoreClass = 'score-high';
            else if (score < 60) scoreClass = 'score-low';
        }
        const bestBadge = idx === 0 ? '<span class="candidate-badge-best">最佳匹配</span>' : '';
        return `
            <div class="candidate-card">
                <div class="candidate-card-header">
                    <div class="candidate-card-title">
                        ${bestBadge}
                        <span class="candidate-name">${escapeHtml(c.skillName || c.skillId)}</span>
                    </div>
                    <div class="candidate-score ${scoreClass}">
                        <span class="score-value">${score}</span>
                        <span class="score-label">匹配度</span>
                    </div>
                </div>
                <div class="candidate-reason">${escapeHtml(c.reason || '未提供匹配理由')}</div>
                <div class="candidate-actions">
                    <button class="btn btn-primary btn-sm continue-candidate-btn"
                            data-skill-id="${escapeHtml(c.skillId)}"
                            data-skill-name="${escapeHtml(c.skillName || c.skillId)}"
                            data-reason="${escapeHtml(c.reason || '')}">
                        <span class="btn-icon">▶</span> 执行此技能
                    </button>
                </div>
            </div>
        `;
    }).join('');

    // 渲染继续执行面板内容
    panel.innerHTML = `
        <div class="panel-sub-header">
            <h3>继续下一轮</h3>
            <p>AI 自动匹配到 ${nextRoundCandidates.length} 个候选技能，选择一个继续执行</p>
        </div>
        <div class="candidate-list">${candidateListHtml}</div>
        <div class="exec-actions" style="margin-top:16px;">
            <button id="cancel-continue-btn" class="btn btn-secondary">取消</button>
        </div>
    `;

    // 为每个候选技能的执行按钮绑定事件
    panel.querySelectorAll('.continue-candidate-btn').forEach(btn => {
        btn.addEventListener('click', () => {
            executeContinue(btn.dataset.skillId, btn.dataset.skillName, btn.dataset.reason);
        });
    });

    // 绑定取消按钮
    panel.querySelector('#cancel-continue-btn').addEventListener('click', () => {
        panel.style.display = 'none';
    });

    // 平滑滚动到继续执行面板
    panel.scrollIntoView({ behavior: 'smooth', block: 'start' });
});

/**
 * 执行继续下一轮（使用自动路由选中的技能）
 * 使用当前轮次的输出作为下一轮输入，调用 continue API
 *
 * @param {string} skillId    - 选中的技能 ID
 * @param {string} skillName  - 技能名称
 * @param {string} reason     - 选择理由
 */
async function executeContinue(skillId, skillName, reason) {
    if (!currentSession) return;

    // 使用当前轮次的输出作为下一轮输入
    const input = currentSession.currentOutput || '';

    showLoading('正在继续执行: ' + skillName + ' ...');
    try {
        const result = await api(`/api/sessions/${currentSession.sessionId}/continue`, {
            method: 'POST',
            body: JSON.stringify({
                skillId: skillId,
                input: input
            })
        });
        if (result.code === 200) {
            currentSession = result.data;
            viewingRound = null;
            // 隐藏继续执行面板
            document.getElementById('continue-panel').style.display = 'none';
            renderSession();
            showToast('继续执行成功: ' + skillName, 'success');
        } else {
            showToast(result.message || '继续执行失败', 'error');
        }
    } catch (e) {
        showToast('继续执行失败: ' + e.message, 'error');
    }
    hideLoading();
}

/**
 * 新建会话按钮点击事件
 * 确认后重置所有会话状态和 UI，回到初始选择界面
 */
document.getElementById('new-session-btn').addEventListener('click', () => {
    if (!confirm('确定要新建会话吗？当前会话将丢失。')) return;
    // 重置所有全局状态
    currentSession = null;
    viewingRound = null;
    currentRouteInfo = null;
    selectedSkillForExec = null;
    execPendingFiles = [];
    // 重置 UI 到初始状态
    document.getElementById('execute-init').style.display = 'block';
    document.getElementById('execute-session').style.display = 'none';
    document.getElementById('floating-reply-btn').style.display = 'none';
    document.getElementById('exec-input-text').value = '';
    document.getElementById('reply-input').value = '';
    document.getElementById('manual-search-section').style.display = 'none';
    document.getElementById('exec-search-input').value = '';
    document.getElementById('exec-skill-results').innerHTML = '';
    document.getElementById('exec-manual-input-area').style.display = 'none';
    renderExecFileList();
    showToast('已新建会话', 'info');
});

// ==================== 技能详情弹窗 (Skill Detail Modal) ====================

/** 当前正在查看详情的技能 ID */
let currentDetailSkillId = null;

/**
 * 显示技能详情弹窗
 * 获取技能详情并渲染到模态框中，包含描述、标签、输入/输出类型、模型配置、提示词模板和文件列表
 *
 * @param {string} skillId - 技能 ID
 */
async function showSkillDetail(skillId) {
    currentDetailSkillId = skillId;
    try {
        const result = await api(`/api/skills/${skillId}`);
        if (result.code !== 200) {
            showToast('加载详情失败', 'error');
            return;
        }

        const skill = result.data;
        document.getElementById('modal-title').textContent = skill.name;

        const tags = (skill.tags || []).join(', ');

        // 渲染技能详情内容到模态框
        document.getElementById('modal-body').innerHTML = `
            <div class="detail-row">
                <span class="detail-label">描述</span>
                <span class="detail-value">${escapeHtml(skill.description || '暂无')}</span>
            </div>
            <div class="detail-row">
                <span class="detail-label">标签</span>
                <span class="detail-value">${escapeHtml(tags || '无')}</span>
            </div>
            <div class="detail-row">
                <span class="detail-label">输入类型</span>
                <span class="detail-value">${escapeHtml(skill.inputType || 'text')}</span>
            </div>
            <div class="detail-row">
                <span class="detail-label">输出类型</span>
                <span class="detail-value">${escapeHtml(skill.outputType || 'text')}</span>
            </div>
            ${skill.model ? `<div class="detail-row"><span class="detail-label">模型</span><span class="detail-value">${escapeHtml(skill.model)}</span></div>` : ''}
            ${skill.temperature != null ? `<div class="detail-row"><span class="detail-label">温度</span><span class="detail-value">${skill.temperature}</span></div>` : ''}
            <div class="detail-row" style="margin-top:16px;">
                <span class="detail-label">提示词模板</span>
            </div>
            <div class="prompt-template">${escapeHtml(skill.promptTemplate || '暂无')}</div>
            <div class="detail-row" style="margin-top:20px;">
                <span class="detail-label">全部文件</span>
                <span class="detail-value" style="font-size:12px;color:var(--gray-400);">点击文件名查看内容</span>
            </div>
            <div id="all-files-list" class="all-files-list">
                <p style="color:var(--gray-400);font-size:13px;padding:12px;">加载文件列表中...</p>
            </div>
            <div id="file-content-viewer" class="file-content-viewer" style="display:none;">
                <div class="file-viewer-header">
                    <span id="file-viewer-name" class="file-viewer-name"></span>
                    <button class="btn btn-sm btn-secondary" onclick="closeFileViewer()">关闭</button>
                </div>
                <div id="file-viewer-content" class="file-viewer-content"></div>
            </div>
        `;

        // 显示模态框
        document.getElementById('skill-modal').style.display = 'flex';

        // 异步加载技能包含的全部文件列表
        loadAllSkillFiles(skillId);
    } catch (e) {
        showToast('加载详情失败: ' + e.message, 'error');
    }
}

/**
 * 加载技能包含的全部文件列表
 * 调用 /api/skills/{skillId}/files/list 接口获取文件列表并渲染
 *
 * @param {string} skillId - 技能 ID
 */
async function loadAllSkillFiles(skillId) {
    const container = document.getElementById('all-files-list');
    try {
        const result = await api(`/api/skills/${skillId}/files/list`);
        if (result.code === 200 && result.data.length > 0) {
            const files = result.data;
            // 渲染每个文件项，点击可查看内容
            container.innerHTML = files.map(f => {
                const icon = f.isBinary ? '📦' : getFileIconByExt(f.name);
                return `
                    <div class="all-file-item" onclick="viewFileContent('${escapeHtml(f.path)}', '${escapeHtml(f.name)}', ${f.isBinary})">
                        <span class="all-file-icon">${icon}</span>
                        <div class="all-file-info">
                            <span class="all-file-name">${escapeHtml(f.name)}</span>
                            <span class="all-file-path">${escapeHtml(f.path)}</span>
                        </div>
                        <span class="all-file-size">${formatSize(f.size)}</span>
                        <span class="all-file-view">👁 查看</span>
                    </div>
                `;
            }).join('');
        } else {
            container.innerHTML = '<p style="color:var(--gray-400);font-size:13px;padding:12px;">暂无文件</p>';
        }
    } catch (e) {
        container.innerHTML = '<p style="color:var(--danger);font-size:13px;padding:12px;">加载文件列表失败</p>';
    }
}

/**
 * 根据文件扩展名返回对应的图标 emoji
 *
 * @param {string} filename - 文件名
 * @returns {string} 对应的 emoji 图标
 */
function getFileIconByExt(filename) {
    const lower = filename.toLowerCase();
    if (lower.endsWith('.md')) return '📝';
    if (lower.endsWith('.py') || lower.endsWith('.sh') || lower.endsWith('.js') || lower.endsWith('.go') || lower.endsWith('.java')) return '📜';
    if (lower.endsWith('.json') || lower.endsWith('.yaml') || lower.endsWith('.yml') || lower.endsWith('.xml')) return '⚙️';
    if (lower.endsWith('.txt') || lower.endsWith('.csv')) return '📄';
    if (lower.endsWith('.html') || lower.endsWith('.css')) return '🌐';
    return '📄';
}

/**
 * 查看技能文件内容
 * 调用 /api/skills/{skillId}/files/content 接口获取文件内容并渲染到查看器
 * 支持文本文件在线预览，二进制文件仅显示提示信息
 *
 * @param {string} filePath - 文件路径
 * @param {string} fileName - 文件名
 * @param {boolean} isBinary - 是否为二进制文件
 */
async function viewFileContent(filePath, fileName, isBinary) {
    const viewer = document.getElementById('file-content-viewer');
    const viewerName = document.getElementById('file-viewer-name');
    const viewerContent = document.getElementById('file-viewer-content');

    viewerName.textContent = fileName;
    viewer.style.display = 'block';
    viewerContent.innerHTML = '<p style="color:var(--gray-400);padding:16px;">加载中...</p>';

    // 滚动到查看器位置
    viewer.scrollIntoView({ behavior: 'smooth', block: 'nearest' });

    try {
        const result = await api(`/api/skills/${currentDetailSkillId}/files/content?path=${encodeURIComponent(filePath)}`);
        if (result.code === 200) {
            const data = result.data;
            if (data.isBinary) {
                // 二进制文件不支持在线预览
                viewerContent.innerHTML = `
                    <div class="binary-file-info">
                        <span>📦</span>
                        <p>这是一个二进制文件（${formatSize(data.size)}）</p>
                        <p style="font-size:12px;color:var(--gray-400);">不支持在线预览二进制文件</p>
                    </div>
                `;
            } else {
                const content = data.content || '';
                const ext = fileName.toLowerCase().split('.').pop();
                // 根据文件类型使用不同的样式渲染内容
                if (ext === 'md') {
                    viewerContent.innerHTML = `<pre class="file-content-text">${escapeHtml(content)}</pre>`;
                } else if (['py', 'sh', 'js', 'go', 'java', 'json', 'yaml', 'yml', 'xml', 'html', 'css', 'sql'].includes(ext)) {
                    viewerContent.innerHTML = `<pre class="file-content-code">${escapeHtml(content)}</pre>`;
                } else {
                    viewerContent.innerHTML = `<pre class="file-content-text">${escapeHtml(content)}</pre>`;
                }
            }
        } else {
            viewerContent.innerHTML = `<p style="color:var(--danger);padding:16px;">加载失败: ${escapeHtml(result.message || '')}</p>`;
        }
    } catch (e) {
        viewerContent.innerHTML = `<p style="color:var(--danger);padding:16px;">加载失败: ${escapeHtml(e.message)}</p>`;
    }
}

/**
 * 关闭文件内容查看器
 */
function closeFileViewer() {
    document.getElementById('file-content-viewer').style.display = 'none';
}

/**
 * 关闭技能详情模态框
 */
function closeModal() {
    document.getElementById('skill-modal').style.display = 'none';
}

// ==================== 工具函数 (Utility) ====================

/**
 * HTML 转义函数
 * 将文本中的特殊字符转义为 HTML 实体，防止 XSS 攻击
 *
 * @param {string} text - 需要转义的文本
 * @returns {string} 转义后的安全 HTML 字符串
 */
function escapeHtml(text) {
    if (text == null) return '';
    const div = document.createElement('div');
    div.textContent = String(text);
    return div.innerHTML;
}

// ==================== 浮动回复按钮事件 ====================

/**
 * 浮动回复按钮点击事件
 * 滚动到回复区域并聚焦输入框
 */
document.getElementById('floating-reply-btn').addEventListener('click', () => {
    const replySection = document.getElementById('reply-section');
    if (replySection) {
        replySection.scrollIntoView({ behavior: 'smooth', block: 'center' });
        // 延迟聚焦，等待滚动完成
        setTimeout(() => {
            const replyInput = document.getElementById('reply-input');
            if (replyInput) replyInput.focus();
        }, 600);
    }
});

// ==================== 初始化 (Init) ====================

// 清理可能残留的上传进度条（页面刷新后 XHR 已中断，进度条不应继续显示）
const staleOverlay = document.getElementById('upload-progress-overlay');
if (staleOverlay) {
    staleOverlay.remove();
}

// 页面加载时检查 AI 状态并加载技能列表
checkAiStatus();
loadSkills();
