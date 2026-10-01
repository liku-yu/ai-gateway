# 命令手册

本项目所有可用命令与接口：Web 控制台管理 API、推理 API、运维、测试压测、常见任务。
架构与设计说明见仓库根 [`README.md`](../README.md)。

---

## 0. 约定

```bash
JAR=target/ai-gateway-0.1.0-SNAPSHOT.jar
B=http://127.0.0.1:8080
J='Content-Type: application/json'

# 登录拿到会话令牌（默认账号 admin / admin123；有效期 12h）
T=$(curl -s -X POST $B/admin/api/login -H "$J" \
      -d '{"username":"admin","password":"admin123"}' \
      | python3 -c "import sys,json;print(json.load(sys.stdin)['token'])")
H="X-Admin-Token: $T"
VKEY='sk-gw-你的虚拟Key'   # 在控制台「密钥」页签发后填入
```

- **Web 控制台**：浏览器打开 `$B/`，用账号密码登录；界面对应下面所有 `/admin/api/**`。
- `/admin/api/**` 鉴权：把会话令牌放在 `X-Admin-Token` 头；如配置了机器令牌
  `gateway.admin.token`，也可以直接用它免登录。
- 金额：定价与预算为「元」，余额为「分」（Redis 键 `gw:bal:app:<id>`）。
- 虚拟 Key 明文只在签发时显示一次。
- **初始系统为空**：先添加供应商 → 模型 → 渠道 → 密钥，再添加应用与虚拟 Key。

---

## 1. 构建、启动、打开控制台

```bash
mvn -DskipTests package
mvn clean package                                   # 含全部测试

# 本地开发（内置开发默认凭据 admin/admin123，开箱可用）
java -jar $JAR --spring.profiles.active=dev

# 压测（= dev + 放宽限流），指定端口
java -jar $JAR --spring.profiles.active=bench --server.port=9090

# 生产：**不提供默认凭据**，先注入环境变量，否则启动阶段直接拒绝运行
export GW_CRYPTO_MASTER_KEY="$(openssl rand -base64 32)"
export GW_API_KEY_SALT="$(openssl rand -hex 32)"
export GW_ADMIN_PASSWORD='<强密码，至少 8 位>'
export GW_DB_PASSWORD='<数据库口令>'
java -jar $JAR
```

```bash
# 打开控制台（浏览器访问 http://localhost:8080/ ）
xdg-open http://localhost:8080/ 2>/dev/null || echo "浏览器打开 http://localhost:8080/"

curl -s $B/actuator/health                          # 健康
curl -s $B/api/info                                 # 入口索引 JSON
curl -s $B/actuator/prometheus | head               # 指标
```

---

## 2. 管理 API（Web 控制台后端）

全部位于 `/admin/api/**`。除登录外，都需要请求头 `X-Admin-Token`。脚本与网页共用同一套接口。

### 登录 / 会话 / 退出

| 方法 路径 | 说明 |
|---|---|
| `POST /admin/api/login` | 账号密码登录 `{username,password}`，返回 `{token,username,expiresInSeconds}` |
| `GET /admin/api/me` | 校验会话并返回登录用户名 |
| `POST /admin/api/logout` | 退出（无状态令牌，由客户端丢弃） |

```bash
curl -s -X POST $B/admin/api/login -H "$J" -d '{"username":"admin","password":"admin123"}'
curl -s $B/admin/api/me -H "$H"
```

> 默认账号 `admin` / `admin123`，通过 `GW_ADMIN_USER` / `GW_ADMIN_PASSWORD` 覆盖。
> 会话是 HMAC 签名令牌（密钥由 `GW_CRYPTO_MASTER_KEY` 派生），无状态、无需 Redis。

### 2.1 概览 / 自检 / 配置

| 方法 路径 | 说明 |
|---|---|
| `GET /admin/api/status` | 配置版本、数量、熔断、适配器 |
| `GET /admin/api/doctor` | 自检（DB/Redis/密钥/默认凭据/适配器） |
| `POST /admin/api/config/refresh` | 手动广播配置刷新 |

