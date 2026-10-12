import CompressionPanel, { FrozenCompression } from './CompressionPanel';
import { useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { call, watch } from './api';
import LoginPage from './LoginPage';
import IndicatorPanel from './IndicatorPanel';
import InformationPanel, { SourceHealth, ProxyHealth } from './InformationPanel';
import ContextBudget from './ContextBudget';
import type { Answer, Dashboard, Direction, Message, Product, Run, Session } from './api';
import OrderManager from './OrderManager';
import JudgePanel from './JudgePanel';
import ResearchProgress from './ResearchProgress';
import AiConfigurationPanel from './AiConfigurationPanel';
import ReviewPanel from './ReviewPanel';
import MarketChart from './MarketChart';
import { SchedulerPanel, MarketJournal } from './RuntimeControls';
import SourceControls from './SourceControls';
import ApiPerformancePanel from './ApiPerformancePanel';

const pages = [
  ['overview', '市场总览', '实时报价、技术指标与 AI 置顶裁决'],
  ['sources', '信息集合', '宏观情报、经济日历与驱动因子'],
  ['research', 'AI 研究', '独立研究 · 交叉质疑 · 修订 · 三方裁决'],
  ['trades', '模拟交易', '内部订单、持仓监控与风控管理'],
  ['review', '策略复盘', '净值曲线、收益归因与模型统计'],
  ['operations', '运行与配置', '模型路由、自动调度与系统监控'],
] as const;
type Page = typeof pages[number][0];

const phases = ['独立研究', '交叉质疑', '修订观点', '三方裁决'];
const directions: Record<Direction, string> = {
  BULLISH: '支持上涨',
  BEARISH: '形成压力',
  MIXED: '多空并存',
  UNKNOWN: '尚无判断',
};

const states: Record<string, string> = {
  RETRY_WAITING: '等待重试',
  INTERRUPTED: '请求重启中断',
  RECOVERY_PENDING: '等待恢复研究',
  FAILED_RECOVERY: '恢复失败',
  COMPLETED_EXPIRED: '裁决完成 · 交易时效已过',
  COMPLETED_WAIT: '裁决完成 · 证据不足等待',
  EXECUTION_TIMED_OUT: '研究执行预算耗尽',
  LIVE: '行情在线',
  DISABLED: '未启用',
  WAITING_CFD_SOURCE: 'CFD 报价尚未接入',
  STALE_OR_CONNECTING: '报价过期或连接中',
  CLOCK_UNVERIFIED: '时钟尚未校准',
  COMPLETED: '已完成',
  RUNNING: '研究中',
  LOW_QUORUM: '有效观点不足',
  FAILED: '失败',
  COMPLETED_ORDER_REJECTED: '研究完成 · 计划拒绝',
  INTERRUPTED_BY_RESTART: '重启中断',
  WAITING_RESPONSE: '等待返回',
  SUBMITTED: '已提交',
  TIMED_OUT: '阶段超时',
  LATE_ARCHIVED: '迟到归档',
  PENDING: '待成交',
  PARTIALLY_FILLED: '部分成交',
  FILLED: '已成交',
  REJECTED: '已拒绝',
  CANCELED: '已取消',
  EXPIRED: '已过期',
  OPEN: '持仓中',
  PARTIALLY_CLOSED: '部分退出',
  CLOSED: '已平仓',
  LIQUIDATED: '模拟强平',
  NONE: '无持仓',
  CURRENT: '最近采集成功',
  FETCH_FAILED: '采集失败',
  VALID_OUTPUT: '返回格式校验通过',
  VALID_OUTPUT_VIA_FALLBACK: '兜底输出校验通过',
  FALLBACK_FAILED: '兜底请求失败',
  NOT_CALLED: '尚未推理',
  VALUED: '已按盘口估值',
  STALE_OR_WAITING_QUOTE: '等待有效报价',
  EXCHANGE_CALIBRATED: '已校准交易所时间',
  NO_CLOSED_SAMPLE: '尚无平仓样本',
  WAITING_CFD_SIMULATION: 'CFD 模拟规则待完善',
  MARKET_CLOSED: '市场休市',
  READ_ONLY_API_KEY_REQUIRED: '需要只读 Key 核实',
  PUBLIC_RULES_TAKER_ASSUMPTION: '公开规则与 Taker 费率假设',
  SPEC_AND_COMMISSION_VERIFIED: '规格与佣金已核实',
  RULE_QUERY_FAILED: '规格查询失败',
  CREDENTIAL_NOT_CONFIGURED: '未配置凭据',
  PUBLIC_COST_ASSUMPTIONS: '公开成本假设',
  RECORDED_COSTS_INCOMPLETE: '部分成本尚未确认',
};

const label = (value?: string | null) => (value ? states[value] ?? value : '—');
const time = (value?: string | null) =>
  value
    ? new Date(value).toLocaleString('zh-CN', {
        timeZone: 'Asia/Shanghai',
        hour12: false,
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit',
      })
    : '未知';

function number(value?: string | null, digits = 4) {
  if (value === null || value === undefined) return '—';
  const [whole, fraction] = value.split('.');
  return (
    whole.replace(/\B(?=(\d{3})+(?!\d))/g, ',') +
    (fraction ? '.' + fraction.slice(0, digits) : '')
  );
}

function Badge({ children, tone = '' }: { children: ReactNode; tone?: string }) {
  return <span className={`gc-badge ${tone}`}>{children}</span>;
}

function DirectionBadge({ value }: { value?: Direction }) {
  return (
    <Badge
      tone={
        value === 'BULLISH'
          ? 'gc-positive'
          : value === 'BEARISH'
          ? 'gc-negative'
          : value === 'MIXED'
          ? 'gc-gold-badge'
          : ''
      }
    >
      {directions[value ?? 'UNKNOWN']}
    </Badge>
  );
}

function Empty({ title, text }: { title: string; text: string }) {
  return (
    <div className="gc-live-empty">
      <span>◇</span>
      <h3>{title}</h3>
      <p>{text}</p>
    </div>
  );
}

function Stat({
  title,
  value,
  detail,
  tone,
}: {
  title: string;
  value: ReactNode;
  detail: string;
  tone?: string;
}) {
  return (
    <div className="gc-stat">
      <div className="gc-stat-label">{title}</div>
      <strong className={tone ?? ''}>{value}</strong>
      <div className="gc-stat-foot">{detail}</div>
    </div>
  );
}

/* ==========================================================================
   Page 1: Overview
   ========================================================================== */
function Overview({ data, navigate }: { data: Dashboard; navigate: (page: Page) => void }) {
  const { market, account, research } = data;
  const verdict = research?.verdict;
  const spread =
    market.quote?.bid && market.quote?.ask
      ? (Number(market.quote.ask) - Number(market.quote.bid)).toFixed(2)
      : '—';

  return (
    <>
      {/* 4 Top KPI Cards */}
      <div className="gc-stat-grid">
        <Stat
          title="卖一 (Ask) / 买一 (Bid)"
          value={
            <>
              {number(market.quote?.ask, 2)}{' '}
              <small>/ {number(market.quote?.bid, 2)}</small>
            </>
          }
          detail={`点差 ${spread} · ${label(market.state)}`}
        />
        <Stat
          title="虚拟账户权益"
          value={
            <>
              {number(account.equity)}{' '}
              <small>{account.currency}</small>
            </>
          }
          detail={`${label(account.state)} · 可用 ${number(account.available)}`}
        />
        <Stat
          title="AI 最新置顶裁决"
          value={
            verdict ? (
              <span style={{ fontSize: '20px' }}>
                <DirectionBadge value={verdict.shortDirection} />
              </span>
            ) : (
              <span className="gc-small">等待裁决</span>
            )
          }
          detail={
            verdict
              ? `行动: ${String(verdict.plan?.action ?? 'WAIT')} · 截止 ${time(research?.evidenceCutoff)}`
              : '多模型独立分析与三方盲评'
          }
        />
        <Stat
          title="研究调度状态"
          value={research ? label(research.status) : '等待研究'}
          detail={
            research
              ? `${phases[research.stage]} · ${time(research.startedAt)}`
              : '获取有效报价后按需启动'
          }
        />
      </div>

      {/* Main 2-Column Dashboard */}
      <div className="gc-primary-grid">
        {/* Left Column: K-Line Chart & Technical Indicators Console */}
        <div style={{ display: 'grid', gap: '20px' }}>
          <MarketChart market={market} />
          <IndicatorPanel data={data} />
        </div>

        {/* Right Column: AI Opportunity Card & Multi-Horizon Glance */}
        <div style={{ display: 'grid', gap: '20px' }}>
          {/* Active AI Verdict Card */}
          <section
            className="gc-panel"
            style={{
              borderLeft: '4px solid var(--gc-gold)',
              boxShadow: 'var(--gc-shadow-md)',
            }}
          >
            <div className="gc-panel-head">
              <div>
                <h3>AI 置顶裁决与机会</h3>
                <span className="gc-small">三方盲评裁决汇总方案</span>
              </div>
              <button
                type="button"
                className="gc-text-button"
                onClick={() => navigate('research')}
              >
                查看辩论详情 ↗
              </button>
            </div>

            {verdict ? (
              <>
                <h2 style={{ fontSize: '18px', margin: '4px 0 12px', color: 'var(--gc-text)' }}>
                  {verdict.title}
                </h2>
                <div style={{ display: 'flex', gap: '8px', marginBottom: '14px', alignItems: 'center' }}>
                  <span className="gc-small">短线方向:</span>
                  <DirectionBadge value={verdict.shortDirection} />
                  <span className="gc-small" style={{ marginLeft: '6px' }}>未来两天:</span>
                  <DirectionBadge value={verdict.longDirection} />
                </div>
                <p className="gc-live-prose">{verdict.output}</p>

                <div className="gc-factor">
                  <span>裁决执行动作</span>
                  <b style={{ color: 'var(--gc-gold)' }}>
                    {String(verdict.plan?.action ?? 'WAIT')}
                  </b>
                </div>
                <div className="gc-factor">
                  <span>证据截止时间</span>
                  <b>{time(research?.evidenceCutoff)}</b>
                </div>
                {research?.decisionExpiresAt && (
                  <div className="gc-factor">
                    <span>交易有效截止</span>
                    <b>{time(research.decisionExpiresAt)}</b>
                  </div>
                )}
              </>
            ) : (
              <Empty
                title="等待研究结论"
                text="各方模型完成独立研判、交叉质疑与修订后，三方独立盲评投票选择最优方案。"
              />
            )}
          </section>

          {/* Multi-Horizon Structure Pulse */}
          {data.analysis && (
            <section className="gc-panel">
              <div className="gc-panel-head">
                <div>
                  <h3>四周期大势初判</h3>
                  <span className="gc-small">Java 规则初判 · 待 AI 深度复核</span>
                </div>
                <button
                  type="button"
                  className="gc-text-button"
                  onClick={() => navigate('sources')}
                >
                  驱动因子 ↗
                </button>
              </div>
              <div style={{ display: 'grid', gap: '8px' }}>
                {data.analysis.horizons.map((h) => (
                  <div
                    key={h.id}
                    style={{
                      padding: '10px 12px',
                      background: 'var(--gc-surface-raised)',
                      borderRadius: 'var(--gc-radius-sm)',
                      border: '1px solid var(--gc-line)',
                      display: 'flex',
                      justifyContent: 'space-between',
                      alignItems: 'center',
                    }}
                  >
                    <div>
                      <strong style={{ fontSize: '12px' }}>{h.label}</strong>
                      <span className="gc-small" style={{ display: 'block' }}>
                        {h.support[0] || h.gaps[0] || '等待结构信号'}
                      </span>
                    </div>
                    {h.direction === 'RANGE' ? <Badge>区间震荡</Badge> : <DirectionBadge
                      value={
                        h.direction === 'BULLISH'
                          ? 'BULLISH'
                          : h.direction === 'BEARISH'
                          ? 'BEARISH'
                          : 'UNKNOWN'
                      }
                    />}
                  </div>
                ))}
              </div>
            </section>
          )}

          {/* Collapsible Execution Rules & Gateway Provenance */}
          <section className="gc-panel">
            <details style={{ cursor: 'pointer' }}>
              <summary
                style={{
                  fontWeight: 600,
                  fontSize: '13px',
                  color: 'var(--gc-text)',
                  display: 'flex',
                  justifyContent: 'space-between',
                }}
              >
                <span>Gate · {data.marketProvenance.nativeSymbol} 规格与规则</span>
                <span className="gc-badge">
                  {data.marketProvenance.transport === 'REST_POLL' ? 'REST 轮询' : 'WebSocket 实时'}
                </span>
              </summary>
              <div style={{ marginTop: '12px', paddingTop: '10px', borderTop: '1px solid var(--gc-line)' }}>
                <p className="gc-small" style={{ margin: '0 0 10px' }}>
                  {market.symbol === 'GATE:XAUUSD'
                    ? 'CFD 报价时间为接口响应时间。规格与佣金由只读验证，USD 独立账户按手数计算成交情景。'
                    : '内部数量统一为 XAU 基础单位，Gate 合约张数按乘数换算，开平仓均按公开 Taker 费率保守计费。'}
                </p>
                <div className="gc-factor">
                  <span>规则状态</span>
                  <b>{label(data.marketProvenance.rulesState)}</b>
                </div>
                {data.marketProvenance.cfdRules && (
                  <div className="gc-factor">
                    <span>CFD 手数规格 / 佣金</span>
                    <b>
                      {String(data.marketProvenance.cfdRules.contract_volume)} XAU / 手 ·{' '}
                      {data.costConfiguration.feePerLot} USD / 手
                    </b>
                  </div>
                )}
                <div className="gc-factor">
                  <span>交易费用假设</span>
                  <b>
                    {data.costConfiguration.feePerLot
                      ? `${data.costConfiguration.feePerLot} USD / 手`
                      : data.costConfiguration.takerFeeRate}
                  </b>
                </div>
              </div>
            </details>
          </section>
        </div>
      </div>
    </>
  );
}

/* ==========================================================================
   Page 3: AI Research Room
   ========================================================================== */
function AnswerContent({
  answer,
  message,
  run,
}: {
  answer: Answer;
  message: Message;
  run: Run;
}) {
  const output = answer.output;
  const actor = run.actors.find((a) => a.id === message.actorId);
  return (
    <>
      <div className="gc-live-message-heading">
        <h3>{output?.title ?? '原始返回已归档'}</h3>
        <span>{answer.routing?.fallbackUsed ? `${answer.routing.usedModel} · 兜底` : actor?.model}</span>
      </div>
      {answer.routing?.fallbackUsed && <p className="gc-small">{answer.routing.requestedModel} → {answer.routing.usedModel} · {answer.routing.reason}{answer.model !== answer.routing.usedModel ? ` · 上游模型 ${answer.model}` : ''}</p>}
      {Boolean(answer.routing?.failures.length) && <details className="gc-live-audit"><summary>主模型失败记录</summary>{answer.routing?.failures.map((failure,index) => <div key={index}><p>{failure.model} · {failure.error}</p>{failure.partial?.raw && <pre>{failure.partial.raw}</pre>}</div>)}</details>}
      {answer.analysis && (
        <details
          className="gc-live-analysis"
          style={{
            background: 'var(--gc-surface-raised)',
            padding: '10px 14px',
            borderRadius: 'var(--gc-radius-sm)',
            margin: '10px 0',
            border: '1px solid var(--gc-line)',
          }}
        >
          <summary style={{ cursor: 'pointer', fontSize: '11px', color: 'var(--gc-gold)' }}>
            💭 {answer.analysisSource === 'PROVIDER_FIELD' ? '厂商可见深度思考过程' : '模型输出思考摘要'} ↗
          </summary>
          <p className="gc-live-prose" style={{ marginTop: '8px' }}>
            {answer.analysis}
          </p>
        </details>
      )}
      {output ? (
        <>
          <p className="gc-live-prose">{output.output}</p>
          <div className="gc-live-direction-row">
            <span>短线:</span>
            <DirectionBadge value={output.shortDirection} />
            <span style={{ marginLeft: '8px' }}>未来两天:</span>
            <DirectionBadge value={output.longDirection} />
          </div>
          {output.changes && <p className="gc-small">观点修订：{output.changes}</p>}
          {!!output.quotedMessages?.length && (
            <div className="gc-small" style={{ color: 'var(--gc-blue)', marginTop: '6px' }}>
              引用发言：
              {output.quotedMessages.map((id) => (
                <a key={id} href={`#message-${id}`} style={{ marginRight: '8px' }}>
                  #{id.split('-').slice(-2).join(' · ')}
                </a>
              ))}
            </div>
          )}
        </>
      ) : (
        <p className="gc-live-prose">结构或完成状态未通过校验，本条输出未参与裁决。</p>
      )}
      <details className="gc-live-audit" style={{ marginTop: '10px' }}>
        <summary style={{ cursor: 'pointer', fontSize: '11px', color: 'var(--gc-muted)' }}>
          工程审计 · 耗时 {answer.durationMs}ms · 上下文预算 ↗
        </summary>
        <div style={{ marginTop: '8px' }}>
          {answer.budget && <ContextBudget budget={answer.budget} audit />}
          <p className="gc-small">
            输出限制：{answer.budget?.outputEnforcement ?? '未发送或未取得预算'} · usage 来源：{answer.usageSource} · 结束状态：{answer.finishReason}
          </p>
          {!!answer.attempts?.length && <div className="gc-live-table-wrap"><table className="gc-live-table"><thead><tr><th>请求结果</th><th>客户端流水号 / 上游 Request ID</th></tr></thead><tbody>{answer.attempts.map((attempt, index) => <tr key={`${attempt.clientTraceId ?? attempt.number}-${index}`}><td>HTTP {attempt.httpStatus ?? '未握手'} · {attempt.outcome === 'VALIDATED_OUTPUT' ? '输出校验通过' : attempt.error ?? attempt.outcome ?? '历史记录'}<br/>{attempt.durationMs}ms</td><td style={{ overflowWrap: 'anywhere' }}>{attempt.clientTraceId ?? '历史未记录'}<br/>{attempt.requestId ?? '上游 ID 未取得'}</td></tr>)}</tbody></table></div>}
          <pre>{answer.raw}</pre>
        </div>
      </details>
    </>
  );
}

function Research({
  data,
  session,
  changed,
  start,
}: {
  data: Dashboard;
  session: Session;
  changed: () => void;
  start: (horizon: string) => void;
}) {
  const [selectedId, setSelectedId] = useState('');
  const [archived, setArchived] = useState<Run | null>(null);
  const [phase, setPhase] = useState(-1);
  const [error, setError] = useState('');

  const selectedLive = Object.values(data.researchByHorizon ?? {}).find(item => item?.id === selectedId) ?? (data.research?.id === selectedId ? data.research : null);
  const selectedRevision = selectedId && selectedLive
    ? JSON.stringify([selectedLive.status, selectedLive.stage, selectedLive.messages.map(message => [message.id, message.state, message.completedAt, message.attempt])])
    : '';
  useEffect(() => {
    if (!selectedId || selectedId.startsWith('lane:')) {
      setArchived(null);
      return;
    }
    const controller = new AbortController();
    call<Run>(`research/${selectedId}`, session, undefined, controller.signal)
      .then(setArchived)
      .catch((err) => {
        if (!controller.signal.aborted) setError(err.message);
      });
    return () => controller.abort();
  }, [selectedId, selectedRevision, session]);

  const run = selectedId.startsWith('lane:') ? data.researchByHorizon?.[selectedId.slice(5) as 'SHORT' | 'LONG'] ?? null : selectedId ? archived : data.research;
  const messages = run?.messages.filter((message) => phase < 0 || message.stage === phase) ?? [];
  const judgeCount = run?.actors.filter((actor) => actor.id.startsWith('J')).length ?? 0;
  const researchCount = (run?.actors.length ?? 0) - judgeCount;

  return (
    <>
      {/* Research Toolbar */}
      <div className="gc-live-toolbar">
        <select
          aria-label="选择研究记录"
          value={selectedId}
          onChange={(event) => {
            setSelectedId(event.target.value);
            setArchived(null);
          }}
          style={{ minWidth: '220px' }}
        >
            <option value="">最新研究记录</option>
          <option value="lane:SHORT">最新短线 · fast_lane</option>
          <option value="lane:LONG">最新长线 · deep_lane</option>
          {data.researchHistory.map((item) => (
            <option value={item.id} key={item.id}>
              {time(item.startedAt)} · {label(item.status)} · {item.horizon}
            </option>
          ))}
        </select>
        <div className="gc-title-actions">
          <button
            className="gc-live-button gc-live-primary"
            disabled={data.scheduling.running || data.market.state !== 'LIVE'}
            onClick={() => {
              setSelectedId('');
              start('SHORT');
            }}
          >
            启动短线研究
          </button>
          <button
            className="gc-live-button"
            disabled={
              data.scheduling.running ||
              (data.market.state !== 'LIVE' &&
                !(data.market.symbol === 'GATE:XAUUSD' && data.market.state === 'MARKET_CLOSED'))
            }
            onClick={() => {
              setSelectedId('');
              start('LONG');
            }}
          >
            启动长线研究
          </button>
          <button className="gc-live-button" onClick={changed}>
            刷新
          </button>
          {run && (
            <a className="gc-live-button" href={`/api/research/${run.id}/export`} download>
              导出研究报告
            </a>
          )}
          {run?.status === 'RUNNING' && (
            <button
              className="gc-live-button"
              onClick={() =>
                void call(`research/${run.id}/cancel`, session, {})
                  .then(changed)
                  .catch((failure) => setError(failure.message))
              }
            >
              取消本轮
            </button>
          )}
        </div>
      </div>

      {error && <p className="gc-live-error" role="alert">{error}</p>}
      {run && <ResearchProgress run={run} time={time} />}

      {/* Main Research Chat & Judicial Consensus Board */}
      <div className="gc-live-research-grid">
        {/* Left: Chat & Debate Stream */}
        <section className="gc-panel gc-live-room">
          <div className="gc-panel-head">
            <div>
              <h3>黄金联合 AI 研究辩论室</h3>
              <span className="gc-small">
                {run
                  ? `${run.horizon} 周期 · 启动于 ${time(run.startedAt)} · 状态 ${label(run.status)}`
                  : '配置模型路由后，各智能体独立提交观点并交叉对抗'}
              </span>
            </div>
            <Badge gc-gold-badge>
              {researchCount} 位辩论 · {judgeCount} 位独立裁决
            </Badge>
          </div>

          {/* Phase Filter Tabs */}
          <div className="gc-live-phases">
            <button aria-pressed={phase === -1} onClick={() => setPhase(-1)}>
              全部阶段 ({run?.messages.length ?? 0})
            </button>
            {phases.map((name, index) => {
              const count = run?.messages.filter((m) => m.stage === index).length ?? 0;
              return (
                <button
                  key={name}
                  aria-pressed={phase === index}
                  onClick={() => setPhase(index)}
                >
                  {index + 1} · {name} ({count})
                </button>
              );
            })}
          </div>

          {run ? (
            <>
              {messages.map((message) => (
                <article
                  className={`gc-live-message ${message.actorId.startsWith('J') ? 'gc-live-judge' : ''}`}
                  key={message.id}
                  id={`message-${message.id}`}
                >
                  <div className="gc-live-avatar">{message.actorId}</div>
                  <div className="gc-live-bubble">
                    <div className="gc-live-message-meta">
                      <b>{message.role}</b>
                      <span>{phases[message.stage]}</span>
                      <time>{time(message.completedAt ?? message.startedAt)}</time>
                      <Badge tone={['FAILED', 'TIMED_OUT'].includes(message.state) || message.error ? 'gc-negative' : message.state === 'SUBMITTED' ? 'gc-positive' : ''}>
                        {label(message.state)}
                      </Badge>
                    </div>
                    {message.answer ? (
                      <AnswerContent answer={message.answer} message={message} run={run} />
                    ) : (
                      <p className="gc-live-prose">
                        {message.error ?? '请求进行中，等待模型返回实际内容…'}
                      </p>
                    )}
                    {message.error && message.answer && (
                      <p className="gc-live-error">{message.error}</p>
                    )}
                  </div>
                </article>
              ))}

              {!messages.length && (
                <Empty
                  title="该阶段暂无消息"
                  text="阶段完成后有效观点才被解封并传递至下一阶段。"
                />
              )}
            </>
          ) : (
            <Empty
              title="等待第一场研究讨论"
              text={
                data.market.state === 'LIVE'
                  ? '点击上方“启动短线研究”，即可实时观察各方观点与交叉质疑。'
                  : `当前${label(data.market.state)}，取得有效报价后即可启动。`
              }
            />
          )}
        </section>

        {/* Right Sidebar: Verdict & Evidence */}
        <aside className="gc-live-room-aside">
          {run && <JudgePanel run={run} showMessage={() => setPhase(-1)} />}

          {/* Verdict Action Plan */}
          <section className="gc-panel">
            <div className="gc-panel-head">
              <h3>置顶裁决与计划行动</h3>
            </div>
            {run?.verdict ? (
              <>
                <div style={{ marginBottom: '10px' }}>
                  <DirectionBadge value={run.verdict.shortDirection} />
                </div>
                <p className="gc-live-prose">{run.verdict.output}</p>
                <div className="gc-factor">
                  <span>执行方案</span>
                  <b>{String(run.verdict.plan?.action ?? 'WAIT')}</b>
                </div>
                {run.orderId && (
                  <div className="gc-factor">
                    <span>关联订单</span>
                    <b>#{run.orderId.slice(-8)}</b>
                  </div>
                )}
              </>
            ) : (
              <p className="gc-small">尚无有效裁决结论。</p>
            )}
          </section>

          {/* Evidence Snapshot */}
          <section className="gc-panel">
            <div className="gc-panel-head">
              <h3>共同证据包快照</h3>
            </div>
            <div className="gc-factor">
              <span>产品标的</span>
              <b>{data.market.symbol}</b>
            </div>
            <div className="gc-factor">
              <span>配置版本</span>
              <b>{run?.routeConfiguration ? `v${run.routeConfiguration.revision}` : '历史配置'}</b>
            </div>
            <div className="gc-factor">
              <span>证据截止时间</span>
              <b>{time(run?.evidenceCutoff)}</b>
            </div>
            <div className="gc-factor">
              <span>交易有效截止</span>
              <b>{time(run?.decisionExpiresAt)}</b>
            </div>
            <p className="gc-small" style={{ wordBreak: 'break-all', marginTop: '10px' }}>
              SHA-256: {run?.evidenceHash ?? '未生成'}
            </p>
          </section>

          {/* Agents Roster */}
          {run?.preprocessing && <FrozenCompression data={run.preprocessing} session={session} />}
          <section className="gc-panel">
            <div className="gc-panel-head">
              <h3>参与智能体阵容</h3>
            </div>
            <div style={{ display: 'grid', gap: '8px' }}>
              {run?.actors.map((actor) => (
                <div
                  key={actor.id}
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'space-between',
                    padding: '8px 10px',
                    background: 'var(--gc-surface-raised)',
                    borderRadius: 'var(--gc-radius-sm)',
                    fontSize: '12px',
                  }}
                >
                  <div>
                    <b>{actor.id} · {actor.role}</b>
                    <span className="gc-small" style={{ display: 'block' }}>
                      {actor.model}
                    </span>
                  </div>
                  <span className="gc-badge">{actor.provider}</span>
                </div>
              ))}
            </div>
          </section>
        </aside>
      </div>
    </>
  );
}

