# Java Agent Lab

用 **Java + Spring Boot** 从零实现一个能用的 LLM Agent —— **不套低代码平台，先手写核心循环再引入框架**。

> 🚧 **状态：开发中**（预备周完成，W1 进行中）

---

## 这个项目的目标

一句话：**一个能真正用起来、也讲得清楚的 Java Agent 后端服务。**

具体是三个目标：

| 目标 | 含义 |
|---|---|
| **1. 真能用** | 不是跑个 demo 就完事。要有超时、重试、限流、可观测性 —— **能放到生产环境思考的水平** |
| **2. 讲得清** | 每一层都自己写一遍，**知道框架替我们做了什么**。工具调用循环先手写（W2），之后才用 Spring AI 重构（W3） |
| **3. 拿得出手** | 作为可展示的工程作品：完整的 API、文档、设计取舍说明 |

**设计上有两条硬原则：**

| 原则 | 原因 |
|---|---|
| **先裸写，再上框架** | 只会调框架 API 的人，遇到问题时无从下手。**手写过一遍 `for` 循环驱动工具调用，才真正理解 Agent 是怎么转起来的** |
| **主项目坚持 Java** | 大量 Agent 项目是 Python 脚本。**Java 的服务稳定性思维（超时、重试、限流、全链路追踪）恰恰是这个领域稀缺的能力** |

---

## 它能做什么

**核心能力**（按路线图逐步实现）：

- 💬 **对话** —— 支持流式输出（SSE 打字机效果）
- 🔧 **工具调用** —— 能自己决定何时查时间、算数学题、联网搜索
- 🧠 **记忆** —— 短期（会话上下文）+ 长期（跨会话记住用户偏好）
- 📚 **RAG** —— 上传文档后能基于文档内容回答
- 📋 **规划** —— 复杂任务先出计划再逐步执行

**和"调用一次 LLM API"的区别：**

```
普通调用：  你问 → 模型凭记忆答（不知道现在几点、算不准 3+5）
这个项目：  你问 → 模型说"我要先查一下" → 服务真的去查 → 结果回灌 → 模型给出答案
```

---

## 技术栈

| 层 | 选型 | 版本 |
|---|---|---|
| 语言 | Java | **21** |
| 框架 | Spring Boot | **4.1.1**（Spring Framework 7） |
| 构建 | Maven（用项目自带 `mvnw`，无需安装） | — |
| 向量库 | PostgreSQL + **pgvector** | pg16 / vector 0.8.6 |
| 会话缓存 | Redis | 7 |
| 依赖编排 | Docker Compose | — |
| LLM | DeepSeek API（OpenAI 兼容格式） | `deepseek-chat` |
| Agent 框架 | **Spring AI 2.0**（W3 引入，与 Spring Boot 4 配套） | — |

**为什么是这套组合**：全部是 Java 后端工程师熟悉的技术，**不需要为了做 AI 而切换到 Python 生态**。


---

## 架构（目标形态）

```
┌──────────────────────────────────────────────────┐
│  输入层    REST API (SSE 流式输出)                 │
├──────────────────────────────────────────────────┤
│  会话层    Redis（短期记忆 / 会话历史）             │
│           PostgreSQL（持久化 / Agent运行记录）      │
├──────────────────────────────────────────────────┤
│  编排层    Agent 循环（手写 → Spring AI 重构）      │
│           ReAct / Plan-and-Execute                │
├──────────────────────────────────────────────────┤
│  能力层    工具（时间/计算/搜索）                   │
│           RAG 检索（PgVector 向量检索）            │
│           长期记忆（用户事实抽取）                  │
├──────────────────────────────────────────────────┤
│  外部      DeepSeek API                           │
└──────────────────────────────────────────────────┘
```

---

## 开发路线与进度

| 阶段 | 内容 | 状态 |
|---|---|---|
| 预备周 | 环境搭建、综述学习 | ✅ **完成** |
| **W1** | LLM 客户端：超时、重试、SSE 流式、Redis 会话 | 🚧 进行中 |
| **W2** | **纯 Java 手写 Tool Calling 循环**（核心） | ⬜ |
| **W3** | Spring AI 重构 + ReAct + 联网搜索工具 | ⬜ |
| **W4** | 长期记忆 + RAG（PgVector） | ⬜ |
| **W5** | Plan-and-Execute + LangGraph 原型 | ⬜ |
| **W6** | 工程化 I：异常、限流、鉴权、全链路 traceId | ⬜ |
| **W7** | 工程化 II：持久化、长任务设计、容器编排 | ⬜ |
| **W8** | 文档打磨 + 面试素材整理 | ⬜ |

### W1 目标

- [ ] 自写 HTTP 客户端调 LLM（**不使用 Spring AI**）
- [ ] `POST /chat` —— 非流式响应
- [ ] `GET /chat/stream/{sessionId}` —— **SSE 流式输出**
- [ ] 超时 + 指数退避重试（仅网络错误/429）
- [ ] 请求 ID + MDC 全链路日志
- [ ] 会话历史存 Redis（重启不丢）

---

## 快速开始

### 前置要求

- JDK 21
- Docker Desktop（含 WSL2）

> Maven **不需要装** —— 项目自带 Maven Wrapper。

### 1. 启动依赖服务

```bash
docker compose up -d
```

拉起两个容器：

| 服务 | 端口 | 凭据 |
|---|---|---|
| PostgreSQL + pgvector | 5432 | `agentdb` / `agent` / `agent123` |
| Redis | 6379 | 密码 `redis123` |

**验证**：

```bash
docker compose ps
docker exec -it agent-postgres psql -U agent -d agentdb -c "\dx"   # 应看到 vector 扩展
docker exec -e REDISCLI_AUTH=redis123 -it agent-redis redis-cli ping  # 应返回 PONG
```

