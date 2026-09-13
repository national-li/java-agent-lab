# Day 1 环境搭建踩坑记录（2026-09-13）

> 用途：后续 8 周的排错手册 + 面试时讲"我踩过什么坑"的素材库
> 原则：只记**现象 → 根因 → 解法**，不记流水账

---

## 一、环境终态（验收基线）

| 组件 | 版本 | 验证方式 |
|---|---|---|
| JDK | 21 | `java -version` |
| IDEA | 统一版 | 启动正常 |
| WSL | 2.7.14 | `wsl --version` |
| Ubuntu | 默认版本 2 | `wsl -l -v` |
| Docker | 29.7.2 | `docker --version` |
| Postgres + pgvector | pg16 / vector **0.8.6** | `docker exec -it agent-postgres psql -U agent -d agentdb -c "\dx"` |
| Redis | 7-alpine | `docker exec -e REDISCLI_AUTH=redis123 -it agent-redis redis-cli ping` → `PONG` |

**容器连接参数**（W1 写代码时直接用）：
```
PostgreSQL:  localhost:5432   库 agentdb   用户 agent   密码 agent123
Redis:       localhost:6379   密码 redis123
```

---

## 二、踩坑记录

### 坑 1：`wsl --install` 卡在 45.8%，关窗口重来 → 从 0% 开始

**现象**：进度条卡在 45.8% 不动，以为死了，关掉 cmd 重开，结果从 4% 重新开始。

**根因**：`wsl --install` 是**一次性下载**，关窗口 = 丢弃全部已下载数据。进度条 UI 刷新和实际下载不同步，长时间不动是正常的。

**解法**：
- 卡住就**等**，或者 `Ctrl+C` 停掉重试
- ❌ **绝对不关窗口**
- 改用 `--web-download` 走微软直连通道，比默认的 Windows Update 通道稳得多
- 拆成两步，避免互相拖累：
  ```powershell
  wsl --install --no-distribution --web-download
  wsl --install -d Ubuntu --web-download
  ```

**判断真假死**：看任务管理器网络占用是否还在走。

---

### 坑 2：组件装完没重启 → 报"WSL2 无法启动，未启用虚拟化"

**现象**：
```
WSL2 无法启动，因为此计算机上未启用虚拟化。
请确保计算机固件设置中"虚拟机平台"可选组件已启用，且虚拟化已开启。
```

**误判方向**：以为是 BIOS 虚拟化没开，准备重启进 BIOS。

**根因**：`wsl --install` 输出的"操作成功完成"后面跟着 **"直到重新启动系统前更改将不会生效"**——VirtualMachinePlatform 组件**已排队但内核还没加载它**。WSL2 启动时检查虚拟化能力，组件没生效就报这个错。

**关键鉴别证据**：`systeminfo` 早就显示 `固件中已启用虚拟化: 是`，说明**问题不在固件层，在 Windows 组件层**。

**解法**：**重启电脑**。报错里那句"请运行 wsl.exe --install --no-distribution"是**误导**——组件已经装了，重装没用。

**延伸知识点**：如果重启后仍报错，可能是 Windows **快速启动**骗过了内核重载，用 `shutdown /s /t 0` 彻底关机（跳过快速启动）。

---

### 坑 3：Ubuntu 建用户填 `admin` → `The group 'admin' already exists`

**现象**：
```
Create a default Unix user account: admin
fatal: The group `admin' already exists.
Failed to create user 'admin'.
```

**根因**：Ubuntu 系统**预留了 `admin` 用户组**，不能建同名用户。

**解法**：换个名字（如 `dev`）。这**不是错误**，是正常的用户名校验。

---

### 坑 4：Docker 填 `127.0.0.1:7890` 代理 → `actively refused`

**现象**：
```
failed to do request: Head "https://docker.m.daocloud.io/v2/library/hello-world/manifests/latest?ns=docker.io":
connecting via app settings HTTPS proxy http://127.0.0.1:7890:
dial tcp 127.0.0.1:7890: connectex: No connection could be made because the target machine actively refused it.
```

**根因（两层）**：
1. **Docker 引擎跑在 WSL2 虚拟机里**，它眼里的 `127.0.0.1` 是**虚拟机自己**，不是 Windows 宿主机。代理装在 Windows 上，虚拟机里那个端口没人监听 → 拒绝连接。
2. 更根本的是：**当时根本没开 VPN**，`7890` 这个 Clash 默认端口是死的。

**重要鉴别**：报错里 `docker.m.daocloud.io` **已经解析成功**——说明镜像源本身没问题，是**代理这一层**挡住的。别把锅算到镜像源头上。

**解法**：
- **先清空代理**（Settings → Resources → Proxies → 取消 Manual proxy）
- 真要配代理，地址必须用 `http://host.docker.internal:7890`（`host.docker.internal` 是 WSL2 访问 Windows 宿主机的专用域名）
- ⚠️ 且 VPN 客户端里必须打开 **"Allow LAN" / "允许局域网连接"**，否则默认只监听 `127.0.0.1`，WSL2 从外面连不进来
- 终极方案：开 VPN 的 **TUN 模式**（网络层接管全部流量，含 WSL2 虚拟网卡），代理配置可以全清空

