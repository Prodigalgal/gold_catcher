# Gold Catcher 黄金分析信源目录

更新日期：2026 年 10 月 12 日。对应产品为 Gate XAU_USDT 黄金永续和 XAUUSD 黄金 CFD，主要分析数分钟至两天的机会，并保留更长周期的背景。

信源包括交易行情、经济数据与预期、财经快讯、国际突发新闻、官方公告、事件日历和黄金供需。第一版先覆盖正确的产品报价、美国重要发布、快速新闻及美元与利率信息，再扩充背景材料。来源用途、采集方式、时效和限制一起记录，避免把日频统计当成分钟行情，或把转载数量当成独立证据数量。

下列是首批 45 项来源记录。同一机构的不同数据通道可以分别登记，不代表 45 家独立机构。公开文档或页面已找到与运行接入已验证是两种状态；此阶段完成来源梳理，尚未取得所有订阅、验证凭据或测量链路延迟。

适配目录共38个通道：20个资讯/日历、10个驱动/预期、8个补充数据；此外还有两个产品行情连接。当前精选默认启用34个，移除EIA_NEW、EIA_PRESS、EIA_ENERGY、BOJ_NEWS四个既有资料缺少核验时间的通道，不删除历史证据。运行配置可直接恢复或停用资讯通道。传输可用、正文可用、数值可用和时效覆盖分别展示，目录数量不代表实时覆盖已全部完成。授权低延迟利率、商业新闻线和发布前预期全量历史仍有缺口。

CNBC三个栏目在当前归档中有各自独有消息，近128次检查均成功，保留采集；同链接和同出版方相同内容指纹合并为一条观察，不重复压缩/计作独立证据。财政部、CFTC、拍卖、GLD与IAU原始统计继续作为结构化背景，日频不冒充盘中。

