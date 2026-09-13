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

## 四、待补（自己填）

- [ ] 今天各环节实际耗时记录（用于校准后面 8 周的时间预估）
- [ ] 哪一步最卡、卡了多久
- [ ] 自己对"Agent = LLM + 状态保存 + 循环"的理解（读完综述后补）
