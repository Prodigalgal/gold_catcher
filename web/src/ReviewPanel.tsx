import { useState } from 'react';
import type { Dashboard } from './api';
import Pagination from './Pagination';

const show = (value?: string | number | null) => (value == null ? '—' : String(value));

export default function ReviewPanel({ data }: { data: Dashboard }) {
  const [tab, setTab] = useState<'equity' | 'models' | 'costs'>('equity');
  const [modelPage, setModelPage] = useState(1);
  const modelPageSize = 6;

  const r = data.review;
  const points = r.equityCurve ?? [];
  const known = points.filter((p) => p.equity !== null);

  // SVG Geometry
  const values = known.map((p) => Number(p.equity));
  const low = Math.min(...values);
  const high = Math.max(...values);
  const span = high - low || 1;

  let previousKnown = false;
  const line = points
    .map((p, i) => {
      if (p.equity === null) {
        previousKnown = false;
        return '';
      }
      const segment = `${previousKnown ? 'L' : 'M'}${
        20 + (i * 620) / Math.max(1, points.length - 1)
      },${170 - ((Number(p.equity) - low) * 140) / span}`;
      previousKnown = true;
      return segment;
    })
    .join(' ');

  const models = r.modelStatistics ?? [];
  const pagedModels = models.slice(
    (modelPage - 1) * modelPageSize,
    modelPage * modelPageSize
  );

  return (
    <>
      {/* 4 Performance KPI Cards */}
      <div className="gc-stat-grid">
        <div className="gc-stat">
          <div className="gc-stat-label">已平仓有效样本</div>
          <strong>{show(r.closedOrders)}</strong>
          <div className="gc-stat-foot">内部模拟撮合样本</div>
        </div>
        <div className="gc-stat">
          <div className="gc-stat-label">已记账净收益</div>
          <strong
            className={
              String(r.netBeforeUnknownCosts).startsWith('-')
                ? 'gc-negative'
                : Number(r.netBeforeUnknownCosts) > 0
                ? 'gc-positive'
                : ''
            }
          >
            {show(r.netBeforeUnknownCosts)}{' '}
            <small>{data.account.currency}</small>
          </strong>
          <div className="gc-stat-foot">已扣除已记账手续费与资金费</div>
        </div>
        <div className="gc-stat">
          <div className="gc-stat-label">完整成本样本胜率</div>
          <strong>
            {show(r.completeWinRatePct)}
            {r.completeWinRatePct != null && <small>%</small>}
          </strong>
          <div className="gc-stat-foot">仅已平仓且成本完整的永续样本</div>
        </div>
        <div className="gc-stat">
          <div className="gc-stat-label">采样最大回撤</div>
          <strong className="gc-negative">
            {show(r.sampledMaximumDrawdown)}{' '}
            <small>{data.account.currency}</small>
          </strong>
          <div className="gc-stat-foot">
            相对回撤 {show(r.sampledMaximumDrawdownPct)}% · 7天分钟采样
          </div>
        </div>
      </div>

      {/* Navigation Sub-Tabs */}
      <div className="gc-tabs" role="tablist" style={{ marginTop: '4px' }}>
        <button
          type="button"
          aria-pressed={tab === 'equity'}
          onClick={() => setTab('equity')}
        >
          权益轨迹与收益概览
        </button>
        <button
          type="button"
          aria-pressed={tab === 'models'}
          onClick={() => setTab('models')}
        >
          AI 模型研判胜率与表现 ({models.length})
        </button>
        <button
          type="button"
          aria-pressed={tab === 'costs'}
          onClick={() => setTab('costs')}
        >
          成本结构与样本质量分解
        </button>
      </div>

      {/* Tab 1: Equity Curve & Performance Overview */}
      {tab === 'equity' && (
        <>
          <section className="gc-panel">
            <div className="gc-panel-head">
              <div>
                <h3>虚拟账户权益轨迹 (Equity Curve)</h3>
                <span className="gc-small">
                  {r.equitySamples ?? 0} 个分钟级采样 · 最大回撤{' '}
                  {show(r.sampledMaximumDrawdownPct)}%
                </span>
              </div>
              <a
                className="gc-live-button"
                href={`/api/simulation/export?symbol=${data.market.symbol}`}
                download
              >
                导出订单记录
              </a>
            </div>

            {known.length > 1 ? (
              <svg
                className="gc-price-chart gc-live-chart"
                viewBox="0 0 680 210"
                role="img"
                aria-label="Java计算的虚拟账户权益采样曲线"
              >
                <defs>
                  <linearGradient id="equityGradient" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0%" stopColor="var(--gc-gold)" stopOpacity="0.25" />
                    <stop offset="100%" stopColor="var(--gc-gold)" stopOpacity="0.0" />
                  </linearGradient>
                </defs>
                {[30, 75, 120, 165].map((yVal) => (
                  <line
                    key={yVal}
                    x1="20"
                    x2="660"
                    y1={yVal}
                    y2={yVal}
                    stroke="var(--gc-line)"
                    strokeDasharray="4 4"
                  />
                ))}
                <path
                  d={line}
                  stroke="var(--gc-gold)"
                  fill="none"
                  strokeWidth="2.5"
                  strokeLinecap="round"
                />
                <text x="20" y="200" fill="var(--gc-muted)">
                  {new Date(points[0].at).toLocaleString('zh-CN', {
                    timeZone: 'Asia/Shanghai',
                  })}
                </text>
                <text x="640" y="200" textAnchor="end" fill="var(--gc-muted)">
                  {new Date(points.at(-1)!.at).toLocaleString('zh-CN', {
                    timeZone: 'Asia/Shanghai',
                  })}
                </text>
              </svg>
            ) : (
              <div className="gc-live-empty">
                <span>◇</span>
                <h3>权益记录正在积累</h3>
                <p>每分钟由 Java 保存当前估值采样；休市或行情过期时保持缺口。</p>
              </div>
            )}
          </section>

          <div className="gc-primary-grid">
            <section className="gc-panel">
              <div className="gc-panel-head">
                <h3>收益质量与样本分布</h3>
              </div>
              {[
                ['已实现毛盈亏', r.gross],
                ['已入账成本净支出', r.fees],
                ['完整成本样本净值', r.completeNet],
                ['完整成本样本盈利因子', r.profitFactor],
                ['完整成本有效样本数', r.fullyCostedOrders],
                ['成本不完整订单', r.incompleteOrders],
                ['盘口断档订单', r.quoteGapOrders],
                ['模拟强平订单', r.liquidatedOrders],
              ].map(([name, value]) => (
                <div className="gc-factor" key={name}>
                  <span>{name}</span>
                  <b>{show(value)}</b>
                </div>
              ))}
            </section>

            <section className="gc-panel">
              <div className="gc-panel-head">
                <h3>研究轮次有效性统计</h3>
              </div>
              {Object.entries(r.researchStatistics ?? {})
                .filter(([key]) => key !== 'scope')
                .map(([name, value]) => (
                  <div className="gc-factor" key={name}>
                    <span>
                      {
                        {
                          runs: '总研究轮数',
                          completed: '成功完成裁决',
                          staleEvidence: '时效过期作废',
                          running: '进行中 / 等待恢复',
                          canceled: '人工取消',
                          lowQuorum: '观点法定人数不足',
                          failed: '失败 / 计划拒绝',
                        }[name] ?? name
                      }
                    </span>
                    <b>{show(value)}</b>
                  </div>
                ))}
              <p className="gc-small" style={{ marginTop: '14px' }}>
                统计范围为最近 40 轮多模型研究；模型输出有效率与最终交易执行相互独立审计。
              </p>
            </section>
          </div>
        </>
      )}

      {/* Tab 2: AI Models Statistics with Pagination */}
      {tab === 'models' && (
        <section className="gc-panel">
          <div className="gc-panel-head">
            <div>
              <h3>AI 模型研究表现与可靠性</h3>
              <span className="gc-small">
                调用次数、有效输出率、超时与迟到统计 · 各模型独立记录
              </span>
            </div>
            <span className="gc-badge gc-gold-badge">共 {models.length} 个模型</span>
          </div>

          <div className="gc-live-table-wrap">
            <table className="gc-live-table">
              <thead>
                <tr>
                  <th>模型名称</th>
                  <th>提供商</th>
                  <th>调用总次数</th>
                  <th>有效提交数</th>
                  <th>失败 / 超时</th>
                  <th>迟到归档</th>
                  <th>累计耗时</th>
                </tr>
              </thead>
              <tbody>
                {pagedModels.map((model) => (
                  <tr key={`${model.provider}/${model.model}`}>
                    <td>
                      <strong>{model.model}</strong>
                    </td>
                    <td>
                      <span className="gc-badge">{model.provider}</span>
                    </td>
                    <td>{model.calls}</td>
                    <td>
                      <span className="gc-badge gc-positive">{model.submitted}</span>
                    </td>
                    <td>
                      <span
                        className={`gc-badge ${
                          model.failed > 0 ? 'gc-negative' : ''
                        }`}
                      >
                        {model.failed}
                      </span>
                    </td>
                    <td>{model.late}</td>
                    <td>
                      <code>{model.durationMillis} ms</code>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {!models.length && (
            <div className="gc-live-empty">
              <span>◇</span>
              <h3>暂无模型统计记录</h3>
              <p>研究轮次推进后，将在此聚合统计各模型的耗时与成功率。</p>
            </div>
          )}

          <Pagination
            currentPage={modelPage}
            totalItems={models.length}
            pageSize={modelPageSize}
            onPageChange={setModelPage}
          />
        </section>
      )}

      {/* Tab 3: Cost Structure Breakdown */}
      {tab === 'costs' && (
        <section className="gc-panel">
          <div className="gc-panel-head">
            <div>
              <h3>成本结构明细分解</h3>
              <span className="gc-small">
                开仓费、平仓费、资金费及掉期支出独立分解
              </span>
            </div>
          </div>
          <div
            style={{
              display: 'grid',
              gridTemplateColumns: 'repeat(auto-fill, minmax(260px, 1fr))',
              gap: '14px',
              margin: '16px 0',
            }}
          >
            {Object.entries(r.costBreakdown ?? {}).map(([name, value]) => (
              <div
                key={name}
                style={{
                  padding: '16px',
                  background: 'var(--gc-surface-raised)',
                  borderRadius: 'var(--gc-radius-sm)',
                  border: '1px solid var(--gc-line)',
                }}
              >
                <span className="gc-stat-label">
                  {
                    {
                      entry: '开仓手续费',
                      exit: '平仓手续费',
                      funding: '资金费净支出',
                      swap: '掉期净支出',
                    }[name] ?? name
                  }
                </span>
                <strong style={{ fontSize: '20px', display: 'block', margin: '8px 0' }}>
                  {value} <small>{data.account.currency}</small>
                </strong>
                <span className="gc-small">已从毛盈亏中扣除</span>
              </div>
            ))}
          </div>
          <p className="gc-small">
            点差和滑点已直接反映在成交均价中，不作二次重复扣减。若掉期或借贷费率暂未核实，则保留成本待核验标识。
          </p>
        </section>
      )}
    </>
  );
}