---

### 坑 5：镜像加速源大面积失效，必须实测

**结论**：2024 年后大量公共 Docker 镜像加速器关停，**网上流传的清单大部分已失效**。

**实测结果（无 VPN，2026-09-13）**：

| 源 | 结果 | 可用 |
|---|---|---|
| `docker.m.daocloud.io` | HTTP 401，**95ms** | ✅ **最快** |
| `hub.rat.dev` | HTTP 401，370ms | ✅ |
| `docker.1panel.live` | HTTP 200，868ms | ✅ |
| `docker.nju.edu.cn` | HTTP 403 | ❌ 拒绝 |
| `mirror.ccs.tencentyun.com` | 不通 | ❌ 腾讯云**内网**源，外部访问不了 |

**方法论（重要）**：**用 `curl`/`Invoke-WebRequest` 直接测 `/v2/` 端点**判断源是否活着。

> `HTTP 401` **属于正常**——镜像源要求认证，说明服务在跑。看到 200 或 401 都算通。

---

### 坑 6：`curl` 在 PowerShell 里是别名

**现象**：照抄网上的 `curl` 命令，参数报错或行为怪异。

**根因**：PowerShell 里 `curl` 是 **`Invoke-WebRequest` 的别名**，参数体系和真正的 curl **完全不同**。

**解法**：**必须写 `curl.exe`**：
```powershell
curl.exe -s --ssl-no-revoke https://api.deepseek.com/chat/completions `
  -H "Content-Type: application/json" `
  -H "Authorization: Bearer sk-xxx" `
  -d '{\"model\":\"deepseek-chat\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"stream\":false}'
```

**Windows 额外的坑**：JSON 内容里**别放中文**（PowerShell 编码会把 JSON 搞乱），用 `hi` 而不是 `你好`。
**`--ssl-no-revoke`**：跳过证书吊销检查，国内网络下少一个报错源。

---

## 三、值得记住的通用排查思路

1. **报错信息要读到最后一个冒号** —— 坑 4 的 `because Docker Desktop has no HTTPS proxy` 和 `127.0.0.1` 就是答案本身，看一眼就定位了。
2. **区分"配置层"和"网络层"问题** —— 坑 4 里镜像源解析成功 = 网络没问题，问题在代理配置。
3. **用证据排除分支，别猜** —— 坑 2 里 `固件中已启用虚拟化: 是` 这条早就把"BIOS 问题"排除了，省下重启进 BIOS 的功夫。
4. **先用最小可复现单元验证** —— 测镜像源用 `alpine`（3MB）而不是 `pgvector`（400MB）。

---

### 坑 8：`git push` 报 `schannel: failed to receive handshake, SSL/TLS connection failed`

**现象**：Git 直连报 `Failed to connect to github.com:443`，配了代理后变成 `schannel: failed to receive handshake`。

**看起来像**：SSL 证书问题 → 容易误入"折腾证书/换 sslBackend"的歧途。

**真实根因**：**VPN 客户端已经不在运行了**。系统代理 `ProxyEnable=1` 指向 `127.0.0.1:7897`（Clash Verge 默认混合端口），但**那个端口无人监听**——任何走这个代理的程序都会握手失败。

**鉴别方法（关键）**：
```powershell
# 端口活着吗？
Get-NetTCPConnection -State Listen -LocalPort 7897
# VPN 客户端在跑吗？
Get-Process | Where-Object { $_.Name -match 'clash|verge|mihomo|v2ray|singbox' }
```

**解法**：**先启动 VPN 客户端**，确认端口在监听，再 push。**Git 的代理配置本身是对的，不用改。**

> ⚠️ 教训：报错文字会误导方向。**先验证"代理端口是否真的在服务"，再考虑 SSL 层面的问题。**

---

### 坑 9：`GH007: Your push would publish a private email address`

**现象**：认证通过、对象传输成功，但被服务端拒绝：
```
remote: error: GH007: Your push would publish a private email address.
 ! [remote rejected] main -> main (push declined due to email privacy restrictions)