/* ==========================================================================
   Page 6: Operations & Config
   ========================================================================== */
function Operations({
  data,
  status,
  session,
  changed,
}: {
  data: Dashboard;
  status: Record<string, string> | null;
  session: Session;
  changed: () => void;
}) {
  const [opTab, setOpTab] = useState<'ai' | 'scheduler' | 'system'>('ai');

  return (
    <>
      {/* 4 Status KPI Cards */}
      <div className="gc-stat-grid">
        <Stat
          title="系统版本与提交"
          value={status?.version ?? '—'}
          detail={`Commit: ${status?.sourceCommit?.slice(0, 10) ?? '开发版'}`}
        />
        <Stat
          title="行情通道状态"
          value={label(data.market.state)}
          detail={data.market.error ?? '官方接口正常轮询'}
        />
        <Stat
          title="自动研究调度"
          value={data.scheduling.automatic ? '自动运行中' : '手动模式'}
          detail={
            data.scheduling.error ||
            (data.scheduling.running ? '有研究正在执行' : '等待调度窗口')
          }
        />
        <Stat
          title="交易所时钟校准"
          value={label(data.clock.state)}
          detail={`偏移 ${data.clock.offsetMillis ?? '0'}ms · 误差上界 ${data.clock.uncertaintyMillis ?? '0'}ms`}
        />
      </div>

      {/* Navigation Sub-Tabs */}
      <div className="gc-tabs" role="tablist" style={{ marginTop: '4px' }}>
        <button
          type="button"
          aria-pressed={opTab === 'ai'}
          onClick={() => setOpTab('ai')}
        >
          AI 模型路由与前置压缩配置
        </button>
        <button
          type="button"
          aria-pressed={opTab === 'scheduler'}
          onClick={() => setOpTab('scheduler')}
        >
          自动研究与调度管理
        </button>
        <button
          type="button"
          aria-pressed={opTab === 'system'}
          onClick={() => setOpTab('system')}
        >
          行情归档、信源与系统监控
        </button>
      </div>

      {/* TAB 1: AI Configuration & Compression */}
      {opTab === 'ai' && (
        <div style={{ display: 'grid', gap: '20px' }}>
          <AiConfigurationPanel session={session} changed={changed} />
          <CompressionPanel data={data.compression} session={session} />
          <SourceControls session={session} changed={changed} />
        </div>
      )}

      {/* TAB 2: Scheduler */}
      {opTab === 'scheduler' && (
        <SchedulerPanel data={data} session={session} changed={changed} />
      )}

      {/* TAB 3: System & Archives */}
      {opTab === 'system' && (
        <div style={{ display: 'grid', gap: '20px' }}>
          <MarketJournal data={data} session={session} />
          <ApiPerformancePanel session={session} />
          <div className="gc-secondary-grid">
            <SourceHealth data={data} />
            <ProxyHealth data={data} />
          </div>
          <section className="gc-panel">
            <div className="gc-panel-head">
              <h3>执行规则与 Gate 成本模型</h3>
            </div>
            <div className="gc-factor">
              <span>报价传输模式</span>
              <b>
                {data.marketProvenance.transport} · {data.marketProvenance.nativeSymbol}
              </b>
            </div>
            <div className="gc-factor">
              <span>交易手续费率</span>
              <b>
                {data.costConfiguration.feePerLot
                  ? `${data.costConfiguration.feePerLot} USD / 手`
                  : data.costConfiguration.takerFeeRate}
              </b>
            </div>
            <div className="gc-factor">
              <span>资金费率监控</span>
              <b>
                {label(data.funding.state)} · 最近成功 {time(data.funding.lastSuccess)}
              </b>
            </div>
            <div className="gc-factor">
              <span>费用与结算口径</span>
              <b>{data.account.costStatus}</b>
            </div>
            <div className="gc-factor">
              <span>强平与风控算法</span>
              <b>{data.account.liquidationMethod}</b>
            </div>
          </section>
        </div>
      )}
    </>
  );
}

