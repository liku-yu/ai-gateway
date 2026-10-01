# AI 网关（LLM Gateway）

一套协议接入多家大模型：**密钥不外泄、成本可核算、故障能自愈、敏感信息不出域**。

- **Web 控制台**：启动服务后浏览器打开 `http://localhost:8080/`，用管理员账号登录即可管理全部资源。
- 命令手册（管理 API / 推理 API / 运维命令）：[`docs/COMMANDS.md`](docs/COMMANDS.md)

> 管理后台的模块划分、渠道「测试 / 复制 / 批量测试」等交互参考了
> [new-api](https://github.com/QuantumNous/new-api) 的设计思路，按本项目（Java/WebFlux、多实例、
> Redis 计费）的架构重新实现，未引入其前端技术栈。

---

## 1. 能力总览

| 能力 | 说明 |
|---|---|
| **Web 控制台** | 概览 / 渠道 / 供应商 / 模型 / 应用 / 密钥 / 定价 / 用量 / 对话 / 系统，纯静态零依赖 |
| OpenAI 兼容统一协议 | `/v1/chat/completions`（含 stream）、`/v1/embeddings`、`/v1/models` |
| Anthropic Messages 入站 | `POST /v1/messages`，Claude Code / Anthropic SDK 可直接指向网关 |
| 多协议上游适配器 | openai / qwen / deepseek / ollama / openai-compatible / azure / anthropic / gemini / openai-responses |
| 责任链 Pipeline | 8 个环节可插拔、可排序，新增环节零改动装配代码 |
| 路由与负载均衡 | 加权随机 / 优先级 / 最少连接 / 一致性 Hash / 成本最优 |
| 故障转移与降级 | 换渠道重试（指数退避）→ 模型降级链；首字节后不重试 |
| 熔断与冷却 | Resilience4j 按渠道熔断 + Redis 共享冷却窗口（多实例一致） |
| 多维限流 | Key / 应用 / 单模型 / TPM / 渠道 / 全局 + 并发闸门，全部 Redis Lua 原子 |
| 配额与计费 | 预扣费 → 上游 usage 结算 → 差额退还；日/月预算；余额可在控制台设置 |
| **Prompt Cache 计费** | 缓存读/写独立单价，归一 OpenAI/Anthropic/Gemini/Responses 的缓存 token |
| 敏感信息脱敏 | 6 类策略（含 Luhn/校验位），占位符替换 + 可选回填，映射只存内存 |
| 日志与审计 | 有界队列异步落库，访问日志 + 审计事件 + 小时级用量聚合 |
| 配置热更新 | Redis Pub/Sub 即时广播 + 定时兜底，多实例一致 |
| 管理 API | Web 控制台的后端；脚本可直接调用（登录后带会话令牌） |
| **可视化设置** | 控制台「设置」页热调重试/超时/熔断/限流默认值/脱敏等，持久化 + 多实例广播 |
| 可观测性 | Actuator + Micrometer + Prometheus 指标端点，配套 Grafana 看板 |

---

## 2. 快速开始

### 2.1 前置依赖与初始化

- JDK 21、Maven 3.9+
- Redis 7+、MySQL 8 / MariaDB 10.5+

```bash
sudo service redis-server start
sudo service mariadb start
sudo mariadb -e "CREATE DATABASE IF NOT EXISTS ai_gateway DEFAULT CHARACTER SET utf8mb4;
CREATE USER IF NOT EXISTS 'gw'@'localhost' IDENTIFIED BY 'gw_dev_pwd';
GRANT ALL PRIVILEGES ON ai_gateway.* TO 'gw'@'localhost'; FLUSH PRIVILEGES;"
```

### 2.2 构建与启动

**本地开发**（内置一套开发默认凭据，开箱可用）：

```bash
mvn -DskipTests package
java -jar target/ai-gateway-0.1.0-SNAPSHOT.jar --spring.profiles.active=dev
```

**生产**（不提供任何默认凭据）：

```bash
# 这三项缺失或仍为默认值时，网关会在启动阶段直接拒绝运行
# （SecurityEnvironmentPostProcessor 在连数据库之前就校验）
export GW_CRYPTO_MASTER_KEY="$(openssl rand -base64 32)"
export GW_API_KEY_SALT="$(openssl rand -hex 32)"
export GW_ADMIN_PASSWORD='<强密码，至少 8 位>'
export GW_DB_PASSWORD='<数据库口令>'
java -jar target/ai-gateway-0.1.0-SNAPSHOT.jar
```

也可以 `cp .env.example .env` 填好后 `set -a && source .env && set +a` 再启动。
本地开发想要更接近生产，也可用 `application-local.yml`（已在 `.gitignore`，不会被提交）。

首次启动自动执行 Flyway 迁移（V1 建表 → V5 清空种子数据）。**初始系统是空的**：
没有供应商、渠道、模型、应用、密钥与定价，全部由你在控制台里自行添加。

本地 `dev` 配置的控制台登录账号：**`admin` / `admin123`**。
生产环境请用环境变量设置账号密码（`GW_ADMIN_USER` / `GW_ADMIN_PASSWORD`）。

### 2.3 打开 Web 控制台并登录

浏览器访问 **http://localhost:8080/** ，用账号 **`admin`** / 密码 **`admin123`** 登录
（用户名密码也可用 `GW_ADMIN_USER` / `GW_ADMIN_PASSWORD` 覆盖）。

> 控制台是静态页面（`src/main/resources/static/`），不依赖任何前端框架或构建步骤。
> 登录成功后会签发一个 HMAC 签名会话令牌（有效期默认 12h），存放在浏览器本地；
> 退出即丢弃。脚本/CI 如需免登录，可配置 `gateway.admin.token` 机器令牌。

### 2.4 首次配置（全部在网页完成）

系统初始为空，按下面顺序添加：

1. **供应商**：`供应商 → 新增供应商`，填 code、显示名、Base URL、适配器
   （OpenAI 兼容中转站选 `openai-compatible`；官方则选 `openai`/`anthropic`/`gemini` 等）。
2. **模型**：`模型 → 新增模型`，创建业务用的逻辑模型，如 `chat-default`。
3. **渠道**：`渠道 → 新增渠道`，选供应商、填模型映射（`chat-default=上游物理模型`）、保存。
4. **密钥**：在渠道行点「密钥」，填入上游真实 Key，勾选启用。密钥加密落库、只回掩码。
5. **测试**：点渠道行的「测试」或工具栏「批量测试」，确认密钥/地址/协议连通。
6. **应用与密钥**：`应用 → 新增应用`（可用模型选 `chat-default`），设置余额；
   然后 `密钥 → 签发密钥`（明文仅显示一次）。
7. **对话**：切到「对话」页，用刚签发的虚拟 Key 直接测试一次请求。
8. **定价**：在「定价」里设置输入/输出/缓存读/缓存写四种价格（元/1K）。

---

## 3. Web 控制台使用说明

| 模块 | 能做什么 |
|---|---|
| **概览** | 配置版本、供应商/渠道/模型/Key/定价数量、熔断数；一键刷新配置；自检结果 |
| **渠道** | 列表（含模型映射、密钥状态、限流、熔断）；新增 / 配置密钥 / 启停 / 重置熔断 / 复制 / 删除 / 测试；批量测试 |
| **供应商** | 列表；新增 / 编辑（名称、Base URL、适配器）/ 删除 |
| **模型** | 逻辑模型列表；新增；设置降级链；启停 |
| **应用** | 租户应用列表；新增；启停；**设置余额** |
| **密钥** | 虚拟 Key 列表；签发（明文仅显示一次）；吊销 |
| **定价** | 生效中的四种价格；设置 / 移除 |
| **用量** | 按应用与天数查询请求/错误/输入/输出/缓存读/缓存写/成本，并显示合计 |
| **对话** | 选模型、填虚拟 Key、发消息（支持流式），直接验证整条链路 |
| **设置** | 可视化调整运行参数（路由策略/重试/超时/熔断/限流默认值/脱敏/会话时长）；保存即热生效 |
| **系统** | 自检、刷新配置、管理 API 清单 |

**页面要点**

- 登录状态保存在浏览器 `localStorage`，刷新不丢失；会话过期会自动回到登录界面。
- 所有写操作都会：重载本地配置 → 通过 Redis 广播让运行中的实例即时生效 → 写审计事件。
- 删除供应商前需先删除其下渠道（后端会拒绝并提示）。
- 「对话」页的虚拟 Key 也保存在本地，方便反复测试。

---

## 4. 技术栈与架构

### 4.1 技术栈

| 层面 | 选型 |
|---|---|
| 语言/运行时 | Java 21（虚拟线程）+ Maven |
| 框架 | Spring Boot 3.4.1，WebFlux（全链路响应式） |
| Web 控制台 | 原生 HTML/CSS/JS 静态资源（零依赖、无构建） |
| HTTP 客户端 | Reactor Netty WebClient |
| 缓存/协调 | Redis 7（Lettuce + 连接池），Pub/Sub 配置广播，Lua 原子限流 |
| 持久化 | MySQL 8 / MariaDB，MyBatis-Plus，Flyway 迁移 |
| 韧性 | Resilience4j 熔断 + 自研 Redis 冷却窗口 |
| 分词 | jtokkit（cl100k_base 懒加载 + 短缓存） |
| 可观测 | Actuator + Micrometer + Prometheus + Grafana |

### 4.2 请求生命周期（责任链）

```
① 请求解析(5) → ② 鉴权(10) → ③ 模型权限(15) → ④ 成本预估(20) → ⑤ 准入控制(30)
→ ⑥ 脱敏(40) → ⑦ 路由准备(50) → ⑧ 调用上游(70，内含渠道闸门/熔断/重试/降级) → ⑨ 结算与日志
```

OpenAI 与 Anthropic 两个入站入口最终都构造同一种内部 `ChatRequest`，走这条责任链。
`FilterChainFactory` 在链尾以 `finalizeOnce` 兜底终结，任何环节失败或客户端断连都不会泄漏预扣费与并发额度。

### 4.3 模块结构

```
src/main/java/com/gateway/
├── protocol/   OpenAI 兼容 DTO（未识别字段原样透传）
├── adapter/    适配器接口 + OpenAI 兼容基类 + 各家实现 + UpstreamInvoker
├── filter/     责任链：GatewayFilter + 请求上下文 + 终结器
├── routing/    5 种路由策略 + Router（候选集过滤）
├── circuit/    Resilience4j 熔断注册表 + Redis 冷却窗口
├── ratelimit/  Redis Lua 限流与多维度编排
├── billing/    预扣 → 结算 → 退还、token 估算
├── masking/    6 类识别策略 + 占位符引擎
├── audit/      有界队列异步落库 + 计量聚合
├── admin/      管理业务层 AdminService + 管理 API（传统 /admin、Web /admin/api）+ 鉴权
├── api/        HTTP 入口：OpenAI 兼容 + Anthropic Messages + /api/info
└── infra/      配置快照与热更新、加密、错误体系、WebClient、指标
src/main/resources/static/   Web 控制台（index.html / app.js / styles.css）
```

**统一管理出口**：所有配置变更都收敛到 `com.gateway.admin.AdminService`
（重载快照 → Redis 广播 → 写审计）。Web 控制台与管理 API 都是它的薄封装。

### 4.4 关键工程决策

- **热路径零 DB 查询**：渠道/Key/定价走本地快照，变更时 Redis 广播刷新。
- **责任链只做校验与准备，不选渠道**：渠道选择收敛到调用器，才能与重试预算、冷却、熔断协同。
- **流式首字节后不重试**；**总 deadline 是硬预算**（流式例外，首字节后只保留逐块 idle 超时）。
- **限流 fail-open**；**日志宁可丢也不阻塞**；**终结幂等且必然发生**。
- **失败归因**：渠道饱和/内容审核/上下文超长不计入熔断；401/403/404 计入并冷却。
- **Web 控制台的阻塞调用切到 boundedElastic**，不在 Netty 事件循环上跑 MyBatis / 配置重载。

---

## 5. 接入上游（Provider 适配器）

供应商（`gw_provider`）= 协议 + baseUrl；渠道（`gw_channel`）= 供应商 + 密钥 + 模型映射。
适配器按 `gw_provider.adapter_class` 解析，缺省回退到 `code`，大小写不敏感并支持别名。

| 适配器 code / 别名 | 协议 | 鉴权 | 备注 |
|---|---|---|---|
| `openai` | OpenAI Chat | `Bearer` | 官方 |
| `qwen` / `deepseek` | OpenAI Chat | `Bearer` | 百炼兼容模式 / DeepSeek |
| `ollama` | Ollama 原生 `/api/chat` | 无 | 本地部署 |
| `openai-compatible`（`custom`/`generic`） | OpenAI Chat/Embedding | `Bearer` | **任意兼容供应商，零代码接入** |
| `azure`（`azure-openai`） | OpenAI Chat | `api-key` + deployment | `…/openai/deployments/{model}/…?api-version=…` |
| `anthropic`（`claude`） | Anthropic Messages | `x-api-key` | 含流式、tool_use、缓存 usage |
| `gemini`（`google`/`vertex`） | Gemini generateContent | `x-goog-api-key` | 含 `:streamGenerateContent?alt=sse` |
| `openai-responses`（`responses`） | OpenAI Responses | `Bearer` | `/v1/responses`，含流式 |

**网页操作**：供应商页「新增供应商」→ 渠道页「新增渠道」→「密钥」→「测试」。

**扩展新协议**：实现 `ModelProvider`（或继承 `AbstractOpenAiCompatibleProvider`），
标注 `@Component`，实现 `code()`/`capabilities()`，可选 `aliases()`；无需改装配代码。

---

## 6. 多协议入站（Anthropic Messages）

| 入站路径 | 协议 |
|---|---|
| `POST /v1/chat/completions` | OpenAI Chat |
| `POST /v1/embeddings` | OpenAI Embeddings |
| `POST /v1/messages` | Anthropic Messages |

```bash
export ANTHROPIC_BASE_URL=http://127.0.0.1:8080
export ANTHROPIC_AUTH_TOKEN=sk-gw-你在控制台签发的虚拟Key   # 也支持 x-api-key 头
```

覆盖：system、多轮消息、图片（data URL）、`tool_use`/`tool_result`、流式
`message_start → content_block_delta* → message_stop`、usage（含缓存字段）。
边界：Anthropic 的 `thinking`、server-side 工具、`cache_control` 暂不透传。

---

## 7. Prompt Cache 计费

### 7.1 归一字段

| 上游 | 原始字段 | 归一后 |
|---|---|---|
| OpenAI | `prompt_tokens_details.cached_tokens` | `cached_tokens` |
| Anthropic | `cache_read_input_tokens` / `cache_creation_input_tokens` | `cached_tokens` / `cache_creation_tokens` |
| Gemini | `cachedContentTokenCount` | `cached_tokens` |
| Responses | `input_tokens_details.cached_tokens` | `cached_tokens` |

### 7.2 计费口径

`prompt_tokens` 恒为**输入总量**（Anthropic 的 `input+read+creation`）：

```
标准输入 = prompt_tokens - cached_tokens - cache_creation_tokens   （下限 0）
成本 = 标准输入×输入价 + cached×缓存读价 + cache_creation×缓存写价 + completion×输出价
```

预扣不假设缓存命中（按标准输入价保守预扣），结算按真实 usage 多退少补。
指标新增 `gateway.tokens.cached`、`gateway.tokens.cache.write`。

### 7.3 配置（网页「定价」页或 API）

```bash
# 先登录拿会话令牌（默认账号 admin / admin123）
TOKEN=$(curl -s -X POST http://127.0.0.1:8080/admin/api/login \
  -H 'Content-Type: application/json' -d '{"username":"admin","password":"admin123"}' \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['token'])")

curl -s -X POST http://127.0.0.1:8080/admin/api/prices -H "X-Admin-Token: $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"provider":"openai","model":"gpt-4o-mini","input":0.00105,"output":0.0042,
       "cacheRead":0.000105,"cacheWrite":0.001313,"currency":"CNY"}'
```

> V3 迁移会给存量定价填保守占位（读 0.1×、写 1.25× 输入价），真实价格请覆盖。

---

## 8. 管理与可观测

### 8.1 管理 API（Web 控制台与脚本共用）

完整清单与示例见 [`docs/COMMANDS.md`](docs/COMMANDS.md)。

- **登录**：`POST /admin/api/login {username,password}` 返回会话令牌；
- 其余接口位于 `/admin/api/**`，把会话令牌放在请求头 `X-Admin-Token` 中。

```
POST /admin/api/login                GET /admin/api/me                POST /admin/api/logout

```
GET  /admin/api/status | /doctor | /providers | /channels | /models | /apps | /keys | /prices | /usage
POST /admin/api/providers | /channels | /channels/{id}/test | /channels/test-all | /keys | /prices | /config/refresh
PUT  /admin/api/providers/{id} | /channels/{id}/key | /channels/{id}/status
     /models/{name}/fallback | /models/{name}/status | /apps/{id}/status
DELETE /admin/api/providers/{id} | /channels/{id} | /channels/{id}/circuit | /keys/{id} | /prices
GET/POST /admin/api/apps/{id}/balance
```

传统的 `/admin/status`、`/admin/channels`、`/admin/config/refresh` 等端点保留，行为一致。

### 8.2 Prometheus 指标与 Grafana

`/actuator/prometheus` 暴露指标，`deploy/grafana/ai-gateway-dashboard.json` 是总览看板。

| 面板 | 指标 | 告警建议 |
|---|---|---|
| QPS | `gateway_request_duration_seconds_count` | — |
| 错误率 | `gateway_request_errors_total` / `..._count` | > 5% 持续 5 分钟 |
| 熔断 OPEN 渠道 | `gateway_circuit_open_channels` | > 0 持续 2 分钟 |
| 冷却中渠道 | `gateway_channel_cooling_count` | > 0 持续 5 分钟 |
| 延迟 / TTFB 分位 | `gateway_request_duration_seconds{quantile}` / `..._ttfb_seconds` | P99 > SLA |
| 限流拦截 | `gateway_ratelimit_rejected_total{dimension}` | 按业务预期设阈值 |
| Token 用量 | `gateway_tokens_prompt_count` / `..._completion_count` / `gateway_tokens_cached_count` | — |
| 成本速率 | `gateway_cost_total` | 日/月预算阈值 |
| 模型降级 | `gateway_fallback_count_total` | 持续非零说明主上游不稳 |
| 日志队列深度 | `gateway_log_queue_depth` | 持续增长说明落库跟不上 |

延迟分位走 Prometheus **summary + `quantile` 标签**，查询需带 `quantile="0.5"`，不要用
`histogram_quantile()`。别往指标里加 traceId 之类高基数字段。

---

## 9. 测试与性能

```bash
mvn test                                   # 全量（82 个用例）
mvn test -Dtest=GatewayEndToEndTest        # 端到端（真实 Redis/MariaDB + mock 上游）
mvn test -Dtest=AdminApiTest               # 管理 API 鉴权与只读端点
mvn test -Dtest=CacheBillingTest           # 缓存计费语义
mvn test -Dtest=AnthropicMessagesTest      # Anthropic 入站/出站/流式
```

压测（`--spring.profiles.active=bench` 放宽限流、关闭 DEBUG）：

```bash
python3 scripts/bench.py --concurrency 64 --duration 15 --label 网关
python3 scripts/bench.py --url http://127.0.0.1:18081/v1/chat/completions \
    --duration 15 --concurrency 64 --label 直连mock
```

参考数据（4 核 / 3.9GB，Redis 与 MariaDB 同机）：网关自身开销 P50 ≈ **2.3ms**；
经网关全链路 c=64 约 1.8k req/s。自身开销含每请求 4 次 Redis 往返、鉴权哈希、脱敏与序列化。

---

## 10. 配置项

| 配置 | 环境变量 | 默认 | 说明 |
|---|---|---|---|
| `gateway.api-key-salt` | `GW_API_KEY_SALT` | **无（生产必填）** | 虚拟 Key 哈希盐；dev profile 提供 `dev-only-salt-change-me` |
| `gateway.crypto.master-key` | `GW_CRYPTO_MASTER_KEY` | **无（生产必填）** | AES-256 主密钥（32 字节 Base64）；dev profile 提供开发值 |
| `gateway.admin.username` | `GW_ADMIN_USER` | `admin` | 控制台登录用户名 |
| `gateway.admin.password` | `GW_ADMIN_PASSWORD` | **无（生产必填）** | 控制台登录密码（至少 8 位）；dev profile 提供 `admin123` |
| `gateway.admin.session-hours` | — | 12 | 登录会话有效期（小时） |
| `gateway.admin.token` | `GW_ADMIN_TOKEN` | 空 | 可选的机器令牌（脚本/CI 免登录）；留空则只允许账号密码登录 |
| `gateway.config.refresh-interval` | — | 30s | 配置兜底刷新间隔 |
| `gateway.defaults.max-retries` | — | 2 | 单请求最大换渠道重试次数 |
| `gateway.defaults.request-timeout-ms` | — | 30000 | 单请求总预算（含重试与降级） |
| `gateway.defaults.channel-concurrency-enabled` | `GW_CHANNEL_CONCURRENCY` | true | 渠道并发闸门 |
| `gateway.defaults.channel-quota-enabled` | `GW_CHANNEL_QUOTA` | true | 渠道 RPM/TPM 闸门 |
| `gateway.defaults.channel-cooldown-seconds` | — | 30 | 渠道冷却窗口 |
| `gateway.masking.enabled` | — | true | 脱敏总开关 |
| `gateway.masking.restore-placeholders` | — | false | 响应占位符是否回填 |
| `gw_price.cache_read_price` | — | 0 | 缓存读单价（元/1K） |
| `gw_price.cache_write_price` | — | 0 | 缓存写单价（元/1K） |

> `gateway.defaults.*`、`gateway.masking.*`、`gateway.admin.session-hours` 属于**可运行时热调**的项，
> 可直接在控制台「设置」页修改（持久化到 `gw_setting`，并广播到所有实例）。
> 其余项（端口 / 数据源 / 加密主密钥 / WebClient 连接池 / 异步队列容量）需改配置并重启。

---

## 11. 安全须知

- **启动即校验**：生产配置下（未激活 `dev`），`GW_CRYPTO_MASTER_KEY` / `GW_API_KEY_SALT` /
  `GW_ADMIN_PASSWORD` 缺失、过弱或仍为默认值时，网关在启动阶段直接拒绝运行，不会静默用默认值。
- `.env` / `application-local.yml` / `secrets/` 已在 `.gitignore` 中，**切勿提交真实凭据**。
- 上游密钥以 AES-256-GCM 加密存储；接口只写不读，日志/审计/响应均不含明文。
- 脱敏映射表只存在于单次请求的内存中，请求结束即回收。
- Web 控制台与 `/admin/**` 生产环境必须放在内网或 API 网关之后，并**替换默认管理员密码**
  （`GW_ADMIN_PASSWORD`）；如配置了机器令牌 `GW_ADMIN_TOKEN` 也需替换。
- 登录会话是 HMAC 签名令牌（密钥由 `GW_CRYPTO_MASTER_KEY` 派生），更换主密钥会使其全部失效。
- **上线前打开控制台「系统」页**：会检查主密钥、默认管理员密码/盐值、DB/Redis 与适配器。
- 更换 `GW_CRYPTO_MASTER_KEY` 会导致既有渠道密钥无法解密，需重新注入。

---

## 12. 与其他 AI 网关的对照

调研了 litellm、Portkey、Bifrost、AxonHub、new-api/one-api、gpt-load、tensorzero、plano、magpie。
本项目已吸收：**Web 管理后台（模块化导航 + 资源化 API + 渠道测试/复制/批量测试，参考 new-api）**、
多协议入站（Anthropic）、多上游适配器与 SPI、缓存 token 计费。
待吸收（按优先级）：语义/精确响应缓存、按 key 轮换与冷却、自适应兼容自愈、prompt cache 亲和路由、
OpenTelemetry、MCP、用户体系与充值计费闭环。

---

## 13. 已知限制与后续路线

**限制**

- Anthropic 入站的 `thinking`、server-side 工具、`cache_control` 未透传；入站错误体沿用 OpenAI 形状。
- Gemini/Responses 的 function calling 仅部分支持。
- 单渠道仍是「一个密钥」，多密钥需建多条渠道。
- 无 Dockerfile / CI；余额为 Redis 运行时状态，生产需对接真实计费。

**建议下一步**

1. 语义/精确响应缓存（显著降本）；
2. 按 key 轮换与冷却（把密钥提升为一等实体）；
3. 自适应兼容自愈（解析上游 400 修正后重试）；
4. prompt cache 亲和路由；
5. OpenTelemetry 分布式追踪；
6. Dockerfile + CI，以及角色/多用户权限体系。

---

## 相关文件

- 命令手册：[`docs/COMMANDS.md`](docs/COMMANDS.md)
- 数据库迁移：`src/main/resources/db/migration/`（V1 建表 → V6 运行时设置）
- Web 控制台：`src/main/resources/static/`
- 压测脚本：`scripts/bench.py`
- Grafana 看板：`deploy/grafana/ai-gateway-dashboard.json`