```

**根因**：GitHub 账号开了 **"Keep my email addresses private"**，但 commit 用的是真实邮箱（QQ 邮箱）。

**解法**：改用 GitHub 专属 noreply 邮箱
```powershell
# 专属地址在 GitHub → Settings → Emails 页面上（带用户 ID 前缀，不是乱码）
git config user.email "53072364+national-li@users.noreply.github.com"
git commit --amend --reset-author --no-edit   # 重写已有 commit 的作者信息
git log -1 --format="%an <%ae>"               # 确认改对
git push -u origin main

# ⚠️ 别漏：改全局配置，否则以后每个新仓库都重踩
git config --global user.email "53072364+national-li@users.noreply.github.com"
```

> ⚠️ **必须用带 ID 前缀的完整格式**。直接写 `用户名@users.noreply.github.com` 有时关联不上（GitHub 老坑）。

---

## 四、API 调用基线（W1 的参照物）

**DeepSeek 非流式调用已跑通**，返回结构记录如下——这是 W1 写客户端时的对照标准：

```json
{
  "id": "508e869d-...",
  "object": "chat.completion",
  "created": 1789293475,
  "model": "deepseek-flash",
  "choices": [{
    "index": 0,
    "message": { "role": "assistant", "content": "Hi! How can I help you today?" },
    "finish_reason": "stop"
  }],
  "usage": {
    "prompt_tokens": 5, "completion_tokens": 9, "total_tokens": 14,
    "prompt_tokens_details": { "cached_tokens": 0 },
    "prompt_cache_hit_tokens": 0, "prompt_cache_miss_tokens": 5
  },
  "system_fingerprint": "aeb56401ca74e127821c4f9126dcb669"
}
```

### 三个必须留意的细节

**① 请求 `deepseek-chat`，返回 `deepseek-flash` —— 模型名是别名**

`deepseek-chat` 是**稳定别名**，不是具体模型名，后端实际指向哪个模型由 DeepSeek 决定。

```
你的代码 ──"deepseek-chat"──> DeepSeek 路由 ──> deepseek-flash（实际模型）
```

**这不是 bug，是设计**：DeepSeek 升级后端模型时你的代码不用改。**但代价是"请求名 ≠ 实际名"，日志如果只记请求名就会撒谎。**

**处理原则：配置里用别名，日志里记实际值。**

```yaml
# application-local.yml
llm:
  model: deepseek-chat        # ✅ 用别名，稳
  # model: deepseek-flash     # ⚠️ 写死具体模型，被下线就得改代码
```

**回报里还有个 `system_fingerprint` 字段，比 model 名更细粒度**：

```json
"system_fingerprint": "aeb56401ca74e127821c4f9126dcb669"
```

后端配置/参数/环境一变它就变。**也建议一起记进日志。**

**② `finish_reason` 是 W2 循环的核心判断依据**

| 值 | 含义 | W2 处理 |
|---|---|---|
| `stop` | 正常结束 | 拿到答案，**跳出循环** |
| `tool_calls` | **模型要调工具** | 执行工具 → 结果塞回 messages → **继续循环** |
| `length` | 被 max_tokens 截断 | 需处理（增大上限或分段） |
| `content_filter` | 被安全策略拦截 | 要区分处理，不能当正常回复 |

> ⚠️ Day 1 只见到过 `stop`——因为**没传 `tools`，模型无工具可调**。W2 传入 `tools` 后，才会第一次亲眼看到 `tool_calls`。

**③ `usage` 里有 Prompt Cache 字段 —— 成本优化素材**
`prompt_cache_hit_tokens` / `prompt_cache_miss_tokens`：相同前缀的 prompt 命中缓存时**计费大幅降低**。Agent 场景下 **system prompt + 工具 schema 固定**，天然适合走缓存。
> 这是面试聊"成本优化"的现成素材。

---

### ⭐ W1 日志该记什么（照这个写）

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

**字段清单与用途对照**：

| 字段 | 为什么要记 |
|---|---|
| `traceId` | W1 验收项：每次调用都能搜到完整记录；W6 的全链路追踪 |
| 请求 model + **实际 model** | 别名为漂移时能发现；成本按实际模型算 |
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
> 第二条要记住：**日志记摘要，不记原文**。W6 的"记录输入输出摘要"就是这个意思。

---

### 非流式 vs 流式（W1 要实现的差异）

| | 非流式 | 流式（SSE） |
|---|---|---|
| 内容字段 | `choices[0].message.content` | **`choices[0].delta.content`** |
| 结束标志 | `finish_reason: "stop"` | **`data: [DONE]`** |
| 拼接 | 本身就是完整的 | **要自己把 delta 拼起来** |

### 命令备忘（Windows 特有问题）

```powershell
# ⚠️ 必须写 curl.exe —— PowerShell 里 curl 是 Invoke-WebRequest 的别名
# ⚠️ JSON 里别放中文（PowerShell 编码会搞乱）
# ⚠️ 有 VPN 时不需要 --proxy
curl.exe -s --ssl-no-revoke https://api.deepseek.com/chat/completions `
  -H "Content-Type: application/json" `
  -H "Authorization: Bearer sk-xxxx" `
  -d '{\"model\":\"deepseek-chat\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"stream\":false}'
