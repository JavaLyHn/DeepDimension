<p align="center">
  <h1 align="center">🧠 DeepDimension</h1>
  <p align="center">
    <strong>企业级 RAG 检索增强生成知识库系统</strong>
  </p>
  <p align="center">
    <a href="https://github.com/JavaLyHn/DeepDimension">
      <img src="https://img.shields.io/badge/GitHub-DeepDimension-blue?logo=github" alt="GitHub">
    </a>
    <img src="https://img.shields.io/badge/JDK-17-green?logo=openjdk" alt="JDK 17">
    <img src="https://img.shields.io/badge/Spring%20Boot-3.4.2-brightgreen?logo=springboot" alt="Spring Boot">
    <img src="https://img.shields.io/badge/Vue-3.x-brightgreen?logo=vuedotjs" alt="Vue 3">
    <img src="https://img.shields.io/badge/License-MIT-yellow" alt="MIT License">
  </p>
</p>

---

## 📖 项目简介

DeepDimension 是一个面向企业场景的 **RAG（Retrieval-Augmented Generation）检索增强生成知识库系统**，采用前后端分离架构。系统实现了从文档上传、智能解析、向量化存储、混合检索到 AI 对话的完整闭环，并引入多项核心优化策略，显著提升检索精度与生成质量。

## ✨ 核心技术亮点

### 1. 🔀 并行混合检索引擎（BM25 + KNN + Cross-Encoder 三阶段）

```
Stage 1: KNN 向量召回 (topK×30)     → 稠密向量语义匹配，广召回
Stage 2: BM25 Rescore (ES 内置)      → 稀疏关键词加权，精筛选
Stage 3: ★ Cross-Encoder 重排 ★     → LLM 成对精排，高精度
```

- **KNN 向量召回**：基于通义千问 Embedding 模型（text-embedding-v4, 2048维），从 Elasticsearch 中召回语义相关文档
- **BM25 关键词检索**：ES 内置 Rescore 机制，对 KNN 结果进行关键词二次加权（queryWeight=0.2, rescoreWeight=1.0）
- **Cross-Encoder 重排**：将 (query, document) 成对输入 LLM 进行交叉注意力评分，0-10 分制归一化，解决 ES 无法捕捉的深层语义相关性问题
  - 专有名词精确匹配提升（如 "HikariCP" 被 BM25 遗漏但被 CE 识别）
  - 长尾语义关联发现（如 "流量管理" 与 "Service Mesh" 的隐含关联）
  - minScore 阈值过滤低质量片段，平均节省 **30%~60%** Token 消耗

### 2. 📊 Dynamic Top-K 调度算法

根据召回分数分布动态调整提交给 LLM 的片段数量，保证信息召回的同时显著降低 Token 消耗并减小上下文噪音：

- **肘部法则（Elbow Method）**：检测分数下降的"拐点"，拐点之后的片段贡献急剧降低，判定为噪声
- **分数阈值截断**：低于阈值（默认 0.3）的片段直接丢弃
- **边界保护**：minK=1 保证至少返回 1 个片段，maxK=10 防止上下文过长
- **过召回策略**：先多召回（topK×3=15条），再动态筛选，确保不遗漏

| 场景 | 过召回 | Dynamic Top-K | 效果 |
|------|--------|---------------|------|
| 高相关查询 | 15条 | 10条 | 充分利用高质量片段 |
| 混合质量(断崖) | 15条 | 3条 | 在断崖处精准截断 |
| 低相关查询 | 15条 | 3条 | 大幅降噪，节省 80% Token |

### 3. 🔄 基于 LLM 的 Query-Rewrite 模块

进行指代消解与语义扩展，显著提升首轮检索的意图识别与命中率：

