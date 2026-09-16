# Agent 学习参考手册

> **配套文件**：`qa-notes.md`（只有问题清单，当索引用）
> 这里存放**详细答案和参考资料**，需要时再翻。
>
> 内容来源：Day 1–2 的学习与提问（2026-09-13 ～ 09-14）

---

## 目录

- [§1 环境与连接参数](#1-环境与连接参数)
- [§2 API 调用基线](#2-api-调用基线)
- [§3 W1 日志该记什么](#3-w1-日志该记什么)
- [§4 Windows 坑清单](#4-windows-坑清单)
- [§5 通用排查方法论](#5-通用排查方法论)
- [§6 ReAct 最小白版解释](#6-react-最小白版解释)
- [§7 问答详解 Q1–Q11](#7-问答详解q1q11)
- [§8 Function Calling 完整规范](#8-function-calling-完整规范)
- [§9 工具数量与企业级扩展](#9-工具数量与企业级扩展)
- [§10 Plan-and-Execute 与选型判据](#10-plan-and-execute-与选型判据)
- [§11 速查命令](#11-速查命令)

---

## §1 环境与连接参数

### 环境终态

| 组件 | 版本 | 验证方式 |
|---|---|---|
| JDK | 21 | `java -version` |
| IDEA | 统一版 | 启动正常 |
| WSL | 2.7.14 | `wsl --version` |
| Ubuntu | 默认版本 2 | `wsl -l -v` |
| Docker | 29.7.2 | `docker --version` |
| **Spring Boot** | **4.1.1**（Spring Framework 7） | `pom.xml` |
| Postgres + pgvector | pg16 / vector **0.8.6** | `dx`（见 §11） |
| Redis | 7-alpine | `docker exec -e REDISCLI_AUTH=redis123 -it agent-redis redis-cli ping` → `PONG` |

> ⚠️ **关于 Spring Boot 4.1.1**（2026-09-16 核对）
> - Spring AI **2.0.0 GA 要求 Spring Boot 4**，不再支持 3.x
> - 所以 **4.1.1 与 W3 要引入的 Spring AI 2.0 正好配套**，不需要迁移 —— 这点很省事
> - Spring Boot 4 做了**模块化拆分**：`spring-boot-starter-web` → **`spring-boot-starter-webmvc`**
>   - 测试依赖同理：`@WebMvcTest` 等注解从 `spring-boot-starter-test` 挪到了 **`spring-boot-starter-webmvc-test`**
> - ⚠️ **注意**：网上示例大多是 Spring Boot 3.x 的，遇到对不上的地方以[官方 4.0 迁移指南](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide)为准

### Maven 镜像（已配置）

`.m2/settings.xml` 已配阿里云镜像，加速依赖下载：

| 镜像 | 用途 |
|---|---|
| `aliyun-public` | 代理 central（聚合 central + jcenter） |
| `aliyun-spring` | 代理 spring-milestones / spring-snapshots（W3 可能需要） |

**验证生效**：
```powershell
.\mvnw.cmd help:effective-settings | Select-String "aliyun"
```

### 连接参数（W1 写代码直接用）

```
PostgreSQL:  localhost:5432   库 agentdb   用户 agent   密码 agent123
Redis:       localhost:6379   密码 redis123
```

### 仓库信息

```
仓库  https://github.com/national-li/java-agent-lab
路径  D:\Deepseek\agent-lab
Git   user.name  = national-li
      user.email = 53072364+national-li@users.noreply.github.com
```

### pgvector 扩展验证

```sql
-- 应该看到 pg_trgm / plpgsql / vector 三行
-- vector 的 description 里有 "ivfflat and hnsw access methods" ← W4 的 RAG 要用
\dx
```

---

## §2 API 调用基线

**DeepSeek 非流式调用实测返回**（W1 写客户端的对照标准）：

```json
{
  "id": "508e869d-c1d9-498c-9a4c-2bcc3d5b5a17",
  "object": "chat.completion",
  "created": 1789293475,
  "model": "deepseek-flash",
  "choices": [{
    "index": 0,
    "message": { "role": "assistant", "content": "Hi! How can I help you today?" },
    "logprobs": null,
    "finish_reason": "stop"
  }],
  "usage": {
    "prompt_tokens": 5,
    "completion_tokens": 9,
    "total_tokens": 14,
    "prompt_tokens_details": { "cached_tokens": 0 },
    "prompt_cache_hit_tokens": 0,
    "prompt_cache_miss_tokens": 5
  },
  "system_fingerprint": "aeb56401ca74e127821c4f9126dcb669"
}
```

### 细节 ① 请求 `deepseek-chat`，返回 `deepseek-flash`

`deepseek-chat` 是**稳定别名**，不是具体模型名：

```
你的代码 ──"deepseek-chat"──> DeepSeek 路由 ──> deepseek-flash（实际模型）
```

**这不是 bug，是设计**：DeepSeek 升级后端时你的代码不用改。**代价是"请求名 ≠ 实际名"。**

**处理原则：配置里用别名，日志里记实际值。**

```yaml
llm:
  model: deepseek-chat        # ✅ 用别名，稳
  # model: deepseek-flash     # ⚠️ 写死具体模型，被下线就得改代码
```

`system_fingerprint` 比 model 名更细粒度，后端配置一变它就变，**也建议记进日志**。

### 细节 ② `finish_reason` —— W2 循环的核心判断依据

| 值 | 含义 | W2 处理 |
|---|---|---|
| `stop` | 正常结束 | 拿到答案，**跳出循环** |
| `tool_calls` | **模型要调工具** | 执行工具 → 结果塞回 messages → **继续循环** |
| `length` | 被 max_tokens 截断 | 需处理（增大上限或分段） |
| `content_filter` | 被安全策略拦截 | 要区分处理，不能当正常回复 |

> ⚠️ Day 1 只见到过 `stop` —— 因为**没传 `tools`，模型无工具可调**。W2 传入 `tools` 后才会第一次看到 `tool_calls`。

### 细节 ③ Prompt Cache 字段

`prompt_cache_hit_tokens` / `prompt_cache_miss_tokens`：相同前缀的 prompt 命中缓存时**计费大幅降低**。Agent 场景下 **system prompt + 工具 schema 固定**，天然适合走缓存。
> 面试聊"成本优化"的现成素材。

### 非流式 vs 流式（SSE）

| | 非流式 | 流式（SSE） |
|---|---|---|
| `object` | `chat.completion` | **`chat.completion.chunk`** |
| 内容字段 | `choices[0].message.content` | **`choices[0].delta.content`** |
| 结束标志 | `finish_reason: "stop"` | **`data: [DONE]`** |
| 拼接 | 本身就是完整的 | **要自己把 delta 拼起来** |

> ⚠️ **最后一条 chunk 的 `delta` 是空对象 `{}`**，只带 `finish_reason` → **解析时 `delta.content` 是 null，不判空会 NPE**。

---

## §3 W1 日志该记什么

**这是 Day 1 细节推导出的结论，W1 直接照抄，也是 W6 可观测性的地基。**

```java
log.info(
    "LLM 调用完成: traceId={}, 请求model={}, 实际model={}, fingerprint={}, " +
    "promptTokens={}, completionTokens={}, totalTokens={}, cacheHit={}, cacheMiss={}, finishReason={}, 耗时={}ms",
    MDC.get("traceId"),                                    // W1 的链路追踪
    req.getModel(),                                        // deepseek-chat（别名）
    resp.getModel(),                                       // deepseek-flash（实际）← 关键
    resp.getSystemFingerprint(),                           // 后端配置指纹
    usage.getPromptTokens(),
    usage.getCompletionTokens(),
    usage.getTotalTokens(),
    usage.getPromptCacheHitTokens(),                       // 成本优化依据
    usage.getPromptCacheMissTokens(),
    choice.getFinishReason(),                              // W2 的循环出口判断
    costMs
);
```

| 字段 | 为什么要记 |
|---|---|
| `traceId` | W1 验收项：每次调用都能搜到完整记录；W6 的全链路追踪 |
| 请求 model + **实际 model** | 别名漂移时能发现；成本按实际模型算 |
| `system_fingerprint` | 后端配置变更的唯一指纹，比 model 名更细 |
| `prompt/completion/total_tokens` | 计费依据；排查上下文膨胀 |
| `cache_hit/miss` | 成本优化效果验证 |
| `finish_reason` | W2 循环分支判断；异常终止的定位依据 |
| 耗时 | 性能基线；超时和重试策略的调参依据 |

**❌ 两个常见错误**：
```java
// 错误1：只记请求的 model → 后端换模型时你不知道
log.info("model={}", req.getModel());

// 错误2：把 api-key 或完整 prompt 打进日志 → 泄露密钥、日志爆炸
log.info("调用: key={}, prompt={}", apiKey, fullPrompt);
```
> **日志记摘要，不记原文** —— W6 的"记录输入输出摘要"就是这个意思。

---

## §4 Windows 坑清单

### 4.1 `wsl --install` 卡住，关窗口重来 → 从 0% 开始

**根因**：`wsl --install` 是**一次性下载**，关窗口 = 丢弃全部已下载数据。进度条 UI 刷新与实际下载不同步，长时间不动是正常的。

**解法**：
- 卡住就**等**，或 `Ctrl+C` 停掉重试；❌ **绝对不关窗口**
- 改用 `--web-download` 走微软直连通道（比默认 Windows Update 通道稳）
- 拆两步，避免互相拖累：
  ```powershell
  wsl --install --no-distribution --web-download
  wsl --install -d Ubuntu --web-download
  ```
  **判断真假死**：看任务管理器网络占用是否还在走。

### 4.2 组件装完没重启 → 报"WSL2 无法启动，未启用虚拟化"

**误判方向**：以为是 BIOS 虚拟化没开，准备重启进 BIOS。

**根因**：VirtualMachinePlatform 组件**已排队但内核还没加载它**。`wsl --install` 输出后面那句 **"直到重新启动系统前更改将不会生效"** 就是提示。

**关键鉴别证据**：`systeminfo` 显示 `固件中已启用虚拟化: 是` → **问题不在固件层，在 Windows 组件层**。

**解法**：**重启电脑**。报错里"请运行 `wsl.exe --install --no-distribution`"是**误导** —— 组件已装，重装没用。

**延伸**：若重启后仍报错，可能是 **Windows 快速启动**骗过内核重载，用 `shutdown /s /t 0` 彻底关机。

### 4.3 Ubuntu 建用户填 `admin` → `The group 'admin' already exists`

**根因**：Ubuntu **预留了 `admin` 用户组**，不能建同名用户。
**解法**：换个名字（如 `dev`）。**这不是错误**，是正常的用户名校验。

### 4.4 Docker 填 `127.0.0.1:7890` 代理 → `actively refused`

**根因（两层）**：
1. **Docker 引擎跑在 WSL2 虚拟机里**，它眼里的 `127.0.0.1` 是**虚拟机自己**，不是 Windows 宿主机 → 端口无人监听 → 拒绝连接
2. 更根本：**当时没开 VPN**，`7890`（Clash 默认端口）是死的

**重要鉴别**：报错里 `docker.m.daocloud.io` **已解析成功** → 镜像源没问题，是**代理这一层**挡住的。**别把锅算到镜像源头上。**

**解法**：
- **先清空代理**（Settings → Resources → Proxies → 取消 Manual proxy）
- 真要配代理，必须用 `http://host.docker.internal:端口`（WSL2 访问 Windows 宿主机的专用域名）
- ⚠️ VPN 客户端必须打开 **"Allow LAN"**，否则默认只监听 `127.0.0.1`
- 终极方案：VPN 的 **TUN 模式**（网络层接管全部流量），代理配置可全清空

### 4.5 镜像加速源大面积失效，必须实测

**结论**：2024 年后大量公共 Docker 镜像加速器关停，**网上流传的清单大部分已失效**。

**实测结果（无 VPN）**：

| 源 | 结果 | 可用 |
|---|---|---|
| `docker.m.daocloud.io` | HTTP 401，**95ms** | ✅ **最快** |
| `hub.rat.dev` | HTTP 401，370ms | ✅ |
| `docker.1panel.live` | HTTP 200，868ms | ✅ |
| `docker.nju.edu.cn` | HTTP 403 | ❌ |
| `mirror.ccs.tencentyun.com` | 不通 | ❌ 腾讯云**内网**源 |

**方法论**：用 `curl` 直接测 `/v2/` 端点判断源是否活着。
> `HTTP 401` **属于正常** —— 镜像源要求认证，说明服务在跑。**200 或 401 都算通**。

### 4.6 `curl` 在 PowerShell 里是别名

**根因**：PowerShell 里 `curl` 是 **`Invoke-WebRequest` 的别名**，参数体系完全不同。
**解法**：**必须写 `curl.exe`**。

**连带坑：JSON 里有空格会炸**
```
-d '{\"content\":\"count from 1 to 5\"}'   ← PowerShell 会把 "from" "to" 当 URL 解析
报错：Could not resolve host: from
```
**最优解：JSON 写进文件，用 `-d "@payload.json"`**（见 §8）。

### 4.7 `.ps1` / `.cmd` 里的中文会乱码

**规律**：

| 文件类型 | 中文安全吗 | 原因 |
|---|---|---|
| `.md` / `.yml` | ✅ 安全 | 规范要求 UTF-8 |
| **`.ps1`** | ❌ **不安全** | PS 5.1 按 **ANSI/GBK** 读无 BOM 文件 |
| **`.cmd` / `.bat`** | ❌ **不安全** | cmd 按 **GBK** 读 |

**症状**：中文变 `鐜鍙` 之类乱码，**还会破坏字符串边界导致语法错**（`Write-Host` 的参数漏到 if 外面）。

**结论**：**Windows 脚本文件里只写 ASCII，中文留给 `.md` 文件。**

### 4.8 `git push` 报 `schannel: failed to receive handshake`

**看起来像**：SSL 证书问题 → 容易误入"折腾证书/换 sslBackend"的歧途。

**真实根因**：**VPN 客户端没在运行**。系统代理指向 `127.0.0.1:7897`（Clash Verge 默认端口），但**那个端口无人监听**。

**鉴别方法**：
```powershell
Get-NetTCPConnection -State Listen -LocalPort 7897    # 端口活着吗？
Get-Process | Where-Object { $_.Name -match 'clash|verge|mihomo|v2ray' }
```
**解法**：**先启动 VPN 客户端**，确认端口在监听再 push。**Git 的代理配置本身是对的，不用改。**

### 4.9 `GH007: Your push would publish a private email address`

**根因**：GitHub 开了 **"Keep my email addresses private"**，但 commit 用的是真实邮箱。

**解法**：
```powershell
git config user.email "53072364+national-li@users.noreply.github.com"   # 本地
git commit --amend --reset-author --no-edit                            # 重写已有 commit
git log -1 --format="%an <%ae>"                                        # 确认
git push

git config --global user.email "53072364+national-li@users.noreply.github.com"  # ⚠️ 别漏全局
```
> **必须用带 ID 前缀的完整格式**。直接写 `用户名@users.noreply.github.com` 有时关联不上。

### 4.10 密钥误写入会公开的模板文件

| 文件 | Git 可见 | 放什么 |
|---|---|---|
| `application-local.example.yml` | ✅ 会提交（**公开**） | **只能放占位符** |
| `application-local.yml` | ❌ 被 `.gitignore` 排除 | **真实 key 放这里** |

**通用原则**：
> **文件名带 `.example` / `.sample` / `.template` 的永远是"给人看的说明书"，只能放占位符。**

**提交前自检（10 秒保命）**：
```powershell
git status                                          # ① 文件级：有没有不该提交的
git check-ignore -v application-local.yml           # ② 必须有输出
git grep -n -I --untracked -E "sk-[a-zA-Z0-9]{20,}" # ③ 内容级：扫明文密钥
```

---

## §5 通用排查方法论

从上面 10 个坑里提炼的**四条通用思路**（比具体解法更值钱）：

1. **报错信息要读到最后一个冒号**
   坑 4.4 的 `because Docker Desktop has no HTTPS proxy` 和 `127.0.0.1` 就是答案本身。

2. **区分"配置层"和"网络层"问题**
   坑 4.4 里镜像源解析成功 = 网络没问题，锅在代理配置。

3. **用证据排除分支，别猜**
   坑 4.2 里 `固件中已启用虚拟化: 是` 早就把"BIOS 问题"排除了，省下重启进 BIOS 的功夫。

4. **先用最小可复现单元验证**
   测镜像源用 `alpine`（3MB）而不是 `pgvector`（400MB）。

**补充第 5 条**（坑 4.8 教训）：
5. **报错文字会误导方向** —— 先验证"最可疑的那一层是否真的在工作"（代理端口活着吗？），再往深层走。

---

## §6 ReAct 最小白版解释

> 第一遍读综述没读懂时的正确打开方式。**文章习惯从"范式""架构"切入，而人理解新东西必须从具体例子开始。**

### 一句话

> **ReAct = 让 LLM 能"动手"，而不只是"动嘴"。**

- **普通调用**：你问 → 它答（只能靠训练时的记忆，**不知道现在几点、算不准 3+5**）
- **ReAct**：你问 → 它说"我要先查" → **你的代码真去查了** → 把结果告诉它 → 它说下一步

### ⭐ 最关键认知

**模型自己什么也做不了。所有"行动"都是你的 Java 代码替它执行的。**

### 例子（记住这个就懂了）

**用户问：现在几点了？顺便算一下 3+5**

| 轮次 | 模型说什么 | 你的代码干什么 |
|---|---|---|
| **1** | "我需要当前时间，**调用 get_current_time**" | 收到 `finish_reason: "tool_calls"` → **执行** → 拿到 `14:30` → **追加进 messages** |
| **2** | "要算 3+5，**调用 calculator**" | 收到 `tool_calls` → **执行** → 拿到 `8` → **追加进 messages** |
| **3** | "现在是 14:30，3+5=8" | 收到 `finish_reason: "stop"` → **结束循环，返回** |

### 术语对应

| 术语 | 实际是什么 | API 里对应 |
|---|---|---|
| **Thought** | 模型说"我打算干什么" | `message.content` |
| **Action** | 模型说"调哪个工具、什么参数" | `message.tool_calls[]` |
| **Observation** | **你的代码执行后拿到的结果** | 你追加的 `role:"tool"` 消息 |

> ⚠️ **Observation 不是模型产生的，是你的代码产生的。** 很多文章不讲清这点。

### 循环是谁在转？—— 你的代码

```java
for (int i = 1; i <= maxRound; i++) {          // ← 循环是你的代码
    response = callLLM(messages, tools);       // ← 模型只负责"说"
    if (response.finishReason == "stop") {
        return response.content;               // ← 判断结束也是你的代码
    }
    if (response.finishReason == "tool_calls") {
        result = executeTool(response.toolCalls);   // ← 执行是你的代码
        messages.add(toolMessage(result));          // ← 状态累积也是你的代码
    }
}
return "我无法继续完成请求";                     // ← 防死循环也是你的代码
```

**模型是无状态的**：每轮都要传**完整历史**。它"看起来记得"，是因为你把历史都传过去了。

### 对比另外两种范式

| 范式 | 做法 | 缺点 |
|---|---|---|
| 纯推理 | 凭记忆直接答 | **会瞎编**，算不对 3+5，不知道现在几点 |
| 纯行动 | 死板走固定流程 | 不会应变 |
| **ReAct** | **边想边做，看结果再想** | 可能绕远路 → 所以要 `maxRound` |

### 自测清单（能答上就算懂）

- [ ] 模型能自己调用工具吗？（**不能**，它只能说"我要调"）
- [ ] 循环是谁在转？（**你的代码**）
- [ ] 模型怎么知道上轮发生了什么？（**你传的 messages**，它本身无状态）
- [ ] 什么时候结束循环？（`finish_reason == "stop"`）
- [ ] 为什么要 `maxRound`？（模型可能反复调工具**不收敛**）
- [ ] Thought 是模型天生就有的吗？（**不是**，是 prompt 诱导的）
- [ ] ReAct 比直接答好在哪？（能拿**实时信息**、做**精确计算**）

---

## §7 问答详解（Q1–Q11）

> 问题清单在 `qa-notes.md`，这里是对应的详细答案。

### Q1 `maxRound` 设多少？

**算不出来，是"经验值 + 成本权衡"。W2 直接设 5。**

它不是"计算出循环次数"，而是**刹车**：
```
❌ 错误理解：我预计要转 5 圈
✅ 正确理解：最多让你转 5 圈，再转就掐了
```

**权衡因素**：典型任务 2-3 轮 / 留余量 4-5 轮 / 成本（每轮都是 API 调用）/ 延迟 / 防死循环

**大多数情况下循环会在 `maxRound` 之前自然结束**（`finish_reason` 变 `stop`）。**它只是兜底的保险丝。**

| 场景 | 调整方向 |
|---|---|
| 简单任务（聊天） | 设小，1-2 |
| 复杂任务（多步搜索、文档分析） | 设大，8-10 |
| 生产环境 | 按成本预算倒推 |
| **面试标准答法** | "按任务复杂度和成本预算权衡，不是固定值；同时必须有上限防死循环" |

**配套保护**：达到上限后日志记"第 N 轮被强制终止"；**检测重复调用**（比等 maxRound 更好）。

### Q2 复杂任务（工具调用很多）会不会永远解决不了？

**⚠️ 先纠正前提：轮 ≠ 工具调用次数**

```
一轮循环 = 一次 LLM 调用
一轮里可以包含【多个】工具调用（并行工具调用）
```

模型可以在**一次响应里**返回多个 `tool_calls`，你的代码全部执行完，结果一起塞回，才进下一轮。

| 场景 | 工具调用次数 | **实际轮次** |
|---|---|---|
| 查 1 个城市天气 | 1 | 1 |
| **查 5 个城市天气** | **5** | **1**（并行） |
| 查天气 → 推荐穿衣 → 查航班 | 3 | 3 |
| 复杂文档分析 | 10+ | 3-5（每轮并行几个） |

**「2-3 轮」指 2-3 次 LLM 往返，不是 2-3 个工具调用 —— 差一个数量级。**

**复杂任务的四层手段**：

| 手段 | 说明 |
|---|---|
| ① 提高 maxRound | 按实际分布取 p95 加余量（W6 可观测性才能拿到数据） |
| ② **让工具"一次给更多"** | `get_weather(city)` → `get_weather_batch(cities[])`，**工具粒度直接决定轮次** |
| ③ 用 Plan-and-Execute 替代 ReAct | ReAct 走一步看一步，步数多了容易失控（W5） |
| ④ 拆分任务 | 100 篇文档拆成 N 个子任务，各自独立跑 |

**⚠️ 有些问题确实做不到，要诚实处理**：

| 情况 | 正确处理 |
|---|---|
| 撞到 maxRound | 不能只说"无法继续" —— 要告知**已完成的部分**和**卡在哪** |
| 反复调同一工具 | **检测循环**（相同工具+参数重复 → 提前中断） |
| 超出能力 | 明确说明"需 N 步，超出单次限制，建议拆分" |

> **好的设计不是假装能做完，而是优雅地告诉用户做不到以及为什么。**

### Q3 什么叫"prompt 诱导"？

> ⭐ 最容易被误解的点

**错误认知**：
> ❌ 模型内部真的在"思考"，`Thought` 是它**内在思维过程的自然流露**。

**真实运作**：
```
输入文字 → 预测下一个词 → 输出文字
          ↑ 这里面没有"思考"这一步
```
**模型是文字接龙机器。**

**"诱导"的意思：`Thought` 是你要求它写，它才写的。**

| 你传的 prompt | 模型输出 |
|---|---|
| `[{"role":"user","content":"现在几点"}]` | `"我不知道当前时间。"` ← **没有 Thought** |
| 多一句 `system: "请先写出你的思考过程，再决定是否调用工具"` | `"我需要知道当前时间，应该调用 get_current_time。"` ← **有 Thought 了** |

**同一句话、同一个模型，只多一句 system prompt，Thought 就出现了。**

**为什么 Thought 有用**：它写出来后成为**后续输出的"上文"**，相当于打了个草稿 —— 类似做题时在草稿纸上写步骤。

**⚠️ 更反直觉：Thought 可能是编的**
```
模型输出 Thought: "我需要先查天气，再决定穿什么"
实际它做的：     直接根据训练数据猜了个回答
```
学术上叫 **"unfaithful chain-of-thought"（不忠实的思想链）**。
> **面试提这点是加分项** —— 说明你知道 CoT 的局限，不是盲信。

**认知对照表**：

| 认知 | 对错 |
|---|---|
| 模型内部有个"思考"步骤，Thought 是它的流露 | ❌ |
| Thought 是 prompt 要求它输出的**文字格式** | ✅ |
| 去掉 prompt 里的要求，Thought 就消失 | ✅ |
| Thought 写出来能改善结果（相当于草稿） | ✅ |
| Thought 一定反映真实推理过程 | ❌ **可能完全是编的** |

> **一句话**：**模型的"思考"是说出来的，而不是"说出来的东西反映了思考"。顺序是反的 —— 是"说出来"这个动作本身帮它把结果算对了。**

**与 W3 的关系**：W3 用 Spring AI 时 **thought 是"配置出来的"** —— 你开启某个选项，框架帮你在 prompt 里加诱导语句。**它不是模型自带的开关。**

### Q4 W1 / W2 / W3 是什么？

**W = Week（周）**

| 缩写 | 时间 | 干什么 |
|---|---|---|
| **预备周** | 9/13 – 9/17 | 环境 + 读综述 |
| **W1** | 9/18 – 9/24 | **SpringBoot 写 LLM 客户端**：超时、重试、SSE 流式、Redis 会话 |
| **W2** | 9/25 – 10/1 | ⭐ **纯 Java 手写 Tool Calling 循环**（全路线最难最重要） |
| **W3** | 10/2 – 10/8 | **Spring AI 重构** + 实现 **ReAct** + 联网搜索工具 |
| **W4** | 10/9 – 10/15 | **记忆系统 + RAG**（PgVector 向量检索） |
| **W5** | 10/16 – 10/22 | **Plan-and-Execute** + Python LangGraph 原型 |
| **W6** | 10/23 – 10/29 | **工程化 I**：异常、限流、鉴权、traceId 可观测性 |
| **W7** | 10/30 – 11/5 | **工程化 II**：持久化、长任务方案、docker compose |
| **W8** | 11/6 – 11/12 | **文档 + 简历**（不写新代码） |

**为什么"懂 ReAct 才能进 W1"** —— W1 是"还没开始做 Agent"的阶段（路线图：**"这周绝对不要碰 Tool Calling、不要碰 RAG"**），但：

| 原因 | 说明 |
|---|---|
| 地基意识 | W1 的 `LlmClient` 是 W2 循环的基础组件 |
| 避免走弯路 | 知道"模型只能建议、执行靠我"，就不会试图让模型自己做事 |
| SSE 的用途 | W1 的流式输出，是为了让 **W2 的 Thought 过程实时显示** |
| 面试连贯性 | 简历写"实现了 Agent"，得能说清整体设计 |

### Q5 Function Calling 结构细节

**Q5-1 `get_current_time` 是固定名字吗？**
**不是，随便起。** 建议**动词+名词**（`get_current_time`、`web_search`），长度 ≤ 64，同请求内不重名。
用处：① 模型返回 `"name"` 告诉你调哪个 ② 你靠它 `toolRegistry.get(name)` 找实现。

**Q5-2 `type: "object"` 是实体类吗？**
**不是**，是 JSON Schema 类型。6 种基础类型：`object`/`array`/`string`/`number`/`integer`/`boolean`。
⚠️ `object` 跟 Java 的 `Object` 类**毫无关系**，也**不需要建实体类** —— W2 直接用 `JsonNode` 读。

```
"type": "object"        ← 声明"这是个对象"
    ↓
"properties": {...}     ← 对象里有哪些字段（每个字段有自己的 type）
    ↓
"required": [...]       ← 哪些字段必填
```

**Q5-3 `assistant` 是什么角色？**
**就是"模型"这个角色。** `role` 共四种：

| `role` | 谁产生的 |
|---|---|
| `system` | **你**（开发者） |
| `user` | **用户** |
| `assistant` | **模型**（包括它说要调工具） |
| `tool` | **你的代码**（W2 新增） |

`assistant` 会出现多次（每轮模型说话都是一条），**全部累积在 messages 里**。
> 💡 这就是"模型无状态"的体现；**W1 的"会话历史存 Redis"存的就是这个 `messages` 数组**。

**Q5-4 `arguments` 是谁传给谁的？为什么是字符串？**

⭐ **你传的是 `tools`（格式说明，没值）；模型返回的是 `arguments`（它生成的值）。**

```
【阶段 1】你 → 模型： "tools": [{ "function": { "name": "...", "parameters": {...} } }]
                     ↑ 工具说明书，只有格式，没有值
【阶段 2】模型 → 你： "arguments": "{\"timezone\":\"Asia/Shanghai\"}"   ← 模型生成的具体值
【阶段 3】你 → 模型： { "role": "tool", "tool_call_id": "...", "content": "22:45:30" }
                                                              ↑ 你的代码执行后的真实结果
```

**类比**：你给模型空白申请表（schema）→ 模型填好还你（arguments）→ 你拿表去办事（执行）。

⚠️ **模型给的是"值"，不是"结果"。模型不知道当前时间**，它只知道"要调这个工具、用上海时区"。

**为什么是字符串？**
1. **模型逐字生成文本，天生没有"对象"概念** ← 最根本原因
2. 服务端不认识你的工具，无法反序列化 → **保持字符串实现完美解耦**
3. 客户端可自己做类型安全解析（`objectMapper.readTree(argsStr)`）
4. 附加好处：**流式友好**（arguments 分片到达，能边收边拼）、**精度无损**（大数字转 double 会失真）

**⚠️ `arguments` 可能不是合法 JSON** —— 解析失败**不能往外抛**：
```java
try {
    JsonNode args = objectMapper.readTree(argsStr);
} catch (JsonProcessingException e) {
    messages.add(toolMessage(toolCall.id(), "参数解析失败: " + e.getMessage() + "。请检查后重试。"));
    // continue，模型下一轮会自己修正 ← Agent 的自我纠错
}
```

### Q6 NPE 是空指针吗？

**是**，`NullPointerException`。模型调工具时 `content` 是 `null`：

```java
// ❌ 崩
if (response.message.content().length() > 0) { ... }
// ✅
if (response.message.content() != null && !response.message.content().isBlank()) { ... }
```

### Q7 参数 JSON Schema 完整示例

```json
{
  "type": "function",
  "function": {
    "name": "query_orders",
    "description": "查询用户订单",
    "parameters": {
      "type": "object",
      "properties": {
        "start_time": {
          "type": "integer",
          "description": "起始时间，Unix 毫秒时间戳，例如 1789293475000 表示 2026-09-14 22:45:30"
        },
        "end_time": { "type": "integer", "description": "结束时间，Unix 毫秒时间戳" },
        "status": {
          "type": "string",
          "description": "订单状态",
          "enum": ["pending", "paid", "shipped", "cancelled"]
        },
        "limit": {
          "type": "integer",
          "description": "返回条数上限，默认 20，最大 100",
          "minimum": 1, "maximum": 100
        },
        "tags": {
          "type": "array",
          "description": "标签过滤",
          "items": { "type": "string" }
        },
        "include_deleted": { "type": "boolean", "description": "是否包含已删除订单，默认 false" }
      },
      "required": ["start_time", "end_time"]
    }
  }
}
```

**⚠️ `description` 必须写清"什么格式的整数"**：
```json
// ❌ 模型不知道你要秒还是毫秒
"start_time": { "type": "integer", "description": "起始时间" }
// ✅ 说清格式 + 给例子
"start_time": { "type": "integer", "description": "Unix 毫秒时间戳，例如 1789293475000" }
```
**不写清的后果**：模型传 `1789293475`（秒）而非毫秒，**你的代码拿到差 1000 倍的值还不报错** → 最难查的 bug。

**可选关键字**：`enum` / `minimum` / `maximum` / `minLength` / `maxLength` / `pattern` / `format` / `default` / `items`
> ⚠️ **都是"提示"不是"强制"** —— 见 Q10。

### Q8 模型返回"两个都空"怎么办？

**不要直接结束，应该"有限重试 + 兜底"，并区分两种情况**：

| 情况 | 表现 | 处理 |
|---|---|---|
| ① 响应格式异常 | `content == null` 且 `toolCalls == null` | **重试**（多为偶发） |
| ② 模型明确放弃 | `content` 有值但说"我做不到" | **正常结束**，把话转给用户 |

```java
if ((message.content() == null || message.content().isBlank())
        && (message.toolCalls() == null || message.toolCalls().isEmpty())) {
    emptyResponseCount++;
    log.warn("第 {} 轮收到空响应（第 {} 次），finishReason={}", i, emptyResponseCount, choice.getFinishReason());
    if (emptyResponseCount >= MAX_EMPTY_RETRY) {
        return "抱歉，服务暂时异常，请稍后重试。";
    }
    messages.add(userMessage("请直接给出回答，或调用合适的工具。"));
    continue;   // ← 继续循环，不是 break
}
```

**为什么不能直接结束**：空响应大多是**偶发**的，直接结束等于**把偶发问题变成用户可见的失败**。

**三个更好的防御**：

**① 校验幻觉出的工具名** ⚠️ 很隐蔽
```java
Tool tool = toolRegistry.get(toolCall.function().name());
if (tool == null) {
    // 模型偶尔调用你根本没定义的工具（get_current_time → get_time）
    messages.add(toolMessage(toolCall.id(), "错误：工具不存在。可用工具：" + toolRegistry.names()));
    continue;
}
```

**② 检测重复调用** —— 比等 maxRound 更精准
```java
Set<String> calledSignatures = new HashSet<>();
String signature = toolCall.function().name() + ":" + toolCall.function().arguments();
if (!calledSignatures.add(signature)) {
    log.warn("检测到重复调用，提前终止：{}", signature);
    return "检测到循环调用，已终止。已完成的部分：...";
}
```

**③ 降级话术要诚实**

| 情况 | 返回给用户 |
|---|---|
| 撞到 maxRound | "任务较复杂，已执行 N 步，**已完成：xxx**，剩余建议拆分" |
| 检测到重复调用 | "检测到循环，已终止。**当前进展：xxx**" |
| 空响应重试耗尽 | "服务暂时异常，请稍后重试" |

> ⭐ **永远不要假装成功，也不要只说"我做不到"** —— 要告诉用户**已做到哪、卡在哪、建议怎么办**。

### Q9 每次都要传 tool 吗？开销浪费吗？

**对，每次请求都要传完整 `tools` 数组** —— 模型**完全没有记忆**。

**开销分两块**：

**① Token 开销（钱）—— 能被 Prompt Cache 缓解**
```
Prompt 构成顺序：
┌─────────────────────────┐
│ system prompt           │ ← 固定
│ tools 定义              │ ← 固定   ┐ 缓存边界
│ messages 历史           │ ← 变化   ┘
└─────────────────────────┘
```
**Prompt Cache 是"最长公共前缀匹配"** → system + tools 不变就命中缓存，**计费大幅降低**。
> 💡 **实测方法**：同一会话连发两次，第二次 `prompt_cache_hit_tokens` 应明显变大。

**② 注意力开销（真正的瓶颈）**

| 工具数量 | 模型表现 |
|---|---|
| 3-4 个 | ✅ 准确率高 |
| 10+ 个 | ⚠️ 开始选错 |
| 20+ 个 | ❌ 显著退化，频繁幻觉工具名 |

> **这正是路线图 W3 强调的原因**："不要沉迷堆一堆花里胡哨工具！3-4 个工具足够。"

**工具多了怎么办（五种解法）**：
① 动态注入（按意图只传相关工具）② 两阶段选择 ③ 工具分组 ④ 多 Agent 拆分（W5）⑤ **简单问题不传 tools**（最直接）

> 💡 **面试加分点**："工具不是越多越好。token 成本可用 Prompt Cache 缓解，但**模型的选择准确率会随工具数量下降**，所以生产上要做动态注入或多 Agent 拆分。"

### Q10 模型不按我的参数要求传怎么办？

> ⭐ **最关键的工程问题**

**第一认知：schema 不是"强制"，只是"提示"**

| 你写的 | 模型可能做的 |
|---|---|
| `"maximum": 100` | 传 200 |
| `"enum": ["pending","paid"]` | 传 `"PENDING"` |
| `required: ["expression"]` | 漏掉该字段 |

> **Schema 的本质是 prompt，不是类型系统。它引导模型，但不保证结果。**

**三层防御（缺一不可）**

**第一层：schema 写清楚（事前引导）**
- `description` 详尽（说清格式、给例子）← 最有效
- `enum` 限定取值
- ⭐ **参数设计得简单** ← 最有效
  ```json
  // ❌ 参数太多，模型容易填错
  { "a": 2, "b": 3, "operator": "+" }
  // ✅ 一个字符串搞定
  { "expression": "2+3" }
  ```

**第二层：你自己必须校验（不能省）**
```java
// ① 工具存在吗？（模型会幻觉出不存在的工具名）
// ② arguments 能解析成 JSON 吗？（可能是坏 JSON）
// ③ 必填字段都在吗？
// ④ 类型对吗？（可能把 integer 传成 string）
// ⑤ 取值范围对吗？（enum、min/max）
// 全部通过才 execute
```

**第三层：校验失败也要"塞回 messages"**
```java
messages.add(toolMessage(toolCall.id(), "错误：start_time 必须是整数时间戳，你传的是字符串"));
// → 模型看到后，下一轮会修正参数
```

**自我纠错闭环**：
```
模型填参数 → 你校验 → 不合法 → 把错误告诉模型 → 模型重新填 → 合法 → 执行
                ↑                                          ↓
                └──────── 最多重复 maxRound 次 ────────────┘
```

> **Schema 是给模型的"建议书"，校验是给你的"安检门"。**
> **别指望模型守规矩 —— 你的代码必须假定它会不守规矩。**

### Q11 SSE 是什么？

**SSE（Server-Sent Events）= 服务器通过一个 HTTP 连接，持续往客户端"推"数据的机制。**

```
【普通 HTTP】客户端 ←─── 完整响应 ── 服务器      ← 等全部生成完才返回
【SSE】     客户端 ←─── "1" ────── 服务器      ← 生成一点，推一点
            客户端 ←─── " 2" ───── 服务器
            客户端 ←─── [DONE] ─── 服务器
```
**这就是 ChatGPT 的"打字机效果"。**

**和 WebSocket 的区别（面试高频）**

| | **SSE** | **WebSocket** |
|---|---|---|
| 方向 | **单向**（服务器→客户端） | **双向** |
| 协议 | **就是普通 HTTP** | 独立协议（要握手升级） |
| 数据格式 | **纯文本** | 二进制/文本 |
| 自动重连 | ✅ 浏览器内置 | ❌ 要自己实现 |
| 代理/防火墙 | ✅ 友好 | ⚠️ 有时被拦 |
| 适合 | **服务端推送、AI 流式输出** | 实时双向（聊天室、游戏） |

**Agent 服务为什么优先选 SSE**：① 单向就够 ② 就是 HTTP（复用鉴权、网关、日志、负载均衡）③ 代理友好 ④ 实现简单

**数据格式**（极简，纯文本）：
```
data: {"choices":[{"delta":{"content":"1"}}]}

data: {"choices":[{"delta":{"content":"，"}}]}

data: [DONE]
```
| 前缀 | 含义 |
|---|---|
| `data:` | 一行数据 |
| `event:` / `id:` / `retry:` | 可选 |
| **空行** | **一条消息结束** |

响应头关键的就一个：`Content-Type: text/event-stream`

**W1 要做的**：
```
字节流（DeepSeek 推的 SSE）
      ↓
你的 Java 代码：解析每一行 → 提取 delta.content → 拼接
      ↓
转发给前端（也是 SSE 格式）
```
① 接收（`WebClient` 读 `text/event-stream`）② 解析（按行拆，去掉 `data: ` 前缀）③ 提取 `delta.content` ④ 转发 ⑤ **遇 `[DONE]` 结束，把完整回复存进 Redis**

> **为什么 W1 要先做**：**W2 的 Thought 过程要靠 SSE 实时显示给用户** —— 否则用户盯着空白屏幕等整个工具循环跑完。

---

## §8 Function Calling 完整规范

> 来源：DeepSeek 文档站路径改版频繁（原 `/guides/function_calling` 已 404），此节为完整自包含说明。
> 格式与 OpenAI 基本一致，是 W2 定义 `Tool` 接口时的对照标准。

### 8.1 核心认知

**模型不执行任何东西。** 它只输出一段**结构化文字**说明"我想调用某个工具、用这些参数"，**真正的执行是你的代码干的**。

### 8.2 请求：你告诉模型有哪些工具

```json
POST https://api.deepseek.com/chat/completions
Headers: Authorization: Bearer sk-xxx
         Content-Type: application/json

{
  "model": "deepseek-chat",
  "messages": [ { "role": "user", "content": "现在几点了？" } ],
  "tools": [
    {
      "type": "function",
      "function": {
        "name": "get_current_time",
        "description": "获取当前的日期和时间。当用户询问现在几点、今天几号时调用。",
        "parameters": {
          "type": "object",
          "properties": {
            "timezone": { "type": "string", "description": "时区，如 Asia/Shanghai" }
          },
          "required": []
        }
      }
    }
  ],
  "stream": false
}
```

**`tools` 数组每项形状固定**：
```
{
  "type": "function",        ← 固定，"这个 tool 是函数型"
  "function": {
    "name":        字符串，自己起
    "description": 字符串，告诉模型【什么时候该用它】 ← ⚠️ 极其重要
    "parameters":  JSON Schema，描述参数结构
  }
}
```

### 8.3 多个 function 的格式 ⚠️ 三个易错点

```json
"tools": [
  { "type": "function", "function": { "name": "get_current_time", "description": "...", "parameters": {...} } },
  { "type": "function", "function": { "name": "calculator",        "description": "...", "parameters": {...} } },
  { "type": "function", "function": { "name": "web_search",        "description": "...", "parameters": {...} } }
]
```

| 易错点 | 说明 |
|---|---|
| **① 数组元素没有顶层 `name`** | 名字在 `function.name` 里，**必须嵌套一层**（复制粘贴时最容易漏） |
| **② 逗号位置** | 元素之间要有逗号，**最后一个不能有**（JSON 不允许尾随逗号，报 400） |
| **③ `"type": "function"` 每个元素都要写** | **不是**只在数组外面写一次 |

**推荐用代码生成，别手写 JSON**：
```java
ArrayNode tools = objectMapper.createArrayNode();
for (Tool tool : toolRegistry.values()) {
    tools.add(wrapAsFunctionSchema(tool));   // 统一包成 {type, function:{...}}
}
requestNode.set("tools", tools);
```
> 好处：**新增工具时零改动** —— 实现 `Tool` 接口并注册进去，`tools` 数组自动包含它。

### 8.4 响应：三种情况（+ 一种异常）

#### 情况 1：要调工具 → `finish_reason: "tool_calls"`

```json
{
  "choices": [{
    "index": 0,
    "message": {
      "role": "assistant",
      "content": null,                    ← ⚠️ 是 null
      "tool_calls": [
        { "id": "call_00_abc123",         ← ⚠️ 必须记住这个 id
          "type": "function",
          "function": {
            "name": "get_current_time",
            "arguments": "{\"timezone\":\"Asia/Shanghai\"}"   ← ⚠️ 字符串不是对象
          } }
      ]
    },
    "finish_reason": "tool_calls"
  }]
}
```

| 点 | 说明 |
|---|---|
| `content` 是 `null` | 调工具时模型不说话 → **不判空直接 NPE** |
| `tool_calls` 是**数组** | ⚠️ **可能一次返回多个**（并行调用） |
| `id` | 回传结果时必须带上，**用来对应** |
| `arguments` 是**字符串** | 要 `objectMapper.readTree(...)` 解析 |

#### 情况 2：直接回答 → `finish_reason: "stop"`
```json
"message": { "role": "assistant", "content": "现在是 2026 年 9 月 15 日...", "tool_calls": null }
```
→ **这就是最终答案，结束循环。**

#### 情况 3：两个都有（较少见但存在）
```json
"message": { "content": "我来查一下当前时间。", "tool_calls": [ ... ] }
```
⚠️ **别写成 `if (content != null) return content;`** —— 那样会**把工具调用漏掉**。
> **判断依据永远是 `tool_calls` 是否非空，不是 `content` 是否非空。**

#### 情况 4：两个都空（异常）
→ 有限重试。详见 §7 Q8。

### 8.5 回传结果：用 `role: "tool"`

**发第二次请求**，把执行结果告诉模型：

```json
{
  "model": "deepseek-chat",
  "messages": [
    { "role": "user", "content": "现在几点了？" },

    { "role": "assistant",
      "content": null,
      "tool_calls": [
        { "id": "call_00_abc123",
          "type": "function",
          "function": { "name": "get_current_time", "arguments": "{\"timezone\":\"Asia/Shanghai\"}" } }
      ] },

    { "role": "tool",                                  ← 新增的第 4 种角色
      "tool_call_id": "call_00_abc123",                ← ⚠️ 对应上面那个 id
      "content": "2026-09-15 21:30:45" }               ← 执行结果（必须字符串）
  ],
  "tools": [ ...同样的工具列表... ]                     ← ⚠️ 要再传一次
}
```

| 点 | 说明 |
|---|---|
| **`tool_call_id`** | **必须对应** `tool_calls[i].id` —— 多个工具时一一配对 |
| **`content` 必须是字符串** | 结果是对象也要 `stringify` |
| **`tools` 要再传** | 模型无状态，不传它就不知道还有工具可用 |
| **⚠️ `messages` 必须完整** | user → assistant → tool **三条一条都不能少**。只传 tool 那条，模型不知道"是谁请求的" → 报错或行为异常 |

### 8.6 完整循环骨架（W2 要写的）

```java
List<Message> messages = new ArrayList<>();
messages.add(userMessage("现在几点了？"));

for (int i = 1; i <= maxRound; i++) {
    Response resp = callLLM(messages, tools);       // 每次都传完整历史 + 全量工具

    // ⭐ 判断依据是 toolCalls，不是 content！
    if (resp.toolCalls != null && !resp.toolCalls.isEmpty()) {

        messages.add(resp.toAssistantMessage());    // ⚠️ 先把这条 assistant 加进历史

        for (ToolCall tc : resp.toolCalls) {        // 可能多个（并行）
            String result = validateAndExecute(tc); // 校验 + 执行（见 §7 Q10）
            messages.add(toolMessage(tc.id(), result));
        }
        continue;

    } else if (resp.content != null && !resp.content.isBlank()) {
        return resp.content;                        // 最终答案
    } else {
        // 两个都空 → 有限重试（见 §7 Q8）
    }
}
return "达到最大轮次，已终止";
```

**两处最容易写错**：
| 错误 | 后果 |
|---|---|
| 忘了把 assistant 那条加进 messages | 下轮缺少"谁请求了工具"，模型困惑 |
| 用 `content != null` 判断结束 | 漏掉工具调用（情况 3）或 NPE（情况 1） |

### 8.7 完整往返示例（含并行调用）

**第 1 次请求**
```json
{ "messages": [{"role":"user","content":"现在几点？顺便算 3+5"}],
  "tools": [get_current_time, calculator] }
```

**第 1 次响应** —— ⭐ 注意**一次返回了 2 个工具调用**
```json
{ "choices": [{
    "message": { "role": "assistant", "content": null,
      "tool_calls": [
        { "id": "call_1", "type": "function",
          "function": { "name": "get_current_time", "arguments": "{}" } },
        { "id": "call_2", "type": "function",
          "function": { "name": "calculator", "arguments": "{\"expression\":\"3+5\"}" } }
      ] },
    "finish_reason": "tool_calls" }] }
```
> 这就是"**1 轮里可以多个工具调用**"（§7 Q2）。

**第 2 次请求**
```json
{ "messages": [
    {"role":"user","content":"现在几点？顺便算 3+5"},
    {"role":"assistant","content":null,"tool_calls":[call_1, call_2]},
    {"role":"tool","tool_call_id":"call_1","content":"2026-09-15 21:30:45"},
    {"role":"tool","tool_call_id":"call_2","content":"8"}
  ],
  "tools": [...] }
```

**第 2 次响应**
```json
{ "choices": [{ "message": { "role": "assistant",
      "content": "现在是 2026 年 9 月 15 日 21:30，3+5 等于 8。", "tool_calls": null },
    "finish_reason": "stop" }] }
```
→ **`stop` → 结束循环。总共 2 轮。**

### 8.8 实战要点

| 要点 | 说明 |
|---|---|
| **`description` 是 prompt** | 说清"**什么时候**用它"比"它能干什么"更重要 |
| **工具名不能重复** | 同一请求里重名会冲突 |
| **`arguments` 可能不是合法 JSON** | try-catch，**失败时把错误塞回 messages**（自我纠错） |
| **模型会幻觉出不存在的工具名** | 必须校验 `toolRegistry.get(name) != null` |
| **并行调用按 `id` 配对** | 多工具时不能靠顺序假设 |
| **结果大小要控制** | 工具返回 10MB 塞进 messages → 下轮 token 爆炸 |

---

## §9 工具数量与企业级扩展

> 来源：对"3-4 个工具足够"的质疑（2026-09-15）

### 9.1 先纠正：「3-4 个」是学习阶段的建议，不是技术上限

路线图原文语境（W3）：
> ❌ 避坑：不要沉迷堆一堆花里胡哨工具！**3-4 个工具足够。重点是理解编排逻辑，不是工具多寡。**

**它在说**：学习阶段别把时间花在堆工具上，**重点在理解循环怎么转**。
**它没说**："生产环境也只用 3-4 个。"

### 9.2 但「工具不能太多」这个约束是真的 —— 机制在这

**核心洞察**：瓶颈不是"工具总数"，而是 **"单次决策里有多少候选在互相竞争"**。

**① 工具选择本质是分类问题，而工具多了必然扎堆**
```json
{ "name": "get_user",         "description": "查询用户信息" }
{ "name": "get_user_profile", "description": "查询用户资料" }
{ "name": "get_user_detail",  "description": "查询用户详情" }
```
**这三者在语义上几乎无法区分** —— 模型不是"选不对"，是**人类也选不对**。

**② 负面干扰（negative interference）**
模型看到"还有别的工具可用"，**会改变选择倾向** —— 准确率下降不是 token 变多导致的，是**上下文干扰项变多**。

**③ 每轮都是独立的选错机会**
```
3 个工具  → 选错概率 ×3
20 个工具 → 选错概率 ×20
```
Agent 跑 5 轮，**错误概率会累积**。

**④ 中文 description 区分度更低** —— 训练数据里中文 tool schema 比英文少。

### 9.3 企业级怎么做 —— 六种解法

**核心原则：不是"少用工具"，而是"减少单次决策的候选数"。**

#### 方案 1：分层 / 两阶段选择（最主流）
```
第 1 步：模型只选【类别】       ← 候选 5-8 个，准确率高
         "我要用订单相关的能力"
                ↓
第 2 步：系统注入该类别的工具    ← 候选 3-4 个
         "可选：query_order / refund_order / cancel_order"
                ↓
第 3 步：模型选具体工具并给参数
```
**模型每次只面对 3-4 个候选，但系统总共可以有几百个工具。**
> 类比：不会把整个图书馆的书名给模型看，先给"分类目录"。

#### 方案 2：动态注入 / 按意图路由
```java
String intent = classifyIntent(userQuery);              // 便宜的分类器，甚至规则
List<Tool> relevant = toolRegistry.byCategory(intent);  // 只注入 3-5 个
callLLM(messages, relevant);
```
**意图分类的成本远低于一次 LLM 调用**，却能显著提升准确率。

#### 方案 3：工具本身"更通用"（减少工具数量的正解）
```
❌ 20 个细粒度工具：
   get_user_by_id / get_user_by_phone / get_user_by_email
   create_order / update_order / cancel_order / refund_order ...

✅ 3 个通用工具：
   sql_query(sql)              ← 一个顶 20 个 CRUD
   http_request(url, method)   ← 通用 HTTP
   run_code(language, code)    ← 通用计算
```
**不发布业务工具，发布"能力原语"** —— 组合式而非枚举式。
> 💡 **Claude Code / Cursor 就是这种设计**：工具很少（读文件/写文件/跑命令/搜索），但能力覆盖极广。

#### 方案 4：工具搜索 / 渐进式披露（MCP 的模式）
```json
{ "name": "search_tools",
  "description": "当你不确定有什么工具可用时，用关键词搜索工具库" }
```
```
模型：不确定怎么查订单 → 调用 search_tools("订单 查询")
系统：返回匹配的 3 个工具定义
模型：好，调用 query_order(...)
```
**模型按需获取工具定义，而不是一次全给。** MCP 生态大量采用（因为服务器可能暴露上百个工具）。

#### 方案 5：多 Agent 拆分（W5 的 LangGraph 场景）
```
主 Agent（编排）
   ├─ 订单 Agent     ── 带 4 个订单工具
   ├─ 库存 Agent     ── 带 3 个库存工具
   └─ 数据分析 Agent ── 带 3 个查询工具
```
**每个子 Agent 只面对自己的 3-5 个工具，总能力不受限。**
> 这正是 W5 学 LangGraph 的原因之一 —— 通用状态机能编排多 Agent。

#### 方案 6：服务端路由（模型只调 1 个工具）
```json
{ "name": "order_service",
  "description": "订单服务统一入口。参数 action 指定动作",
  "parameters": {
    "action": { "type": "string", "enum": ["query","create","cancel","refund"] },
    "payload": { "type": "object" } } }
```
**模型只看到 1 个工具**，具体操作由你的代码按 `action` 分发。
**代价**：模型对细节控制力下降（`payload` 自由对象容易填错），适合高度模式化的场景。

### 9.4 正确的认知框架

| 说法 | 对不对 |
|---|---|
| "3-4 个工具足够" | ⚠️ **学习阶段建议**，不是普适规律 |
| "工具总数不能超过 10" | ❌ 错 —— **企业级几百个工具很正常** |
| **"单次决策的候选数要控制在 3-7 个"** | ✅ **这才是真实约束** |
| "工具多了会选错" | ✅ 对，但**是"一次给太多"导致的**，不是"总数多" |
| "工具要设计得更通用" | ✅ 对 —— **能显著减少候选数** |

> **一句话：限制的不是"你有多少工具"，而是"模型在一次决策里要面对多少个候选"。**

### 9.5 面试答法 ⭐

**被问"企业级 Agent 工具很多怎么办"**：

> "工具数量本身不是问题，**问题是单次决策的候选数**。模型选工具本质是个分类任务，候选多了会因语义相近和负面干扰而退化。
>
> 生产上我见过几种做法：
> 1. **分层选择** —— 模型先选类别，系统再注入该类的 3-5 个工具，总库可以几百个
> 2. **按意图动态注入** —— 便宜的意图分类器先路由，只给相关工具
> 3. **工具设计得更通用** —— 用 `sql_query` 替代 20 个 CRUD，Claude Code 就是这种组合式设计
> 4. **工具搜索 / 渐进披露** —— 给模型一个 `search_tools`，MCP 生态常用
> 5. **多 Agent 拆分** —— 每个子 Agent 只带自己那组工具
>
> token 成本可以用 Prompt Cache 缓解，但**准确率下降是消不掉的**，所以核心是**控制候选数而不是控制总数**。"

### 9.6 回到路线图：为什么让你只做 3-4 个

```
W2 的核心是：for 循环 + finish_reason 判断 + 状态累积
             ← 这些跟工具有几个完全无关
```

| 3-4 个工具的好处 | 说明 |
|---|---|
| 调试快 | 出错时容易判断是循环逻辑问题还是工具选择问题 |
| 日志清爽 | 每轮日志不爆炸 |
| 覆盖核心场景 | 本地工具（时间/计算）+ 外部 API（搜索）+ RAG 检索，足够演示全部机制 |

**你要练的是"控制循环"，不是"管理工具库"。**

---

## §10 Plan-and-Execute 与选型判据

> 来源：2026-09-15 读完综述后的对话验收。**W5（10/16）动手时对照本节。**

### 10.1 两者本质区别

| | **ReAct** | **Plan-and-Execute** |
|---|---|---|
| 控制方式 | **交织（interleaved）**：想一步、做一步 | **plan-then-execute**：先出完整计划，再逐条执行 |
| 每步依赖 | **每步都依赖上一步的 Observation** ← "动态"的来源 | **执行阶段默认不回头改计划** ← "刚性"的来源 |
| 谁控制流程 | **模型**每步参与决策 | ⭐ **代码**遍历计划列表 |
| LLM 参与度 | 每一步 | 规划阶段 + 单步内部 |

⚠️ **关键澄清**：Plan-and-Execute 的执行阶段**仍然可以调工具、仍然有 Observation**。
**两者区别不是"有没有工具调用"，而是"有没有事先定好的完整计划"。**

### 10.2 ⭐ 为什么执行器是「你的 Java 代码」

> W5 路线图原文："你的 **Java 代码**逐条执行计划里的任务"

**控制流对比**：
```
【ReAct / W2】
  模型说下一步 → 代码执行 → 模型再说下一步
  → 模型在【每一步】都参与决策

【Plan-and-Execute / W5】
  ① 规划阶段：LLM 生成 ["步骤1","步骤2","步骤3"]     ← LLM 参与
  ② 执行阶段：代码 for 循环遍历列表                   ← ⭐ 代码控制流程
              每一步内部才调 LLM / 工具                ← LLM 只负责单步
```

> **类比**：
> - **ReAct** = 每个路口都问导航"下一个路口怎么走"
> - **Plan-and-Execute** = 导航先给你完整路线，**然后你自己一站一站开**

**三个理由**：

**① 可靠性 —— LLM 会"忘记"计划，代码不会**
LLM 不可靠地遵循多步指令，可能：跳步 / 重复执行 / 顺序打乱 / **自己 invent 计划外的步骤**。
**用代码遍历列表，顺序 100% 保证。**

**② 可控性 —— 能在步骤之间插入逻辑（最重要）**
```java
for (Step step : plan.getSteps()) {
    validateStep(step);                  // 前置校验（权限、参数合法性）
    StepResult r = execute(step);

    if (r.isFailed()) {
        plan = replan(plan, step, r);    // 失败 → 重新规划
        continue;
    }

    auditLog(step, r);                   // 每步审计（W6 可观测性）
    persistProgress(step, r);            // 持久化进度 → 中断可恢复（W7 长任务）
    notifyUser(step);                    // 每步实时推送（配合 W1 的 SSE）

    if (needsUserConfirm(step)) break;   // 危险操作等人工确认
}
```
**让 LLM 自己跑，这些都做不到** —— 你只拿到一段文本，解析出来就已经晚了。

**③ 成本与确定性**
- 单步失败只重试那一步，不用重跑整个计划
- 进度存库 → 可中断恢复（W7 的长任务设计）
- 执行阶段不用每步都问"下一步干什么"，token 更省

### 10.3 ⚠️ 纠错场景的关键认知：规划 ≠ 预演

**常见误解**：
> ❌ "Plan-and-Execute 会在**规划阶段**就发现数据源没权限，提前告知"

**这做不到。** 因为：
```
规划阶段：LLM 只是【生成一份步骤文本】
          ["1. 连接数据源", "2. 拉取数据", "3. 分析", "4. 出报告"]
          ↑ 它【不执行】任何东西 → 【不可能知道】有没有权限
```
**权限问题只有"真的去连一次"才会暴露** —— 那已经是执行阶段了。

**真正的区别是「纠错成本与时机」**：

| | **ReAct** | **Plan-and-Execute** |
|---|---|---|
| 发现问题时 | 第 1 步就撞上（立刻就去连了） | **第 3 步**才撞上（前两步白跑） |
| 怎么应对 | **天然灵活** —— "那我换个数据源" | **计划刚性** —— 要重新规划 |
| 开销 | 试错消耗 token | **已完成步骤的成本沉没** |
| 用户体验 | 能看到"边试边调整" | 等半天，最后说"做不了" |

**那"提前发现"能不能实现？能 —— 靠代码预检，不是靠 LLM**：
```java
// 生成计划后、执行前，做一次轻量预检（真实探测，不是 LLM 推理）
for (Step step : plan.getSteps()) {
    if (!precheck(step)) {          // 权限校验、资源存在性检查
        replan();
    }
}
```

### 10.4 ⭐ 选型判据（最硬的一条）

> **"能不能在开始前写出一份像样的计划？"**
> - **能 → Plan-and-Execute**
> - **不能（目标/环境不明）→ 只能 ReAct**

**三个场景对照**：

| 场景 | 选型 | 真正的理由 |
|---|---|---|
| A. 查天气 | **ReAct** | 目标明确的单步任务，**规划开销纯属浪费**（多一次 LLM 调用 + 多一轮延迟） |
| B. 50 页 PDF 总结 + 发邮件 | **Plan-and-Execute** | ① 步骤天然有序（解析→分块→总结→拼装→发送）② 多步耗时，用户需要知道"要多久、到哪了" ③ 步骤强依赖，计划确定性有价值 ④ 失败点明确，可精确重试那一步 |
| C. 客服（用户随便说一句） | **ReAct** | ⭐ **意图未知 → 根本无法规划**。"我那个订单好像有问题"连"步骤1"都写不出来。ReAct 可以先澄清再决定动作 |

### 10.5 固有缺点（五个，按严重程度排序）

| # | 缺点 | 为什么是缺点 |
|---|---|---|
| **1** | **计划刚性** ⭐ | 计划一旦生成，**遇意外只能推倒重来**。ReAct 撞墙立刻改道，它要重新规划（丢掉已完成成果 + 又一次 LLM 调用） |
| **2** | **规划质量决定一切** | 计划错了后面全错。**第一版计划往往不够好** —— LLM 不知道你的数据长什么样、有哪些坑 |
| **3** | **长计划容易漂移** | 步骤一多（20+），LLM 生成的计划本身就不可靠：漏步、顺序错、粒度不均 |
| **4** | **延迟靠前** | 用户要**等完整计划生成**才开始执行 → 首字节响应变慢（除非边规划边推流） |
| **5** | **额外的 LLM 调用开销** | 规划本身是一次额外调用，token 和延迟都增加 |

### 10.6 ⭐ 三种模式光谱（W5 要做的是中间那个）

```
纯 ReAct              ← 完全没有计划
混合（W5 要做的）      ← 有计划，但执行中可更新    ⭐ 工业界实际采用
纯 Plan-and-Execute    ← 计划完全固定
```

**W5 路线图原文**：
> 每一步执行完，**可以允许 LLM 更新计划**（增加、删除步骤）

**这就是对"计划刚性"的补救** —— 兼顾"有序"和"应变"。

### 10.7 面试答法

**被问"ReAct 和 Plan-and-Execute 怎么选"**：

> "核心判据是**能不能在开始前写出一份像样的计划**。目标明确、步骤有序、多步耗时的任务用 Plan-and-Execute；目标或环境不明、需要边走边看的用 ReAct。
>
> 但要注意 Plan-and-Execute 有个常见误解 —— **规划阶段发现不了执行期的问题**（比如权限），因为 LLM 只是生成步骤文本，不执行任何东西。所以它的真正劣势是**纠错成本高**：计划刚性，遇意外要重新规划，已完成步骤的成本沉没。补救办法是**执行中允许更新计划**，做成混合模式，这也是工业界的实际做法。
>
> 另外执行阶段的控制流**应该由代码而不是 LLM 控制** —— LLM 不可靠地遵循多步指令（跳步、重复、顺序错乱），而且只有代码控制流程时才能在步骤之间插入审计日志、进度持久化、人工确认这些逻辑。"

---

## §11 速查命令

### PowerShell 快捷命令（profile 里已配）

| 命令 | 作用 |
|---|---|
| `cdl` | 回项目目录 |
| `dps` | 看容器状态（最常用） |
| `dup` | 起容器（幂等） |
| `dx` | 看 Postgres 扩展（验证 pgvector） |
| `dpg` | 进 psql |
| `notes` | 打开本文件 |
| `qna` | 打开问题清单 |

### Docker Compose

```powershell
cd D:\Deepseek\agent-lab
docker compose ps              # 看状态（两个 Up/healthy 就正常）
docker compose up -d           # 启动（幂等）
docker compose stop            # 停容器（保留数据）
docker compose down            # 停并删容器（数据卷保留）
docker compose down -v         # ⚠️ 连数据卷一起删
docker compose logs -f postgres
```

### 调 API（Windows 正确姿势）

**推荐：JSON 写文件，用 `-d @文件`**（避免 PowerShell 引号地狱）

```powershell
# 1. 建 payload 文件（_payload*.json 已在 .gitignore 排除）
@'
{
  "model": "deepseek-chat",
  "messages": [ { "role": "user", "content": "count from 1 to 5" } ],
  "stream": false
}
'@ | Set-Content -Path _payload.json -Encoding UTF8

# 2. 调用
curl.exe -s --ssl-no-revoke https://api.deepseek.com/chat/completions `
  -H "Content-Type: application/json" `
  -H "Authorization: Bearer $env:DEEPSEEK_API_KEY" `
  -d "@_payload.json"
```

**流式（SSE）**：把 `"stream"` 改 `true`，并加 `-N` 禁用缓冲
```powershell
curl.exe -N --ssl-no-revoke https://api.deepseek.com/chat/completions `
  -H "Content-Type: application/json" `
  -H "Authorization: Bearer $env:DEEPSEEK_API_KEY" `
  -d "@_payload-sse.json"
```

**不带文件的写法**（用 PowerShell 变量，注意**不要** `\"` 转义）：
```powershell
$body = '{"model":"deepseek-chat","messages":[{"role":"user","content":"hi"}],"stream":false}'
curl.exe -s --ssl-no-revoke https://api.deepseek.com/chat/completions `
  -H "Content-Type: application/json" `
  -H "Authorization: Bearer $env:DEEPSEEK_API_KEY" `
  -d $body
```

### 环境变量

```powershell
# 永久设置（用户级）
[Environment]::SetEnvironmentVariable("DEEPSEEK_API_KEY", "sk-xxx", "User")

# 当前窗口加载（profile 已自动做，手动版）
$env:DEEPSEEK_API_KEY = [Environment]::GetEnvironmentVariable("DEEPSEEK_API_KEY", "User")
```

### Git 日常

```powershell
git add .
git status                                     # ← 必看，确认没有不该提交的
git commit -m "..."
git push                                       # -u 已绑定，不用带 origin main

# 提交前安全自检
git check-ignore -v application-local.yml      # 必须有输出
git grep -n -I --untracked -E "sk-[a-zA-Z0-9]{20,}"   # 应为空
```

### 测镜像源是否活着

```powershell
curl.exe -i https://docker.m.daocloud.io/v2/
# HTTP 401 = ✅ 活着（要求认证，正常）；超时/拒绝 = ❌ 挂了
```
