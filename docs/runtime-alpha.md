# 0.3.0 当前实现与验证口径

当前交易行情只使用 Gate。设计文件中的完整功能目标不等于已经实现；实际运行证据、推理可靠性和部署证据分别核验。

## Gate 行情与 Java 计算

XAU_USDT 使用官方 futures.book_ticker 与 futures.tickers。REST contracts、risk_limit_tiers、candlesticks 与 funding_rate 每60秒读取。原生数量为合约张数；Java 将数量、盘口尺寸和成交量乘以 quanto_multiplier，统一到基础 XAU 数量。价格乘基础数量才是 USDT 名义金额与盈亏。规则步长使用整张合约；杠杆采用显式标注的整数选择假设。

XAUUSD CFD 使用官方 /tradfi/symbols、/{symbol}/tickers、/{symbol}/klines。报价每秒查询，K 线每60秒；同一 K 线通道请求间隔至少300ms，报价失败指数退避。接口 timestamp 是服务器响应时间，没有最后一次 tick 时间、标记价格或盘口数量，不能伪造深度。后台使用 Gate v4 HMAC-SHA512，只调用允许的 detail 和 commissions 两个 GET；响应选择合约及费用字段，密钥和签名不进入文档、日志或前端。

两产品的六周期为1、5、15、60、240分钟与日线，每周期最多200根。只计算截止前已收盘的 K 线；API 时间单位、原始周期起点及合约单位分别转换。没有成交量的 CFD 不填零。BigDecimal/DECIMAL128 计算 SMA20、EMA20/60、Wilder RSI14、ATR14、MACD(12,26,9)和收盘涨跌；React 接收十进制字符串，Number 仅用于图表像素。

Gate /spot/time 校准应用 UTC，使用单调时间保持 elapsed time。记录 RTT、误差估计和本机偏移；RTT超过800ms拒绝新样本，六分钟未校准禁止执行。不会修改宿主时钟。重连首盘口不执行开仓；重复序号不重复消费可见数量，队列溢出、迟到或断档保留状态。

## 官方信息

接入 Fed press_all/speeches、BEA 新闻、ECB press RSS，以及 BLS RSS/ICS 和 BEA 官方日历网页。新闻默认30秒、日历每小时，支持 ETag、去重与修订，发布/收到时间分别保存。BLS 的 HTTP403 如实记录。BEA ICS 在本次查询中返回旧日程，因此直接解析官方当前年度发布表，不把请求成功当作有效新日历。

日历使用 America/New_York 和 DST，输出 UTC，再由页面显示北京时间。实际值、预期值、修订、商业快讯及关联市场价格流仍按信源目录继续接入，不虚构缺失数据。

## any2api 上下文证据

读取了 any2api 的 `docs/reports/PROVIDER_LIMITS_AND_TURNS_BENCHMARK_2026-10-08.md`，核对 `scripts/benchmark_results_*.json` 和网关代码。非敏感摘录在 `config/context-evidence.json`，不复制账号标识或 Key。

scale 是填充文本构造参数，不是真实 token 数。scale=8000 已知正文 16320 字符、41920 UTF-8 字节，不含指令、JSON 或适配器封装。结果未保留 usage_source，因此 reportedPromptUsage 不能当作已验证 token 容量；413 也不能换算确定 token 边界。

快配置：MiniMax-M3、grok-3、longcat-pro，claude-sonnet-5 独立裁决。深配置：MiMo、GLM、DeepSeek 与 Arena，应选 LONG 并增加阶段等待。Qwen 未被报告成功验证，不自动加入。

历史路线验证采用 balanced（MiniMax、MiMo、GLM、Arena）。切换 Gate 前的2026-10-08 run `254851cb-423a-44a0-91f1-b8f2fe05c03d`：三家首轮有效；GLM 在质疑/修订各一次 HTTP 502，两家有效观点继续；Arena 裁决有效，终态 COMPLETED / WAIT，未生成内部订单。总耗时约307秒，各次有效返回约26–96秒，请求约12–20KB / 10.7–15.7千字符。失败不被成功覆盖。

此前 Grok/LongCat 在复杂研究输入下各出现90秒等待超时；报告短问答耗时不能当作研究周期承诺。balanced 是一次可完成流程的验证，不能证明所有账号、持续可靠性或盈利能力。真实验证仅覆盖 WAIT，模拟 CREATE/部分成交/退出/费用由 Java 参考测试验证。