- **指代消解**：将"它"、"这个"、"前者"、"后者"等代词替换为对话历史中的具体实体
- **语义补全**：将省略的、不完整的查询补充为完整的语义表达（如 "那价格呢" → "Redis和Memcached的价格区别"）
- **智能检测**：两层检测机制（指代词正则 + 省略/口语化模式匹配），口语化查询检测覆盖率 **86.7%**
- **首轮跳过**：无历史记录时直接返回原查询，节省 LLM 调用开销
- **降级保护**：LLM 调用失败时自动降级为原始查询，不影响主流程

```
用户: "它的API文档在哪里"  +  历史: [Q:FastAPI是什么, A:...]
    ↓ Query-Rewrite
搜索查询: "FastAPI的API文档在哪里"  ← 命中率大幅提升
回答查询: "它的API文档在哪里"       ← 保持自然对话感
```

### 4. 📑 Parent-Child Indexing（父子索引）策略

针对长文档语义割裂问题，采用小粒度子块定位检索、回溯父级段落以恢复上下文：

- **解析阶段**：Apache Tika 流式解析 + "父文档-子切片"策略，先按 parentChunkSize(1MB) 切割父块，再按 chunkSize(512字符) 语义分割为子块
- **智能分块**：段落 → 句子 → HanLP 中文分词，三级分割保持语义完整性
- **内存监控**：超过 80% 内存阈值自动触发 GC，防止 OOM

### 5. 🔐 基于组织标签的三级权限控制

```
请求 → JwtAuthenticationFilter (Token验证+自动刷新)
     → OrgTagAuthorizationFilter (组织标签授权)
        → 私人空间(仅拥有者) / 组织资源(标签匹配+层级继承) / 公开资源(所有人)
```

- **层级继承**：子标签用户自动继承父标签资源的访问权限
- **私有标签保护**：`PRIVATE_` 前缀标签不可删除/不可被管理员移除
- **Token 管理**：JWT + Redis 双重验证，支持自动刷新（预刷新+宽限期刷新）、黑名单、批量登出

### 6. 📤 大文件分片上传与异步处理

```
前端分片上传 → MinIO存储 → Redis Bitmap追踪 → 合并分片
    → Kafka异步消息 → Tika解析+智能分块 → 向量化 → ES索引
```

- **分片上传**：前端 MD5 去重 + 断点续传 + 并发控制（最多3个同时上传）
- **Redis Bitmap**：高效追踪分片上传状态
- **Kafka 异步**：文件合并后通过 Kafka 异步触发解析和向量化，不阻塞用户请求
- **死信队列**：3秒×4次重试策略，处理失败的消息进入 DLT

## 🏗️ 系统架构

```
┌──────────────────────────────────────────────────────────────────┐
│                        Frontend (Vue 3 + Naive UI)               │
│  Chat │ Knowledge Base │ Org-Tag │ User Mgmt │ Personal Center  │
└────────────────────────────┬─────────────────────────────────────┘
                             │ HTTP / WebSocket
┌────────────────────────────▼─────────────────────────────────────┐
│                     Backend (Spring Boot 3.4.2)                   │
│                                                                    │
│  ┌──────────┐  ┌──────────────┐  ┌──────────────┐                │
│  │Controller│→ │  Service     │→ │  Repository  │                │
│  │   Layer  │  │    Layer     │  │    Layer     │                │
│  └──────────┘  └──────┬───────┘  └──────────────┘                │
│                       │                                            │
│  ┌────────────────────▼────────────────────────┐                  │
│  │           Core Processing Pipeline           │                  │
│  │                                                │                  │
│  │  QueryRewrite → HybridSearch → CrossEncoder   │                  │
│  │       ↓              ↓              ↓          │                  │
│  │  指代消解      KNN+BM25       LLM精排         │                  │
│  │  语义扩展      混合检索       高精度重排       │                  │
│  │                    ↓                           │                  │
│  │             DynamicTopK → ChatHandler          │                  │
│  │             动态筛选     流式响应               │                  │
│  └────────────────────────────────────────────────┘                  │
│                                                                    │
│  ┌──────────┐  ┌──────────────┐  ┌──────────────┐                │
│  │JWT Filter│  │OrgTag Filter │  │  Kafka       │                │
│  │认证+刷新 │  │三级权限控制  │  │异步文件处理  │                │
│  └──────────┘  └──────────────┘  └──────────────┘                │
└────────────────────────────┬─────────────────────────────────────┘
                             │
┌────────────────────────────▼─────────────────────────────────────┐
│                      Infrastructure Layer                         │
│                                                                    │
│  ┌──────┐  ┌──────┐  ┌────────────┐  ┌──────┐  ┌──────────┐    │
│  │MySQL │  │Redis │  │Elasticsearch│  │MinIO │  │DeepSeek  │    │
│  │ JPA  │  │Cache │  │  KNN+BM25  │  │Object│  │+Embedding│    │
│  └──────┘  └──────┘  └────────────┘  └──────┘  └──────────┘    │
└──────────────────────────────────────────────────────────────────┘
```