```bash
curl -s $B/admin/api/status -H "$H"
curl -s $B/admin/api/doctor -H "$H"
curl -s -X POST $B/admin/api/config/refresh -H "$H"
```

### 2.2 供应商

| 方法 路径 | 说明 |
|---|---|
| `GET /admin/api/providers` | 列表（含渠道数） |
| `POST /admin/api/providers` | 新增 `{code,name,baseUrl,adapter,disabled}` |
| `PUT /admin/api/providers/{id}` | 编辑 `{name,baseUrl,adapter,disabled}` |
| `DELETE /admin/api/providers/{id}` | 删除（有渠道时拒绝） |

`adapter` 取值：`openai-compatible`、`anthropic`、`gemini`、`openai-responses`、`azure`、
`openai`、`qwen`、`deepseek`、`ollama`。

```bash
curl -s -X POST $B/admin/api/providers -H "$H" -H "$J" \
  -d '{"code":"myrelay","name":"My Relay","adapter":"openai-compatible","baseUrl":"https://relay.example.com/v1"}'
curl -s -X PUT $B/admin/api/providers/10 -H "$H" -H "$J" -d '{"name":"My Relay 2","adapter":"openai-compatible"}'
curl -s -X DELETE $B/admin/api/providers/10 -H "$H"
```

### 2.3 渠道

| 方法 路径 | 说明 |
|---|---|
| `GET /admin/api/channels` | 列表（模型映射/密钥状态/熔断/限流） |
| `POST /admin/api/channels` | 新增 `{provider,name,models:{逻辑名:物理名},baseUrl,weight,priority,rpm,tpm,concurrency,timeoutMs,status}` |
| `PUT /admin/api/channels/{id}/key` | 写/轮换密钥 `{apiKey,baseUrl,activate}` |
| `PUT /admin/api/channels/{id}/status` | 启停 `{status:ACTIVE\|DISABLED}` |
| `DELETE /admin/api/channels/{id}` | 删除渠道 |
| `POST /admin/api/channels/{id}/copy` | 复制渠道 `{name}`（新渠道默认 DISABLED） |
| `POST /admin/api/channels/{id}/test` | 单渠道连通性测试 `{model,timeout}` |
| `POST /admin/api/channels/test-all` | 批量测试所有渠道 `{model,timeout}` |
| `DELETE /admin/api/channels/{id}/circuit` | 重置熔断与冷却 |

```bash
# 新增渠道（默认 DISABLED）
curl -s -X POST $B/admin/api/channels -H "$H" -H "$J" -d '{
  "provider":"myrelay","name":"myrelay-main",
  "models":{"chat-default":"gpt-5.5","chat-cheap":"gpt-5.5-mini"},
  "baseUrl":"https://relay.example.com/v1","rpm":500,"tpm":200000,"concurrency":100,"status":"DISABLED"}'

# 写密钥并启用
curl -s -X PUT $B/admin/api/channels/12/key -H "$H" -H "$J" \
  -d '{"apiKey":"sk-xxx","activate":true}'

# 测试 / 批量测试
curl -s -X POST $B/admin/api/channels/12/test -H "$H" -H "$J" -d '{}'
curl -s -X POST $B/admin/api/channels/test-all -H "$H" -H "$J" -d '{"timeout":15}'

# 复制 / 启停 / 重置熔断 / 删除
curl -s -X POST $B/admin/api/channels/12/copy -H "$H" -H "$J" -d '{"name":"myrelay-copy"}'
curl -s -X PUT  $B/admin/api/channels/12/status -H "$H" -H "$J" -d '{"status":"DISABLED"}'
curl -s -X DELETE $B/admin/api/channels/12/circuit -H "$H"
curl -s -X DELETE $B/admin/api/channels/12 -H "$H"
```

### 2.4 逻辑模型

| 方法 路径 | 说明 |
|---|---|
| `GET /admin/api/models` | 列表 |
| `POST /admin/api/models` | 新增 `{name,type,description,fallback:[...]}` |
| `PUT /admin/api/models/{name}/fallback` | 设置降级链 `{models:[...]}` |
| `PUT /admin/api/models/{name}/status` | 启停 `{enabled:true\|false}` |

