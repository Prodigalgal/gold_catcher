import { useEffect, useState } from 'react';
import { call } from './api';
import type { Account, AuditEvent, Order, Session } from './api';
import Pagination from './Pagination';

const numeric = (value?: string | null) => (value == null ? '—' : value);
const stamp = (value?: string | null) =>
  value
    ? new Date(value).toLocaleString('zh-CN', {
        timeZone: 'Asia/Shanghai',
        hour12: false,
      })
    : '—';

export default function OrderManager({
  account,
  session,
  changed,
}: {
  account: Account;
  session: Session;
  changed: () => void;
}) {
  const [selected, setSelected] = useState('');
  const [audit, setAudit] = useState<AuditEvent[]>([]);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  const [filter, setFilter] = useState<'ALL' | 'OPEN' | 'PENDING' | 'CLOSED'>('ALL');
  const [page, setPage] = useState(1);
  const pageSize = 8;

  const [reduce, setReduce] = useState('');
  const [stop, setStop] = useState('');
  const [target, setTarget] = useState('');
  const [distance, setDistance] = useState('');
  const [activation, setActivation] = useState('');
  const [orderTab, setOrderTab] = useState<'rules' | 'risk' | 'events'>('rules');

  const order = account.orders.find((o) => o.id === selected);
  const cfd = account.symbol === 'GATE:XAUUSD';

  useEffect(() => {
    if (!order) return;
    setStop(order.stopLoss ?? '');
    setTarget(order.takeProfit ?? '');
    setDistance(order.trailingDistance ?? '');
    setActivation(order.trailingActivation ?? '');
    setReduce('');
  }, [selected]);

  useEffect(() => {
    setAudit([]);
    if (!selected) return;
    const controller = new AbortController();
    void call<AuditEvent[]>(
      `simulation/orders/${selected}/events`,
      session,
      undefined,
      controller.signal
    )
      .then(setAudit)
      .catch((failure) => {
        if (!controller.signal.aborted) setError(failure.message);
      });
    return () => controller.abort();
  }, [selected, account.orders, session]);

  const command = async (o: Order, action: string, fields: Record<string, unknown> = {}) => {
    setBusy(true);
    setError('');
    try {
      await call(`simulation/orders/${o.id}/command`, session, { action, ...fields });
      changed();
    } catch (failure) {
      setError((failure as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const filteredOrders = [...account.orders]
    .reverse()
    .filter((o) => {
      if (filter === 'ALL') return true;
      if (filter === 'OPEN') return o.positionStatus === 'OPEN';
      if (filter === 'PENDING') return ['PENDING', 'PARTIALLY_FILLED'].includes(o.status);
      if (filter === 'CLOSED') return ['CLOSED', 'LIQUIDATED'].includes(o.positionStatus);
      return true;
    });

  const pagedOrders = filteredOrders.slice((page - 1) * pageSize, page * pageSize);

  return (
    <>
      {/* Account KPI Strip */}
      <div className="gc-stat-grid">
        <div className="gc-stat">
          <div className="gc-stat-label">账户权益 / 余额</div>
          <strong>
            {numeric(account.equity ?? account.balance)}{' '}
            <small>{account.currency}</small>
          </strong>
          <div className="gc-stat-foot">
            余额 {numeric(account.balance)} · {account.state}
          </div>
        </div>
        <div className="gc-stat">
          <div className="gc-stat-label">冻结保证金与预留</div>
          <strong>
            {numeric(account.frozenMargin)}{' '}
            <small>{account.currency}</small>
          </strong>
          <div className="gc-stat-foot">可用资金 {numeric(account.available)}</div>
        </div>
        <div className="gc-stat">
          <div className="gc-stat-label">未实现浮动盈亏</div>
          <strong
            className={
              account.unrealized?.startsWith('-')
                ? 'gc-negative'
                : Number(account.unrealized) > 0
                ? 'gc-positive'
                : ''
            }
          >
            {numeric(account.unrealized)}{' '}
            <small>{account.currency}</small>
          </strong>
          <div className="gc-stat-foot">按盘口最新买卖价估值</div>
        </div>
        <div className="gc-stat">
          <div className="gc-stat-label">预计退出手续费</div>
          <strong>
            {numeric(account.estimatedExitFees)}{' '}
            <small>{account.currency}</small>
          </strong>
          <div className="gc-stat-foot">Taker 费率保守测算</div>
        </div>
      </div>

      {/* Orders Console */}
      <section className="gc-panel">
        <div className="gc-panel-head">
          <div>
            <h3>内部模拟订单与持仓</h3>
            <span className="gc-small">
              {cfd
                ? 'CFD 独立 USD 账户 · 按手数计算成交情景与手续费'
                : '永续合约按后续盘口撮合 · 模拟真实 Taker 费率与资金费'}
            </span>
          </div>
          <div className="gc-title-actions">
            <div className="gc-segment" role="tablist">
              <button
                type="button"
                className={filter === 'ALL' ? 'active' : ''}
                onClick={() => {
                  setFilter('ALL');
                  setPage(1);
                }}
              >
                全部 ({account.orders.length})
              </button>
              <button
                type="button"
                className={filter === 'OPEN' ? 'active' : ''}
                onClick={() => {
                  setFilter('OPEN');
                  setPage(1);
                }}
              >
                持仓中 ({account.orders.filter((o) => o.positionStatus === 'OPEN').length})
              </button>
              <button
                type="button"
                className={filter === 'PENDING' ? 'active' : ''}
                onClick={() => {
                  setFilter('PENDING');
                  setPage(1);
                }}
              >
                挂单等待 (
                {
                  account.orders.filter((o) =>
                    ['PENDING', 'PARTIALLY_FILLED'].includes(o.status)
                  ).length
                }
                )
              </button>
              <button
                type="button"
                className={filter === 'CLOSED' ? 'active' : ''}
                onClick={() => {
                  setFilter('CLOSED');
                  setPage(1);
                }}
              >
                已平仓 (
                {
                  account.orders.filter((o) =>
                    ['CLOSED', 'LIQUIDATED'].includes(o.positionStatus)
                  ).length
                }
                )
              </button>
            </div>
            <a
              className="gc-live-button"
              href={`/api/simulation/export?symbol=${account.symbol}`}
              download
            >
              导出账本 CSV
            </a>
          </div>
        </div>

        {error && (
          <p className="gc-live-error" role="alert">
            {error}
          </p>
        )}

        <div className="gc-live-table-wrap">
          <table className="gc-live-table">
            <thead>
              <tr>
                <th>标的 / 方向</th>
                <th>订单状态</th>
                <th>持仓状态</th>
                <th>{cfd ? '手数' : '数量 (XAU)'} / 成交</th>
                <th>杠杆</th>
                <th>均价</th>
                <th>已记账净值</th>
                <th>成本与数据</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              {pagedOrders.map((o) => (
                <tr key={o.id}>
                  <td>
                    <button
                      type="button"
                      className="gc-text-button"
                      onClick={() => setSelected(o.id)}
                    >
                      <span
                        className={`gc-badge ${
                          o.side === 'BUY' ? 'gc-positive' : 'gc-negative'
                        }`}
                        style={{ marginRight: '6px' }}
                      >
                        {o.side === 'BUY' ? '做多' : '做空'}
                      </span>
                      {o.type}
                    </button>
                  </td>
                  <td>
                    <span className="gc-badge">{o.status}</span>
                  </td>
                  <td>
                    <span
                      className={`gc-badge ${
                        o.positionStatus === 'OPEN'
                          ? 'gc-gold-badge'
                          : o.positionStatus === 'CLOSED'
                          ? ''
                          : 'gc-negative'
                      }`}
                    >
                      {o.positionStatus}
                    </span>
                  </td>
                  <td>
                    <strong>{numeric(o.quantity)}</strong> / {numeric(o.filled)}
                  </td>
                  <td>{o.leverage}x</td>
                  <td>{numeric(o.averageEntry)}</td>
                  <td>
                    <strong
                      className={
                        o.netRealized?.startsWith('-')
                          ? 'gc-negative'
                          : Number(o.netRealized) > 0
                          ? 'gc-positive'
                          : ''
                      }
                    >
                      {numeric(o.netRealized)}
                    </strong>
                  </td>
                  <td>
                    <span className="gc-small">
                      {o.costIncomplete ? '成本待确认' : '标准假设'}
                      {o.quoteGap ? ' · 盘口缺口' : ''}
                    </span>
                  </td>
                  <td>
                    <div style={{ display: 'flex', gap: '6px' }}>
                      <button
                        type="button"
                        className="gc-live-button"
                        disabled={
                          busy || !['PENDING', 'PARTIALLY_FILLED'].includes(o.status)
                        }
                        onClick={() => void command(o, 'CANCEL')}
                      >
                        撤单
                      </button>
                      <button
                        type="button"
                        className="gc-live-button"
                        disabled={busy || o.openQuantity === '0'}
                        onClick={() => void command(o, 'CLOSE')}
                      >
                        平仓
                      </button>
                      <button
                        type="button"
                        className={`gc-live-button ${selected === o.id ? 'gc-live-primary' : ''}`}
                        onClick={() => setSelected(selected === o.id ? '' : o.id)}
                      >
                        {selected === o.id ? '收起' : '风控管理'}
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        {!filteredOrders.length && (
          <div className="gc-live-empty">
            <span>◇</span>
            <h3>当前筛选下暂无订单记录</h3>
            <p>有效裁决产生内部交易计划后，将按报价机制撮合并在此跟踪。</p>
          </div>
        )}

        {/* Pagination */}
        <Pagination
          currentPage={page}
          totalItems={filteredOrders.length}
          pageSize={pageSize}
          onPageChange={setPage}
        />
      </section>

      {/* Selected Order Management Console */}
      {order && (
        <section
          className="gc-panel"
          style={{
            borderLeft: '4px solid var(--gc-gold)',
            boxShadow: 'var(--gc-shadow-md)',
          }}
        >
          <div className="gc-panel-head">
            <div>
              <h3>
                订单风控与状态管理 · #{order.id.slice(-8)} (v{order.version})
              </h3>
              <span className="gc-small">
                {order.side === 'BUY' ? '做多' : '做空'} · {order.quantity}{' '}
                {cfd ? '手' : 'XAU'} · 入场均价 {numeric(order.averageEntry)}
              </span>
            </div>
            <div className="gc-title-actions">
              <div className="gc-segment" role="tablist">
                <button
                  type="button"
                  className={orderTab === 'rules' ? 'active' : ''}
                  onClick={() => setOrderTab('rules')}
                >
                  退出规则与减仓
                </button>
                <button
                  type="button"
                  className={orderTab === 'risk' ? 'active' : ''}
                  onClick={() => setOrderTab('risk')}
                >
                  AI 风险与失效条件
                </button>
                <button
                  type="button"
                  className={orderTab === 'events' ? 'active' : ''}
                  onClick={() => setOrderTab('events')}
                >
                  生命周期审计 ({audit.length})
                </button>
              </div>
              <button
                type="button"
                className="gc-live-button"
                onClick={() => setSelected('')}
              >
                ✕ 关闭面板
              </button>
            </div>
          </div>

          {orderTab === 'rules' && (
            <>
              <div className="gc-form-grid">
                <label>
                  止损价 (Stop Loss)
                  <input
                    aria-label="止损价"
                    inputMode="decimal"
                    value={stop}
                    onChange={(e) => setStop(e.target.value)}
                    placeholder="留空清除"
                  />
                </label>
                <label>
                  止盈价 (Take Profit)
                  <input
                    aria-label="止盈价"
                    inputMode="decimal"
                    value={target}
                    onChange={(e) => setTarget(e.target.value)}
                    placeholder="留空清除"
                  />
                </label>
                <label>
                  追踪距离 (Trailing Distance)
                  <input
                    aria-label="追踪距离"
                    inputMode="decimal"
                    value={distance}
                    onChange={(e) => setDistance(e.target.value)}
                    placeholder="留空清除"
                  />
                </label>
                <label>
                  追踪激活价 (Trailing Activation)
                  <input
                    aria-label="追踪激活价"
                    inputMode="decimal"
                    value={activation}
                    onChange={(e) => setActivation(e.target.value)}
                    placeholder="留空清除"
                  />
                </label>
              </div>

              <div
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: '12px',
                  flexWrap: 'wrap',
                  margin: '16px 0',
                }}
              >
                <button
                  type="button"
                  className="gc-live-button gc-live-primary"
                  disabled={busy || !!order.closedAt || order.status === 'REJECTED'}
                  onClick={() =>
                    void command(order, 'MODIFY', {
                      stopLoss: stop || null,
                      takeProfit: target || null,
                      trailingDistance: distance || null,
                      trailingActivation: activation || null,
                    })
                  }
                >
                  保存并更新退出规则
                </button>

                <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                  <input
                    aria-label="减仓数量"
                    inputMode="decimal"
                    placeholder={`减仓数量 (${cfd ? '手' : 'XAU'})`}
                    value={reduce}
                    onChange={(e) => setReduce(e.target.value)}
                    style={{ width: '160px' }}
                  />
                  <button
                    type="button"
                    className="gc-live-button"
                    disabled={busy || !reduce || order.openQuantity === '0'}
                    onClick={() => void command(order, 'REDUCE', { quantity: reduce })}
                  >
                    内部减仓
                  </button>
                </div>
              </div>

              <div className="gc-secondary-grid" style={{ marginTop: '16px' }}>
                <div>
                  <div className="gc-factor">
                    <span>有效截止 / 持有至</span>
                    <b>
                      {stamp(order.expiresAt)} / {stamp(order.holdUntil)}
                    </b>
                  </div>
                  <div className="gc-factor">
                    <span>剩余持仓数量 / 追踪锚点</span>
                    <b>
                      {order.openQuantity} / {numeric(order.trailingAnchor)}
                    </b>
                  </div>
                </div>
                <div>
                  <div className="gc-factor">
                    <span>开仓 / 平仓费用</span>
                    <b>
                      {order.entryFees} / {order.exitFees}
                    </b>
                  </div>
                  <div className="gc-factor">
                    <span>资金费 / 掉期支出</span>
                    <b>
                      {order.fundingFees} / {order.swapFees ?? '0'}
                    </b>
                  </div>
                </div>
              </div>
            </>
          )}

          {orderTab === 'risk' && (
            <div style={{ display: 'grid', gap: '14px' }}>
              <div>
                <h4 style={{ margin: '0 0 6px' }}>模型事前风险评估</h4>
                <p className="gc-live-prose" style={{ margin: 0 }}>
                  {order.riskAssessment || '无特定风险备注'}
                </p>
              </div>
              <div>
                <h4 style={{ margin: '0 0 6px', color: 'var(--gc-gold)' }}>
                  失效条件 (Invalidation)
                </h4>
                <p className="gc-live-prose" style={{ margin: 0 }}>
                  {order.invalidate || '无明确失效条件'}
                </p>
              </div>
              {order.rejection && (
                <p className="gc-live-error">拒绝原因：{order.rejection}</p>
              )}
            </div>
          )}

          {orderTab === 'events' && (
            <div>
              <div className="gc-live-table-wrap">
                <table className="gc-live-table">
                  <thead>
                    <tr>
                      <th>序号</th>
                      <th>事件类型</th>
                      <th>发生时刻</th>
                      <th>事件详情</th>
                    </tr>
                  </thead>
                  <tbody>
                    {audit.map((evt) => (
                      <tr key={evt.sequence}>
                        <td>#{evt.sequence}</td>
                        <td>
                          <span className="gc-badge gc-gold-badge">{evt.type}</span>
                        </td>
                        <td>{stamp(evt.occurredAt)}</td>
                        <td>
                          <code style={{ fontSize: '11px' }}>
                            {JSON.stringify(evt.payload)}
                          </code>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              {!audit.length && (
                <p className="gc-small" style={{ padding: '12px' }}>
                  暂无审计事件记录。
                </p>
              )}
            </div>
          )}
        </section>
      )}
    </>
  );
}
