# 0.2.0 当前实现与验证口径

本文件描述当前 Alpha；system-design、source-catalog 和 ui-design 仍包含后续完整目标，不能当作运行证据。

## 行情与 Java 计算

Bybit V5 instruments-info / risk-limit / kline 与 WS orderbook.1 / ticker。六周期为 1、5、15、60、240 分钟和日线，每周期读取 200 根，仅计算截止前已收盘的 K 线，约每 60 秒更新；规则每小时更新。

ticker delta 保留未变化字段；重连首盘口不执行开仓。同一盘口序号不重复消费数量，心跳仍更新报价新鲜度。断线、队列溢出、时间未校准及过期报价均可见。

BigDecimal / DECIMAL128 计算 SMA20、EMA20/60、Wilder RSI14、Wilder ATR14、MACD(12,26,9)、成交量和收盘涨跌。EMA 用 SMA 种子；ATR 种子取有前收盘的 14 个真实波幅。样本不足不填零。React 只显示 Java 的十进制字符串，图表的 Number 只用于像素定位。

Bybit 公共时间接口校准应用 UTC，用单调时间维持 elapsed time。记录本机偏移、RTT 误差估计和校准时间；RTT 大于 800ms 拒绝新样本，六分钟未校准则禁止执行。校准仍存在网络不对称误差，不修改宿主时钟。

## 官方信息

Fed press_all / speeches RSS、BLS 最新指标 RSS 和官方 ICS：新闻默认轮询 30 秒，日历一小时，ETag 条件请求、去重、修订版本、公布/收到时间分别记录。失败退避并保留上次成功时间。RSS 禁止 DTD/外部实体，外链只接受 HTTPS。日历使用 TZID 与 DST，不虚构预期/实际值。BLS 返回 403 时明确记录失败。

BEA、财政部、其他央行、市场新闻、地缘事件、交易所公告、宏观价格序列、日历预期/实际/修订及更快的商业新闻流仍按信源目录接入。

## any2api 上下文证据

读取了 any2api 的 `docs/reports/PROVIDER_LIMITS_AND_TURNS_BENCHMARK_2026-10-08.md`，核对 `scripts/benchmark_results_*.json` 和网关代码。非敏感摘录在 `config/context-evidence.json`，不复制账号标识或 Key。

scale 是填充文本构造参数，不是真实 token 数。scale=8000 已知正文 16320 字符、41920 UTF-8 字节，不含指令、JSON 或适配器封装。结果未保留 usage_source，因此 reportedPromptUsage 不能当作已验证 token 容量；413 也不能换算确定 token 边界。

快配置：MiniMax-M3、grok-3、longcat-pro，claude-sonnet-5 独立裁决。深配置：MiMo、GLM、DeepSeek 与 Arena，应选 LONG 并增加阶段等待。Qwen 未被报告成功验证，不自动加入。

本地默认采用 balanced 路线（MiniMax、MiMo、GLM、Arena）。2026-10-08 实测 run `254851cb-423a-44a0-91f1-b8f2fe05c03d`：三家首轮有效；GLM 在质疑/修订各一次 HTTP 502，两家有效观点继续；Arena 裁决有效，终态 COMPLETED / WAIT，未生成内部订单。总耗时约307秒，各次有效返回约26–96秒，请求约12–20KB / 10.7–15.7千字符。失败不被成功覆盖。

此前 Grok/LongCat 在复杂研究输入下各出现90秒等待超时；报告短问答耗时不能当作研究周期承诺。balanced 是一次可完成流程的验证，不能证明所有账号、持续可靠性或盈利能力。真实验证仅覆盖 WAIT，模拟 CREATE/部分成交/退出/费用由 Java 参考测试验证。

每路线独立校验消息数、Unicode 字符、UTF-8 字节、网关 ceil(bytes/3) 估算、输出预留、实时目录输入/输出/上下文与字符声明。16000 字符、30000 字节、10000 输入估算属于保守预算，不承诺最大容量。厂商 scoped 模型详情使用裸 ID，统一详情使用 provider/model。