```bash
curl -s -X POST $B/admin/api/models -H "$H" -H "$J" \
  -d '{"name":"chat-fast","type":"CHAT","fallback":["chat-cheap"]}'
curl -s -X PUT $B/admin/api/models/chat-fast/fallback -H "$H" -H "$J" -d '{"models":["chat-cheap"]}'
curl -s -X PUT $B/admin/api/models/chat-fast/status -H "$H" -H "$J" -d '{"enabled":false}'
```

### 2.5 应用 / 余额

| 方法 路径 | 说明 |
|---|---|
| `GET /admin/api/apps` | 列表 |
| `POST /admin/api/apps` | 新增 `{name,tenantId,daily,monthly,models:[...]}` |
| `PUT /admin/api/apps/{id}/status` | 启停 `{enabled}` |
| `GET /admin/api/apps/{id}/balance` | 查询余额（分 / 元） |
| `POST /admin/api/apps/{id}/balance` | 设置余额 `{yuan}` 或 `{fen}` |

```bash
curl -s -X POST $B/admin/api/apps -H "$H" -H "$J" \
  -d '{"name":"acme","tenantId":7,"daily":100,"monthly":2000,"models":["chat-default"]}'
curl -s -X POST $B/admin/api/apps/1/balance -H "$H" -H "$J" -d '{"yuan":1000}'
curl -s $B/admin/api/apps/1/balance -H "$H"
```

> 余额是 Redis 运行时状态（`gw:bal:app:1`），生产应对接真实计费系统。

### 2.6 虚拟 Key

| 方法 路径 | 说明 |
|---|---|
| `GET /admin/api/keys[?appId=]` | 列表（不显示明文） |
| `POST /admin/api/keys` | 签发 `{appId,rpm,tpm,concurrency,expire:"yyyy-MM-dd"}`，返回明文一次 |
| `DELETE /admin/api/keys/{id}` | 吊销 |

```bash
curl -s -X POST $B/admin/api/keys -H "$H" -H "$J" \
  -d '{"appId":1,"rpm":600,"tpm":200000,"concurrency":50,"expire":"2026-12-31"}'
curl -s -X DELETE $B/admin/api/keys/5 -H "$H"
```

### 2.7 定价

| 方法 路径 | 说明 |
|---|---|
| `GET /admin/api/prices` | 生效中的定价 |
| `POST /admin/api/prices` | 设置 `{provider,model,input,output,cacheRead,cacheWrite,currency}` |
| `DELETE /admin/api/prices?provider=&model=` | 置为失效（保留历史） |

```bash
curl -s -X POST $B/admin/api/prices -H "$H" -H "$J" -d '{
  "provider":"anthropic","model":"claude-sonnet-4",
  "input":0.021,"output":0.105,"cacheRead":0.0021,"cacheWrite":0.0263,"currency":"CNY"}'
curl -s -X DELETE "$B/admin/api/prices?provider=anthropic&model=claude-sonnet-4" -H "$H"
```

### 2.8 用量

```bash
curl -s "$B/admin/api/usage?days=7" -H "$H"
curl -s "$B/admin/api/usage?days=1&appId=1" -H "$H"
```

### 2.9 运行时设置（Web 控制台可视化配置）

| 方法 路径 | 说明 |
|---|---|
| `GET /admin/api/settings` | 列出可热调的设置项（含类型/范围/默认值/是否被覆盖） |
| `PUT /admin/api/settings` | 批量更新 `{values:{"defaults.max-retries":"3",...}}` |
| `DELETE /admin/api/settings/{key}` | 恢复某项为配置文件/环境变量默认值 |

```bash
curl -s $B/admin/api/settings -H "$H"
curl -s -X PUT $B/admin/api/settings -H "$H" -H "$J" \
  -d '{"values":{"defaults.max-retries":"3","masking.enabled":"true"}}'
curl -s -X DELETE $B/admin/api/settings/defaults.max-retries -H "$H"
```

