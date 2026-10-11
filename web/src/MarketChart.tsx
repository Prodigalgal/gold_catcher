import { useEffect, useState } from 'react';
import { call } from './api';
import type { Candle, Market } from './api';

const frames = [
  ['1', '1分钟'],
  ['5', '5分钟'],
  ['15', '15分钟'],
  ['60', '1小时'],
  ['240', '4小时'],
  ['D', '日线'],
];

export default function MarketChart({ market }: { market: Market }) {
  const [frame, setFrame] = useState('5');
  const [history, setHistory] = useState<Candle[]>([]);
  const [error, setError] = useState('');
  const [hover, setHover] = useState<number | null>(null);

  useEffect(() => {
    if (frame === '5') {
      setHistory([]);
      return;
    }
    const controller = new AbortController();
    setHistory([]);
    const load = () =>
      call<{ candles: Candle[] }>(
        `market/candles?symbol=${market.symbol}&frame=${frame}`,
        {},
        undefined,
        controller.signal
      )
        .then((result) => {
          setHistory(result.candles);
          setError('');
        })
        .catch((failure) => {
          if (!controller.signal.aborted) setError(failure.message);
        });
    void load();
    const timer = setInterval(load, 60000);
    return () => {
      controller.abort();
      clearInterval(timer);
    };
  }, [market.symbol, frame]);

  const bars = (frame === '5' ? market.candles : history).slice(-90);
  const selected =
    hover === null ? bars.at(-1) : bars[Math.min(hover, bars.length - 1)];

  // SVG Geometry
  const low = Math.min(...bars.map((b) => Number(b.low)));
  const high = Math.max(...bars.map((b) => Number(b.high)));
  const span = high - low || 1;

  const x = (i: number) => 25 + ((i + 0.5) * 620) / Math.max(1, bars.length);
  const y = (value: string) => 175 - ((Number(value) - low) * 145) / span;
  const width = Math.max(2, (620 / Math.max(1, bars.length)) * 0.7);

  const priceDiff =
    selected && Number(selected.open) > 0
      ? ((Number(selected.close) - Number(selected.open)) / Number(selected.open)) * 100
      : 0;

  return (
    <section className="gc-panel">
      <div className="gc-panel-head">
        <div>
          <h3>
            {market.symbol} · 真实已收盘 K 线
          </h3>
          <span className="gc-small">
            Gate 官方接口 · Java 校验 OHLC 历史柱
          </span>
        </div>
        <div className="gc-title-actions">
          <div className="gc-segment" role="tablist">
            {frames.map(([key, label]) => (
              <button
                key={key}
                type="button"
                className={frame === key ? 'active' : ''}
                onClick={() => {
                  setHover(null);
                  setFrame(key);
                }}
              >
                {label}
              </button>
            ))}
          </div>
          <span className="gc-badge gc-gold-badge">{bars.length} 根样本</span>
        </div>
      </div>

      {bars.length > 1 ? (
        <>
          {/* Real-time OHLC HUD */}
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'space-between',
              padding: '8px 12px',
              background: 'var(--gc-surface-raised)',
              borderRadius: 'var(--gc-radius-sm)',
              marginBottom: '10px',
              fontSize: '12px',
              fontFamily: 'var(--gc-font-mono)',
              border: '1px solid var(--gc-line)',
              flexWrap: 'wrap',
              gap: '8px',
            }}
          >
            <div style={{ display: 'flex', gap: '14px', alignItems: 'center', flexWrap: 'wrap' }}>
              <span>
                开 <strong style={{ color: 'var(--gc-text)' }}>{selected?.open}</strong>
              </span>
              <span>
                高 <strong style={{ color: 'var(--gc-text)' }}>{selected?.high}</strong>
              </span>
              <span>
                低 <strong style={{ color: 'var(--gc-text)' }}>{selected?.low}</strong>
              </span>
              <span>
                收 <strong style={{ color: 'var(--gc-text)' }}>{selected?.close}</strong>
              </span>
              <span
                className={`gc-badge ${priceDiff >= 0 ? 'gc-positive' : 'gc-negative'}`}
              >
                {priceDiff >= 0 ? '+' : ''}
                {priceDiff.toFixed(2)}%
              </span>
            </div>
            <div style={{ color: 'var(--gc-muted)', fontSize: '11px' }}>
              成交量: {selected?.volume ?? '源未提供'} · 结束:{' '}
              {selected
                ? new Date(selected.end).toLocaleTimeString('zh-CN', {
                    timeZone: 'Asia/Shanghai',
                    hour12: false,
                  })
                : '—'}
            </div>
          </div>

          <svg
            className="gc-price-chart gc-live-chart"
            viewBox="0 0 680 215"
            role="img"
            aria-label={`${market.symbol} ${
              frames.find((f) => f[0] === frame)?.[1]
            } 真实K线图`}
            onMouseLeave={() => setHover(null)}
            onMouseMove={(event) => {
              const bounds = event.currentTarget.getBoundingClientRect();
              const cursorX =
                ((event.clientX - bounds.left) * 680) / bounds.width - 25;
              const idx = Math.max(
                0,
                Math.min(
                  bars.length - 1,
                  Math.floor((cursorX * bars.length) / 620)
                )
              );
              setHover(idx);
            }}
          >
            {/* Grid Lines & Price Labels */}
            {[30, 78, 127, 175].map((value, idx) => {
              const priceAtGrid = high - (idx / 3) * span;
              return (
                <g key={value}>
                  <line
                    x1="25"
                    x2="655"
                    y1={value}
                    y2={value}
                    stroke="var(--gc-line)"
                    strokeDasharray="3 3"
                  />
                  <text x="660" y={value + 4} textAnchor="start" fontSize="9">
                    {priceAtGrid.toFixed(1)}
                  </text>
                </g>
              );
            })}

            {/* Candle Bars */}
            {bars.map((bar, i) => {
              const openY = y(bar.open);
              const closeY = y(bar.close);
              const isUp = Number(bar.close) >= Number(bar.open);
              const color = isUp ? 'var(--gc-positive)' : 'var(--gc-negative)';
              const isHovered = hover === i;

              return (
                <g key={bar.start} opacity={hover !== null && !isHovered ? 0.75 : 1}>
                  <line
                    x1={x(i)}
                    x2={x(i)}
                    y1={y(bar.high)}
                    y2={y(bar.low)}
                    stroke={color}
                    strokeWidth={isHovered ? 2 : 1}
                  />
                  <rect
                    x={x(i) - width / 2}
                    y={Math.min(openY, closeY)}
                    width={width}
                    height={Math.max(1.5, Math.abs(closeY - openY))}
                    fill={color}
                    rx="1"
                  />
                </g>
              );
            })}

            {/* Timestamps */}
            <text x="25" y="206">
              {new Date(bars[0].start).toLocaleString('zh-CN', {
                timeZone: 'Asia/Shanghai',
                month: '2-digit',
                day: '2-digit',
                hour: '2-digit',
                minute: '2-digit',
              })}
            </text>
            <text x="655" y="206" textAnchor="end">
              {new Date(bars.at(-1)!.end).toLocaleString('zh-CN', {
                timeZone: 'Asia/Shanghai',
                month: '2-digit',
                day: '2-digit',
                hour: '2-digit',
                minute: '2-digit',
              })}
            </text>
          </svg>
        </>
      ) : (
        <div className="gc-live-empty">
          <span>◇</span>
          <h3>等待本周期已收盘行情</h3>
          <p>{error || market.state}</p>
        </div>
      )}
    </section>
  );
}