```

---

## 五、待补（自己填）

- [ ] 今天各环节实际耗时记录（用于校准后面 8 周的时间预估）
- [ ] 哪一步最卡、卡了多久
- [ ] 自己对"Agent = LLM + 状态保存 + 循环"的理解（读完综述后补）

---

## 六、安全提醒

- [ ] **API key 已在会话中明文出现过 → 去 https://platform.deepseek.com/api_keys 删除重建**
- [ ] 新 key 只写进 `application-local.yml`（已在 `.gitignore` 中排除）
- [ ] 永远不要把 key 写进 README、笔记、提交历史

### ⚠️ 差点发生的事故：key 写进了会公开的模板文件

**现象**：把真实 key 填进了 `application-local.example.yml`——这个文件**带 `.example` 后缀，`.gitignore` 挡不住，会被提交到公开仓库**。

**根因**：两个文件名字只差 `.example`，作用**完全相反**，很容易搞混。

| 文件 | Git 可见 | 放什么 |
|---|---|---|
| `application-local.example.yml` | ✅ 会提交（**公开**） | **只能放占位符** |
| `application-local.yml` | ❌ 被 `.gitignore` 第 34 行排除 | **真实 key 放这里** |

**检查结果**：本地 `main` 与 `origin/main` 的 commit hash 相同、索引里只有 4 个文件 → **key 从未被提交，未泄露**。

**通用原则（重要）**：

> **文件名带 `.example` / `.sample` / `.template` 的，永远是"给人看的说明书"，只能放占位符。**
> **真实值放在去掉后缀的那个文件里，并用 `.gitignore` 排除。**

**提交前强制自检（10 秒保命，养成习惯）**：

```powershell
cd D:\Deepseek\agent-lab

# ① 看有没有不该提交的文件溜进来
git status

# ② 全仓库扫明文密钥（有输出就停下处理）
Get-ChildItem -Recurse -File -Force |
  Where-Object { $_.FullName -notmatch '\\\.git\\' } |
  Select-String -Pattern 'sk-[a-zA-Z0-9]{20,}' |
  Select-Object Path, LineNumber, Line

# ③ 确认本地配置文件被忽略（必须有输出）
git check-ignore -v application-local.yml
```

**配置写法（三层，按需选）**：

```yaml
api-key: ${DEEPSEEK_API_KEY:}                  # 方式1：纯环境变量（生产标准）
api-key: ${DEEPSEEK_API_KEY:sk-真实key}        # 方式2：环境变量优先 + yml 兜底 ← 推荐
api-key: sk-真实key                            # 方式3：直接写死（最直白）
```

> 语法：`${环境变量名:默认值}` —— 冒号前是变量名，冒号后是"读不到时用的值"。

---

**Git 身份（已配好，勿重复踩坑）**：
```
user.name  = national-li
user.email = 53072364+national-li@users.noreply.github.com
remote     = https://github.com/national-li/java-agent-lab.git
```
以后提交只需：`git add . && git commit -m "..." && git push`（`-u` 已绑定，不用再带）