每路线独立校验消息数、Unicode 字符、UTF-8 字节、网关 ceil(bytes/3) 估算、输出预留、实时目录输入/输出/上下文与字符声明。16000 字符、30000 字节、10000 输入估算属于保守预算，不承诺最大容量。厂商 scoped 模型详情使用裸 ID，统一详情使用 provider/model。

supported_parameters 中的 response_format 不等于支持 JSON 模式。当前优先用已声明支持的严格函数 `submit_research` 提交数据，没有外部副作用。只发送映射为 mapped 的输出别名，MiMo non_binding_only 不当作强制上限。无效格式、长度、引用或未完整返回保留错误/原文，不猜测修复。

## 多 AI 讨论与上下文

独立研究→交叉质疑→修订→新上下文裁决。首轮共享冻结证据；后续只读取上一阶段截至期限的同一批有效观点。角色绑定和读取顺序按 seed 轮换，不用响应先后决定投票顺序。研究阶段至少两种不同模型有效，裁决至少一份有效，迟到内容归档并排除。

每条消息保留摘要或厂商返回的可见分析、正式输出、引用、修订、原文、耗时、读取顺序与预算。没有可见分析时不编造。前端采用聊天室展示。

输入选四项新闻摘要和六项日历，原始版本与链接可追溯。指标视图由 Java 舍入至八位小数，原精度另存；模型取得前四个风险档位及明确范围，完整规则另存。可执行计划的数值与引用不截短；同伴 output/changes 摘录限制400/120字符，带归档提示。严格函数支持时移除重复 JSON 格式样例，保留实际 schema 和语义说明。

按每条通道的字符、UTF-8字节、网关估算、声明能力与输出预留执行预算校验，不通过扩大未知容量来处理超限。超限不发送、不下单。原始账户和订单数值不静默裁剪。自动研究需显式启用，并与手动研究共用一个执行位。

## 内部永续模拟与账务

支持 WAIT、CREATE(MARKET/LIMIT/STOP_MARKET/STOP_LIMIT)、CANCEL、CLOSE。MARKET entryPrice 是买入最高/卖出最低允许成交价，余单保留至过期；属于内部语义。没有固定0.8%、止损比例、盈亏比或最低盈利门槛；数量和风险由模型提出，Java 校验规则、余额和时间。

订单等待生效延迟，只使用其后的有效买卖一，数量在内部订单间共享，退出先于开仓。使用公开 Taker 费率在开平仓各扣一次，成交为 L1 假设，不代表真实排队或券商执行证明。保证金与余单预留分别冻结，释放保证金不重复增加本金。

跨资金费边界保存当时持有数量和3秒内标记价格，再用 Gate 历史费率结算。正费率多头支付、空头收取，负费率相反；账户余额与净值使用有符号成本，结算幂等。缺少边界 mark、未知时间或报价缺口保持 costIncomplete，不能填零。简化资金费模型从账户现金扣除，未完整复刻交易所保证金消耗顺序。

新鲜 mark 与 Gate 风险档位用于简化逐仓强平，不能当作交易所精确强平复刻。没有全 tick 回放、L2排队、多实例领导权、AMEND/REDUCE或多腿策略。单 Java 实例、单副本/Recreate；账本与事件同事务持久化，重启不重置本金，离线期间不补造成交。

CFD 已有报价、K线、实际规格和佣金只读查询。原始 swap 类型、时区与3日费字段保留，但执行语义、隔夜费类型/日历映射仍未验证，因此暂只输出形势判断，不用永续规则代替 CFD 下单与强平。

## 登录与验证

独立 /login 页面，admin 账号和密码从后端配置。SHA-256 PoW 默认18位，由 Web Worker 计算；挑战绑定匿名会话、单次使用、单调时间过期，密码失败提高后续难度，签发频率和状态数量受控。Basic关闭，登录轮换会话，Cookie HttpOnly/SameSite=Strict，HTTPS环境 Secure；POST必须使用CSRF，退出撤销会话。

测试覆盖参考指标、单位换算、已收盘时间、新闻XXE/修订、日历DST、封存阶段/迟到/门槛、成交与费用、资金费方向/缺失/恢复幂等、PoW会话绑定/过期/限流、CSRF和注销。发布验收单独检查 CI、镜像digest、Argo同步、版本、PoW登录以及两种Gate行情；不据此声称稳定盈利或CFD完整模拟。
