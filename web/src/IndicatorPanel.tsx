import { useState } from 'react';
import type { Dashboard, IndicatorReading } from './api';

const frames = [
  ['1', '1分钟'],
  ['5', '5分钟'],
  ['15', '15分钟'],
  ['60', '1小时'],
  ['240', '4小时'],
  ['D', '1天'],
] as const;

const freshness: Record<string, string> = {
  CURRENT: '数据及时',
  REFRESH_DELAYED: '刷新延迟',
  BAR_GAP: '收盘数据有缺口',
  MARKET_CLOSED: '休市参考',
  NO_HISTORY: '等待历史数据',
};

const timestamp = (value?: string | null) =>
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

const tone = (reading?: IndicatorReading) =>
  ['UPTREND', 'POSITIVE'].includes(reading?.code ?? '')
    ? 'gc-positive'
    : ['DOWNTREND', 'NEGATIVE'].includes(reading?.code ?? '')
    ? 'gc-negative'
    : '';

function decimal(value?: string | null, digits = 4) {
  if (value === undefined || value === null) return '—';
  const [whole, fraction] = value.split('.');
  return (
    whole.replace(/\B(?=(\d{3})+(?!\d))/g, ',') +
    (fraction ? '.' + fraction.slice(0, digits) : '')
  );
}

const definitions: Record<string, [string, string]> = {
  close: ['已收盘价格', '当前周期最后一根已收盘K线的收盘价'],
  changePct: ['单根变化 %', '最近两根收盘价之间的百分比变化'],
  sma20: ['SMA20', '最近20根收盘价的算术平均'],
  ema20: ['EMA20', '以首20根SMA为种子；α=2/21'],
  ema60: ['EMA60', '以首60根SMA为种子；α=2/61'],
  rsi14: ['RSI14', 'Wilder平滑涨跌幅；平价为50，取值0–100'],
  atr14: ['ATR14', 'Wilder平滑真实波幅；与价格单位相同'],
  atrPct: ['ATR / 收盘价 %', 'ATR14除以当前周期收盘价，再乘100'],
  macd: ['MACD线', 'EMA12减EMA26'],
  macdSignal: ['MACD信号线', 'MACD线的9周期EMA'],
  macdHistogram: ['MACD柱', 'MACD线减信号线，使用未乘2的口径'],
  bbMiddle: ['布林带中轨', '最近20根收盘价的SMA'],
  bbUpper: ['布林带上轨', 'SMA20加2倍总体标准差'],
  bbLower: ['布林带下轨', 'SMA20减2倍总体标准差'],
  bbWidthPct: ['布林带宽 %', '上下轨之差除以中轨，再乘100'],
  bbPercentB: ['布林 %B', '(收盘价−下轨)/(上轨−下轨)，通常0–1；零带宽时无定义'],
  ema20DistancePct: ['距EMA20 %', '(收盘价−EMA20)/EMA20×100'],
  roc12Pct: ['ROC12 %', '当前收盘价相对12根之前收盘价的百分比变化'],
  vwap20: ['滚动VWAP20', '最近20根(H+L+C)/3按成交量加权；窗口VWAP，不是每日重置VWAP'],
  volume: ['最近一根成交量', 'Gate永续原生合约成交张数经Java乘以合约乘数，换算为XAU数量'],
  volumeRatio20: ['成交量 / 20根均量', '最近一根成交量除以最近20根平均成交量，包含当前已收盘K线'],
};

