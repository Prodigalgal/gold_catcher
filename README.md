# Gold Catcher

Java / React 黄金研究与内部交易模拟系统。分析 Bybit XAUUSDT 与 XAUUSD.s，由 Java 计算指标和账务，经 any2api 调用多个 LLM 研究、复核和提出内部订单。

当前版本 **0.2.0，内部模拟 Alpha**。六个页面读取 Java 服务的实际数据：XAUUSDT 公共盘口、六周期指标、官方订阅、分阶段多 AI 研究、内部订单和持久化账本。独立离线设计原型保留在 `web/prototype.html`。

XAUUSD.s 等待 MT5 实际报价及成本规则；资金费结算、L2 排队和完整行情回放仍待实现。L1 撮合、配置费率和简化逐仓强平均明确标记为模拟假设。系统只使用公共行情、官方信源和 any2api，不使用交易所下单接口。

## 设计

- [系统与模拟规则](docs/system-design.md)
- [信源目录](docs/source-catalog.md)
- [六页面与信息影响看板](docs/ui-design.md)
- [K8S 与 CI/CD](docs/deployment-design.md)
- [当前实现、上下文证据与模拟边界](docs/runtime-alpha.md)

## 本地查看

前端需要 Node.js 22.12+，后端需要 JDK 25。

```powershell
cd backend
.\gradlew.bat test bootJar
cd ..
./scripts/start-backend.ps1 -KeyFile 'D:\WorkSpace\Project\服务器管理\private\any2api\distribution\api-keys.env' -NoBuild
```

另一个终端启动前端：

```powershell
cd web
npm ci
npm run dev
```

打开 `http://127.0.0.1:5173`。未配置本地登录时，脚本生成并保留 `data/local-access.json`（Git 忽略），在登录页输入其中的用户名和密码。后端绑定回环地址；账户和记录保存在 `data/gold-catcher.mv.db`，重启保留。凭据不复制到仓库，不进入 React 构建。

研究默认手动启动，费率默认未知；模型应选择 WAIT，错误提出 CREATE 时 Java 记录拒绝。配置说明见 `.env.example`，默认路线为 `config/ai-routes.balanced.example.json`（MiniMax、MiMo、GLM 与 Arena），预算属于保守运行配置。可用 `-RoutesFile` 选择其他路线文件。真实复杂讨论可能需要数分钟，已有条件订单由 Java 行情循环持续处理。

直接启动后端时，必须配置 `GOLD_AUTH_USERNAME` 与 `GOLD_AUTH_PASSWORD` 才有可用账号。HTTP Basic 与 CSRF 保持启用，浏览器凭据不写入 localStorage/sessionStorage。健康探针只表示进程健康，不表示行情或研究有效。

保留离线原型：`npm run build:prototype`，再运行 `python scripts/create_preview.py <输出路径>`。原型不请求后端，示例与运行界面分开构建。

## 发布

源码私仓：<https://github.com/Prodigalgal/gold_catcher>。生产配置沿用 `Prodigalgal/ircs-prod-config` 的独立 GitOps 边界，目标目录 `gold-catcher/oracle`，目标域名 `gold.mnnu.eu.org`。

CI 检查 Java、React 和清单；发布工作流构建 ARM64 镜像，按 digest 回写 GitOps，由 Argo CD 同步，再验证同步版本及受保护的 HTTP 接口。当前 `RELEASE_ENABLED=false`，生产 Application、DNS 和业务运行资源均未启用。模板的初始副本数为零，激活步骤见部署设计。