## 🛠️ 技术栈

### 后端

| 技术 | 版本 | 用途 |
|------|------|------|
| Spring Boot | 3.4.2 | 核心框架 |
| Spring Security | 6.x | 认证与授权 |
| Spring Data JPA | 3.x | ORM 持久化 |
| Elasticsearch | 8.x | 向量检索 + 全文检索 |
| Redis | 7.x | 缓存 + Token管理 + 上传状态 |
| Kafka | 3.x | 异步文件处理消息队列 |
| MinIO | - | 对象存储（文件分片+合并） |
| Apache Tika | 2.x | 文档解析（PDF/Word/Excel/PPT等） |
| HanLP | - | 中文分词与语义分割 |
| DeepSeek API | - | LLM 对话（流式SSE） |
| 通义千问 Embedding | text-embedding-v4 | 文本向量化（2048维） |
| JWT | - | 无状态认证 + 自动刷新 |
| WebClient | - | 非阻塞 HTTP 调用 |

### 前端

| 技术 | 版本 | 用途 |
|------|------|------|
| Vue 3 | 3.x | 核心框架 |
| TypeScript | 5.x | 类型安全 |
| Pinia | - | 状态管理 |
| Naive UI | - | UI 组件库 |
| Vite | - | 构建工具 |
| @vueuse/core | - | WebSocket 组合式函数 |
| vue-markdown-shiki | - | Markdown 渲染 |
| SoybeanAdmin | - | 管理后台脚手架 |

## 📁 项目结构

