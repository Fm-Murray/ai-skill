# AI 技能编排系统 - 项目详细文档

## 目录

- [1. 项目概述](#1-项目概述)
- [2. 技术栈](#2-技术栈)
- [3. 项目架构](#3-项目架构)
- [4. 目录结构](#4-目录结构)
- [5. 核心模型说明](#5-核心模型说明)
- [6. 存储层设计](#6-存储层设计)
- [7. API 接口文档](#7-api-接口文档)
- [8. 两轮大模型调用架构](#8-两轮大模型调用架构)
- [9. 核心调用流程](#9-核心调用流程)
- [10. 技能格式规范](#10-技能格式规范)
- [11. 字符编码处理](#11-字符编码处理)
- [12. 脚本执行机制](#12-脚本执行机制)
- [13. 前端架构](#13-前端架构)
- [14. 配置说明](#14-配置说明)
- [15. 部署指南](#15-部署指南)

---

## 1. 项目概述

本项目是一个基于 Spring Boot 2.7.18 的 AI 技能编排系统，核心能力是通过**两轮大模型调用架构**实现用户意图识别、技能自动路由、内容生成和脚本后处理的完整流水线。

系统支持：
- 技能上传（松散文件 / 压缩包 ZIP / TAR.GZ）
- 技能管理（列表、详情、文件浏览、删除）
- AI 语义搜索与自动路由（多候选展示 + 思考过程）
- 多轮链式执行（会话导航：前进 / 回退 / 重新执行）
- Python 脚本后处理（自动依赖安装、argparse 参数模式、二进制文件 Base64 编码）
- 中文编码兼容（UTF-8 / GBK 自动检测）

---

## 2. 技术栈

| 层级 | 技术 | 说明 |
|------|------|------|
| 后端框架 | Spring Boot 2.7.18 | JDK 8 |
| HTTP 客户端 | RestTemplate | 调用 OpenAI 兼容 API |
| 存储后端 | FTP (commons-net 3.10.0) | 通过 StorageService 抽象层 |
| 压缩处理 | commons-compress 1.26.1 | ZIP / TAR.GZ 解压 |
| YAML 解析 | SnakeYAML | 解析 skill.md 前置元数据 |
| JSON 处理 | Jackson | 请求构建与响应解析 |
| 前端 | 原生 HTML / CSS / JavaScript | 无框架依赖 |
| 构建工具 | Maven | spring-boot-maven-plugin |

---

## 3. 项目架构

### 3.1 整体架构图

```
┌─────────────────────────────────────────────────────────────────┐
│                        浏览器前端 (HTML/JS)                       │
│  ┌──────────┐  ┌──────────────┐  ┌──────────────┐              │
│  │ 技能上传  │  │  技能管理     │  │  技能执行     │              │
│  └─────┬────┘  └──────┬───────┘  └──────┬───────┘              │
└────────┼──────────────┼─────────────────┼──────────────────────┘
         │              │                 │
         ▼              ▼                 ▼
┌─────────────────────────────────────────────────────────────────┐
│                    Controller 层 (REST API)                      │
│  ┌─────────────────┐         ┌──────────────────────┐           │
│  │ SkillController  │         │ ExecutionController   │           │
│  │ /api/skills/**   │         │ /api/sessions/**      │           │
│  └────────┬────────┘         └──────────┬───────────┘           │
└───────────┼─────────────────────────────┼───────────────────────┘
            │                             │
            ▼                             ▼
┌─────────────────────────────────────────────────────────────────┐
│                      Service 层 (业务逻辑)                       │
│  ┌─────────────────┐  ┌──────────────────┐  ┌───────────────┐  │
│  │  SkillService    │  │ ExecutionService  │  │  AiService    │  │
│  │ - 技能上传/解析   │  │ - 会话管理         │  │ - LLM 调用    │  │
│  │ - 技能搜索       │  │ - 两轮执行         │  │ - 路由判断    │  │
│  │ - 上下文装配     │  │ - 脚本后处理       │  │ - 思考过程    │  │
│  └────────┬────────┘  └────────┬─────────┘  └───────┬───────┘  │
└───────────┼────────────────────┼───────────────────┼───────────┘
            │                    │                    │
            ▼                    │                    ▼
┌──────────────────────┐         │     ┌──────────────────────┐
│  StorageService (FTP) │         │     │  OpenAI 兼容 API      │
│  FtpStorageService    │         │     │  (Ollama/GLM/Qwen3)  │
│  - store / read       │         │     │  /chat/completions    │
│  - list / delete      │         │     └──────────────────────┘
└──────────────────────┘         │
                                 ▼
                    ┌──────────────────────┐
                    │  Python 脚本执行      │
                    │  - 依赖自动安装       │
                    │  - argparse 模式     │
                    │  - 二进制文件处理     │
                    └──────────────────────┘
```

### 3.2 分层职责

| 层级 | 职责 | 核心类 |
|------|------|--------|
| Controller | HTTP 路由、参数校验、响应封装 | `SkillController`, `ExecutionController` |
| Service | 业务逻辑、AI 调用、脚本执行 | `SkillService`, `ExecutionService`, `AiService` |
| Storage | 文件持久化抽象 | `StorageService` -> `FtpStorageService` |
| Model | 数据实体与 DTO | `Skill`, `ExecutionSession`, `RouteResult` 等 |
| Config | 配置注入与 CORS | `AiConfig`, `CorsConfig`, `StorageConfig` |

---

## 4. 目录结构

```
springboot-ai-skill/
├── pom.xml                          # Maven 构建配置
├── src/main/
│   ├── java/com/example/aiskill/
│   │   ├── AiSkillApplication.java          # 应用入口
│   │   ├── config/
│   │   │   ├── AiConfig.java               # AI 配置 + RestTemplate Bean
│   │   │   ├── CorsConfig.java             # 跨域配置
│   │   ├── controller/
│   │   │   ├── SkillController.java         # 技能管理接口 (/api/skills)
│   │   │   ├── ExecutionController.java     # 执行会话接口 (/api/sessions)
│   │   ├── model/
│   │   │   ├── ApiResult.java              # 统一响应封装
│   │   │   ├── Skill.java                  # 技能实体
│   │   │   ├── SkillIndex.java             # 轻量技能索引
│   │   │   ├── ExecutionSession.java       # 执行会话
│   │   │   ├── ExecutionRound.java         # 执行轮次
│   │   │   ├── RouteResult.java            # 路由判断结果（单候选）
│   │   │   ├── RouteCandidates.java        # 路由判断结果（多候选）
│   │   │   └── dto/
│   │   │       ├── ExecuteRequest.java     # 执行请求 DTO
│   │   │       ├── ContinueRequest.java    # 继续执行请求 DTO
│   │   │       └── SearchRequest.java      # 搜索请求 DTO
│   │   ├── service/
│   │   │   ├── AiService.java              # AI 服务（LLM 调用 + 路由判断）
│   │   │   ├── SkillService.java           # 技能服务（上传/解析/搜索/上下文装配）
│   │   │   └── ExecutionService.java       # 执行服务（会话管理/两轮执行/脚本后处理）
│   │   └── storage/
│   │       ├── StorageService.java          # 存储抽象接口
│   │       ├── FtpStorageService.java       # FTP 存储实现
│   │       ├── StorageConfig.java           # 存储 Bean 配置
│   │       └── FileInfo.java               # 文件信息实体
│   └── resources/
│       ├── application.yml                  # 应用配置
│       └── static/
│           ├── index.html                   # 主页面
│           ├── css/style.css                # 样式表
│           └── js/app.js                    # 前端逻辑
```

---

## 5. 核心模型说明

### 5.1 Skill（技能实体）

```
Skill
├── id: String                 # 技能唯一标识 (skill_UUID前8位)
├── name: String               # 技能名称
├── description: String        # 技能描述
├── tags: List<String>         # 技能标签
├── inputType: String          # 输入类型
├── outputType: String         # 输出类型
├── model: String              # AI 模型名称（覆盖默认配置）
├── temperature: Double        # 采样温度（覆盖默认配置）
├── maxTokens: Integer         # 最大 Token 数（覆盖默认配置）
├── promptTemplate: String     # Prompt 模板正文
├── files: List<SkillFile>     # 附属文件列表
├── storagePath: String        # 存储路径
├── createdAt: LocalDateTime   # 创建时间
│
└── SkillFile（内部类）
    ├── name: String           # 文件名
    ├── type: String           # 类型: material | script | md | other
    ├── size: long             # 文件大小（字节）
    └── relativePath: String   # 相对于技能目录的路径
```

### 5.2 ExecutionSession（执行会话）

```
ExecutionSession
├── sessionId: String          # 会话唯一标识
├── rounds: List<ExecutionRound>  # 执行轮次列表
├── currentRound: int          # 当前轮次编号
├── createdAt: LocalDateTime   # 创建时间
├── canGoBack(): boolean       # 是否可回退 (currentRound > 1)
├── canContinue(): boolean     # 是否可继续 (最新轮次有输出)
├── getCurrentOutput(): String # 获取当前轮次输出
└── getCurrentInput(): String  # 获取当前轮次输入
```

### 5.3 ExecutionRound（执行轮次）

```
ExecutionRound
├── round: int                     # 轮次编号（从1开始）
├── skillId: String                # 使用的技能 ID
├── skillName: String              # 使用的技能名称
├── input: String                  # 本轮输入
├── output: String                 # 本轮输出（AI 生成 + 脚本后处理）
├── continuedFromPrevious: boolean # 是否从上一轮输出继续
├── timestamp: LocalDateTime       # 执行时间
├── status: String                 # running | success | error
└── errorMessage: String           # 错误信息（status=error 时）
```

### 5.4 路由判断模型

```
SkillIndex（轻量索引 - 第一轮输入）
├── id: String
├── name: String
├── description: String
└── tags: String

RouteResult（单候选结果）
├── skillId: String
├── skillName: String
├── reason: String            # 选择理由
└── score: Double             # 匹配分数 (0-100)

RouteCandidates（多候选结果）
├── userInput: String          # 用户原始输入
├── candidates: List<RouteResult>  # 候选列表（按分数降序）
├── thinkingProcess: String    # AI 思考过程
├── size(): int
└── isSingleCandidate(): boolean
```

### 5.5 ApiResult（统一响应封装）

```
ApiResult<T>
├── code: int        # 200=成功, 400=参数错误, 404=未找到, 500=服务器错误
├── message: String  # 响应消息
└── data: T          # 业务数据
```

---

## 6. 存储层设计

### 6.1 存储抽象接口

`StorageService` 接口定义了统一的文件操作契约，屏蔽底层存储实现细节：

| 方法 | 功能 |
|------|------|
| `store(path, content)` | 存储文件（自动创建父目录） |
| `read(path)` | 读取文件为字节数组 |
| `exists(path)` | 判断路径是否存在 |
| `delete(path)` | 删除文件或递归删除目录 |
| `createDirectory(path)` | 创建目录（含父目录） |
| `listDirectories(dirPath)` | 列出直接子目录名 |
| `listFiles(dirPath)` | 递归列出所有文件 |
| `init()` | 初始化存储后端 |

### 6.2 FTP 存储实现

`FtpStorageService` 是当前唯一的存储实现，采用**每次操作新建连接**策略：

```
操作流程:
  connect() → login → setFileType(BINARY) → enterLocalPassiveMode
       → 执行操作 (store/read/list/delete)
       → disconnectQuietly()
```

关键设计决策：
- **短连接策略**：每次操作新建 FTP 连接，避免空闲超时断连问题
- **二进制传输模式**：`FTP.BINARY_FILE_TYPE` 保证文件内容不被篡改
- **被动模式**：`enterLocalPassiveMode()` 穿越防火墙/NAT
- **保活机制**：`setControlKeepAliveTimeout(60)` 防止长传输时控制连接断开

### 6.3 存储目录结构

```
/skills/                          # FTP 根目录 (storage.ftp.base-dir)
├── skill_a1b2c3d4/              # 技能目录 (skill_ + UUID前8位)
│   ├── skill.md                 # 技能描述文件（必需）
│   ├── meta.json                # 技能元数据缓存
│   ├── scripts/                 # 脚本文件目录
│   │   ├── generate_pptx.py
│   │   └── requirements.txt
│   └── materials/               # 素材文件目录
│       └── template.txt
├── skill_e5f6g7h8/
│   └── ...
```

---

## 7. API 接口文档

### 7.1 技能管理接口 (`/api/skills`)

#### POST `/api/skills/upload` - 上传技能

上传技能文件（松散文件或压缩包），必须包含 `skill.md`。

- **请求格式**: `multipart/form-data`
- **参数**: `files` (文件数组，至少包含 skill.md)
- **响应**: `ApiResult<Skill>`

```bash
curl -X POST http://localhost:8080/api/skills/upload \
  -F "files=@skill.md" \
  -F "files=@generate_pptx.py" \
  -F "files=@requirements.txt"
```

#### GET `/api/skills` - 获取技能列表

返回所有已上传技能的摘要信息。

- **响应**: `ApiResult<List<Map>>`，每项包含 id, name, description, tags, fileCount, createdAt

#### GET `/api/skills/{skillId}` - 获取技能详情

- **路径参数**: `skillId` - 技能 ID
- **响应**: `ApiResult<Skill>`

#### GET `/api/skills/{skillId}/files/list` - 列举技能文件

递归列出技能目录下的所有文件。

- **响应**: `ApiResult<List<Map>>`，每项包含 name, path, size, isBinary

#### GET `/api/skills/{skillId}/files/content?path=xxx` - 读取文件内容

- **查询参数**: `path` - 文件相对路径
- **响应**: `ApiResult<Map>`，包含 name, size, isBinary, content
  - 文本文件: content 为字符串（自动检测编码）
  - 二进制文件: content 为 Base64 编码字符串

#### DELETE `/api/skills/{skillId}` - 删除技能

删除技能元数据及存储中的全部文件。

- **响应**: `ApiResult<Void>`

#### POST `/api/skills/search` - 搜索技能

- **请求体**: `{"description": "搜索描述", "useAi": true}`
- **响应**: `ApiResult<List<Map>>`

#### GET `/api/skills/ai-status` - AI 配置状态

- **响应**: `ApiResult<Map>`，包含 `configured` 布尔字段

---

### 7.2 技能执行接口 (`/api/sessions`)

#### POST `/api/sessions` - 启动会话（指定技能）

- **请求体**: `{"skillId": "skill_xxx", "input": "输入文本"}`
- **响应**: `ApiResult<Map>`，包含 sessionId, currentRound, rounds, canGoBack, canContinue, currentOutput

#### POST `/api/sessions/with-files` - 启动会话（带文件）

- **请求格式**: `multipart/form-data`
- **参数**: `skillId` (必填), `input` (选填), `files` (选填)
- 文件内容以 `=== 上传文件: 文件名 ===` 标签拼接至文本输入后

#### POST `/api/sessions/auto-execute` - AI 自动选择并执行（纯文本）

两轮大模型调用一步完成：路由判断 → 内容生成 → 后处理。

- **请求体**: `{"input": "输入文本"}`
- **响应**: 额外包含 `autoSelectedSkill`, `selectedSkillName`, `selectedSkillId`, `routeReason`, `twoRoundExecution`

#### POST `/api/sessions/auto-execute-with-files` - AI 自动选择并执行（带文件）

- **请求格式**: `multipart/form-data`
- **参数**: `input` (选填), `files` (选填)

#### POST `/api/sessions/route` - 第一轮路由判断（仅路由）

返回多候选技能列表和 AI 思考过程，不执行内容生成。

- **请求体**: `{"input": "输入文本"}`
- **响应**: `ApiResult<Map>`，包含 candidates, thinkingProcess, userInput, candidateCount

#### POST `/api/sessions/route-with-files` - 第一轮路由判断（带文件）

- **请求格式**: `multipart/form-data`

#### POST `/api/sessions/execute-routed` - 第二轮内容生成

用户从候选列表选择技能后，执行第二轮内容生成。

- **请求体**: `{"input": "输入文本", "skillId": "skill_xxx", "routeReason": "理由", "thinkingProcess": "思考过程"}`
- **响应**: 同 auto-execute，额外包含 `userSelected: true`

#### POST `/api/sessions/{sessionId}/continue` - 继续下一轮

- **请求体**: `{"skillId": "skill_xxx", "input": "可选输入"}`
- input 为空时自动使用上一轮输出

#### POST `/api/sessions/{sessionId}/continue-with-files` - 继续下一轮（带文件）

- **请求格式**: `multipart/form-data`

#### POST `/api/sessions/{sessionId}/back` - 返回上一轮

移除当前轮次，指针回退。

#### POST `/api/sessions/{sessionId}/reexecute` - 重新执行当前轮次

- **请求体**(可选): `{"input": "可选新输入"}`

#### GET `/api/sessions/{sessionId}` - 查询会话状态

#### POST `/api/sessions/{sessionId}/auto-continue-route` - 自动寻找下一轮技能

基于当前输出自动路由判断，返回候选技能。前端据此控制"继续下一轮"按钮的显示/隐藏。

- **响应**: `ApiResult<Map>`，包含 `hasNextSkill`, `candidates`, `thinkingProcess`

---

## 8. 两轮大模型调用架构

### 8.1 架构设计

```
┌──────────────────────────────────────────────────────────────────┐
│                     skills 仓库 (FTP 共享存储)                    │
│  ┌─────────┐  ┌─────────┐  ┌─────────┐  ┌─────────┐            │
│  │ skill_A  │  │ skill_B  │  │ skill_C  │  │ skill_D  │           │
│  └────┬────┘  └────┬────┘  └────┬────┘  └────┬────┘            │
└───────┼────────────┼────────────┼────────────┼──────────────────┘
        │            │            │            │
        ▼            ▼            ▼            ▼
┌──────────────────────────────────────────────────────────────────┐
│  步骤1: 加载 skills 索引 (SkillService.loadSkillsIndex)           │
│  仅加载 name + description，不加载完整文档，控制 token 消耗         │
│  → [SkillIndex(id, name, desc, tags), ...]                      │
└──────────────────────────┬───────────────────────────────────────┘
                           │
                           ▼
┌──────────────────────────────────────────────────────────────────┐
│  步骤2: 第一轮 - 路由判断 (AiService.routeSkillMultiple)          │
│  标准 chat 调用，仅传入技能索引，大模型只输出 JSON                 │
│  → RouteCandidates {candidates, thinkingProcess}                 │
│                                                                   │
│  系统提示词核心:                                                  │
│  "你是一个技能路由助手...只输出 JSON...按匹配度排序..."            │
│                                                                   │
│  输出格式:                                                        │
│  {"candidates": [{"skillId":"...", "skillName":"...",             │
│    "reason":"...", "score":95}, ...]}                            │
└──────────────────────────┬───────────────────────────────────────┘
                           │
                    用户选择技能 (或自动选择最高分)
                           │
                           ▼
┌──────────────────────────────────────────────────────────────────┐
│  步骤3: 装配上下文 (SkillService.assembleContext)                 │
│  中间层自建，读取选中技能的完整 skill.md 正文                     │
│  依据 when 规则挂载引用材料，组装完整 prompt                      │
│  → 完整 system prompt 字符串                                     │
└──────────────────────────┬───────────────────────────────────────┘
                           │
                           ▼
┌──────────────────────────────────────────────────────────────────┐
│  步骤4: 第二轮 - 内容生成 (AiService.chat)                       │
│  标准 chat 调用，送入完整上下文                                   │
│  大模型按 skill 业务规程输出结构化 JSON 结果                      │
│  → rawOutput (AI 原始输出)                                      │
└──────────────────────────┬───────────────────────────────────────┘
                           │
                           ▼
┌──────────────────────────────────────────────────────────────────┐
│  步骤5: 后处理与交付 (ExecutionService.postProcess)               │
│  中间层自建:                                                      │
│  1. JSON 输出清理 (cleanJsonOutput) - 去除 Markdown 代码块        │
│  2. Schema 格式校验 - 验证 JSON 有效性                            │
│  3. 脚本执行 - 执行 Python/Shell 脚本对输出进行后处理             │
│  → processedOutput (最终交付内容)                                │
└──────────────────────────────────────────────────────────────────┘
```

### 8.2 设计原则

| 原则 | 说明 |
|------|------|
| 职责分离 | 第一轮只做路由选择（轻量），第二轮才加载完整文档做内容生成 |
| Token 优化 | 第一轮仅传入 name + description，不传入完整 promptTemplate |
| 结构化流转 | 全程以 JSON 流转，后处理再做文档渲染 |
| 中间层承担 | 存储、索引、上下文组装、校验脚本执行由中间层负责，大模型只负责推理 |

### 8.3 AiService 核心方法

| 方法 | 用途 | 特点 |
|------|------|------|
| `chat()` | 标准 LLM 调用 | 返回 content，兼容 reasoning_content 回退 |
| `chatWithReasoning()` | 带思考过程的 LLM 调用 | 同时返回 content 和 reasoning_content |
| `routeSkill()` | 单候选路由判断 | 低温度(0.1)，max_tokens=1000 |
| `routeSkillMultiple()` | 多候选路由判断 | 低温度(0.1)，max_tokens=2000，捕获思考过程 |
| `searchSkillsByAi()` | AI 语义搜索 | 返回排序后的技能 ID 列表 |
| `isConfigured()` | 配置状态检查 | 验证 API Key 是否有效 |

### 8.4 模型兼容性

系统兼容多种 OpenAI 兼容 API：

| 模型/平台 | 兼容处理 |
|-----------|----------|
| Ollama | 支持 `think` 参数（think=false 禁用推理模式） |
| GLM (智谱) | 回退解析 `reasoning_content` 字段 |
| Qwen3 (通义千问) | 回退解析 `reasoning` 字段 |
| 标准 OpenAI | 直接使用 `content` 字段 |

---

## 9. 核心调用流程

### 9.1 AI 自动执行完整流程

```
用户输入 "生成一份升职的PPT"
         │
         ▼
┌─────────────────────────────────────────────┐
│ 前端: app.js                                 │
│ 1. 收集输入文本和上传文件                      │
│ 2. 调用 /api/sessions/route (第一轮)         │
│    或 /api/sessions/auto-execute (一步完成)  │
└─────────────────────┬───────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────────┐
│ 后端: ExecutionController                    │
│ → ExecutionService.routeOnly(input)          │
└─────────────────────┬───────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────────┐
│ ExecutionService.routeOnly()                 │
│                                              │
│ 步骤1: SkillService.loadSkillsIndex()        │
│   → 从内存缓存加载所有技能的轻量索引           │
│   → [SkillIndex{id, name, desc, tags}, ...] │
│                                              │
│ 步骤2: AiService.routeSkillMultiple()        │
│   → chatWithReasoning(systemPrompt, input)   │
│   → 大模型返回多候选 JSON + 思考过程          │
│   → RouteCandidates{candidates, thinking}    │
└─────────────────────┬───────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────────┐
│ 前端: 渲染候选列表和思考过程                   │
│ - 显示 AI 思考过程（可折叠）                   │
│ - 显示候选技能卡片（含匹配分数、选择理由）     │
│ - 用户点击选择一个技能                        │
└─────────────────────┬───────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────────┐
│ 前端: 调用 /api/sessions/execute-routed      │
│ {input, skillId, routeReason, thinking}      │
└─────────────────────┬───────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────────┐
│ ExecutionService.executeRouted()             │
│                                              │
│ 步骤3: SkillService.assembleContext(skill)   │
│   → 读取完整 skill.md prompt 模板            │
│   → 拼接素材文件内容 ({materials})           │
│   → 拼接脚本文件信息 ({scripts})             │
│   → 检测 argparse 脚本，追加 JSON 输出指令    │
│   → 完整 system prompt                       │
│                                              │
│ 步骤4: AiService.chat(context, userMessage)  │
│   → 大模型按 skill 指令生成结构化 JSON        │
│   → rawOutput (AI 原始输出)                  │
│                                              │
│ 步骤5: ExecutionService.postProcess()        │
│   → cleanJsonOutput(): 去除 Markdown 代码块  │
│   → Schema 校验: 验证 JSON 有效性            │
│   → executeScript(): 执行 Python 脚本        │
│     - 检测 argparse 模式                     │
│     - 自动安装缺失依赖 (pip install)          │
│     - 写入 input.txt，执行 --input/--output  │
│     - 读取 output.txt                        │
│     - 二进制文件 Base64 编码                  │
│   → processedOutput (最终结果)               │
│                                              │
│ 创建会话和执行轮次记录                        │
│ → 返回 TwoRoundResult{session, routeResult}  │
└─────────────────────┬───────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────────┐
│ 前端: 渲染执行结果                            │
│ - 显示选中技能信息和路由理由                   │
│ - 渲染输出内容（文本 / 二进制文件下载）        │
│ - 自动调用 /auto-continue-route 寻找下一轮    │
│ - 显示导航按钮:                               │
│   ← 返回上一轮 | ↻ 重新执行 | 继续下一轮 →   │
└─────────────────────────────────────────────┘
```

### 9.2 多轮链式执行流程

```
轮次1: 技能A (输入: 用户原始输入)
  ↓ 输出
轮次2: 技能B (输入: 轮次1的输出，continuedFromPrevious=true)
  ↓ 输出
轮次3: 技能C (输入: 轮次2的输出)
  ↓
  [用户选择"返回上一轮"]
  → 移除轮次3，指针回到轮次2
  [用户选择"重新执行"]
  → 用相同技能和输入重新执行轮次2
  [系统自动找到下一轮候选]
  → 显示"继续下一轮"按钮
  [用户选择"继续下一轮"]
  → 选择候选技能，执行轮次3
```

### 9.3 技能上传流程

```
用户上传文件
     │
     ▼
┌──────────────────────────────────┐
│ SkillController.uploadSkill()    │
│ → SkillService.uploadSkill(files)│
└──────────────┬───────────────────┘
               │
               ▼
┌──────────────────────────────────────────────┐
│ SkillService.uploadSkill()                    │
│                                               │
│ 1. 检测是否包含压缩包 (zip/tar.gz/tar)         │
│    - 是 → uploadSkillFromArchive()            │
│    - 否 → uploadSkillFromLooseFiles()         │
│                                               │
│ 2. 查找 skill.md 文件 (必需)                   │
│    - 未找到 → 抛出 IllegalArgumentException    │
│                                               │
│ 3. 解析 skill.md:                             │
│    - YAML front matter → 元数据 (name, desc...)│
│    - 正文部分 → promptTemplate                │
│                                               │
│ 4. 生成技能 ID: skill_ + UUID前8位             │
│                                               │
│ 5. 存储到 FTP:                                │
│    - skill.md → /skills/skill_xxx/skill.md    │
│    - .py/.sh → /skills/skill_xxx/scripts/     │
│    - .txt/.md → /skills/skill_xxx/materials/  │
│    - 其他 → /skills/skill_xxx/                │
│                                               │
│ 6. 保存 meta.json (技能元数据缓存)             │
│                                               │
│ 7. 更新内存缓存 skillCache                     │
│                                               │
│ 8. 返回 Skill 对象                             │
└──────────────────────────────────────────────┘
```

---

## 10. 技能格式规范

### 10.1 skill.md 文件格式

```markdown
---
# YAML Front Matter (元数据)
name: PPT生成器
description: 根据用户需求生成 PPT 演示文稿
tags: [ppt, presentation, 文档生成]
inputType: text
outputType: pptx
model: glm-5.2
temperature: 0.7
maxTokens: 8192
---

# Prompt 模板正文

你是一个 PPT 生成助手。请根据用户输入生成 PPT 内容...

用户输入:
{input}

参考材料:
{materials}

可用脚本:
{scripts}
```

### 10.2 文件分类规则

| 文件类型 | 扩展名 | 存储目录 | 说明 |
|----------|--------|----------|------|
| 脚本 | .py, .sh, .js, .rb, .pl, .go, .java, .bat | scripts/ | 可执行的后处理脚本 |
| 素材 | .txt, .md, .pdf, .docx, .doc, .csv, .json, .xml, .html, .yaml, .yml | materials/ | 参考材料 |
| 描述 | skill.md | 根目录 | 技能描述文件（必需） |
| 其他 | 其他扩展名 | 根目录 | 其他附属文件 |

### 10.3 压缩包支持

| 格式 | 扩展名 | 处理方式 |
|------|--------|----------|
| ZIP | .zip | ZipFile 解压，自动检测文件名编码 (UTF-8/GBK) |
| TAR.GZ | .tar.gz, .tgz | GzipCompressorInputStream + TarArchiveInputStream |
| TAR | .tar | TarArchiveInputStream |

压缩包必须包含 `skill.md`，解压后按松散文件方式处理。

---

## 11. 字符编码处理

### 11.1 编码检测策略

`SkillService.readTextWithEncodingDetection(byte[])` 方法实现了三级编码检测：

```
输入: byte[] 文件内容
        │
        ▼
┌─────────────────────────────────┐
│ 1. 检查 UTF-8 BOM (EF BB BF)    │
│    存在 → 去除 BOM，UTF-8 解码   │
└──────────────┬──────────────────┘
               │ 不存在 BOM
               ▼
┌─────────────────────────────────┐
│ 2. 尝试 UTF-8 严格解码           │
│    使用 REPORT 策略              │
│    成功 → 返回 UTF-8 解码结果    │
│    失败 (CharacterCodingException) │
└──────────────┬──────────────────┘
               │ 解码失败
               ▼
┌─────────────────────────────────┐
│ 3. 回退到 GBK 解码               │
│    成功 → 返回 GBK 解码结果      │
│    失败 → 使用系统默认编码       │
└─────────────────────────────────┘
```

### 11.2 编码问题修复点

| 修复点 | 问题描述 | 解决方案 |
|--------|----------|----------|
| ZIP 文件名编码 | Windows 中文系统使用 GBK 编码 ZIP 文件名 | `detectZipCharset()` 检测后选择 UTF-8 或 GBK |
| 文件内容读取 | 硬编码 UTF-8 读取 GBK 文件导致乱码 | 全部替换为 `readTextWithEncodingDetection()` |
| RestTemplate | StringHttpMessageConverter 默认 ISO-8859-1 | 设置 `setDefaultCharset(UTF_8)` |
| Multipart 表单 | multipart 解析先于编码过滤器执行 | `resolve-lazily: true` 延迟解析 |
| 脚本输出 | stdout/stderr 可能使用 GBK 编码 | `readStream()` 使用编码检测方法 |

### 11.3 涉及编码处理的文件

- `SkillService.java` - 文件内容读取、ZIP 解压
- `ExecutionService.java` - 脚本输出读取、临时文件写入
- `ExecutionController.java` - 上传文件内容读取
- `AiConfig.java` - RestTemplate 编码配置
- `application.yml` - multipart 延迟解析配置

---

## 12. 脚本执行机制

### 12.1 脚本执行流程

```
postProcess(rawOutput, skill)
        │
        ▼
┌───────────────────────────────────────────┐
│ 1. JSON 输出清理 (argparse 脚本专用)       │
│    cleanJsonOutput(rawOutput)              │
│    - 去除 ```json 代码块标记               │
│    - 去除 ``` 代码块标记                   │
│    - 提取 { 到 } 之间的 JSON               │
│    - 去除 BOM 头                           │
└──────────────────┬────────────────────────┘
                   │
                   ▼
┌───────────────────────────────────────────┐
│ 2. Schema 格式校验                         │
│    尝试解析为 JSON，验证格式有效性          │
│    非 JSON 输出跳过校验                    │
└──────────────────┬────────────────────────┘
                   │
                   ▼
┌───────────────────────────────────────────┐
│ 3. 遍历技能中的脚本文件                    │
│    对每个 .py / .sh 文件:                  │
│                                           │
│    a. 检测 argparse 模式:                  │
│       - 脚本包含 "argparse"                │
│       - 包含 "--input" 或 "-i"             │
│       - 包含 "--output" 或 "-o"            │
│                                           │
│    b. 写入临时文件和目录                    │
│       - 创建临时目录 skill_exec_xxx        │
│       - 写入脚本文件                       │
│       - (argparse) 写入 input.txt          │
│                                           │
│    c. Python 依赖自动安装:                  │
│       - 检查 requirements.txt              │
│       - 扫描 import 语句                   │
│       - python3 -c "import xxx" 检测       │
│       - pip install --break-system-packages│
│                                           │
│    d. 执行脚本:                            │
│       - argparse: --input input.txt        │
│                    --output output.txt     │
│                    超时 120s               │
│       - stdin: 通过 stdin 传入输入          │
│                 超时 60s                   │
│                                           │
│    e. 读取输出:                            │
│       - argparse: 读取 output.txt          │
│         - 二进制 → Base64 编码 JSON        │
│         - 文本 → 编码检测读取              │
│       - stdin: 读取 stdout                 │
│                                           │
│    f. 错误处理:                            │
│       - 非 0 退出码: 附加 stderr 输出      │
│         "--- 脚本错误输出 ---"             │
│       - 超时: 强制终止进程                 │
│                                           │
│    g. 清理临时文件                          │
└───────────────────────────────────────────┘
```

### 12.2 argparse 模式 vs stdin 模式

| 特性 | argparse 模式 | stdin 模式 |
|------|---------------|------------|
| 触发条件 | 脚本含 argparse + --input/--output | 默认模式 |
| 输入传递 | 写入 input.txt，通过 --input 参数 | 通过 stdin 管道 |
| 输出获取 | 读取 output.txt 文件 | 收集 stdout |
| 超时时间 | 120 秒 | 60 秒 |
| 二进制输出 | 支持（Base64 编码） | 不支持 |
| 典型场景 | PPTX 生成、图片生成 | 文本处理、数据转换 |

### 12.3 Python 依赖自动安装

```
installPythonDependencies(skill, scriptText, workDir)
                    │
                    ▼
        ┌───────────────────────┐
        │ 1. 检查 requirements.txt │
        │    存在 → pip install -r │
        │    返回                  │
        └───────────┬───────────┘
                    │ 不存在
                    ▼
        ┌───────────────────────┐
        │ 2. 扫描 import 语句     │
        │    正则匹配:            │
        │    - import xxx         │
        │    - import xxx.yyy     │
        │    - from xxx import    │
        │    排除标准库模块        │
        └───────────┬───────────┘
                    │
                    ▼
        ┌───────────────────────┐
        │ 3. 逐个检查并安装       │
        │    python3 -c "import" │
        │    失败 → pip install   │
        │    import→pip 名映射    │
        └───────────────────────┘
```

常用 import 到 pip 包名映射：

| import 名 | pip 包名 |
|-----------|----------|
| PIL | Pillow |
| cv2 | opencv-python |
| docx | python-docx |
| pptx | python-pptx |
| yaml | PyYAML |
| bs4 | beautifulsoup4 |
| sklearn | scikit-learn |
| dateutil | python-dateutil |
| jwt | PyJWT |
| dotenv | python-dotenv |

### 12.4 二进制文件处理

当脚本输出二进制文件（PPTX、PDF、图片等）时：

```
脚本执行 → output.txt 包含二进制数据
     │
     ▼
isBinaryContent(outputBytes)
  - 检查文件头魔数 (ZIP/PDF/PNG/JPEG/GIF)
  - 检查前 1024 字节是否含 NULL 字节
     │
     ▼ (是二进制)
detectFileExtension(outputBytes, scriptName)
  - PK\x03\x04 + 脚本名含 "ppt" → pptx
  - PK\x03\x04 + 脚本名含 "doc" → docx
  - %PDF → pdf
  - \x89PNG → png
  - \xFF\xD8\xFF → jpg
     │
     ▼
Base64 编码 + 包装为 JSON:
{
  "type": "binary_file",
  "filename": "PPT生成器.pptx",
  "mime": "application/vnd...presentationml.presentation",
  "size": 65200,
  "data": "UEsDBBQAAAAAA..."
}
     │
     ▼
前端解析 JSON，提供下载链接
```

---

## 13. 前端架构

### 13.1 页面结构

```
index.html
├── 顶部导航栏
│   ├── 技能上传 Tab
│   ├── 技能管理 Tab
│   └── 技能执行 Tab
│
├── 技能上传区域
│   ├── 文件拖拽/选择区
│   └── 上传按钮
│
├── 技能管理区域
│   ├── 技能列表 (卡片式)
│   │   └── 每张卡片: 名称、描述、标签、文件数、操作按钮
│   ├── 文件浏览器 (模态框)
│   │   ├── 文件列表
│   │   └── 文件内容查看 (文本/Base64图片)
│   └── 删除按钮
│
└── 技能执行区域
    ├── 输入区
    │   ├── 文本输入框
    │   ├── 文件上传区
    │   ├── 🤖 AI 自动选择并执行 按钮
    │   └── 🔍 手动搜索技能 按钮
    │
    ├── AI 思考过程区 (可折叠)
    │   └── 显示 reasoning_content
    │
    ├── 候选技能列表
    │   └── 每个候选: 技能名、匹配分数、选择理由、执行按钮
    │
    ├── 执行结果区
    │   ├── 技能信息 (名称、路由理由)
    │   ├── 输出内容 (文本/文件下载)
    │   └── 轮次历史
    │
    └── 导航按钮区
        ├── ← 返回上一轮 (第一轮时 disabled)
        ├── ↻ 重新执行
        └── 继续下一轮 → (无候选时隐藏)
```

### 13.2 前端核心函数

| 函数 | 功能 |
|------|------|
| `loadSkills()` | 加载技能列表到管理页面 |
| `uploadSkill()` | 上传技能文件 |
| `viewSkillFiles(skillId)` | 打开文件浏览器 |
| `readFileContent(skillId, path)` | 读取文件内容 |
| `aiAutoExecute()` | AI 自动选择并执行 |
| `routeOnly(input)` | 第一轮路由判断 |
| `renderRouteResult(data)` | 渲染候选列表和思考过程 |
| `toggleThinkingProcess()` | 展开/折叠思考过程 |
| `executeCandidate(skillId)` | 执行选中的候选技能 |
| `renderSession(session)` | 渲染执行结果 |
| `renderOutput(output)` | 渲染输出（文本/二进制文件下载） |
| `autoContinueRoute(sessionId)` | 自动寻找下一轮技能 |
| `goBack(sessionId)` | 返回上一轮 |
| `reExecute(sessionId)` | 重新执行 |
| `continueNext(sessionId, skillId)` | 继续下一轮 |

### 13.3 前端交互流程

```
用户输入 + 点击 "🤖 AI 自动选择并执行"
     │
     ├── 有上传文件 → 调用 /route-with-files
     │                (multipart/form-data)
     │
     └── 纯文本 → 调用 /route
                   (JSON body)
         │
         ▼
    响应: {candidates, thinkingProcess}
         │
         ▼
    renderRouteResult(data)
    ├── 渲染思考过程 (可折叠)
    └── 渲染候选卡片
         │
         ▼
    用户点击候选技能的 "执行" 按钮
         │
         ▼
    executeCandidate(skillId)
    → 调用 /execute-routed
    → 响应: {session, routeResult}
         │
         ▼
    renderSession(session)
    ├── 渲染技能信息
    ├── renderOutput(output)
    │   ├── 检测 binary_file JSON → 显示下载按钮
    │   └── 纯文本 → 显示文本内容
    └── autoContinueRoute(sessionId)
         ├── 有候选 → 显示 "继续下一轮" 按钮
         └── 无候选 → 隐藏 "继续下一轮" 按钮
```

---

## 14. 配置说明

### 14.1 application.yml 完整配置

```yaml
server:
  port: 8080                          # 服务端口
  servlet:
    encoding:
      charset: UTF-8                  # 字符编码
      enabled: true
      force: true

spring:
  application:
    name: ai-skill
  servlet:
    multipart:
      max-file-size: 100MB            # 单个文件最大大小
      max-request-size: 500MB         # 请求最大大小
      resolve-lazily: true            # 延迟解析 multipart（解决中文乱码）
  web:
    resources:
      static-locations: classpath:/static/

# AI 配置 - 支持任何 OpenAI 兼容 API
ai:
  base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
  api-key: sk-xxx                     # API 密钥
  model: glm-5.2                      # 默认模型
  temperature: 0.7                    # 默认采样温度
  max-tokens: 4096                    # 默认最大 Token 数
  timeout: 300000                     # 请求超时（毫秒）
  think: false                        # 禁用推理模式（Qwen3 等）

# 存储配置 - 仅支持 FTP
storage:
  ftp:
    host: ${FTP_HOST:localhost}       # FTP 主机
    port: ${FTP_PORT:21}              # FTP 端口
    username: ${FTP_USERNAME:admin}   # FTP 用户名
    password: ${FTP_PASSWORD:admin}# FTP 密码
    base-dir: ${FTP_BASE_DIR:/skills} # 存储根目录

logging:
  level:
    com.example.aiskill: DEBUG        # 项目日志级别
    org.springframework.web: INFO     # Spring Web 日志级别
```

### 14.2 环境变量覆盖

所有 FTP 配置项均支持环境变量覆盖，便于 Docker 部署：

| 环境变量 | 默认值 | 说明 |
|----------|--------|------|
| `FTP_HOST` | localhost | FTP 服务器地址 |
| `FTP_PORT` | 21 | FTP 服务器端口 |
| `FTP_USERNAME` | skill | FTP 用户名 |
| `FTP_PASSWORD` | skill123 | FTP 密码 |
| `FTP_BASE_DIR` | /skills | 存储根目录 |

---

## 15. 部署指南

### 15.1 本地开发

```bash
# 前置条件: JDK 8, Maven, Python 3, FTP 服务

# 1. 启动 FTP 服务 (如 vsftpd)
#    配置: host=localhost, port=21, user=admin, pass=admin, dir=/skills

# 2. 编译打包
cd springboot-ai-skill
mvn clean package -DskipTests

# 3. 运行
java -jar target/ai-skill-1.0.0.jar

# 4. 访问
# http://localhost:8080
```

### 15.2 JDK 8 配置

```bash
# 设置 JAVA_HOME
export JAVA_HOME=/path/to/jdk8
export PATH=$JAVA_HOME/bin:$PATH

# 验证
java -version
# java version "1.8.0_xxx"
```

### 15.3 技能上传示例

```bash
# 上传松散文件
curl -X POST http://localhost:8080/api/skills/upload \
  -F "files=@skill.md" \
  -F "files=@generate_pptx.py" \
  -F "files=@requirements.txt"

# 上传 ZIP 压缩包
curl -X POST http://localhost:8080/api/skills/upload \
  -F "files=@skill-pack.zip"
```

### 15.4 执行示例

```bash
# AI 自动选择并执行 (一步完成)
curl -X POST http://localhost:8080/api/sessions/auto-execute \
  -H "Content-Type: application/json" \
  -d '{"input": "生成一份升职的PPT"}'

# 分两步执行: 先路由
curl -X POST http://localhost:8080/api/sessions/route \
  -H "Content-Type: application/json" \
  -d '{"input": "生成一张程序员的漫画图片"}'

# 再执行选中的技能
curl -X POST http://localhost:8080/api/sessions/execute-routed \
  -H "Content-Type: application/json" \
  -d '{"input": "生成一张程序员的漫画图片", "skillId": "skill_xxx"}'
```

---

## 附录: 关键设计决策

| 决策 | 理由 |
|------|------|
| 两轮 LLM 调用分离 | 第一轮仅传索引降低 token 消耗，第二轮才加载完整文档 |
| FTP 短连接策略 | 避免空闲超时断连，牺牲少量性能换取稳定性 |
| 内存会话存储 | 会话数据无需持久化，ConcurrentHashMap 保证线程安全 |
| Python 依赖自动安装 | 提高技能可移植性，避免手动安装依赖的痛点 |
| Base64 二进制传输 | 避免 JSON 传输中的字符集问题，前端可直接解码下载 |
| 三级编码检测 | 兼容 UTF-8 BOM、无 BOM UTF-8、GBK 三种常见编码 |
| multipart 延迟解析 | 确保字符编码过滤器先于 multipart 解析执行 |
| think: false 配置 | Qwen3 等推理模型禁用推理模式以获得更快响应 |
