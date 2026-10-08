# Gold Catcher

Java / React 黄金研究与内部交易模拟系统。交易行情统一使用 Gate：XAU_USDT 黄金永续、XAUUSD 黄金 CFD；指标与账务由 Java 计算，多个模型经 any2api 独立研究、交叉质疑、修订和裁决。

当前版本 **0.3.0，内部模拟 Alpha**。独立登录页面采用 Session、CSRF 和单次 SHA-256 PoW，登录凭据只在后端配置。六个主页面展示实际行情、信息、讨论、永续内部订单、复盘和运行状态。

Gate XAU_USDT 已接入 WebSocket 买卖一、公开规格、风险档位、六周期 K 线与历史资金费。CFD 已接入公开 Bid/Ask 与六周期 K 线，并支持后台签名只读查询规格及佣金。CFD 的完整成交、隔夜费类型与结算约定仍需核实；不套用永续撮合或其他平台规则。系统没有外部下单调用。

## 设计与运行边界

- [系统与模拟规则](docs/system-design.md)
- [信源目录](docs/source-catalog.md)
- [页面与信息影响看板](docs/ui-design.md)
- [K8S 与 CI/CD](docs/deployment-design.md)
- [当前实现、上下文证据与模拟边界](docs/runtime-alpha.md)

## 本地运行

需要 JDK 25、Node.js 22.12+。

```powershell
cd backend
.\gradlew.bat test bootJar
cd ..
.\scripts\start-backend.ps1 -NoBuild -KeyFile @(
  'D:\WorkSpace\Project\服务器管理\private\any2api\distribution\api-keys.env',
  'D:\WorkSpace\Project\服务器管理\private\gold-catcher\gate.env'
)
```

另一个终端执行 `cd web`、`npm ci`、`npm run dev`，打开 http://127.0.0.1:5173。后端绑定回环地址。本地访问配置保存在 Git 忽略的 `data/local-access.json`，账本与研究保存在 `data/gold-catcher.mv.db`，重启保留。

Gate 凭据保存在“服务器管理”的私密目录，权限限制为当前 Windows 用户，源仓库和 React 不保存 Key。`GOLD_GATE_API_KEY`、`GOLD_GATE_API_SECRET` 仅由 Java 读取。没有 Gate Key 仍可读取两种产品的公开行情。

永续默认采用 Gate 公开合约 Taker 费率，开平仓均保守计费；不是已核实的个人 VIP 费率。配置 `GOLD_TAKER_FEE_RATE` 可显式覆盖。交易数量使用基础 XAU 数量，原生 Gate 合约张数由 Java 按 `quanto_multiplier` 换算。

研究默认手动启动。默认路线为 `config/ai-routes.balanced.example.json`；可用 `-RoutesFile` 选择路线。预算参考 any2api 实测报告，但不当作已证明的最大上下文容量。PoW 在 Web Worker 中计算，浏览器不保存密码到 localStorage/sessionStorage。

直接启动 Java 时需要配置 `GOLD_AUTH_USERNAME` 和 `GOLD_AUTH_PASSWORD`；生产 HTTPS 配置 `GOLD_AUTH_COOKIE_SECURE=true`。Basic 不提供登录旁路，修改操作必须使用 CSRF。健康探针不代表行情或研究有效。

离线设计原型仍可通过 `npm run build:prototype` 独立构建，示例数据不进入运行界面。

## 交付

源码仓库：https://github.com/Prodigalgal/gold_catcher 。GitOps 配置使用独立的 `Prodigalgal/ircs-prod-config`，目录 `gold-catcher/oracle`，域名 `gold.mnnu.eu.org`。

生产入口：[Gold Catcher](https://gold.mnnu.eu.org)。2026 年 10 月 8 日已激活 Java、React 和 PostgreSQL，完成 PoW 登录、Gate 行情与 CFD 签名读取验收。研究默认手动启动，CFD 完整模拟仍待完善；发布版本和两个镜像摘要以生产 GitOps 的 `release.json` 为准。

CI 验证 Java、React、清单及发布变更；Release 构建 ARM64 镜像，按 digest 提升 GitOps，Argo CD 同步后以 PoW Session 核验 API、Web 源码版本和 Gate 行情。验收模式明确为 INTERNAL_SIMULATION_ALPHA，不将 CFD 完整模拟、全 tick 回放或盈利能力列为已完成能力。

源码中的部署模板保持零副本。生产激活记录与实时验证分开记录，不能把 CI 通过当作已经部署。
