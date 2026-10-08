import { useState } from 'react';
import type { Dashboard, IndicatorReading } from './api';

const frames = [['1', '1分钟'], ['5', '5分钟'], ['15', '15分钟'], ['60', '1小时'], ['240', '4小时'], ['D', '1天']] as const;
const freshness: Record<string, string> = { CURRENT: '数据及时', REFRESH_DELAYED: '刷新延迟', BAR_GAP: '收盘数据有缺口', MARKET_CLOSED: '休市参考', NO_HISTORY: '等待历史数据' };
const timestamp = (value?: string | null) => value ? new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false, month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit' }) : '未知';
const tone = (reading?: IndicatorReading) => ['UPTREND', 'POSITIVE'].includes(reading?.code ?? '') ? 'gc-positive' : ['DOWNTREND', 'NEGATIVE'].includes(reading?.code ?? '') ? 'gc-negative' : '';
function decimal(value?: string | null, digits = 4) {
  if (value === undefined || value === null) return '—';
  const [whole, fraction] = value.split('.');
  return whole.replace(/\B(?=(\d{3})+(?!\d))/g, ',') + (fraction ? '.' + fraction.slice(0, digits) : '');
}
const definitions: Record<string, [string, string]> = {
  close: ['已收盘价格', '当前周期最后一根已收盘K线的收盘价'], changePct: ['单根变化 %', '最近两根收盘价之间的百分比变化'],
  sma20: ['SMA20', '最近20根收盘价的算术平均'], ema20: ['EMA20', '以首20根SMA为种子；α=2/21'], ema60: ['EMA60', '以首60根SMA为种子；α=2/61'],
  rsi14: ['RSI14', 'Wilder平滑涨跌幅；平价为50，取值0–100'], atr14: ['ATR14', 'Wilder平滑真实波幅；与价格单位相同'], atrPct: ['ATR / 收盘价 %', 'ATR14除以当前周期收盘价，再乘100'],
  macd: ['MACD线', 'EMA12减EMA26'], macdSignal: ['MACD信号线', 'MACD线的9周期EMA'], macdHistogram: ['MACD柱', 'MACD线减信号线，使用未乘2的口径'],
  bbMiddle: ['布林带中轨', '最近20根收盘价的SMA'], bbUpper: ['布林带上轨', 'SMA20加2倍总体标准差'], bbLower: ['布林带下轨', 'SMA20减2倍总体标准差'],
  bbWidthPct: ['布林带宽 %', '上下轨之差除以中轨，再乘100'], bbPercentB: ['布林 %B', '(收盘价−下轨)/(上轨−下轨)，通常0–1；零带宽时无定义'],
  ema20DistancePct: ['距EMA20 %', '(收盘价−EMA20)/EMA20×100'], roc12Pct: ['ROC12 %', '当前收盘价相对12根之前收盘价的百分比变化'],
  vwap20: ['滚动VWAP20', '最近20根(H+L+C)/3按成交量加权；窗口VWAP，不是每日重置VWAP'],
  volume: ['最近一根成交量', 'Gate永续原生合约成交张数经Java乘以合约乘数，换算为XAU数量'],
  volumeRatio20: ['成交量 / 20根均量', '最近一根成交量除以最近20根平均成交量，包含当前已收盘K线']
};