| 新增运行通道 | 接口与调度 | 已取得的用途与限制 |
| --- | --- | --- |
| GATE_USIDX / EURUSD / USDJPY | [Gate 官方 CFD API](https://www.gate.com/docs/developers/apiv4/en/cfd/) 的公开 /tradfi tickers，每5秒；1分钟 klines 每60秒 | 美元关联报价，约5/15/60分钟变化；timestamp 是响应时刻。USIDX 基准构成未独立核实，不标为 ICE USDX |
| CNBC_US2Y / US10Y / US10YTIPS | [CNBC 2年](https://www.cnbc.com/quotes/US2Y)、[10年](https://www.cnbc.com/quotes/US10Y)、[10年TIPS](https://www.cnbc.com/quotes/US10YTIPS) 公开网页，每60秒 | 带日期与时区的提供方报价，Java按收益率差计算bp；保留首次收到时间、原报价和哈希。无SLA，开市连续性待验证；过期报价不进入短线因子 |
| CNBC_TOP / ECONOMY / WORLD | 出版方公开RSS，每30秒；[接入规则](public-intraday.md) | 标题/摘要、出版方报告时间；跨栏目同链接合并，不抓付费正文，不等同于授权低延迟新闻线 |
| TREASURY_NOMINAL | [财政部 XML](https://home.treasury.gov/treasury-daily-interest-rate-xml-feed)，daily_treasury_yield_curve，每小时 | 2年及10年名义收益率，日频统计与上一可用日变化，单位 bp |
| TREASURY_REAL | 同一官方 XML，daily_treasury_real_yield_curve，每小时 | 10年实际收益率日频背景；允许负值，不代表盘中报价 |
| FF_EXPECTATIONS | [Forex Factory 本周 JSON 导出](https://nfs.faireconomy.media/ff_calendar_thisweek.json)，每30分钟 | 美元数据发布与讲话安排、提供方预期；不包含实际值。未映射事件保留原文，不参与自动数值匹配 |
| TE_EXPECTATIONS | [Trading Economics 公开日历](https://tradingeconomics.com/calendar)，平时30分钟；官方美元发布窗口60秒 | 单独保存网页Consensus调查预期与Forecast自有预测、前值、提供方实际值和参考期。必须取得显式时区，未知时间/格式不猜测。数值surprise仍以官方实际值为准，优先使用发布前已收到的TE调查预期；FF为备用，不平均两提供方。网页没有授权API推送的时效保证 |
| DOL_CLAIMS | [DOL 官方 PDF](https://www.dol.gov/ui/data.pdf)，通常每15分钟；纽约工作日08:00–09:30每15秒 | 季调初请/续请人数、前值和修订，公布时刻按原文及DST换算，原PDF版本保存 |
| CENSUS_ECON | [经济指标 RSS](https://www.census.gov/economic-indicators/indicator.xml)，每60秒 | 零售、耐用品、住房、贸易等18类当前摘要；变化单位与定义保留，未支持模板不猜值。完整数列API目前需要注册Key |
| CFTC_GOLD | [CFTC 官方公开数据](https://publicreporting.cftc.gov/stories/s/COT-Help/p2fg-u73y/)，通常15分钟；周五纽约15:00–16:30每30秒 | 黄金 Futures Only、代码088691、管理基金多空/净值及生产商持仓；周二统计背景，精确首发时刻未知 |
| TREASURY_AUCTIONS | [Fiscal Data 拍卖数据](https://fiscaldata.treasury.gov/datasets/treasury-securities-auctions-data/)，通常5分钟；常见截止窗口每15秒 | 公告、竞争性投标截止、发行额、中标收益率、投标倍数；截止不等于公布，未来拍卖不含提前取得的结果 |
| IAU_HOLDINGS | [iShares 发行方](https://www.ishares.com/us/products/239561/ishares-gold-trust-fund)，每小时 | 金衡盎司、公吨、份额、净资产，按各字段as-of分组；日频持仓不是盘中资金流，不伪造首发时刻 |
| GLD_HOLDINGS | [SPDR GLD 官方报表](https://www.spdrgoldshares.com/usa/gld/) 的 historical-archive XLSX，每小时 | 严格核验工作表、字段、统计日和正数；取最近两个可用日的黄金持仓、每份额含金量和净资产，保存原数值/哈希/首次知识时刻；不是盘中申赎流，精确首发时刻未知 |
| GATE_DEPTH / GATE_TRADES | [Gate 官方 Futures REST](https://www.gate.com/docs/developers/apiv4/en/futures/)，每2秒 | 20档快照与最近最多100条成交；张数用quanto_multiplier换算XAU，Java计算失衡、样本量和VWAP。另有100档增量WS序号核对与原始事件归档；接收范围不代表完整历史/主动成交方向，CFD不共享此数据 |
| BOE_NEWS / BOE_SPEECH | [英国央行 RSS](https://www.bankofengland.co.uk/rss)，每30秒 | 新闻与讲话原文；单独保留来源发布时间、正文和收到时刻 |
| BOJ_NEWS | [日本央行官方 RSS](https://www.boj.or.jp/en/rss/whatsnew.xml)，每30秒 | 发布目录与HTML/PDF/XLSX源单元格；Feed时刻未独立核实为首发，完整政策日历仍有缺口 |
| EIA_PRESS / EIA_ENERGY / EIA_NEW | [EIA 官方 RSS](https://www.eia.gov/tools/rssfeeds/)，每30秒 | 新闻稿、能源摘要与产品更新；EST按来源字面固定偏移解析，不补缺失年份；NEW只收摘要/报告链接，不把入口页当新闻全文。结构化库存/油价未完成 |

页面“信息集合 → 补充数据”提供8类实际覆盖清单。仍未完成：具有延迟保证的盘中美国利率、利率期货/FedWatch/期权、授权商业快讯、已核实一致预期、连续微观行情、全球ETF/央行储备/库存/亚洲市场、私营调查与地区央行、能源/外交/平台公告/相关资产的剩余通道。具体机构按下表逐条登记，未实现的目标不计入成功通道数。

Investing经济日历当前直连403，未纳入活跃采集队列。Trading Economics公开网页调查值不等同于已取得其授权API；官方API的Calendar和Streaming可作为未来稳定通道。Bloomberg授权经济数据/调查值当前未配置，不能把网页新闻猜测当作市场一致预期。预期是预测输入，需要结合官方实际值、修订、利率/美元与价格反应；不直接把正向surprise固定解释为黄金上涨。

## 稳定性、准确性与及时性验收

信息页单独展示当前启用通道的采集检查状态、连续失败、当前进程最近128次完成检查的成功率和耗时P95。耗时包括请求、解析与保存，失败检查同样计入；窗口开始时间和样本数可见，重启重新累计，不宣称已经证明长期可用率。最近成功超过正常轮询的三倍（至少30秒）时动态标为检查过期；失败的重试间隔不冒充正常频率。正文有独立检查统计，列表成功不代替正文成功。

官方BLS/BEA确切日历时刻前10分钟至后15分钟，对应新闻通道每5秒检查、对应日历每60秒。进入窗口后立即按最近成功时间判断是否到期，既有失败退避不被加速规则覆盖。FOMC只有会议日期时，纽约最终会议日FED_PRESS每15秒，不补造官方公布小时。FF提供方预期在官方美元发布前30分钟至后10分钟每60秒，其他时段仍30分钟。调度缓存最多5秒、定时器最多5秒及请求/处理耗时另计；这些频率不能作为端到端首发延迟保证。

采集失败指数退避，429/503按[HTTP Retry-After规则](https://www.rfc-editor.org/rfc/rfc9110.html#name-retry-after)至少等待服务端指定时间；重启保留未到期的重试截止。补充官方资料可按已配置源名单走直连优先、CF代理备用，Gate私有/价格接口不借用该备用路由。

发布时间、首次收到和完成校验分开处理。正文知识时刻在下载及解析完成后记录，晚到正文不能进入更早研究截点；正文未改变的重复检查不移动首次知识时刻。只有核验的正式发布时间才能计算公布至收到的延迟；启动/历史版本补录另标，日程、未知发布时间和API响应时刻不替代消息首发。数据日期、原始十进制值、单位与修订保留；错误格式和未知模板拒绝入数值视图。原始文件及历史证据保存在内部系统，不把发行方完整报表提交到仓库或对外再分发。

上线验收包含PoW登录后的完整数据通路、当前配置启用通道的采集成功及当前进程至少一次检查、TE实际日历字段、GLD真实报表解析、数量乘数和微观样本、手机与桌面页面，以及完整多轮请求预算检查。真实新闻首发延迟要在新发布后依据测量记录评估，当前成功轮询不等于已完成这一测量。

## 优先级和核查状态

| 标记 | 含义 |
| --- | --- |
| P0 | 首版核心能力；标注“选一”的商业来源是备选通道，不要求全部订阅 |
| P1 | 补充行情解释、事件覆盖和核验能力 |
| P2 | 慢频背景、研究发现或后续扩展 |
| 接口文档 | 已找到官方机器接口说明；凭据、品种、额度和实际质量另行验证 |
| 页面或报表 | 已找到原始发布入口；稳定采集方式、许可与延迟尚需验证 |
| 商业授权 | 需要按实际方案取得数据使用权和通道权限；公开网页不等于实时机器服务 |

表中的秒、分钟和小时为本系统拟采用的采集调度，受来源额度与实测结果约束，不是供应商的延迟保证。WebSocket 或推送按事件接收；RSS 本身通常仍需客户端轮询。所有来源分别保存最后发布、最后收到、最后处理、当前覆盖缺口与健康状态。

## 产品行情和关联市场

| ID 与优先级 | 来源及采集内容 | 接入与权限 | 采集调度 | 用途与限制 |
| --- | --- | --- | --- | --- |
| M01 P0 | Gate XAU_USDT：[官方 Futures WebSocket](https://www.gate.com/docs/developers/futures/ws/en/) 的 futures.book_ticker 与 futures.tickers | 公共 WebSocket，运行已接入 | 连续接收，重连首报价不参与开仓 | 买卖一、可见合约张数、标记价格；Java 按 quanto_multiplier 转成基础 XAU 数量，不能将张数直接当盎司 |
| M02 P0 | Gate 永续规格、K 线、资金费和风险档：[官方 REST](https://www.gate.com/docs/developers/apiv4/en/futures/) | 公共 REST，运行已接入 | 规格、六周期 K 线及资金费每60秒；时间校准每180秒 | 公布的 Taker 费率用于保守模拟，个人费率仍未验证；资金费使用历史结算记录与边界附近标记价格 |
| M03 P0 | Gate XAUUSD CFD：[官方 CFD API](https://www.gate.com/docs/developers/apiv4/en/cfd/) | tickers、klines、symbols 公开；detail 与 commissions 需要只读 Key | 买卖报价每秒；K 线每60秒，请求间隔至少300ms，失败退避 | 不需要 MT5；报价没有 tick 时间或可见数量，独立USD手数报价情景账本；合约规格和佣金由只读签名接口核实，swap类型/结算时刻与真实执行仍未知 |
| M04 P0 通道候选 | [CME 实时期货与期权数据 API](https://www.cmegroup.com/market-data/real-time-futures-and-options-data-api.html)：黄金及相关利率、股指、能源合约 | 商业授权的 WebSocket JSON；接口文档 | 连续接收 | 黄金参考、跨市场反应和利率期货；文档服务含成交、最优报价和日统计，不等同于全深度。合约到期和换月分别处理 |
| M05 P0 通道候选 | [ICE USDX](https://www.ice.com/forex/usdx)：美元指数及美元指数期货 | ICE 或授权供应商行情，商业通道待确定 | 优先实时流；延迟数据明确标记 | 指数与期货分别记录；不能把单一 EURUSD、广义美元日频指数或其他平台合成值标成 ICE USDX |
| M06 P1 | [TraderMade 外汇流](https://www.tradermade.com/docs/streaming-data-api)：EURUSD、USDJPY 等关联汇率 | WebSocket JSON、API key、按方案授权；接口文档 | 连续接收 | 外汇联动和必要换算；所选方案品种与 Bid/Ask 需验证，不能根据通用文档认定已覆盖所有贵金属 |
| M07 P0 | Gate [公告](https://www.gate.com/announcements)与平台状态资料 | 官方网页与 API 文档，后续接入 | 公告目标30–60秒，按许可与实测限制调整 | 维护、交易时段、产品规则、资金费与费用变更；公开公告不能代替实际连接健康 |

系统只接入 Gate。XAU_USDT 永续与 XAUUSD CFD 的数据、成本、计量单位和账户分别记录。二者不能互换成交依据。

## 经济数据和央行信息

| ID 与优先级 | 来源及采集内容 | 接入与权限 | 发布与采集调度 | 用途与限制 |
| --- | --- | --- | --- | --- |
| E01 P0 | 美联储：FOMC 声明、纪要、预测、讲话与证词；[RSS](https://www.federalreserve.gov/feeds/feeds.htm)、[会议日历](https://www.federalreserve.gov/monetarypolicy/fomccalendars.htm) | 官方 RSS、HTML/PDF、日历；公开入口 | 平时 30–60 秒；发布窗口按许可约 2–10 秒检查 | 政策原文和时间安排；Board RSS 不代表所有地区联储或现场讲话均已覆盖 |
| E02 P0 | BLS：CPI、PPI、非农、失业率、工资、JOLTS；[发布稿](https://www.bls.gov/bls/news-release/home.htm)、[API](https://www.bls.gov/developers/)、[日历](https://www.bls.gov/schedule/) | 发布稿及公共 API；v2 需要注册 | 按发布事件采集，历史 API 用于补齐；发布窗口加速 | 官方实际值和修订；API 可查询不保证与新闻稿同一瞬间更新，市场一致预期不由 BLS 提供 |
| E03 P0 | BEA：PCE、核心 PCE、个人收入支出、GDP；[日历](https://www.bea.gov/news/schedule)、[API 文档](https://apps.bea.gov/api/_pdf/bea_web_service_api_user_guide.pdf) | 官方发布稿、需注册 UserID 的 API | 按日历采集首发，随后补齐与核验 | 增长与通胀数据；初值、二次估计及修订分开，API 和发布稿延迟分别测量 |
| E04 P0 | DOL：[首次及持续申领失业救济报告](https://www.dol.gov/ui/data.pdf)、[ETA 发布入口](https://www.dol.gov/newsroom/releases/ETA) | 官方新闻与 PDF | 周度发布窗口检查；保存每期副本 | 就业变化；固定 data.pdf 会更新，不能只保留同一 URL 而丢失历史版本 |
| E05 P1 | Census：零售销售、耐用品、住房、贸易；[指标 API](https://www.census.gov/data/developers/data-sets/economic-indicators.html)、[日历](https://www.census.gov/economic-indicators/calendar-listview.html) | 官方发布页与分数据集 API | 按月度等发布日历；日历每日复核 | 分数据集验证字段与 key 条件，发布日期和统计所属月份分别保存 |
| E06 P0 | ISM：制造业与服务业 PMI、价格、就业、新订单；[报告入口](https://www.ismworld.org/supply-management-news-and-reports/reports/ism-pmi-reports/)、[日历](https://www.ismworld.org/supply-management-news-and-reports/reports/rob-report-calendar/) | 原始发布机构页面；底层数据与机器使用按其方案 | 月度发布窗口采集 | ISM 和 S&P PMI 是不同调查；标题指数与分项分别保存，不混为同一数列 |
| E07 P1 | S&P Global：美国及主要经济体 PMI 初值、终值；[发布页](https://www.pmi.spglobal.com/Public/Release/PressReleases?language=en)、[日历](https://pmi.spglobal.com/Public/Release/ReleaseDates?language=en) | 原始发布机构页面；详细数据需授权 | 月度及初值发布窗口采集 | 保留调查范围、初终值和发布时间；公开新闻稿不等于完整历史数据库许可 |
| E08 P1 | [ADP 就业报告](https://adpemploymentreport.com/)及发布日历 | 原始发布机构页面；机器通道待验证 | 按其月度报告和 weekly NER pulse 的各自日历 | 私营就业调查，不能等同于 BLS 非农，也不能混用月度和周度口径 |
| E09 P0 选一 | [Trading Economics 经济日历](https://docs.tradingeconomics.com/economic_calendar/schema/)、[发布流](https://docs.tradingeconomics.com/economic_calendar/streaming/) | REST 与 WebSocket，key 和方案授权；接口文档 | 发布流连续接收；未来日历约 15–60 分钟刷新 | 实际值、前值、市场一致预期、修订、官方出处；`Forecast` 与 `TEForecast` 分开，供应商预测不冒充一致预期 |
| E10 P1 | Treasury：[每日名义与实际收益率 XML](https://home.treasury.gov/treasury-daily-interest-rate-xml-feed) | 官方 XML/报表，接口文档 | 日频，预计更新后拉取；通常约 1 小时检查 | 利率背景与曲线；不是分钟级可交易收益率。CNBC公开盘中报价另行接入；具有延迟保证的分钟级TIPS/现金收益率仍需授权来源 |
| E11 P1 | [FRED API](https://fred.stlouisfed.org/docs/api/fred/)与[ALFRED 历史版本](https://fred.stlouisfed.org/docs/api/fred/realtime_period.html)；如 [DGS10](https://fred.stlouisfed.org/series/DGS10)、[DFII10](https://fred.stlouisfed.org/series/DFII10)、[T10YIE](https://fred.stlouisfed.org/series/T10YIE) | API key、按系列来源和使用条件；接口文档 | 按系列日、周、月频率更新 | 历史背景、版本与回放；这些示例系列为日频，不能当实时利率，原始发布机构仍保留 |
| E12 P1 | [ECB RSS](https://www.ecb.europa.eu/home/html/rss.en.html)：政策、讲话、记者会与发布 | 官方 RSS 与原文 | 通常 30–60 秒；事件窗口加速 | 欧元与全球利率背景；RSS 由客户端轮询，不是保证即时到达的推送 |
| E13 P1 | [英国央行 RSS](https://www.bankofengland.co.uk/rss)：政策、新闻、讲话 | 官方 RSS 与原文 | 通常 30–60 秒；事件窗口加速 | 英镑及利率背景；各频道独立记录覆盖范围 |
| E14 P1 | [日本央行货币政策会议](https://www.boj.or.jp/en/mopo/mpmsche_minu/index.htm)、决定与纪要 | 官方日历、HTML/PDF | 日历每日更新，发布窗口加速 | 日元与政策背景；决定、意见摘要、纪要不是同一发布时间 |
| E15 P1 | TreasuryDirect：[拍卖日程](https://www.treasurydirect.gov/auctions/when-auctions-happen/)、[公告与结果](https://www.treasurydirect.gov/auctions/announcements-data-results/announcement-results-press-releases/) | 官方页面、XML/PDF 发布入口 | 日程约 15–60 分钟复核，结果按拍卖事件获取 | 国债供给、投标倍数与中标收益率；公告、投标截止、结果和发行日期分开，不假定历史 API 秒级更新 |
| E16 P1 | EIA：[开放数据](https://www.eia.gov/opendata/)、[石油周报日历](https://www.eia.gov/petroleum/supply/weekly/schedule.php) | 官方 API 与报告；key/接口规则按文档 | 周度等发布窗口采集，节假日改期以日历为准 | 能源与通胀背景；与黄金的关系需结合当时市场反应，不机械决定方向 |
| E17 P1 | [OPEC 官方新闻](https://www.opec.org/press-releases.html)：会议、产量决定、政策声明 | 官方原文及公布的会议安排 | 平时约 1–5 分钟，重要会议窗口加速 | 能源供给与政策事件；公告内容和实际执行、各方表态分别记录 |

## 新闻快讯和官方公告

| ID 与优先级 | 来源及采集内容 | 接入与权限 | 采集调度 | 用途与限制 |
| --- | --- | --- | --- | --- |
| N01 P0 选一 | [金十数据官方 MCP](https://mcp.jin10.com/app/doc.html)：快讯、资讯、参考行情 | 需要 MCP Token；`list_flash`、`search_flash`、`list_news`、`search_news`、`get_news` 等已有文档 | 若许可，快讯约 5–15 秒增量拉取，资讯约 1–5 分钟；额度与补缺参数待测 | 中文突发报道和解释；文档中的报价不是 Gate 成交源，当前公开工具列表未列经济日历，不能据此假定已覆盖 |
| N02 P1 | [华尔街见闻快讯](https://wallstreetcn.com/live)：全球财经快讯与事件提醒 | 公开页面已确认；官方稳定机器接口、授权与方案待确定 | 获准后优先推送或约 15–60 秒增量获取 | 中文交叉核验；不把网页内部接口当成已承诺的开放 API |
| N03 P0 选一 | LSEG/Reuters：[机器新闻产品](https://www.lseg.com/en/data-catalogue/news)、[News Service](https://developers.lseg.com/en/product/news/news_service_rdp) | 商业授权；MRN/实时平台及 News Service 的流与 API 为不同方案 | 按取得的实时通道接收；搜索与历史补查另调度 | 国际突发、宏观和原始报道；要确认标题、正文、修订、主题覆盖、存储及外部 LLM 使用条件 |
| N04 P0 选一 | [Bloomberg Event-Driven Feeds](https://professional.bloomberg.com/products/data/enterprise-catalog/event-driven-feeds/)：文字新闻、经济事件与分析 | 商业机器服务；方案和传输方式待确定 | 实时流按授权接收 | 快速国际与金融新闻；新闻服务与 B-PIPE 行情、普通网页或终端订阅分别确认 |
| N05 P1 | [Benzinga 新闻 API](https://docs.benzinga.com/home)与[新闻流说明](https://www.benzinga.com/apis/blog/client-spotlight-bloomberg-terminals-integration-of-benzingas-newswire/) | REST/WebSocket、API key 与商业方案；接口文档 | 流式接收，按更新时间增量补查 | 财经和公司新闻候选；黄金、央行与地缘覆盖需样本验证，不预先当作全球快讯完整替代 |
| N06 P2 | [GDELT 数据](https://gdeltproject.org/data.html)、[DOC 检索接口](https://blog.gdeltproject.org/gdelt-doc-2-0-api-debuts/amp/) | 公开数据与检索入口；资源配额分别核查 | GDELT 2.0 Event/GKG 按文档为 15 分钟批次；检索另测时效 | 广泛发现与历史事件；不承担秒级首报，文章检索和批次数据的时效不能混为一个保证 |
| N07 P1 | 白宫：[行政行动](https://www.whitehouse.gov/presidential-actions/)、[简报与声明](https://www.whitehouse.gov/briefings-statements/) | 官方 HTML 与原始文件 | 通常 30–60 秒；重大政策窗口加速 | 关税、制裁相关政策和政府声明；报道发布时间、签署时间、生效时间分别保存 |
| N08 P1 | 美国 Treasury [新闻](https://home.treasury.gov/news/press-releases)和 [OFAC 行动](https://ofac.treasury.gov/recent-actions) | 官方页面与文件 | 通常 30–60 秒；重要事件加速 | 财政、金融稳定与制裁；正式措施、名单变动和政策解释分开 |
| N09 P1 | [中国人民银行](https://www.pbc.gov.cn/)：货币政策、公开市场操作、政策发布 | 官方页面和公告；机器接口待验证 | 通常 1–5 分钟；已知发布窗口加速 | 中国政策与流动性背景；公告的统计日期和发布时间分别保存 |
| N10 P1 | [中国外交部记者会](https://www.mfa.gov.cn/web/wjdt_674879/fyrbt_674889/)及外交声明 | 官方文字稿与链接 | 通常 1–5 分钟；重大事件加速 | 地缘和贸易事件核验；记者会文字稿可能晚于现场讲话，不称作现场即时流 |
| N11 P1 | [欧盟理事会新闻与声明](https://www.consilium.europa.eu/en/press/press-releases/) | 官方页面、政策与新闻入口 | 通常 1–5 分钟；重大政策窗口加速 | 制裁、外交和政策；保留机构与发布主体，官方立场不自动等于被陈述事件已独立证实 |
| N12 P2 | [SEC EDGAR API](https://www.sec.gov/search-filings/edgar-application-programming-interfaces)：金融机构、黄金相关上市公司重要披露 | 公共 JSON、按 SEC 请求规范；接口文档 | 对关注名单约 1–5 分钟增量获取，夜间批量归档 | 金融风险与行业事件；披露流不提供完整的未来财报日历，不全量轮询所有公司 |
| N13 P1 候选 | [X 官方过滤流](https://docs.x.com/x-api/posts/filtered-stream/introduction)：机构与指定官方账号发言 | 官方 API、授权层级与配额；接口文档 | 获得权限后流式接收 | 早期事件发现；账号身份、原帖时间、引用链和后续更正要核验，社交帖不能自动升格为已核实事实 |

消息保存时区分原始事实、机构表态、媒体报道、分析评论和未经核实消息。新闻的“快”与证据可信度分别记录。金十、见闻、其他媒体同时转引 Reuters 或同一份公告时，归为同一原始出处与事件。

AP、AFP、Dow Jones，以及财联社等中文机构可继续列入备选池；在确认其具体数据产品、机器接口、黄金相关覆盖和权限后登记正式来源，不先填入未验证的接口与秒级承诺。

## 黄金供需和持仓背景

| ID 与优先级 | 来源及采集内容 | 接入与权限 | 发布与采集调度 | 用途与限制 |
| --- | --- | --- | --- | --- |
| G01 P1 | World Gold Council：[ETF 持仓与资金流](https://www.gold.org/goldhub/data/global-gold-backed-etf-holdings-and-flows)、[央行黄金储备](https://www.gold.org/goldhub/data/gold-reserves-by-country)、Goldhub 供需研究 | 官方聚合与研究、下载文件；部分内容与权利按页面确认 | ETF 周/月资料分别采集；央行与季度供需按实际更新 | 中长周期背景；央行月度文件的文档说明存在约两个月滞后，不能冒充今天净买入 |
| G02 P1 | [SPDR GLD](https://www.spdrgoldshares.com/usa/gld/)：黄金持有量、份额、净值与历史归档 | 发行方页面、历史文件；机器使用条件待验证 | 日度，约 1 小时检查更新并记录 as-of | 发行方持仓变化；交易日口径与结算日金条清单可能不同，不能当盘中实时申赎流 |
| G03 P1 | [iShares IAU](https://www.ishares.com/us/products/239561/ishares-gold-trust-fund)：黄金持有量、份额与基金数据 | 发行方页面及可用下载 | 日度，约 1 小时检查更新并记录字段 as-of | ETF 交叉背景；页面各字段日期可不同，交易日与会计口径不能混用 |
| G04 P1 | CFTC COT：[发布日历](https://www.cftc.gov/MarketReports/CommitmentsofTraders/ReleaseSchedule/index.htm)、[公开报告与 API 入口](https://publicreporting.cftc.gov/stories/s/COT-Help/p2fg-u73y/) | 官方报告、PRE 查询；数据集字段和端点接入时确认 | 周度发布窗口采集，按节假日表调整 | 黄金期货持仓结构；通常周五公布此前周二快照，不能当作今天机构仓位 |
| G05 P2 | [CME 库存与交割报告](https://www.cmegroup.com/solutions/clearing/operations-and-deliveries/nymex-delivery-notices.html) | 交易所报告与下载文件 | 按日度与月度报告，通常约 1 小时检查 | registered/eligible、交割与库存背景；类别调整不直接等于当日实物消费或新增买盘 |
| G06 P2 | [LBMA 黄金基准价](https://www.lbma.org.uk/prices-and-data/lbma-precious-metal-prices) | LBMA/IBA 基准数据，历史表与使用需要相应许可 | 伦敦每日两次定价，依授权发布后获取 | 基准参考；不是连续 XAUUSD 报价，也不是 Gate 可成交价 |
| G07 P1 | [上海黄金交易所](https://www.sge.com.cn/)：每日行情、上海金基准、公告与交易时段 | 官方页面/报表；实时行情按授权产品确认 | 日报告与基准按日程采集；实时通道另验证 | 亚洲市场与境内外价差；网站延时行情需标识，人民币/克与美元/金衡盎司先做单位、汇率及时间对齐 |
| G08 P1 | [SAFE 官方储备资产](https://www.safe.gov.cn/safe/gfcbzc/index.html)：中国黄金储备数量与金额 | 官方统计及年度、月份表 | 月度发布窗口，平时日检查 | 央行需求背景；数量变动和美元估值变动分开，金额增加不自动代表增持 |

## 日历来源和事件范围

日历与新闻分别保存。日历说明计划何时发生，新闻与原始发布说明实际上发生了什么；取消、改期、推迟和未公布结果本身也是事件状态。

| 日历类别 | 优先来源 | 首版保存的事件 |
| --- | --- | --- |
| 美国经济数据 | [BLS 日历](https://www.bls.gov/schedule/)、[BEA 日历](https://www.bea.gov/news/schedule)、[Census 日历](https://www.census.gov/economic-indicators/calendar-listview.html)，DOL 发布安排；E09 补充统一日历 | CPI/PPI、就业、工资、JOLTS、PCE、GDP、初请、零售、耐用品、住房、贸易 |
| 调查与私人指标 | ISM、S&P Global、ADP 的原始日历，E09 交叉核验 | 制造业和服务业 PMI、初值/终值、ADP 月报与周度更新 |
| 央行决定 | [FOMC 日历](https://www.federalreserve.gov/monetarypolicy/fomccalendars.htm)、ECB/BoE/BoJ 官方会议安排 | 决议、预测、记者会、纪要、意见摘要；分事件 ID 与实际时点 |
| 讲话与听证 | [美联储活动日历](https://www.federalreserve.gov/newsevents/calendar.htm)、各地区联储及其他央行活动安排；E09 补充 | 讲话、听证、公开采访、记者会；开始时间与文字稿发布时间分开 |
| 美国国债拍卖 | [TreasuryDirect 日程](https://www.treasurydirect.gov/auctions/when-auctions-happen/)和公告 | Bills/Notes/Bonds/TIPS/FRNs，公告、截止、结果、发行及 Quarterly Refunding |
| 能源与国际政策 | EIA 周报计划、OPEC 正式会议安排、相关机构活动与政策公告 | 库存发布、正式产量会议、已宣布的政策生效日；不能把媒体猜测的会期当已确认 |
| 黄金统计发布 | WGC、CFTC、ETF 发行方、SAFE | COT 周报、ETF 汇总、黄金储备、季度需求报告；数据所属期与发布时间分开 |
| 产品交易日程 | Gate 产品规则与公告、CFD 合约交易时段、[CME 交易及假日日历](https://www.cmegroup.com/trading-hours.html) | 开闭市、节假日、提前收市、维护窗口；逐产品核对，不沿用另一黄金品种日程 |
| 合约与费用事件 | CME 合约参考资料、Gate 当前资金费时间、CFD swap/rollover 与合约规则 | 到期、交割、换月、资金费结算、隔夜费日程及适用规则变化 |
| 政治与外交安排 | 政府、议会、外交机构和国际组织正式安排，可靠新闻作补充 | 已确认选举、听证、峰会、谈判、政策生效；时间未知则保存日期或范围，不编造时点 |
| 企业披露 | 关注的金融机构与黄金相关公司的 IR 日历，SEC 发布核验 | 已确认财报、重要公告、金融风险事件；仅收相关名单，非全市场财报扫描 |

每个日历事件包含原始事件 ID、规范事件 ID、发布机构、类别、国家、统计期、原始时区、计划时间、时间精度、首次获知时间、最近修订时间和状态。美国东部时间按 `America/New_York` 转换，北京时间只作展示；伦敦、东京、央行所在地与交易服务器分别处理。日历页的夏令时缩写含糊时保留原文并核验，不能写死全年北京时间。

日历每天做全量核对，近期事件按约 15–60 分钟增量复核；进入可配置的事件窗口后提高官方发布与日历查询优先级，或依授权流接收更新。窗口长度和频率依据事件、配额及实测配置，不要求所有官网全天每两秒轮询。

发布日期前保存一致预期和前值版本。发布后保存实际值、修订后的前值和更正；Java 计算同单位的差值或标准化 surprise。没有可验证的事前预期时保留 UNKNOWN，不能用发布后预测回填。不同供应商的样本、均值/中位数或自有预测分别保存，不简单求平均后称为“市场一致预期”。

## 第一版接入组合

先接入公共基础：M01、M02、M07；CFD 接入 M03；官方核心 E01–E04、E06 和主要发布日历。E11 提供带版本的历史背景。Java 指标以这些源数据计算，不能依赖新闻网站展示的技术指标代替。

快速事件层选定一个主要结构化经济日历与预期通道，如 E09，再选一个主要快讯通道：N01 先核验中文覆盖、额度和延迟；需要更广或更快的国际原始报道时，在 N03/N04 等中比较实际样本与方案。N02 作为中文补充，不能假定多个中文转载源带来同等数量的独立证据。

关联行情层取得 M04/M05 或具有相应授权的供应商通道，验证黄金参考、美元和利率数据。无法获得分钟级现金或实际收益率时，展示实时期货代理与日频真实收益率的区别；Java 不把国债期货价格直接标成现金收益率。

黄金背景层先接 G01–G04、G07、G08，按低频刷新。其他官方新闻、能源事件和研究发现逐步加入。免费原始发布能先形成基本覆盖，快速新闻、预期和实时关联行情的不足单独显示，不把未接入项列为已覆盖。

## 来源记录和验收

Java 的每个适配器输出统一记录，但保留各来源原始字段。每条来源配置至少包括：source_id、原始机构与分发商、通道类型、权限引用、覆盖范围、产品/系列映射、数据单位、原始时区、普通与事件期频率、配额、修订与补缺规则，以及原文保存和外部 LLM 处理的许可条件。密钥在 Java 后端配置，不进入 React、文档或证据正文。

每条事件保存源 ID、原始出处、URL、发布时间、源更新时间、首次收到、提取完成、统计所属期、原文或允许的内容、修订版本和内容哈希。不能在补抓时把现在接收到的旧新闻改成过去已经获知。多媒体现场消息、英文原稿、中文翻译与官方文字稿分别记录时间，避免用译稿时间推断原稿首次发布时间。

来源健康分为未接入、已配置、可获取、已验证字段、已验证连续性与延迟等阶段。网页可打开、API 返回 200、能抓到一条样本和持续及时覆盖分别验收。至少检查：

1. 品种、单位、字段与时区正确；数据精度符合对应合约与系列。
2. 同一事件的转载去重、原始出处和修订链可还原。
3. 预期值在实际发布前确实已收到；实际值及前值修订可追溯。
4. 能测量发布到收到、提取、研究触发与内部订单生效的延迟；缺少可靠源时间时标未知。
5. 实测正常期与重大事件期的覆盖、p50/p95 延迟、缺口、重连和补抓；供应商声称“实时”不替代测量。
6. 限流和退避有可见状态，恢复不漏页、不重复计事件；单次调用返回上限不能截掉中间消息。
7. 新闻与日历不会因重复触发挤占报价处理；原始行情连续保存，LLM 使用冻结证据快照。
8. 未授权正文、未知费用与未验证来源不会被标记成已取得或已核实；不同通道权限分别配置。

下一阶段逐项确定适配器、所选商业方案和字段映射，并用连续样本实测时效。本目录的优先级是接入顺序，不是交易风险限制；风险承担与策略仍由 LLM 结合实际可用信息评估。
