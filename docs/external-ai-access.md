# PAT 与只读 MCP 服务

入口：运行与配置 → **PAT 令牌与 MCP**。创建令牌后，完整令牌只在本次响应中显示；页面关闭后不能恢复，可撤销并重新创建。

远程 MCP 地址：`https://gold.mnnu.eu.org/api/mcp`。与 Java 服务一同部署，沿用现有 `/api` 网关、数据库、TLS 和 CI/CD，无需另开端口。MCP 和配套 REST 查询均不刷新信源、不调用模型、不启动研究、不写入订单。

## 权限与认证

| 权限 | 数据 |
| --- | --- |
| `market:read` | 产品、最新报价、K 线、Java 指标、市场深度 |
| `information:read` | 信息库检索、原文、压缩摘要、经济日历、宏观驱动、经济数据与预期、信源状态 |
| `research:read` | 研究历史、模型观点、三方裁决与有效期 |

每个调用方分别创建令牌，填写 1–365 天有效期和每分钟 10–600 次请求上限（默认 90 天、120 次）。每个账户最多 100 枚有效令牌。每枚令牌最多 2 个同时进行的读取请求，进程总共最多 6 个；超限返回 `429` 和 `Retry-After`。计数窗口为进程内每个 UTC 分钟；当前单服务器副本，若扩多副本需将限流迁移到共享存储。

调用使用 `Authorization: Bearer <YOUR_PAT>`。不要将令牌放进 URL、查询参数、日志、提示词或 Git。数据库持久保存随机令牌的 SHA-256 摘要、范围、有效期、撤销状态、最近使用时间和已认证请求数。每次请求从数据库核验撤销和过期状态；重启后仍有效，撤销后新请求立即拒绝。已在执行的只读请求可正常结束。使用计数包含认证后的业务错误，不代表查询成功数。

令牌管理接口沿用 **Owner Session + PoW 登录 + CSRF**：

- `GET /api/access/tokens`：列表及范围，无密钥。
- `POST /api/access/tokens`：`{"name":"AI client","scopes":["market:read","information:read"],"expiresInDays":90,"requestsPerMinute":120}`。
- `POST /api/access/tokens/{id}/revoke`：撤销，重复调用结果一致。

PAT 无法调用内部 Session、模型配置、调度或订单接口；Owner Cookie 也不替代外部接口的 Bearer 令牌。

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

只接受 OAuth 的客户端不能直接使用此 PAT 服务；当前不提供 OAuth 授权流程。仅支持本地 stdio 的客户端可使用标准库适配器：

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
- `POST /api/external/v1/openai/tool`：`{"tool_call": <单个完整工具调用>}`。接受 Chat 的 `id/type/function` 或 Responses 的 `call_id/type/name/arguments`；返回 `message`（`role=tool/tool_call_id/content`）与 `responseOutput`（`type=function_call_output/call_id/output`）。每次仅执行一个只读工具，权限与 MCP 完全一致。

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

## 工具目录

`tools/list` 只列出令牌权限内的工具；直接调用未授权工具仍返回 403。所有工具 `readOnlyHint=true`，查询结果既提供文本 JSON 也提供 `structuredContent`。

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

协议依据：[Streamable HTTP](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http)、[版本兼容](https://modelcontextprotocol.io/specification/2026-07-28/basic/versioning)、[工具](https://modelcontextprotocol.io/specification/2026-07-28/server/tools)。