```
DeepDimension/
├── src/main/java/com/lyhn/deepdimension/
│   ├── client/                     # 外部 API 客户端
│   │   ├── DeepSeekClient.java     # DeepSeek 流式/非流式调用
│   │   └── EmbeddingClient.java    # 通义千问向量化客户端
│   ├── config/                     # 配置类
│   │   ├── SecurityConfig.java     # Spring Security 配置
│   │   ├── WebSocketConfig.java    # WebSocket 配置
│   │   ├── KafkaConfig.java        # Kafka 生产者/消费者/死信队列
│   │   ├── EsConfig.java           # Elasticsearch 客户端
│   │   ├── EsIndexInitializer.java # ES 索引自动初始化
│   │   ├── MinioConfig.java        # MinIO 对象存储
│   │   ├── RedisConfig.java        # Redis 序列化
│   │   ├── AdminUserInitializer.java  # 启动时创建管理员
│   │   ├── OrgTagInitializer.java     # 启动时创建默认标签
│   │   └── properties/             # 配置属性类
│   │       ├── AiProperties.java   # AI 相关配置(prompt/generation/queryRewrite/crossEncoder)
│   │       └── MinioProperties.java
│   ├── controller/                 # REST 控制器
│   │   ├── UserController.java     # 用户注册/登录/登出
│   │   ├── AuthController.java     # Token 刷新
│   │   ├── AdminController.java    # 管理员接口(用户/标签/对话)
│   │   ├── UploadController.java   # 文件分片上传
│   │   ├── DocumentController.java # 文档管理/下载/预览
│   │   ├── SearchController.java   # 混合检索
│   │   ├── ChatController.java     # WebSocket 聊天
│   │   └── ConversationController.java # 对话历史
│   ├── entity/                     # 数据传输对象
│   │   ├── EsDocument.java         # ES 文档实体
│   │   ├── SearchResult.java       # 搜索结果(含crossEncoderScore)
│   │   ├── TextChunk.java          # 文本分块
│   │   └── Message.java            # 聊天消息
│   ├── exception/                  # 异常处理
│   ├── filter/                     # 过滤器
│   │   ├── JwtAuthenticationFilter.java    # JWT 认证+自动刷新
│   │   └── OrgTagAuthorizationFilter.java  # 组织标签三级授权
│   ├── handler/                    # 处理器
│   │   ├── ChatHandler.java        # 聊天核心(RAG全流程)
│   │   └── ChatWebSocketHandler.java # WebSocket 生命周期管理
│   ├── model/                      # JPA 实体
│   │   ├── User.java               # 用户
│   │   ├── Conversation.java       # 对话记录
│   │   ├── FileUpload.java         # 文件上传记录
│   │   ├── DocumentVector.java     # 文档向量(子块)
│   │   ├── ChunkInfo.java          # 分片信息
│   │   └── OrganizationTag.java    # 组织标签(树形)
│   ├── repository/                 # 数据访问层
│   ├── service/                    # 核心业务服务
│   │   ├── HybridSearchService.java     # 混合检索(KNN+BM25+CrossEncoder)
│   │   ├── CrossEncoderReranker.java    # Cross-Encoder 重排
│   │   ├── DynamicTopKSelector.java     # Dynamic Top-K 调度
│   │   ├── QueryRewriteService.java     # 查询重写(指代消解+语义扩展)
│   │   ├── ParseService.java            # 文档解析(父子分块+HanLP)
│   │   ├── VectorizationService.java    # 文本向量化
│   │   ├── ElasticsearchService.java    # ES 操作封装
│   │   ├── UploadService.java           # 分片上传+合并
│   │   ├── DocumentService.java         # 文档管理
│   │   ├── ConversationService.java     # 对话历史
│   │   ├── UserService.java             # 用户+组织标签管理
│   │   ├── OrgTagCacheService.java      # 组织标签缓存(层级继承)
│   │   ├── TokenCacheService.java       # JWT Token 缓存+黑名单
│   │   └── FileTypeValidationService.java # 文件类型验证
│   └── utils/                      # 工具类
│       ├── JwtUtils.java           # JWT 生成/验证/刷新
│       └── PasswordUtil.java       # BCrypt 密码加密
│
├── src/main/resources/
│   ├── application.yml             # 主配置文件
│   └── es-mappings/
│       └── knowledge_base.json     # ES 索引映射(ik分词+dense_vector)
│
├── frontend/                       # Vue 3 前端
│   └── src/
│       ├── views/
│       │   ├── chat/               # AI 聊天(WebSocket流式)
│       │   ├── chat-history/       # 聊天历史(管理员)
│       │   ├── knowledge-base/     # 知识库(上传/检索/预览)
│       │   ├── org-tag/            # 组织标签管理(管理员)
│       │   ├── user/               # 用户管理(管理员)
│       │   └── personal-center/    # 个人中心
│       ├── store/modules/
│       │   ├── auth-store.ts       # 认证状态
│       │   ├── chat-store.ts       # 聊天状态(WebSocket)
│       │   └── knowledge-base-store.ts # 上传状态管理
│       ├── service/
│       │   ├── api/                # API 接口封装
│       │   └── request/            # Axios 封装(Token刷新+错误去重)
│       └── components/custom/
│           ├── org-tag-cascader.vue # 组织标签级联选择器
│           └── file-preview.vue     # 文件预览组件
│
└── src/test/                       # 单元测试
    └── com/lyhn/deepdimension/service/
        ├── DynamicTopKSelectorTest.java     # 25个测试用例
        ├── QueryRewriteServiceTest.java     # 46个测试用例
        └── CrossEncoderRerankerTest.java    # 28个测试用例
```