export default function IndicatorPanel({ data }: { data: Dashboard }) {
  const [selected, setSelected] = useState('5');
  const all = data.market.indicators, indicator = all[selected], values = indicator?.values ?? {}, readings = indicator?.readings ?? {};
  const priceUnit = data.market.symbol === 'GATE:XAUUSD' ? 'USD / XAU' : 'USDT / XAU';
  const ready = indicator?.state === 'READY';
  const value = (key: string, digits = 4) => <span title={values[key] ?? '此指标暂不可用'}>{decimal(values[key], digits)}</span>;
  const reading = (key: string) => <span className={`gc-metric-reading ${tone(readings[key])}`} title={readings[key]?.detail}>{readings[key]?.label ?? '等待计算'}</span>;
  return <section className="gc-panel gc-metrics" aria-label="产品技术指标">
    <div className="gc-panel-head"><div><h3>产品指标</h3><span className="gc-small">Java 计算 · 六个周期 · 仅使用已收盘 K 线</span></div><span className="gc-badge">{data.marketProvenance.nativeSymbol}</span></div>
    <div className="gc-metric-frames" role="group" aria-label="指标周期">
      {frames.map(([key, name]) => <button key={key} type="button" aria-pressed={selected === key} onClick={() => setSelected(key)}>
        <b>{name}</b><span className={tone(all[key]?.readings?.trend)}>{all[key]?.readings?.trend?.label ?? (all[key] ? '正在预热' : '等待数据')}</span>
      </button>)}
    </div>
    {!indicator ? <div className="gc-live-empty"><h3>当前周期等待历史数据</h3><p>取得本产品的历史 K 线后，由 Java 计算并展示。</p></div> : <>
      <div className="gc-metric-summary">
        <div><span className="gc-small">{frames.find(([key]) => key === selected)?.[1]}已收盘价格</span><strong>{value('close', 2)} <small>{priceUnit}</small></strong><span className={values.changePct?.startsWith('-') ? 'gc-negative' : 'gc-positive'}>单根变化 {value('changePct')}%</span></div>
        <div className="gc-metric-provenance"><span className={`gc-badge ${indicator.freshness === 'CURRENT' ? '' : 'gc-metric-warning'}`}>{freshness[indicator.freshness ?? ''] ?? '新鲜度未记录'}</span><span>{ready ? '全部指标已预热' : '部分指标预热中'} · {indicator.samples} 根样本</span><span>已收盘至 {timestamp(indicator.closedThrough)}</span><span>最近计算 {timestamp(indicator.calculatedAt)}</span></div>
      </div>
      {data.marketProvenance.historyError && <p className="gc-live-error">历史行情刷新失败：{data.marketProvenance.historyError}。下方保留最近一次计算结果，请核对截止时间。</p>}
      <div className="gc-metric-cards">
        <article className="gc-metric-card"><div className="gc-metric-name">01 / 趋势结构</div><h4>EMA 20 / 60</h4>{reading('trend')}<div className="gc-metric-pair"><span>EMA20 <b>{value('ema20')}</b></span><span>EMA60 <b>{value('ema60')}</b></span></div><p>SMA20 {value('sma20')} · 距EMA20 {value('ema20DistancePct')}%</p><small>60根样本起 · {priceUnit}</small></article>
        <article className="gc-metric-card"><div className="gc-metric-name">02 / 相对强弱</div><h4>RSI 14</h4><strong>{value('rsi14', 2)}</strong><div className="gc-rsi-range" role="img" aria-label={`RSI14 ${values.rsi14 ?? '样本不足'}，30与70为参考界线`}><i/>{values.rsi14 != null && <b style={{ left: `${Math.max(0, Math.min(100, Number(values.rsi14)))}%` }}/>}</div><div className="gc-rsi-labels"><span>0</span><span>30</span><span>70</span><span>100</span></div>{reading('rsi')}<small>15根样本起 · Wilder 平滑</small></article>
        <article className="gc-metric-card"><div className="gc-metric-name">03 / 动量差值</div><h4>MACD 12 / 26 / 9</h4><strong className={tone(readings.macd)}>{value('macdHistogram')}</strong>{reading('macd')}<p>线 {value('macd')} · 信号 {value('macdSignal')}</p><small>35根样本起 · 柱值未乘2</small></article>
        <article className="gc-metric-card"><div className="gc-metric-name">04 / 价格动量</div><h4>ROC 12</h4><strong>{value('roc12Pct')}<small>%</small></strong><p>与12根之前的收盘价相比</p><small>13根样本起 · 已收盘变化</small></article>
        <article className="gc-metric-card"><div className="gc-metric-name">05 / 波动幅度</div><h4>ATR 14</h4><strong>{value('atr14')}</strong><p>相对收盘价 {value('atrPct')}%</p><small>15根样本起 · {priceUnit} · Wilder 平滑</small></article>
        <article className="gc-metric-card"><div className="gc-metric-name">06 / 波动区间</div><h4>布林带 20 / 2</h4>{reading('bollinger')}<div className="gc-metric-pair"><span>上轨 <b>{value('bbUpper', 2)}</b></span><span>中轨 <b>{value('bbMiddle', 2)}</b></span><span>下轨 <b>{value('bbLower', 2)}</b></span></div><p>带宽 {value('bbWidthPct')}% · %B {value('bbPercentB')}</p><small>20根样本起 · 总体标准差</small></article>
        <article className="gc-metric-card"><div className="gc-metric-name">07 / 成交重心</div><h4>滚动 VWAP 20</h4><strong>{value('vwap20')}</strong><p>{data.market.symbol === 'GATE:XAUUSD' ? 'CFD源未提供成交量，无法计算' : '最近20根典型价格按成交量加权'}</p><small>20根样本起 · {priceUnit} · 窗口加权</small></article>
        <article className="gc-metric-card"><div className="gc-metric-name">08 / 成交活跃度</div><h4>成交量 / 均量</h4><strong>{value('volumeRatio20', 2)}{values.volumeRatio20 != null && <small> 倍</small>}</strong><p>最近一根 {value('volume', 2)} {data.market.symbol === 'GATE:XAUUSD' ? '' : 'XAU'}</p><small>{data.market.symbol === 'GATE:XAUUSD' ? '源未提供成交量，不补造零值' : '最近20根平均量，包含当前已收盘样本'}</small></article>
      </div>
      <p className="gc-metric-note">结构标签由 Java 根据当前数值生成，决策时仍需结合新闻、成本和 AI 研究。显示值按小数位截短；可展开查看原始精度及口径。</p>
      <details className="gc-metric-details"><summary>指标口径与当前周期原始数值</summary><div className="gc-metric-definitions">{Object.entries(definitions).map(([key, [name, explanation]]) => <div key={key}><b>{name}</b><code>{values[key] ?? (key.startsWith('volume') || key === 'vwap20' ? '缺少有效成交量' : '样本不足或数学值无定义')}</code><span>{explanation}</span></div>)}</div></details>
      <details className="gc-metric-details"><summary>全周期对照</summary><div className="gc-live-table-wrap"><table className="gc-live-table"><thead><tr><th>周期</th><th>结构</th><th>新鲜度 / 收盘至</th><th>EMA20</th><th>EMA60</th><th>RSI14</th><th>ATR%</th><th>MACD柱</th></tr></thead><tbody>{frames.filter(([key]) => all[key]).map(([key, name]) => { const item = all[key]; return <tr key={key}><td>{name}</td><td className={tone(item.readings?.trend)}>{item.readings?.trend?.label ?? '等待计算'}</td><td>{freshness[item.freshness ?? ''] ?? '未知'} · {timestamp(item.closedThrough)}</td>{['ema20', 'ema60', 'rsi14', 'atrPct', 'macdHistogram'].map(metric => <td key={metric} title={item.values[metric] ?? '暂不可用'}>{decimal(item.values[metric])}</td>)}</tr>; })}</tbody></table></div></details>
    </>}
  </section>;
}