可选键（14 个）：`defaults.routing-strategy`、`defaults.max-retries`、`defaults.request-timeout-ms`、
`defaults.retry-backoff-ms`、`defaults.channel-cooldown-seconds`、`defaults.channel-concurrency-enabled`、
`defaults.channel-quota-enabled`、`defaults.app-rpm-limit`、`defaults.app-model-rpm-limit`、
`defaults.app-tpm-limit`、`defaults.global-rpm-limit`、`masking.enabled`、
`masking.restore-placeholders`、`admin.session-hours`。

> 修改会持久化到 `gw_setting` 并广播，立即热生效；超出范围/非法值返回 400。

### 2.10 传统管理端点（保留）

`GET /admin/status`、`GET /admin/channels`、`POST /admin/config/refresh`、
`PUT /admin/channels/{id}/key`、`PUT /admin/channels/{id}/status`、
`DELETE /admin/channels/{id}/circuit` —— 语义与 `/admin/api/**` 相同，供旧脚本使用。

---

## 3. 推理 API

### 3.1 OpenAI 兼容

```bash
# 对话（非流式 / 流式）
curl -s -X POST $B/v1/chat/completions -H 'Authorization: Bearer $VKEY' -H "$J" \
  -d '{"model":"chat-default","messages":[{"role":"user","content":"你好"}]}'
curl -sN -X POST $B/v1/chat/completions -H 'Authorization: Bearer $VKEY' -H "$J" \
  -d '{"model":"chat-default","stream":true,"messages":[{"role":"user","content":"你好"}]}'

# 向量化 / 模型列表
curl -s -X POST $B/v1/embeddings -H 'Authorization: Bearer $VKEY' -H "$J" \
  -d '{"model":"embed-default","input":["你好"]}'
curl -s $B/v1/models -H 'Authorization: Bearer $VKEY'
```

网关私有扩展放在 `extra_body`：

| 字段 | 说明 |
|---|---|
| `routing.strategy` | `weighted`(默认) / `priority` / `leastconn` / `hash` / `cheapest` |
| `routing.allow_fallback` | 是否允许模型降级，默认 true |
| `routing.fallback_models` | 覆盖降级链 |
| `routing.session_key` | 一致性 Hash 稳定键 |
| `masking.enabled` / `masking.types` / `masking.restore` | 覆盖应用级脱敏策略 |
| `timeout_ms` | 单请求总预算（流式只约束首字节） |
| `max_cost` | 预估成本上限，超出拒绝 |

### 3.2 Anthropic Messages 入站

```bash
curl -s -X POST $B/v1/messages -H 'x-api-key: $VKEY' -H "$J" \
  -d '{"model":"chat-default","max_tokens":100,"messages":[{"role":"user","content":"你好"}]}'
curl -sN -X POST $B/v1/messages -H 'x-api-key: $VKEY' -H "$J" \
  -d '{"model":"chat-default","max_tokens":100,"stream":true,"messages":[{"role":"user","content":"你好"}]}'
```

Claude Code：

```bash
export ANTHROPIC_BASE_URL=http://127.0.0.1:8080
export ANTHROPIC_AUTH_TOKEN=$VKEY
```

---

## 5. 运维命令

### 5.1 Redis（余额 / 预算 / 冷却 / 限流）

| 键 | 含义 | 示例 |
|---|---|---|
| `gw:bal:app:<id>` | 应用余额（分） | `redis-cli set gw:bal:app:1 100000` |
| `gw:budget:app:<id>:d:<yyyyMMdd>` | 当日预算累计（分） | `redis-cli get gw:budget:app:1:d:20261001` |
| `gw:budget:app:<id>:m:<yyyyMM>` | 当月预算累计（分） | `redis-cli get gw:budget:app:1:m:202610` |
| `gw:cool:ch:<id>` | 渠道冷却标记 | `redis-cli ttl gw:cool:ch:1` |
| `rl:*` | 限流计数 | `redis-cli --scan --pattern 'rl:*'` |
| `gw:config:refresh` | 配置刷新频道 | `redis-cli publish gw:config:refresh refresh` |