/* ==========================================================================
   Main Root Application
   ========================================================================== */
function App() {
  const [page, setPage] = useState<Page>('overview');
  const [product, setProduct] = useState<Product>('XAU_USDT');
  const [session, setSession] = useState<Session | null>(null);
  const [restoring, setRestoring] = useState(true);
  const [data, setData] = useState<Dashboard | null>(null);
  const [status, setStatus] = useState<Record<string, string> | null>(null);
  const [error, setError] = useState('');
  const [connected, setConnected] = useState(false);
  const [busy, setBusy] = useState(false);
  const [refreshTick, setRefreshTick] = useState(0);
  const [theme, setTheme] = useState<'dark' | 'light'>('dark');

  const changed = () => setRefreshTick((value) => value + 1);

  useEffect(() => {
    const controller = new AbortController();
    call<Session>('session', {}, undefined, controller.signal)
      .then((value) => {
        setSession(value);
        history.replaceState(null, '', '/');
      })
      .catch(() => {
        if (!controller.signal.aborted) history.replaceState(null, '', '/login');
      })
      .finally(() => {
        if (!controller.signal.aborted) setRestoring(false);
      });
    return () => controller.abort();
  }, []);

  useEffect(() => {
    if (!session) {
      setData(null);
      return;
    }
    setData(null);
    setError('');
    const controller = new AbortController();
    let pending = false,
      last = 0;
    const refresh = async () => {
      if (pending || controller.signal.aborted) return;
      pending = true;
      last = Date.now();
      try {
        const fresh = await call<Dashboard>(
          `dashboard?symbol=${product}&view=${page}`,
          session,
          undefined,
          controller.signal
        );
        if (!controller.signal.aborted) {
          setData(fresh);
          setError('');
        }
      } catch (e) {
        if (!controller.signal.aborted) {
          if ((e as Error).message === 'AUTH_REQUIRED') {
            setSession(null);
            history.replaceState(null, '', '/login');
          } else setError((e as Error).message);
        }
      } finally {
        pending = false;
      }
    };
    void refresh();
    const timer = setInterval(refresh, 2500);
    void watch(
      session,
      () => {
        if (Date.now() - last > 1500) void refresh();
      },
      setConnected,
      controller.signal
    );
    call<Record<string, string>>('system/status', session, undefined, controller.signal)
      .then(setStatus)
      .catch(() => {});
    return () => {
      controller.abort();
      clearInterval(timer);
      setConnected(false);
    };
  }, [session, product, page, refreshTick]);

  const logout = async () => {
    if (!session) return;
    try {
      await call('auth/logout', session, {});
      setSession(null);
      setData(null);
      setError('');
      history.replaceState(null, '', '/login');
    } catch (failure) {
      setError((failure as Error).message);
    }
  };

  const start = async (horizon: string) => {
    if (!session) return;
    setBusy(true);
    setError('');
    try {
      await call<Run>('research', session, { symbol: product, horizon });
      changed();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  if (!session) return <LoginPage restoring={restoring} onLogin={setSession} />;

  const title = pages.find((p) => p[0] === page)!;
  const spread =
    data?.market?.quote?.bid && data?.market?.quote?.ask
      ? (Number(data.market.quote.ask) - Number(data.market.quote.bid)).toFixed(2)
      : '—';

  return (
    <div className="gc-app gc-live-app" style={{ colorScheme: theme }}>
      {/* Modern Sidebar */}
      <aside className="gc-sidebar">
        <div className="gc-brand">
          <div className="gc-brand-mark">Au</div>
          <div>
            <strong>GOLD CATCHER</strong>
            <span>AI 黄金研究工作台</span>
          </div>
        </div>
        <div className="gc-nav-caption">工作空间导航</div>
        <nav>
          {pages.map(([key, name], index) => (
            <button
              type="button"
              key={key}
              aria-current={page === key ? 'page' : undefined}
              onClick={() => {
                setPage(key);
                setError('');
              }}
            >
              <span className="gc-nav-index">0{index + 1}</span>
              <span>{name}</span>
              {page === key && <span className="gc-nav-dot" />}
            </button>
          ))}
        </nav>
        <div className="gc-sidebar-bottom">
          <div className="gc-mode-dot" />
          <div>
            内部模拟模式
            <span>
              Java 实时撮合与账本
              <br />
              多模型博弈研究
            </span>
          </div>
        </div>
      </aside>

      {/* Main Workspace Canvas */}
      <div className="gc-workspace">
        {/* Modern Header Bar with Live Market Ticker */}
        <header className="gc-header">
          <div className="gc-breadcrumb">
            工作空间 <span>/</span> <strong>{title[1]}</strong>
          </div>

          {/* Center Market Ticker */}
          {data?.market?.quote && (
            <div className="gc-header-ticker">
              <span className="gc-ticker-symbol">{data.market.symbol}</span>
              <div className="gc-ticker-quotes">
                <span style={{ color: 'var(--gc-positive)' }}>
                  {number(data.market.quote.bid, 2)}
                </span>
                <small>/</small>
                <span style={{ color: 'var(--gc-negative)' }}>
                  {number(data.market.quote.ask, 2)}
                </span>
              </div>
              <span className="gc-ticker-spread">点差 {spread}</span>
              <span
                className={`gc-badge ${
                  data.market.state === 'LIVE' ? 'gc-positive' : 'gc-gold-badge'
                }`}
              >
                ● {label(data.market.state)}
              </span>
            </div>
          )}

          {/* Header Right Actions */}
          <div className="gc-live-header-actions">
            <Badge tone={connected ? 'gc-positive' : ''}>
              {connected ? '● 实时推送在线' : '快照轮询'}
            </Badge>
            <button
              type="button"
              className="gc-live-button"
              aria-label="切换明暗主题"
              onClick={() => setTheme((value) => (value === 'dark' ? 'light' : 'dark'))}
            >
              {theme === 'dark' ? '☀ 日间' : '☾ 暗黑'}
            </button>
            <button
              type="button"
              className="gc-live-button"
              onClick={() => void logout()}
            >
              退出
            </button>
          </div>
        </header>

        {/* Content Viewport */}
        <main className="gc-main">
          {/* Title Row with Product Switcher */}
          <div className="gc-title-row">
            <div>
              <span className="gc-eyebrow">INSTITUTIONAL AI TERMINAL</span>
              <h1>{title[1]}</h1>
              <p>{title[2]}</p>
            </div>
            <div className="gc-title-actions">
              <div className="gc-product-selector">
                <button
                  type="button"
                  className={product === 'XAU_USDT' ? 'active' : ''}
                  onClick={() => setProduct('XAU_USDT')}
                >
                  Gate · XAU_USDT 永续
                </button>
                <button
                  type="button"
                  className={product === 'GATE:XAUUSD' ? 'active' : ''}
                  onClick={() => setProduct('GATE:XAUUSD')}
                >
                  Gate · XAUUSD CFD
                </button>
              </div>
            </div>
          </div>

          {error && <div className="gc-live-error" role="alert">{error}</div>}

          {!data || (data.dashboardView && data.dashboardView !== page) ? (
            <section className="gc-panel">
              <Empty
                title="正在同步金融数据"
                text="连接 Java 后端引擎并取得最新快照…"
              />
            </section>
          ) : (
            <>
              {page === 'overview' && <Overview data={data} navigate={setPage} />}
              {page === 'sources' && <InformationPanel key={product} data={data} />}
              {page === 'research' && (
                <Research
                  key={product}
                  data={data}
                  session={session}
                  changed={changed}
                  start={start}
                />
              )}
              {page === 'trades' && (
                <OrderManager
                  key={product}
                  account={data.account}
                  session={session}
                  changed={changed}
                />
              )}
              {page === 'review' && <ReviewPanel data={data} />}
              {page === 'operations' && (
                <Operations
                  data={data}
                  status={status}
                  session={session}
                  changed={changed}
                />
              )}
            </>
          )}
        </main>

        {/* Global Footer */}
        <footer className="gc-footer">
          <span>GOLD CATCHER · v{status?.version ?? '—'} · 机构级黄金 AI 研究终端</span>
          <span>快照同步: {time(data?.serverTime)} · 北京时间</span>
        </footer>
      </div>
    </div>
  );
}

export default App;