export default function IndicatorPanel({ data }: { data: Dashboard }) {
  const [selected, setSelected] = useState('5');
  const [subTab, setSubTab] = useState<'cards' | 'table' | 'definitions'>('cards');

  const all = data.market.indicators;
  const indicator = all[selected];
  const values = indicator?.values ?? {};
  const readings = indicator?.readings ?? {};
  const priceUnit = data.market.symbol === 'GATE:XAUUSD' ? 'USD / XAU' : 'USDT / XAU';
  const ready = indicator?.state === 'READY';

  const value = (key: string, digits = 4) => (
    <span title={values[key] ?? '此指标暂不可用'}>{decimal(values[key], digits)}</span>
  );

  const reading = (key: string) => (
    <span className={`gc-badge ${tone(readings[key])}`} title={readings[key]?.detail}>
      {readings[key]?.label ?? '等待计算'}
    </span>
  );

  return (
    <section className="gc-panel gc-metrics" aria-label="产品技术指标">
      <div className="gc-panel-head">
        <div>
          <h3>
            技术指标矩阵 · {frames.find(([key]) => key === selected)?.[1]}
          </h3>
          <span className="gc-small">
            Java 校验已收盘 K 线 · 六周期动态跟踪
          </span>
        </div>
        <div className="gc-title-actions">
          <div className="gc-segment" role="tablist">
            <button
              type="button"
              className={subTab === 'cards' ? 'active' : ''}
              onClick={() => setSubTab('cards')}
            >
              指标看板
            </button>
            <button
              type="button"
              className={subTab === 'table' ? 'active' : ''}
              onClick={() => setSubTab('table')}
            >
              跨期对照表
            </button>
            <button
              type="button"
              className={subTab === 'definitions' ? 'active' : ''}
              onClick={() => setSubTab('definitions')}
            >
              口径定义
            </button>
          </div>
          <span className="gc-badge gc-gold-badge">{data.marketProvenance.nativeSymbol}</span>
        </div>
      </div>

      {/* Cycle Selector Bar */}
      <div className="gc-metric-frames" role="group" aria-label="指标周期">
        {frames.map(([key, name]) => {
          const item = all[key];
          return (
            <button
              key={key}
              type="button"
              aria-pressed={selected === key}
              onClick={() => setSelected(key)}
            >
              <b>{name}</b>
              <span className={tone(item?.readings?.trend)}>
                {item?.readings?.trend?.label ?? (item ? '预热中' : '等待')}
              </span>
            </button>
          );
        })}
      </div>

      {!indicator ? (
        <div className="gc-live-empty">
          <span>◇</span>
          <h3>当前周期等待历史数据</h3>
          <p>取得本产品的历史 K 线后，由 Java 计算并展示。</p>
        </div>
      ) : (
        <>
          {/* Quick Summary Strip */}
          <div className="gc-metric-summary">
            <div>
              <span className="gc-small">
                {frames.find(([key]) => key === selected)?.[1]} 已收盘价格
              </span>
              <strong>
                {value('close', 2)} <small>{priceUnit}</small>
              </strong>
              <span
                className={`gc-badge ${
                  values.changePct?.startsWith('-') ? 'gc-negative' : 'gc-positive'
                }`}
              >
                单根变化 {value('changePct')}%
              </span>
            </div>
            <div className="gc-metric-provenance">
              <span
                className={`gc-badge ${
                  indicator.freshness === 'CURRENT' ? 'gc-positive' : 'gc-gold-badge'
                }`}
              >
                {freshness[indicator.freshness ?? ''] ?? '新鲜度未记录'}
              </span>
              <span>
                {ready ? '全部已预热' : '部分预热中'} · {indicator.samples} 根样本
              </span>
              <span>收盘至 {timestamp(indicator.closedThrough)}</span>
            </div>
          </div>

          {data.marketProvenance.historyError && (
            <p className="gc-live-error">
              历史行情刷新失败：{data.marketProvenance.historyError}。下方保留最近一次计算结果。
            </p>
          )}

          {/* SubTab 1: Metric Cards */}
          {subTab === 'cards' && (
            <div className="gc-metric-cards">
              <article className="gc-metric-card">
                <div className="gc-metric-name">01 / 趋势排列</div>
                <h4>EMA 20 / 60</h4>
                {reading('trend')}
                <div className="gc-factor" style={{ marginTop: '8px' }}>
                  <span>EMA20</span>
                  <b>{value('ema20')}</b>
                </div>
                <div className="gc-factor">
                  <span>EMA60</span>
                  <b>{value('ema60')}</b>
                </div>
                <div className="gc-factor">
                  <span>距EMA20</span>
                  <b>{value('ema20DistancePct')}%</b>
                </div>
              </article>

              <article className="gc-metric-card">
                <div className="gc-metric-name">02 / 相对强弱</div>
                <h4>RSI 14</h4>
                <strong>{value('rsi14', 2)}</strong>
                <div
                  className="gc-rsi-range"
                  role="img"
                  aria-label={`RSI14 ${values.rsi14 ?? '样本不足'}`}
                >
                  <i />
                  {values.rsi14 != null && (
                    <b
                      style={{
                        left: `${Math.max(0, Math.min(100, Number(values.rsi14)))}%`,
                      }}
                    />
                  )}
                </div>
                <div className="gc-rsi-labels">
                  <span>超卖 30</span>
                  <span>中轴 50</span>
                  <span>超买 70</span>
                </div>
                <div style={{ marginTop: '6px' }}>{reading('rsi')}</div>
              </article>

              <article className="gc-metric-card">
                <div className="gc-metric-name">03 / 动量差值</div>
                <h4>MACD (12, 26, 9)</h4>
                <strong className={tone(readings.macd)}>{value('macdHistogram')}</strong>
                {reading('macd')}
                <div className="gc-factor" style={{ marginTop: '8px' }}>
                  <span>DIF 线</span>
                  <b>{value('macd')}</b>
                </div>
                <div className="gc-factor">
                  <span>DEA 信号</span>
                  <b>{value('macdSignal')}</b>
                </div>
              </article>

              <article className="gc-metric-card">
                <div className="gc-metric-name">04 / 价格动量</div>
                <h4>ROC 12 %</h4>
                <strong>
                  {value('roc12Pct')}
                  <small>%</small>
                </strong>
                <p className="gc-small">相对 12 根 K 线前收盘价变化</p>
                <div className="gc-factor" style={{ marginTop: '12px' }}>
                  <span>计算周期</span>
                  <b>12 根样本</b>
                </div>
              </article>

              <article className="gc-metric-card">
                <div className="gc-metric-name">05 / 真实波幅</div>
                <h4>ATR 14</h4>
                <strong>{value('atr14')}</strong>
                <p className="gc-small">占收盘价比 {value('atrPct')}%</p>
                <div className="gc-factor" style={{ marginTop: '12px' }}>
                  <span>单位</span>
                  <b>{priceUnit}</b>
                </div>
              </article>

              <article className="gc-metric-card">
                <div className="gc-metric-name">06 / 波动区间</div>
                <h4>布林带 (20, 2)</h4>
                {reading('bollinger')}
                <div className="gc-factor" style={{ marginTop: '8px' }}>
                  <span>上轨</span>
                  <b>{value('bbUpper', 2)}</b>
                </div>
                <div className="gc-factor">
                  <span>中轨</span>
                  <b>{value('bbMiddle', 2)}</b>
                </div>
                <div className="gc-factor">
                  <span>下轨</span>
                  <b>{value('bbLower', 2)}</b>
                </div>
                <div className="gc-factor">
                  <span>带宽 / %B</span>
                  <b>
                    {value('bbWidthPct')}% / {value('bbPercentB')}
                  </b>
                </div>
              </article>

              <article className="gc-metric-card">
                <div className="gc-metric-name">07 / 成交重心</div>
                <h4>滚动 VWAP 20</h4>
                <strong>{value('vwap20')}</strong>
                <p className="gc-small">
                  {data.market.symbol === 'GATE:XAUUSD'
                    ? 'CFD 无成交量数据'
                    : '20根典型价格成交量加权'}
                </p>
                <div className="gc-factor" style={{ marginTop: '12px' }}>
                  <span>加权窗口</span>
                  <b>20 根 K 线</b>
                </div>
              </article>

              <article className="gc-metric-card">
                <div className="gc-metric-name">08 / 成交活跃度</div>
                <h4>量比 / 20均量</h4>
                <strong>
                  {value('volumeRatio20', 2)}
                  {values.volumeRatio20 != null && <small> 倍</small>}
                </strong>
                <p className="gc-small">
                  最新一根量：{value('volume', 2)}{' '}
                  {data.market.symbol === 'GATE:XAUUSD' ? '' : 'XAU'}
                </p>
                <div className="gc-factor" style={{ marginTop: '12px' }}>
                  <span>参考基准</span>
                  <b>20 根均量</b>
                </div>
              </article>
            </div>
          )}

          {/* SubTab 2: Cross-frame comparison table */}
          {subTab === 'table' && (
            <div className="gc-live-table-wrap">
              <table className="gc-live-table">
                <thead>
                  <tr>
                    <th>周期</th>
                    <th>趋势结构</th>
                    <th>新鲜度 / 收盘截止</th>
                    <th>EMA20</th>
                    <th>EMA60</th>
                    <th>RSI14</th>
                    <th>ATR%</th>
                    <th>MACD柱</th>
                  </tr>
                </thead>
                <tbody>
                  {frames
                    .filter(([key]) => all[key])
                    .map(([key, name]) => {
                      const item = all[key];
                      return (
                        <tr key={key}>
                          <td>
                            <strong>{name}</strong>
                          </td>
                          <td>
                            <span className={`gc-badge ${tone(item.readings?.trend)}`}>
                              {item.readings?.trend?.label ?? '计算中'}
                            </span>
                          </td>
                          <td>
                            <span className="gc-small">
                              {freshness[item.freshness ?? ''] ?? '未知'} ·{' '}
                              {timestamp(item.closedThrough)}
                            </span>
                          </td>
                          {['ema20', 'ema60', 'rsi14', 'atrPct', 'macdHistogram'].map(
                            (metric) => (
                              <td key={metric} title={item.values[metric] ?? '暂不可用'}>
                                {decimal(item.values[metric])}
                              </td>
                            )
                          )}
                        </tr>
                      );
                    })}
                </tbody>
              </table>
            </div>
          )}

          {/* SubTab 3: Definitions */}
          {subTab === 'definitions' && (
            <div className="gc-live-table-wrap" style={{ padding: '12px' }}>
              <div
                style={{
                  display: 'grid',
                  gridTemplateColumns: 'repeat(auto-fill, minmax(280px, 1fr))',
                  gap: '12px',
                }}
              >
                {Object.entries(definitions).map(([key, [name, explanation]]) => (
                  <div
                    key={key}
                    style={{
                      padding: '10px 12px',
                      background: 'var(--gc-surface-raised)',
                      borderRadius: 'var(--gc-radius-sm)',
                      border: '1px solid var(--gc-line)',
                    }}
                  >
                    <div
                      style={{
                        display: 'flex',
                        justifyContent: 'space-between',
                        alignItems: 'baseline',
                        marginBottom: '4px',
                      }}
                    >
                      <strong style={{ color: 'var(--gc-gold)' }}>{name}</strong>
                      <code style={{ fontSize: '11px', color: 'var(--gc-muted)' }}>
                        {decimal(values[key])}
                      </code>
                    </div>
                    <p className="gc-small" style={{ margin: 0 }}>
                      {explanation}
                    </p>
                  </div>
                ))}
              </div>
            </div>
          )}
        </>
      )}
    </section>
  );
}
