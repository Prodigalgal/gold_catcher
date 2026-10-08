# Gold Catcher

Java / React 黄金研究与内部交易模拟系统。分析 Bybit XAUUSDT 与 XAUUSD.s，由 Java 计算指标和账务，经 any2api 调用多个 LLM 研究、复核和提出内部订单。

当前是**设计与工程骨架阶段**：页面展示明确标记的示例数据，Java 服务只提供受保护的版本与阶段信息。行情采集、指标、AI 调度、内部撮合及账本尚未实现。系统不连接外部真实或模拟交易接口。

## 设计

- [系统与模拟规则](docs/system-design.md)
- [信源目录](docs/source-catalog.md)
- [六页面与信息影响看板](docs/ui-design.md)
- [K8S 与 CI/CD](docs/deployment-design.md)

## 本地查看

前端需要 Node.js 22.12+，后端需要 JDK 25。

```powershell
cd web
npm ci
npm run dev
```

React 原型监听本地 `127.0.0.1:5173`。六个页面、产品选择、研究阶段和订单详情均可切换，操作只改变页面展示。

```powershell
cd backend
.\gradlew.bat test bootJar
```

后端 API 默认拒绝访问；设置 `GOLD_AUTH_USERNAME` 与 `GOLD_AUTH_PASSWORD` 后，使用 HTTP Basic 访问 `/api/system/status`。`/actuator/health/liveness` 与 `/actuator/health/readiness` 只供集群探针，不经公网路由暴露。进程健康不代表行情、AI 或交易功能就绪。

## 发布

源码私仓：<https://github.com/Prodigalgal/gold_catcher>。生产配置沿用 `Prodigalgal/ircs-prod-config` 的独立 GitOps 边界，目标目录 `gold-catcher/oracle`，目标域名 `gold.mnnu.eu.org`。

CI 检查 Java、React 和清单；发布工作流构建 ARM64 镜像，按 digest 回写 GitOps，由 Argo CD 同步，再验证同步版本及受保护的 HTTP 接口。当前 `RELEASE_ENABLED=false`，生产 Application、DNS 和业务运行资源均未启用。模板的初始副本数为零，激活步骤见部署设计。
