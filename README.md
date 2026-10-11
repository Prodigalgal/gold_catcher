# Gold Catcher

Java / React 黄金研究与内部交易模拟系统。交易行情统一使用 Gate：XAU_USDT 黄金永续、XAUUSD 黄金 CFD；指标与账务由 Java 计算，多个模型经 any2api 独立研究、交叉质疑、修订和裁决。

当前版本 **0.4.7，内部模拟 Alpha**。独立登录页面采用 Session、CSRF 和单次 SHA-256 PoW，登录凭据只在后端配置。六个主页面展示行情、信息、讨论、两套独立内部账本、复盘和运行状态。

Gate XAU_USDT 已接入 WebSocket 买卖一、成交和带序号的100档增量盘口，REST 基准用于序号衔接；另有公开规格、风险档位、六周期 K 线与历史资金费。行情事件持久化，连接状态、价格新鲜度、丢帧与归档范围分别展示。CFD 有公开 Bid/Ask、六周期 K 线、签名只读规格与佣金；独立 USD 账本按手数、合约单位和账户保证金计算报价情景，隔夜费类型与结算时刻未核实时标注成本不完整。系统没有外部下单调用。

信息采集配置37个通道：17个官方新闻/日历、3个公开财经媒体、9个驱动/预期、8个补充数据。包括 Fed/ECB/BoE/BoJ、BEA、BLS、EIA、CNBC、财政部、DOL、Census、CFTC、IAU/GLD 和 Gate 样本。官方正文支持 HTML/PDF/XLSX；XLSX 只读取源单元格及缓存结果，不执行公式。信息页分别展示采集、正文、发布/统计/收到时间和质量；正文失败不会被 RSS 成功掩盖。CF/VLESS 代理为可选备用，订阅只保存在服务器管理私密目录。

Gate USIDX/EURUSD/USDJPY 每5秒检查，美国财政部名义和实际收益率每小时检查日频曲线，Forex Factory 本周日历每30分钟检查。CNBC 2年/10年名义收益率、10年TIPS实际收益率每60秒检查带日期和时区的提供方报价；财经头条、经济、全球RSS每30秒检查公开摘要，同链接跨栏目去重。Java 计算收益率基点变化，提取 CPI、就业、PPI、JOLTS、PCE 和 GDP 的明确数值及修订，生成四周期结构观察。公开网页无延迟保证，休市/过期报价与采集成功分别显示；授权低延迟行情和商业新闻线仍有覆盖缺口。详见[公开利率与财经媒体接入](docs/public-intraday.md)。

## 设计与运行边界

- [系统与模拟规则](docs/system-design.md)
- [信源目录](docs/source-catalog.md)
- [页面与信息影响看板](docs/ui-design.md)
- [K8S 与 CI/CD](docs/deployment-design.md)
- [当前实现、上下文证据与模拟边界](docs/runtime-alpha.md)
- [运行稳定性验收](docs/stability.md)
- [提供商、端点与模型配置](docs/ai-configuration.md)

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

研究默认手动启动，运行页可保存短线/长线/新闻合并调度。默认路线为 `config/ai-routes.balanced.example.json`，短/长线分别使用 fast/deep，当前 fallback 为空；提供商、端点、模型与预算可在运行页保存，无需重新部署；本轮四阶段内不更换投票模型。支持取消、总期限和迟到归档。PoW 在 Web Worker 中计算，浏览器不保存密码到 localStorage/sessionStorage。

**MIMO 保留前置信息压缩**，通过 any2api 将官方正文提炼成中文精华摘要。Flash 压缩请求不计票；MIMO Pro 与 DeepSeek、Grok 默认参与长短线辩论，并由同一组三个模型以新上下文同时裁决。至少三份有效裁决齐全，按严格多数选一个原候选方案，无法形成多数则 WAIT；Java 不混合价格、仓位和失效条件。详见[三方裁决规则](docs/adjudication.md)。

MIMO 压缩无需返回引用或片段编号，Java 在后台关联原文与版本。长文章分块合并去重，每次调用有硬预算；失败、尚未处理或晚于研究截止的结果使用原文摘录。价格、指标、经济数据、账户与订单由 Java 提供。运行页与聊天室可查看摘要、原始输出、预算及各方裁决票。[压缩配置](config/ai-compression.mimo.example.json) 固定厂商为 MIMO。

所有 emulated_function_bridge 通道按 JSON 文本提交，只有明确的原生严格函数能力才使用函数提交。参数名受支持不等于支持 JSON 模式，`response_format` 只有结构化输出能力明确时才发送。预算包含完整请求并保留10%余量；逻辑证据可选用无损 YAML/JSON 编码，金融数值不裁剪。未知上游容量、超限和未完整输出均保留错误。行情和采集独立运行，观点不足或结论过期时不产生内部行动。

模拟页面支持撤销、减仓、退出、修改止盈/止损/持有期限与追踪止损。复盘展示各自资金曲线、按分钟样本计算的回撤、费用分解及研究运行统计。CFD 报价情景与未知费用样本不计入完整成本胜率；CSV/JSON 与订单事件支持核对。

直接启动 Java 时需要配置 `GOLD_AUTH_USERNAME` 和 `GOLD_AUTH_PASSWORD`；生产 HTTPS 配置 `GOLD_AUTH_COOKIE_SECURE=true`。Basic 不提供登录旁路，修改操作必须使用 CSRF。健康探针不代表行情或研究有效。

离线设计原型仍可通过 `npm run build:prototype` 独立构建，示例数据不进入运行界面。

## 交付

源码仓库：https://github.com/Prodigalgal/gold_catcher 。GitOps 配置使用独立的 `Prodigalgal/ircs-prod-config`，目录 `gold-catcher/oracle`，域名 `gold.mnnu.eu.org`。

生产入口：[Gold Catcher](https://gold.mnnu.eu.org)。Java、React 和 PostgreSQL 由 Argo CD 管理；运行版本、源码和两个镜像摘要以生产 GitOps 的 `release.json` 及认证接口为准。实际 LLM 整轮完成、部署成功和离线预算测试分别验收。研究仍默认手动，CFD 执行与掉期假设保持可见。

CI 验证 Java、React、清单及发布变更；Release 构建 ARM64 镜像，按 digest 提升 GitOps，Argo CD 同步后以 PoW Session 核验 API、Web 源码版本和 Gate 行情。验收模式明确为 INTERNAL_SIMULATION_ALPHA，不将 CFD 完整模拟、全 tick 回放或盈利能力列为已完成能力。

源码中的部署模板保持零副本。生产激活记录与实时验证分开记录，不能把 CI 通过当作已经部署。

研究协议与超时恢复规则见 [docs/research-resilience.md](docs/research-resilience.md)。