## 🚀 快速开始

### 环境要求

- JDK 17+
- Node.js 18.20+ / pnpm 8.7+
- MySQL 8.0+
- Redis 7.0+
- Elasticsearch 8.x（需安装 IK 分词插件）
- MinIO
- Kafka 3.x

### 1. 克隆项目

```bash
git clone https://github.com/JavaLyHn/DeepDimension.git
cd DeepDimension
```

### 2. 配置后端

修改 `src/main/resources/application.yml`：

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/deepdimension?useSSL=false&serverTimezone=UTC
    username: your_username
    password: your_password
  data:
    redis:
      host: localhost
      port: 6379
  kafka:
    bootstrap-servers: localhost:9092

deepseek:
  api:
    url: https://api.deepseek.com/v1
    key: your_deepseek_api_key

embedding:
  api:
    url: https://dashscope.aliyuncs.com/compatible-mode/v1
    key: your_dashscope_api_key

elasticsearch:
  host: localhost
  port: 9200
  username: elastic
  password: your_es_password

minio:
  endpoint: http://localhost:9000
  accessKey: your_access_key
  secretKey: your_secret_key
  bucketName: deep-dimension
```

### 3. 启动后端

```bash
cd DeepDimension
mvn spring-boot:run
```

后端启动后自动执行：
- 创建 `knowledge_base` ES 索引（含 IK 分词 + dense_vector 映射）
- 初始化管理员账号（admin / admin123）
- 创建默认组织标签

### 4. 启动前端

```bash
cd frontend
pnpm install
pnpm dev
```

访问 `http://localhost:9527` 即可使用。

## 🔌 API 概览

### 认证相关

| 方法 | 端点 | 说明 |
|------|------|------|
| POST | `/api/v1/users/register` | 用户注册 |
| POST | `/api/v1/users/login` | 用户登录 |
| POST | `/api/v1/auth/refreshToken` | 刷新 Token |
| POST | `/api/v1/users/logout` | 登出 |
| GET | `/api/v1/users/me` | 获取当前用户信息 |

### 知识库

| 方法 | 端点 | 说明 |
|------|------|------|
| POST | `/api/v1/upload/chunk` | 上传文件分片 |
| POST | `/api/v1/upload/merge` | 合并分片 |
| GET | `/api/v1/upload/status` | 查询上传进度 |
| GET | `/api/v1/documents/uploads` | 获取文件列表 |
| GET | `/api/v1/documents/download` | 下载文件 |
| DELETE | `/api/v1/documents/{fileMd5}` | 删除文档 |
| GET | `/api/v1/search/hybrid` | 混合检索 |

### 聊天

| 方法 | 端点 | 说明 |
|------|------|------|
| WebSocket | `/proxy-ws/chat/{token}` | AI 对话（流式响应） |
| GET | `/api/v1/chat/websocket-token` | 获取停止指令 Token |
| GET | `/api/v1/users/conversation` | 获取对话历史 |

### 管理员

| 方法 | 端点 | 说明 |
|------|------|------|
| GET | `/api/v1/admin/users/list` | 分页用户列表 |
| PUT | `/api/v1/admin/users/{userId}/org-tags` | 分配组织标签 |
| GET/POST | `/api/v1/admin/org-tags` | 组织标签 CRUD |
| GET | `/api/v1/admin/org-tags/tree` | 标签树结构 |
| GET | `/api/v1/admin/conversation` | 查看所有对话 |

