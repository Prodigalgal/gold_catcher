# OAuth、Bearer PAT 与 MCP 系统操作

入口：运行与配置 → **PAT 令牌与 MCP**。创建令牌后，完整令牌只在本次响应中显示；页面关闭后不能恢复，可撤销并重新创建。

远程 MCP 地址：`https://gold.mnnu.eu.org/api/mcp`。与 Java 服务一同部署，沿用现有 `/api` 网关、数据库、TLS 和 CI/CD，无需另开端口。0.11.0 共 57 个独立工具，覆盖查询、内部模拟交易、研究、压缩、配置与访问管理。读取不启动推理；写操作分别授权。系统没有真实交易所下单工具。

## ChatGPT 网页端：OAuth 接入

在 ChatGPT 的自定义 MCP 设置中填写 `https://gold.mnnu.eu.org/api/mcp`，认证选择 **OAuth**；客户端 ID 和客户端密钥留空，由客户端通过 DCR 自动注册。随后跳转到 Gold Catcher，使用现有账号完成 PoW 登录，在独立授权页核对应用名称、回调域名与读取权限，点击允许后返回 ChatGPT。

已有 Bearer PAT 继续有效，适用于支持自定义 Authorization 请求头的 MCP 客户端、REST 和下文的 OpenAI API 调用。OAuth 不把 PAT 交给客户端，撤销 OAuth 连接也不会撤销 PAT。ChatGPT 网页端的连接方式与 Responses API 直接提供 Bearer 凭据属于两种接入方式。[OpenAI 自定义 MCP 文档](https://developers.openai.com/api/docs/guides/custom-mcp-server)、[OAuth 认证要求](https://developers.openai.com/plugins/build/auth)。

OAuth 连接可在 **运行与配置 → PAT 令牌与 MCP → OAuth 已授权连接** 查看和撤销。该页面显示应用、回调域名、授权范围、期限、最近使用和请求计数，不显示访问令牌、刷新令牌或授权码。

### OAuth 协议与边界

- 资源标识：`https://gold.mnnu.eu.org/api/mcp`；授权服务器 issuer：`https://gold.mnnu.eu.org`，精确匹配，不根据 Host 或代理头生成。
- 资源发现：`GET /.well-known/oauth-protected-resource/api/mcp`，根路径 `/.well-known/oauth-protected-resource` 也返回同一份资源文档。未认证 MCP 请求返回 401，并通过 `WWW-Authenticate: Bearer resource_metadata="..."` 指向文档。
- 授权服务器发现：`GET /.well-known/oauth-authorization-server`，公布注册、授权、兑换、撤销地址，`S256`、授权码/刷新 grant 和 11 个 scope。发现支持的权限不等于授予权限；授权页默认不勾选写权限。
- 注册：`POST /api/oauth/register`，仅支持 public client 的 `token_endpoint_auth_method=none`，无需客户端密钥。支持 DCR，当前不声明 CIMD、OIDC、client_credentials 或 private_key_jwt 能力。注册不授予数据权限，不访问客户端提供的任何 URL。
- 回调 URI 必须与注册值逐字匹配；只接受 HTTPS 或本机回环 HTTP，不接受用户信息段、fragment、普通 HTTP 或通配符。客户端名称未经身份认证，授权页同时显示完整回调地址供用户核对。
- 授权：`GET /api/oauth/authorize` 必须携带 `response_type=code`、`client_id`、`redirect_uri`、`resource`、`scope`、`code_challenge`、`code_challenge_method=S256`。`state` 原样返回。授权请求保存在对应浏览器 Session 中，10 分钟失效；登录轮换 Session ID 后继续保留。GET 不创建授权或令牌；Owner + CSRF 的 POST 才能批准。
- 访问授权仅 30 天，授权码 120 秒且只能使用一次，访问令牌最长 3600 秒。刷新令牌继承授权的绝对到期时间，每次刷新都轮换；重用已消费的刷新令牌会提交整组授权撤销。刷新可缩小权限，不能扩大。
- 兑换：`POST /api/oauth/token`，表单格式，`grant_type=authorization_code` 或 `refresh_token`；均须提供 `client_id` 和精确的 `resource`。授权码还须提供原始 `redirect_uri` 和 `code_verifier`。拒绝重复表单参数、错误客户端、资源、回调和校验值。
- 协议撤销：`POST /api/oauth/revoke`，表单 `client_id` 和 `token`，接受该客户端的访问/刷新令牌，撤销对应整组授权；未知令牌不泄漏存在性。
- 全部授权回调均带精确 `iss`，成功和拒绝均回传 `state`，支持 ChatGPT 的稳定回调模式。OAuth 令牌为 256 位随机 opaque Bearer，数据库仅保存 SHA-256 摘要；资源、Owner、范围、期限和撤销状态在服务端逐次校验。不同于 JWT，本服务没有向外提供签名公钥或 ID token。
- Session Cookie 为 HttpOnly、SameSite=Lax，允许跨站顶层 OAuth 导航；生产仍使用 Secure。所有 Owner 状态修改与授权确认继续强制 CSRF，登录继续强制 PoW。授权页禁止 iframe 嵌入，禁止向外站发送完整 referrer。
- OAuth 与 PAT 使用相同工具、范围检查、限流和并发控制。每个 OAuth 授权默认 120 请求/分钟、2 个同时进行的请求，所有外部请求合计 6 个并发。旧 OAuth 授权保留原范围；需要写权限时重新连接并在授权页选择对应权限。
- DCR 每进程每分钟最多 20 次，最多保留 10000 个客户端；每个 Owner 最多 100 个有效授权，后台最多列出最近 500 条。已注册客户端不会随访问令牌轮换而删除。

### OAuth 验收

`scripts/verify_oauth_sdk.py` 使用官方 MCP Python SDK 2.3.0、原生请求头，执行发现 → DCR → PoW/CSRF 授权 → S256 兑换 → 新旧 MCP 协议工具调用 → 实际刷新 → 重放撤销，最后撤销全部临时授权。不执行模型推理，也不替用户在 ChatGPT 账户中创建连接。Java 合同测试另行覆盖跨 Session 请求、缺失 CSRF、拒绝授权、回调/客户端/resource 替换、权限扩大、过期和重启后的持久凭据读取。

## 权限与认证

| 权限 | 数据 |
| --- | --- |
| `market:read` | 产品、最新报价、K 线、Java 指标、市场深度 |
| `information:read` | 信息库检索、原文、压缩摘要、经济日历、宏观驱动、经济数据与预期、信源状态 |
| `research:read` | 研究历史、模型观点、三方裁决与有效期 |
| `simulation:read` | 模拟账户、订单、事件、复盘、CSV 导出 |
| `configuration:read` | AI / 信源配置、模型状态、产品成本配置 |
| `system:read` | 系统、调度、代理、压缩、归档、数据流重放、完整研究和操作回执 |
| `simulation:write` | 模拟建单、撤单、修改退出条件、减仓、平仓 |
| `research:write` | 启动/取消研究、调整自动研究配置 |
| `information:write` | 重新排队新闻压缩 |
| `configuration:write` | 保存 AI / 信源配置、刷新模型目录 |
| `access:manage` | 创建/撤销 PAT、修改 PAT 授权/有效期、查看/撤销 OAuth 授权 |

每个调用方分别创建令牌，选择永久有效或填写 1–365 天有效期，并设置每分钟 10–600 次请求上限（默认 90 天、120 次）。每个账户最多 100 枚有效令牌，永久令牌也计入上限。每枚令牌最多 2 个同时进行的读取请求，进程总共最多 6 个；超限返回 `429` 和 `Retry-After`。计数窗口为进程内每个 UTC 分钟；当前单服务器副本，若扩多副本需将限流迁移到共享存储。

调用使用 `Authorization: Bearer <YOUR_PAT>`。不要将令牌放进 URL、查询参数、日志、提示词或 Git。数据库持久保存随机令牌的 SHA-256 摘要、范围、有效期、撤销状态、最近使用时间和已认证请求数。永久令牌的 expiresAt 为 null，持续有效至撤销。每次请求从数据库核验撤销和过期状态；重启后仍有效，撤销后新请求立即拒绝。已在执行的只读请求可正常结束。使用计数包含认证后的业务错误，不代表查询成功数。

令牌管理接口沿用 **Owner Session + PoW 登录 + CSRF**：

- `GET /api/access/tokens`：列表及范围，无密钥。
- `POST /api/access/tokens`：`{"name":"AI client","scopes":["market:read","information:read"],"expiresInDays":90,"requestsPerMinute":120}`。
- `POST /api/access/tokens/{id}/revoke`：撤销，重复调用结果一致。
- 创建时 `expiresInDays=0` 表示永久有效；省略仍默认 90 天。UI 提供“永久有效”选项。
- `POST /api/access/tokens/{id}/permanent`：将自己现有的有效 PAT 设为永久，不改变完整令牌；不能恢复已过期或已撤销的令牌。UI 在有效的有限期令牌旁提供“设为永久”。
- `POST /api/access/tokens/{id}/scopes`：原 key 不变，修改 `scopes` 与可选 `allowedTools`；要求 Owner + CSRF。`allowedTools=null` 表示权限内全部工具（将来同范围新增工具自动可见），数组表示仅允许这些工具，空数组表示不允许工具。省略该字段保留原工具白名单，并移除不再属于新 scope 的工具。未知工具或跨范围工具被拒绝。
- 在令牌列表点击“编辑权限”，可选择“完整系统操作”或逐项指定工具。新范围从下一次请求生效，MCP / REST / OpenAI 三个入口共同执行；AI 客户端刷新工具列表即可发现新授权，服务无需重启。过期/撤销令牌不能借修改权限恢复。

PAT 继续不能冒充 Owner Session 或直接调用内部会话接口；操作须通过已授权的外部工具。Owner Cookie 也不替代外部接口 Bearer。

示例：给已经发布的 key 增加模拟交易工具，无需重新生成密钥：

```json
{
  "scopes": ["market:read", "simulation:read", "simulation:write", "system:read"],
  "allowedTools": ["get_quote", "get_simulation_account", "place_simulation_order", "cancel_simulation_order", "get_operation"]
}
```

旧 PAT 升级后保持原 scope，`allowedTools` 默认为 null；不会自动获得写权限。外部 AI 创建或修改其他 PAT 时，不能授予超过自己的 scope 或工具白名单；省略工具范围时继承调用方限制。

## 写操作、重试与模拟规则

每个操作分别命名和声明参数，准确标注 `readOnlyHint`、`destructiveHint`、`openWorldHint`。所有写工具要求 `request_key`（8–128 位 ASCII 字母、数字、`_.:-`）；同一意图重试必须沿用同一参数和 key。同账户相同 key 只派发一次，成功或业务拒绝均保存回执；不同参数复用 key 返回 `REQUEST_KEY_CONFLICT`。回执仅存输入摘要，不存 API Key 等输入明文；创建 PAT 的 secret 只在首次响应返回，重放和查询回执均不返回。

写工具结果包含 `operationId`、`ok`、`replayed`、`result`。HTTP 200 不等于操作成功：业务拒绝为 `ok=false`，MCP 同时设置 `isError=true`。执行中重复请求返回 `COMMAND_IN_PROGRESS`；进程中断或结果持久化失败后返回 `COMMAND_OUTCOME_UNKNOWN`，可查询 `get_operation` 并核对实际订单/研究状态，不会自动再次执行。

`place_simulation_order` 使用十进制字符串、明确产品数量单位、有效期和风险说明，复用 Java 账本对报价、规则、成本、现金和价格步长的校验。`PENDING` 不是成交，`close_simulation_order` / `reduce_simulation_position` 仅提交退出请求，由后续有效报价决定成交与费用。修改入口只调整退出条件；修改入场价格或数量需撤销未成交部分后新建。CFD 仍保留报价情景和未核实隔夜费等限制。

`start_research` 返回排队/准入回执，使用 `get_research` 查询后续进展；研究、压缩和模型目录刷新可能访问上游。AI 配置保存仍检查版本与三个独立厂商席位，仅支持 MIMO、GLM、LongCat、MiniMax、Grok。长线、短线、压缩保持独立执行槽。

操作工具组：

| 用途 | 工具 |
| --- | --- |
| 模拟交易 | `place_simulation_order`, `cancel_simulation_order`, `modify_simulation_order`, `close_simulation_order`, `reduce_simulation_position` |
| 模拟账本 | `get_simulation_account`, `list_simulation_orders`, `get_simulation_order`, `get_simulation_order_events`, `get_strategy_review`, `export_simulation_orders` |
| 研究与压缩 | `start_research`, `cancel_research`, `update_research_schedule`, `reprocess_news_compression` |
| 配置 | `get_ai_configuration`, `update_ai_configuration`, `get_source_configuration`, `update_source_configuration`, `refresh_model_catalogs`, `get_model_status`, `get_market_cost_configuration` |
| 运行与重放 | `get_system_status`, `get_api_performance`, `get_source_proxy_status`, `get_research_schedule`, `get_compression_status`, `get_compression_detail`, `get_news_compressions`, `get_market_archive`, `export_research`, `get_inference_data_flow`, `get_data_flow_stage`, `replay_inference_data_flow`, `replay_analysis`, `replay_indicators`, `replay_news`, `get_operation` |
| 访问管理 | `list_access_tokens`, `create_access_token`, `update_access_token_scopes`, `revoke_access_token`, `make_access_token_permanent`, `list_oauth_authorizations`, `revoke_oauth_authorization` |

REST 查询 GET 不执行写操作。命令入口为固定 `POST /api/external/v1`，请求体 `{"name":"工具名","arguments":{...}}`；与 MCP / OpenAI Function Calling 使用相同目录、参数检查、权限和回执。请求上限 32 KiB。长研究、压缩详情、归档、重放和 CSV 导出返回最多 16000 码点的文本页，按 `nextOffset` 拼接。

## MCP 接入

实现无会话 Streamable HTTP，直接返回 `application/json`；不提供独立 GET SSE 流。支持现代协议 `2026-07-28`，以及初始化协议 `2025-11-25`、`2025-06-18`、`2025-03-26`。现代客户端使用 `server/discover` 和每次请求的 `_meta`；旧客户端使用 `initialize`、`notifications/initialized`。

示例（支持自定义请求头的远程 MCP 客户端，配置结构以客户端要求为准）：

```json
{
  "mcpServers": {
    "gold-catcher": {
      "type": "http",
      "url": "https://gold.mnnu.eu.org/api/mcp",
      "headers": { "Authorization": "Bearer <YOUR_PAT>" }
    }
  }
}
```

只接受 OAuth 的客户端使用上文授权流程。支持本地 stdio 的客户端仍可使用标准库 PAT 适配器：

```json
{
  "mcpServers": {
    "gold-catcher": {
      "command": "python",
      "args": ["/absolute/path/to/gold_catcher/scripts/gold_catcher_mcp.py"],
      "env": {
        "GOLD_CATCHER_MCP_URL": "https://gold.mnnu.eu.org/api/mcp",
        "GOLD_CATCHER_PAT": "<YOUR_PAT>"
      }
    }
  }
}
```

适配器不接受跨站重定向，HTTPS 之外只允许本机回环 HTTP，标准输出仅传 MCP 消息。

### GPT / OpenAI Responses

支持远程 MCP 工具的 OpenAI Responses 请求可直接指定 `server_url` 和 `authorization`，后者填写本系统 PAT 原值（不加 `Bearer ` 前缀）。OpenAI API Key 用于向模型服务认证，PAT 用于模型服务向本系统读取数据，两个密钥分别设置。

```python
import os
from openai import OpenAI

client = OpenAI()  # OPENAI_API_KEY
response = client.responses.create(
    model=os.environ["OPENAI_MODEL"],  # 选择支持远程 MCP 的模型
    input="读取 XAU_USDT 报价、15 分钟 K 线和最新新闻，说明数据时间和覆盖缺口。",
    tools=[{
        "type": "mcp",
        "server_label": "gold_catcher",
        "server_url": "https://gold.mnnu.eu.org/api/mcp",
        "authorization": os.environ["GOLD_CATCHER_PAT"],
        "allowed_tools": ["get_quote", "get_candles", "get_indicators", "search_news", "get_news"],
        "require_approval": "never"
    }]
)
print(response.output_text)
```

示例仅授权列出的只读工具自动读取；可根据数据分享要求改为要求审批。PAT 需包含相应权限。不得将 OpenAI API Key 当作本系统 PAT，也不要把 PAT 加入用户消息。每次 Responses 请求均应重新提供 `authorization`。该连接方式依据 [OpenAI 官方 MCP 文档](https://developers.openai.com/api/docs/guides/tools-connectors-mcp)。接口和 SDK 验收不等于已执行付费 GPT 推理。

### OpenAI 兼容 Function Calling

对于支持 Function Calling 的 Chat Completions/Responses 供应商，提供两个使用同一 PAT 校验的适配接口：

- `GET /api/external/v1/openai/tools?format=chat`：返回 Chat Completions `tools` 数组。
- `GET /api/external/v1/openai/tools?format=responses`：返回 Responses 扁平 function 工具数组。
- `POST /api/external/v1/openai/tool`：`{"tool_call": <单个完整工具调用>}`。接受 Chat 的 `id/type/function` 或 Responses 的 `call_id/type/name/arguments`；返回 `message`（`role=tool/tool_call_id/content`）与 `responseOutput`（`type=function_call_output/call_id/output`）。每次仅执行一个已授权工具，权限与 MCP 完全一致。

Chat Completions 应用循环：获取工具定义 → 发送模型请求 → 接收 `tool_calls` → 将单个调用交给上述执行接口 → 把 `message` 追加到对话 → 继续调用模型。先追加模型本身含 `tool_calls` 的 assistant 消息，再追加对应的 tool 消息。Responses 使用返回的 `responseOutput`。流式参数须先完整拼接；模型产生的非法 JSON、未知工具和越权请求会被拒绝，应由应用处理错误并告知模型。只兼容纯文本而不支持工具调用的接口不会自动获得数据访问能力。

工具适配器不会代理模型推理，也不会将 PAT 转发给模型提供商。工具参数使用非严格模式（`strict=false`）保留可选参数语义，服务器仍自行严格校验类型和范围。格式依据 [OpenAI 官方 Function Calling 文档](https://developers.openai.com/api/docs/guides/function-calling)。

手动验证初始化请求（令牌通过环境变量提供）：

```bash
curl --fail-with-body https://gold.mnnu.eu.org/api/mcp \
  -H "Authorization: Bearer $GOLD_CATCHER_PAT" \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  --data '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"gold-reader","version":"1.0"}}}'
```

反向代理必须透传 `Authorization`、`Origin`、`MCP-Protocol-Version`、`Mcp-Method`、`Mcp-Name`；现有 `/api` HTTPRoute 已覆盖。带 `Origin` 的 MCP 请求须在 `GOLD_EXTERNAL_ALLOWED_ORIGINS` 列表中（逗号分隔；默认生产域名和本地 5173 开发源）；不根据请求的 Host/转发头自动信任 Origin。服务器对服务器请求通常不带 Origin。没有配置跨域浏览器 CORS 授权。

生产 Cloudflare 对普通 Python/MCP 客户端曾返回 403/1010。按 [deploy/cloudflare-programmatic-api.json](../deploy/cloudflare-programmatic-api.json) 对本域名的明确机器接口及 OAuth 发现/注册/兑换/撤销路径关闭 BIC 浏览器头检查；全局 BIC 仍开启，其他规则保持原样。数据接口继续强制 Bearer PAT 或 OAuth 令牌、范围、有效期、Origin 和限流。Owner 管理、登录、授权确认和普通页面未加入例外。配置变更使用限于本 zone 的一小时临时令牌，读回后撤销。后续增加 REST 工具路径须同步清单；MCP 入口固定无需增加路径。

## 工具目录

`tools/list` 只列出令牌权限内的工具；直接调用未授权工具仍返回 403。读取工具标注 `readOnlyHint=true`，写工具标注 false。查询结果既提供文本 JSON 也提供 `structuredContent`。

| 工具 | 参数与默认值 |
| --- | --- |
| `list_products` | 无；两种产品与六种 K 线周期 |
| `get_quote` | `symbol=XAU_USDT`；含状态、来源时刻、接收时刻、产品规格 |
| `get_candles` | `symbol=XAU_USDT, frame=5, limit=100, closedOnly=true`，可选 `from,to` |
| `get_indicators` | `symbol=XAU_USDT`，可选 `frame`，省略则全部周期 |
| `get_market_depth` | `symbol=XAU_USDT`；CFD 明确 `NOT_PROVIDED` |
| `search_news` | `query,source,since,until,cursor,limit=20`；标题及 feed 摘要字面检索，含已有压缩摘要 |
| `get_news` | 必填 `id`，`representation=summary/original`（默认 summary），`offset=0,maxCharacters=8000` |
| `get_calendar` | `from,to,cursor,limit=20`；按日历时段相交筛选 |
| `get_drivers` | `symbol=XAU_USDT`；美元、收益率、实际值、预期、补充资料与覆盖缺口 |
| `get_source_status` | 无；官方、媒体、宏观来源采集状态 |
| `list_research` | `symbol=XAU_USDT`，可选 `horizon=SHORT/LONG,cursor,limit=20` |
| `get_research` | 必填 `id`，`messageOffset=0,limit=6`（最大 12 条消息） |

输入参数严格校验，未知字段、浮点页大小、字符串布尔值和未知产品/周期均拒绝。产品为 `XAU_USDT` 或 `GATE:XAUUSD`；K 线周期为 `1,5,15,60,240,D`。

### 数据边界

- 金融数值保留十进制字符串；未知保留空值。所有时间为 ISO-8601 UTC，界面显示北京时间。
- 报价查询返回实际状态。`MARKET_CLOSED`、`STALE_OR_CONNECTING` 不冒充实时可成交价格；CFD REST 快照不证明 tick 连续性。
- K 线读取**当前采集器窗口，最多 200 根**，不补抓远端历史。`from` 包含、`to` 不包含，均按 K 线开始时刻筛选。默认排除尚未收盘 K 线；升序返回最近 `limit` 根。若 `hasEarlierInWindow=true`，以 `nextBefore` 作为下一页的 `to`。`availableFrom/availableThrough` 为窗口范围，`requestedFromOutsideCache` 明示历史缺口。
- 新闻/日历/研究列表每页最多 50 条，按 `updated_at DESC,id DESC` 游标扫描，每次最多检查 500 条。稀疏匹配可能产生空页：**只要 `nextCursor` 非空就继续**。游标绑定查询条件和起始 `asOf`；之后更新的记录须重新查询，不宣称数据库历史快照隔离。
- 新闻时间过滤用 `receivedAt`。首发时间不明、feed 时间未核实、正文抓取失败、正文与当前 feed 不一致、模型压缩未完成/旧配置摘要均保留标记。检索范围为标题和 feed 摘要；原文通过 `get_news` 读取。摘要查询只读缓存，不触发模型请求。
- 原文按 Unicode 码点分段，每页最多 16000，使用 `nextOffset` 继续。`sourceTruncated` 表示采集源本身已截断，与分页不同。
- 日期精度日历保留 `originalZone`、`scheduledDate/endDate` 和 `timePrecision`，不虚构精确公布时间。列表按记录更新排序。
- 研究返回显式字段投影，排除路由密钥/密文、原始提示词、账户快照和请求归档。辩论消息分段，普通文本字段最多 8000 码点并标明裁剪；完整交易方案及财务数值不改写。`LOW_QUORUM`/超时/过期不等于有效裁决。
- MCP 请求最大 32KiB；JSON-RPC 批量、无效 Origin、现代协议的头/正文不匹配均拒绝。错误不回显凭据、请求正文或服务端堆栈。

## REST 等价入口

`GET /api/external/v1` 返回本令牌工具目录；`GET /api/external/v1/{toolName}` 使用相同参数和权限。

```text
GET /api/external/v1/get_candles?symbol=XAU_USDT&frame=15&limit=100
GET /api/external/v1/search_news?query=gold&limit=10
GET /api/external/v1/get_news?id=<record-id>&representation=original&offset=0
```

返回码：401 令牌缺失/无效/撤销/过期；403 范围不足或 MCP Origin 拒绝；400 参数/协议错误；405 不支持的方法；429 超限。MCP 业务“记录不存在”返回 `isError=true`，不能仅凭 HTTP 200 判成功。

## 验收

Java 测试涵盖真实 PoW/Session/CSRF、PAT 隔离、撤销和过期、持久化摘要、限流、现代/旧版 MCP、错误消息与 Origin、精确价格、Unicode 分页、稀疏游标及研究私有字段排除。发布后可使用 `scripts/verify_external_access.py` 创建临时验收令牌，验证全部工具和撤销，再在 finally 中清理。该脚本不调用模型或启动研究。

CI 还安装 `scripts/requirements-mcp-test.txt` 中固定版本的官方 Python SDK，通过 `scripts/verify_mcp_sdk.py --stdio` 实测初始化版 HTTP、现代 HTTP 和 stdio 适配器。验收服务器关闭行情、新闻、压缩和自动研究；生产发布后另用真实已有新闻、K 线和研究记录验收，不将本地空库当成真实数据验证。

官方发布/正文验收按来源在数据库中分页取样，独立于首页最新 300 条展示窗口，并继续验证 BEA 有日期、BLS 正文声明时刻/长度/版本一致性和日历。样本可为历史资料，仅证明采集与解析链路曾得到可读取资料，不表示刚刚发布；当前各源的连接/延迟健康检查仍单独保留。冷门历史正文详情在内存未命中时读取持久化记录，不发网络请求、不重做压缩、不修改研究证据缓存。

协议依据：[Streamable HTTP](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http)、[版本兼容](https://modelcontextprotocol.io/specification/2026-07-28/basic/versioning)、[工具](https://modelcontextprotocol.io/specification/2026-07-28/server/tools)。