### 5.2 SQL（诊断）

```bash
mariadb -u gw -pgw_dev_pwd ai_gateway -e \
  "SELECT logical_model,provider,cached_tokens,cache_creation_tokens,cost,success,status_code \
   FROM gw_request_log ORDER BY id DESC LIMIT 20;"
mariadb -u gw -pgw_dev_pwd ai_gateway -e \
  "SELECT provider,model,SUM(req_cnt),SUM(cached_tokens),SUM(cost) FROM gw_usage_hourly \
   WHERE stat_hour >= CURDATE() GROUP BY provider,model;"
mariadb -u gw -pgw_dev_pwd ai_gateway -e \
  "SELECT event_type,actor,target_type,target_id,create_time FROM gw_audit_event ORDER BY id DESC LIMIT 20;"
mariadb -u gw -pgw_dev_pwd ai_gateway -e \
  "SELECT version,description,success FROM flyway_schema_history ORDER BY installed_rank;"
mariadb -u gw -pgw_dev_pwd ai_gateway -e \
  "SELECT setting_key,setting_value,update_time FROM gw_setting;"
```

### 5.3 环境变量

> 前三项（`GW_API_KEY_SALT` / `GW_CRYPTO_MASTER_KEY` / `GW_ADMIN_PASSWORD`）在**生产**下必填；
> 缺失或仍为默认值时网关拒绝启动。本地开发用 `--spring.profiles.active=dev` 可跳过。

```bash
export GW_API_KEY_SALT='<长随机串>'
export GW_CRYPTO_MASTER_KEY="$(openssl rand -base64 32)"
export GW_ADMIN_USER='admin'
export GW_ADMIN_PASSWORD='<强密码>'
export GW_ADMIN_TOKEN=''      # 可选机器令牌；留空则只允许账号密码登录
export GW_REDIS_HOST=127.0.0.1
export GW_REDIS_PORT=6379
export GW_DB_PASSWORD='...'
export GW_CHANNEL_CONCURRENCY=true
export GW_CHANNEL_QUOTA=true
```

## 6. 测试与压测

```bash
mvn test                                   # 全量（82 个用例）
mvn test -Dtest=GatewayEndToEndTest        # 端到端（真实 Redis/MariaDB + mock 上游）
mvn test -Dtest=AdminApiTest               # 管理 API 鉴权与只读端点
mvn test -Dtest=CacheBillingTest           # 缓存计费语义
mvn test -Dtest=AdapterRegistryTest        # 适配器注册与别名
mvn test -Dtest=UsageTranslationTest       # 三家 usage/终止原因翻译
mvn test -Dtest=AnthropicMessagesTest      # Anthropic 入站/出站/流式

python3 scripts/bench.py --concurrency 64 --duration 15 --label 网关
python3 scripts/bench.py --url http://127.0.0.1:18081/v1/chat/completions \
    --duration 15 --concurrency 64 --label 直连mock
```

---

## 7. 常见任务速查

**在网页里接入一个 OpenAI 兼容厂商**
```
供应商 → 新增供应商（code=myrelay，适配器=openai-compatible，Base URL=…/v1）
渠道   → 新增渠道（供应商=myrelay，模型映射 chat-default=gpt-5.5）
渠道   → 该行「密钥」填入 sk-xxx 并启用
渠道   → 该行「测试」确认连通
```

**给新租户开通**
```
应用 → 新增应用（名称 acme，日/月预算，可用模型）
密钥 → 签发密钥（选应用，设置 RPM/TPM/并发），保存明文
应用 → 该行「余额」设置金额（元）
对话 → 用该 Key 发一条消息验证
```

**排查某个渠道**
```
渠道 → 「测试」看错误详情；「重置熔断」恢复被冷却的渠道
用量 → 观察该 provider 的错误数与成本
系统 → 刷新配置 / 查看自检
```

**让 Claude Code 用网关里的任意模型**
```bash
export ANTHROPIC_BASE_URL=http://127.0.0.1:8080
export ANTHROPIC_AUTH_TOKEN=$VKEY
```