## 🔍 RAG 全流程

```
用户提问: "Spring Boot自动配置的原理是什么？"
    │
    ▼ ① Query-Rewrite（指代消解+语义扩展）
    │  检测到指代词/省略 → LLM重写为完整查询
    │  首轮对话/明确查询 → 跳过，节省开销
    │
    ▼ ② HybridSearch（三阶段混合检索）
    │  Stage1: KNN向量召回 → 450条候选
    │  Stage2: BM25 Rescore → 15条筛选
    │  Stage3: Cross-Encoder重排 → 精排+过滤
    │
    ▼ ③ Dynamic Top-K（动态片段选择）
    │  分析分数分布 → 肘部法则+阈值截断
    │  15条 → 筛选为3~10条高质量片段
    │
    ▼ ④ DeepSeek 流式响应
    │  注入 RAG 上下文 → SSE 流式输出
    │  来源引用 (来源#N: 文件名)
    │
    ▼ ⑤ 对话历史持久化
       Redis 缓存最近20条 → MySQL 持久化
```

## 🧪 测试

项目包含 **99 个单元测试用例**，覆盖三大核心算法模块：

```bash
# 运行全部测试
mvn test -Dtest="com.lyhn.deepdimension.service.DynamicTopKSelectorTest,com.lyhn.deepdimension.service.QueryRewriteServiceTest,com.lyhn.deepdimension.service.CrossEncoderRerankerTest"

# 单独运行各模块
mvn test -Dtest=com.lyhn.deepdimension.service.DynamicTopKSelectorTest    # 25个
mvn test -Dtest=com.lyhn.deepdimension.service.QueryRewriteServiceTest    # 46个
mvn test -Dtest=com.lyhn.deepdimension.service.CrossEncoderRerankerTest   # 28个
```

| 模块 | 测试数 | 覆盖内容 |
|------|--------|---------|
| Dynamic Top-K | 25 | 肘部法则、阈值截断、边界保护、排序验证、5种端到端场景 |
| Query-Rewrite | 46 | 指代词检测(12种)、省略表达(9种)、历史窗口、LLM响应解析(11种)、7种端到端场景 |
| Cross-Encoder | 28 | JSON评分解析(8种)、LLM响应提取(6种)、排序过滤、降级保护、5种端到端场景 |

## ⚙️ 配置项

### AI 相关配置

```yaml
ai:
  prompt:
    rules: "你是DeepDimension知识助手..."
    ref-start: "<<REF>>"
    ref-end: "<<END>>"
  generation:
    temperature: 0.3
    max-tokens: 2000
    top-p: 0.9
  query-rewrite:
    system-prompt: null        # null使用默认提示词
    temperature: 0.1           # 低温度保证确定性
    max-tokens: 256
  cross-encoder:
    enabled: true              # 是否启用Cross-Encoder重排
    top-n: 20                  # 重排候选数量
    min-score: 0.1             # 最低分数阈值
    temperature: 0.05          # 极低温度保证评分一致性
    max-tokens: 512
```

### 文件解析配置

```yaml
file:
  parsing:
    chunk-size: 512            # 子块最大字符数
    parent-chunk-size: 1048576 # 父块大小(1MB)
    buffer-size: 8192          # 流式缓冲区(8KB)
    max-memory-threshold: 0.8  # 内存阈值(80%)
```

## 📄 支持的文档格式

| 类别 | 格式 |
|------|------|
| 文档 | PDF, DOCX, DOC, TXT, RTF, MD, ODT |
| 表格 | XLSX, XLS, CSV, ODS |
| 演示 | PPTX, PPT, ODP |
| 数据 | JSON, XML, HTML |
| 电子书 | EPUB |

## 📜 License

[MIT License](LICENSE)

---

<p align="center">
  Made with ❤️ by <a href="https://github.com/JavaLyHn">JavaLyHn</a>
</p>