supported_parameters 中的 response_format 不等于支持 JSON 模式。当前优先用已声明支持的严格函数 `submit_research` 提交数据，没有外部副作用。只发送映射为 mapped 的输出别名，MiMo non_binding_only 不当作强制上限。无效格式、长度、引用或未完整返回保留错误/原文，不猜测修复。

## 多 AI 讨论

同一截止证据上的独立研究 → 交叉质疑 → 修订 → 新上下文裁决。后续阶段只取得上阶段截至 deadline 的同一批有效观点。seed 轮换角色绑定，阅读次序独立打乱并记录；不使用到达顺序决定输入基础顺序。每阶段至少两个不同研究模型有效，裁决至少一个有效。迟到为 LATE_ARCHIVED，不进入已结束投票。

聊天室显示实际摘要/厂商可见分析、正式输出、引用、修订、时间、预算、usage 来源、阅读顺序和原文。接口不提供内部思考时不编造。阶段超时不等于上游撤销，同一 Provider 有请求未结束时禁止再并发。

模型材料选最近四项新闻及前180字符；同伴正式输出/修订有长度选择说明，完整稿另存。指标视图由 Java 舍入至八位小数，原精度另存；模型取得首四风险档位并明确不能自补其他档位，完整规则另存 research-rules。活跃账户与订单数值不静默裁剪，超预算拒绝调用。约100U保证金是参考习惯，模型仍自行评估实际数量与风险。

自动模式需显式配置，新闻触发合并与周期调度共用一个研究执行位。当前没有 token 流展示、观察者笔记、多轮重开或概率校准；有效观点不足不能当作有效决策。

## 内部模拟与账务

支持 WAIT、CREATE(MARKET/LIMIT/STOP_MARKET/STOP_LIMIT)、CANCEL、CLOSE。MARKET 的 entryPrice 为做多最高/做空最低允许成交价，剩余数量保留至过期；不是交易所市价 IOC 的复刻。AMEND/REDUCE、多腿与拖尾尚未实现。

未知费率拒绝 CREATE，配置费率明确是假设。模型自定数量、杠杆、收益目标、止损和风险；没有固定0.8%、止损比例或盈亏比门槛。Java 校验实际步长、杠杆/风险档、虚拟余额、时间和必要字段。

订单等待生效延迟，报价 exchangeTime/receivedAt 均须晚于 activatedAt。买入按卖一、卖出按买一；同一报价退出先执行、数量在内部订单间共享。触价不等于成交，fill 为 L1_ASSUMPTION，没有真实排队、L2穿档或券商成交证明。

保证金和余单预留分别冻结，手续费按成交扣一次，释放保证金不再次加本金。余额、权益、可用额、毛盈亏、开平仓费、已记账净值及浮盈分开。账本和事件同事务提交，重启不重置本金。

新鲜 mark 与公开维护保证金档位驱动简化逐仓强平，同侧阶梯扣除分摊。跨资金费时点、未知时点或报价缺口为 costIncomplete，本版本不虚构资金费。高杠杆断档可能越过退出价，结果不能当作交易所精确强平复刻。

当前执行边界为单 Java 实例，K8S 单副本/Recreate；没有跨实例领导权或全 tick 回放。贡献成交的报价、数量、价格、费用和依据持久化；离线期间的成交/触发不补造。XAUUSD.s 等待独立 MT5 报价、规则和成本。

## 验证与交付

测试覆盖指标参考值/未来排除、输入预算、新闻修订/XXE、日历DST、阶段封存/迟到/门槛、订单生效/部分成交/共享数量/费用/恢复/简化强平，以及接口鉴权。环境验证分别记录行情与校准、信源失败、实际推理终态、有效讨论结果与浏览器检查。

K8S 清单更新 PostgreSQL 用户和运行模式，初始副本仍为零；RELEASE_ENABLED 仍关闭。CI 不代表部署或 AI 可靠性。生产验收依旧要求正式 INTERNAL_SIMULATION，Alpha 不自动通过。Argo/DNS/Secret 的激活与 CFD、资金费和回放是后续工作。