### 2. 配置 API Key

Key **不写在配置文件里**，用环境变量：

```powershell
# Windows PowerShell
[Environment]::SetEnvironmentVariable("DEEPSEEK_API_KEY", "sk-你的key", "User")
```

> 💡 Linux/macOS：`export DEEPSEEK_API_KEY=sk-你的key`

### 3. 启动应用

```bash
./mvnw spring-boot:run          # Linux/macOS
.\mvnw.cmd spring-boot:run      # Windows
```

---

## 配置说明

配置分两层，**共享的和个人的分开**：

| 文件 | 内容 | 是否提交 |
|---|---|---|
| `src/main/resources/application.yml` | 基础配置（数据库、Redis、超时重试）—— **敏感值全部是环境变量占位符** | ✅ 提交 |
| `src/main/resources/application-local.yml` | 个人覆盖（本地 key、调试日志级别） | ❌ 已 gitignore |
| `application-local.example.yml` | 上面那个的**模板** | ✅ 提交 |

**环境变量**（都有默认值，按需覆盖）：

| 变量 | 默认值 | 说明 |
|---|---|---|
| `DEEPSEEK_API_KEY` | 无 | **必填** |
| `DEEPSEEK_BASE_URL` | `https://api.deepseek.com` | |
| `DEEPSEEK_MODEL` | `deepseek-chat` | 稳定别名，实际模型由服务端决定 |
| `POSTGRES_HOST` / `PORT` / `DB` / `USER` / `PASSWORD` | localhost / 5432 / agentdb / agent / agent123 | |
| `REDIS_HOST` / `PORT` / `PASSWORD` | localhost / 6379 / redis123 | |

---

## 项目结构

```
agent-lab/
├── src/main/java/com/example/agent/     # 应用代码
├── src/main/resources/
│   ├── application.yml                  # 基础配置（提交）
│   └── application-local.yml            # 个人配置（不提交）
├── initdb/01-init.sql                   # Postgres 初始化（建 vector 扩展）
├── docker-compose.yml                   # 依赖服务编排
├── agent-notes.md                       # 开发参考手册（踩坑/API/设计）
├── qa-notes.md                          # 学习问题清单
├── application-local.example.yml        # 配置模板
└── .env.example                         # 环境变量模板
```

### 开发文档

| 文件 | 内容 |
|---|---|
| `agent-notes.md` | **参考手册**：环境参数、API 返回结构、日志设计、Windows 踩坑清单、Function Calling 规范、ReAct 与 Plan-and-Execute 选型 |
| `qa-notes.md` | **问题索引**：学习过程中真正卡住的 50 个问题，按阶段分组 |

---

## 设计取舍

> 这部分会随开发持续补充。

### 已确定的选择

> 每一条都写清**为什么选**和**代价是什么** —— 没有代价的技术选型通常是没想清楚。

| 决策 | 理由 | 代价 |
|---|---|---|
| **先手写循环再上框架** | 理解底层机制。只会调框架 API 的人，出问题时无从下手 | 前期开发慢；Spring AI 那部分要重写一遍 |
| **主项目用 Java 不用 Python** | 服务稳定性（超时/重试/限流/可观测性）是差异化优势 | Java 的 Agent 生态不如 Python 丰富，部分新特性会晚一步 |
| **PostgreSQL + pgvector 而非 Milvus** | 复用已有数据库，**不引入额外的运维负担** | 超大规模向量检索性能不如专用向量库；索引调优选项更少 |
| **Maven 而非 Gradle** | 团队更熟悉；`mvnw` 让协作者零安装 | 构建脚本灵活性不如 Gradle |
| **配置分基础/个人两层** | 敏感信息不进仓库，**同时保证别人 clone 后能直接跑** | 多一个文件要维护，新人需要理解这个约定 |
| **SSE 而非 WebSocket** | 单向推送就够；走普通 HTTP，**复用现成的鉴权、网关、日志链路** | 不支持客户端→服务端的实时推送（本项目也不需要） |
| **DeepSeek 而非 OpenAI** | 中文能力好、成本低、OpenAI 兼容格式便于迁移 | 部分高级特性（如某些结构化输出模式）支持情况需实测 |

### 已知待办 / 局限

- [ ] **长任务**（耗时几分钟的 Agent 运行）尚未设计异步方案 —— 当前是同步 HTTP，会超时。计划：提交任务 → 返回 taskId → 异步处理 → 轮询查询（W7）
- [ ] **未接入 OpenTelemetry** —— 当前只有标准日志输出 + traceId，生产环境应补上链路追踪
- [ ] **无前端页面** —— 有意为之，用 REST API + Swagger 演示功能，把时间留给后端架构
- [ ] **RAG 未做重排（Reranker）** —— 检索质量有提升空间，见 W4 的设计说明
- [ ] **长期记忆无遗忘机制** —— 简单抽取策略存在记忆冲突、无用信息堆积的问题

---

## 开发文档

项目里有三份配套文档（在仓库根目录）：

| 文件 | 内容 |
|---|---|
| `agent-notes.md` | **开发参考手册** —— 环境参数、API 返回结构、日志字段设计、Windows 环境踩坑清单、Function Calling 规范、ReAct 与 Plan-and-Execute 选型判据 |
| `qa-notes.md` | **学习问题索引** —— 开发过程中真正卡住的 50 个问题，按学习阶段分组 |
| `README.md` | 本文件 |

**为什么把这些放进仓库**：Agent 开发有大量"文档没写、踩过才知道"的细节（代理配置、SSE 分片、模型别名漂移…）。**记录下来既是备忘，也方便别人少走弯路。**

---

## 许可证

学习/展示项目，暂未指定许可证。
