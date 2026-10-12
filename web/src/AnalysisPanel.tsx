import { useState } from 'react';
import type { AnalysisView, Driver, EconomicRelease } from './api';
import { SupplementalPanel } from './SupplementalPanel';
import Pagination from './Pagination';

const direction: Record<string, string> = {
  BULLISH: '偏多结构',
  BEARISH: '偏空结构',
  MIXED: '存在分歧',
  RANGE: '横盘结构',
  UNKNOWN: '信息不足',
};

const time = (v?: string | null) =>
  v
    ? new Date(v).toLocaleString('zh-CN', {
        timeZone: 'Asia/Shanghai',
        hour12: false,
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit',
      })
    : '尚未取得';

const decimal = (v?: string | null) =>
  v == null ? '—' : v.includes('.') ? v.replace(/(\.\d{4})\d+$/, '$1') : v;

const unit = (v: string) =>
  v === 'COUNT'
    ? '人／个'
    : v === 'PERCENT_QOQ_ANNUALIZED_SA'
    ? '% · 年化季环比'
    : v.includes('MOM')
    ? '% · 月环比'
    : v.includes('YOY')
    ? '% · 同比'
    : '%';

const tone = (d: string) =>
  d === 'BULLISH' ? 'gc-positive' : d === 'BEARISH' ? 'gc-negative' : '';

function DriverCard({ driver, at }: { driver: Driver; at: string }) {
  const daily = driver.frequency === 'DAILY';
  const rates = driver.frequency === 'INTRADAY_PUBLIC_WEB';
  const sourceAge = driver.responseAt ? Date.parse(at) - Date.parse(driver.responseAt) : Infinity;
  const recent = rates
    ? sourceAge >= -2000 &&
      sourceAge <= 180000 &&
      Date.parse(at) - Date.parse(driver.receivedAt) <= 180000
    : !daily &&
      driver.marketStatus === 'open' &&
      Date.parse(at) - Date.parse(driver.receivedAt) <= 15000;

  return (
    <article
      className="gc-panel gc-driver-card"
      style={{
        background: 'var(--gc-surface-raised)',
        padding: '16px',
        display: 'flex',
        flexDirection: 'column',
        justifyContent: 'space-between',
      }}
    >
      <div className="gc-panel-head" style={{ marginBottom: '8px' }}>
        <a
          href={driver.url}
          target="_blank"
          rel="noreferrer"
          style={{ fontWeight: 600, fontSize: '13px' }}
        >
          {driver.label} ↗
        </a>
        <span className={`gc-badge ${recent ? 'gc-positive' : ''}`}>
          {daily ? '日频背景' : recent ? (rates ? '近期报价' : '近期检查') : '延迟或休市'}
        </span>
      </div>
      <strong style={{ fontSize: '22px', fontFamily: 'var(--gc-font-mono)', margin: '4px 0' }}>
        {decimal(driver.value)}
        <small>{driver.unit === 'PERCENT' ? '%' : driver.unit === 'INDEX_POINTS' ? ' 点' : ''}</small>
      </strong>
      {daily ? (
        <div className="gc-factor">
          <span>前一统计日</span>
          <b>
            {decimal(driver.previous)}% · {decimal(driver.change)} bp
          </b>
        </div>
      ) : (
        <div style={{ display: 'grid', gap: '4px', margin: '8px 0' }}>
          {[5, 15, 60].map((m) => {
            const value = rates
              ? driver.changesBp?.[String(m)]
              : driver.returnsPct[String(m)];
            return (
              <div className="gc-factor" key={m} style={{ padding: '3px 0' }}>
                <span>约 {m} 分钟变化</span>
                <b>
                  {value == null ? '等待基线' : `${decimal(value)}${rates ? ' bp' : '%'}`}
                </b>
              </div>
            );
          })}
        </div>
      )}
      <p className="gc-small" style={{ margin: '8px 0 0' }}>
        {driver.note}
      </p>
    </article>
  );
}

function ReleaseRow({ release }: { release: EconomicRelease }) {
  return (
    <details
      className="gc-economic-row"
      style={{
        background: 'var(--gc-surface-raised)',
        padding: '12px 16px',
        borderRadius: 'var(--gc-radius-sm)',
        marginBottom: '8px',
        border: '1px solid var(--gc-line)',
      }}
    >
      <summary
        style={{
          cursor: 'pointer',
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          flexWrap: 'wrap',
          gap: '12px',
        }}
      >
        <span>
          <b style={{ fontSize: '13px', color: 'var(--gc-text)' }}>{release.label}</b>
          <small className="gc-small" style={{ marginLeft: '8px' }}>
            {release.period} · {unit(release.unit)}
          </small>
        </span>
        <div style={{ display: 'flex', gap: '16px', fontFamily: 'var(--gc-font-mono)' }}>
          <span>
            <small style={{ color: 'var(--gc-muted)', marginRight: '4px' }}>实际</small>
            <b>{decimal(release.actual)}</b>
          </span>
          <span>
            <small style={{ color: 'var(--gc-muted)', marginRight: '4px' }}>预期</small>
            <b>{decimal(release.forecast)}</b>
          </span>
          <span>
            <small style={{ color: 'var(--gc-muted)', marginRight: '4px' }}>差值</small>
            <b
              className={
                Number(release.surprise) > 0
                  ? 'gc-positive'
                  : Number(release.surprise) < 0
                  ? 'gc-negative'
                  : ''
              }
            >
              {decimal(release.surprise)}
            </b>
          </span>
        </div>
      </summary>
      <div className="gc-economic-detail" style={{ marginTop: '12px', paddingTop: '10px', borderTop: '1px solid var(--gc-line)' }}>
        <p className="gc-live-prose" style={{ margin: '6px 0' }}>
          {release.excerpt}
        </p>
        <div className="gc-factor">
          <span>正式公布 / 取得</span>
          <b>
            {time(release.publishedAt)} / {time(release.extractedAt)}
          </b>
        </div>
        <div className="gc-factor">
          <span>前值</span>
          <b>{decimal(release.previous)}</b>
        </div>
        <p className="gc-small" style={{ marginTop: '8px' }}>
          {release.expectationReceivedAt
            ? `预期取得：${time(release.expectationReceivedAt)} · 来源 ${release.expectationSource}`
            : '缺少发布前匹配的预期值，超预期差值未记录。'}
        </p>
      </div>
    </details>
  );
}

export default function AnalysisPanel({
  analysis,
  compact = false,
}: {
  analysis?: AnalysisView;
  compact?: boolean;
}) {
  const [tab, setTab] = useState('趋势因子');
  const [releasePage, setReleasePage] = useState(1);
  const [expPage, setExpPage] = useState(1);

  if (!analysis)
    return (
      <section className="gc-panel">
        <h3>趋势观察正在初始化</h3>
      </section>
    );

  const releases = analysis.releases;
  const pagedReleases = releases.slice((releasePage - 1) * 6, releasePage * 6);

  const expectations = analysis.expectations;
  const pagedExp = expectations.slice((expPage - 1) * 8, expPage * 8);

  return (
    <div className="gc-driver-board">
      {/* 4 Horizons Summary Cards */}
      <section className="gc-panel" style={{ marginBottom: '16px' }}>
        <div className="gc-panel-head">
          <div>
            <h3>四周期大势初判 (Multi-Horizon Structure)</h3>
            <span className="gc-small">
              Java 规则初判 · 更新时间 {time(analysis.calculatedAt)}
            </span>
          </div>
          <span className="gc-badge gc-gold-badge">规则初判 · 待 AI 复核</span>
        </div>

        <div
          style={{
            display: 'grid',
            gridTemplateColumns: 'repeat(auto-fill, minmax(260px, 1fr))',
            gap: '14px',
          }}
        >
          {analysis.horizons.map((h) => (
            <article
              className="gc-panel"
              key={h.id}
              style={{
                background: 'var(--gc-surface-raised)',
                padding: '16px',
                border: '1px solid var(--gc-line)',
              }}
            >
              <div className="gc-panel-head" style={{ marginBottom: '8px' }}>
                <b>{h.label}</b>
                <span className={`gc-badge ${tone(h.direction)}`}>
                  {direction[h.direction] ?? h.direction}
                </span>
              </div>
              <p className="gc-small" style={{ margin: '0 0 10px' }}>
                {h.scope === 'PRICE_STRUCTURE_ONLY'
                  ? '主要依据价格走势与均线结构'
                  : '价格结构与宏观关联背景综合'}
              </p>
              <div style={{ display: 'grid', gap: '6px' }}>
                {h.support.slice(0, 2).map((s, i) => (
                  <div key={`s${i}`} style={{ fontSize: '11px', color: 'var(--gc-positive)' }}>
                    ✓ {s}
                  </div>
                ))}
                {h.counter.slice(0, 2).map((s, i) => (
                  <div
                    key={`c${i}`}
                    style={{
                      fontSize: '11px',
                      color: s.includes('DOWNTREND') ? 'var(--gc-negative)' : 'var(--gc-muted)',
                    }}
                  >
                    ⚠ {s}
                  </div>
                ))}
              </div>
            </article>
          ))}
        </div>
      </section>

      {!compact && (
        <>
          <div className="gc-tabs" aria-label="因子分析视图">
            {['趋势因子', '经济发布', '预期与覆盖', '补充数据'].map((name) => (
              <button
                key={name}
                type="button"
                aria-pressed={tab === name}
                onClick={() => setTab(name)}
              >
                {name}
              </button>
            ))}
          </div>

          {/* TAB: Drivers */}
          {tab === '趋势因子' && (
            <section className="gc-panel">
              <div
                style={{
                  display: 'grid',
                  gridTemplateColumns: 'repeat(auto-fill, minmax(300px, 1fr))',
                  gap: '14px',
                }}
              >
                {analysis.drivers.map((d) => (
                  <DriverCard key={d.id} driver={d} at={analysis.calculatedAt} />
                ))}
              </div>
              {!analysis.drivers.length && (
                <p className="gc-small" style={{ padding: '16px' }}>
                  等待美元报价和官方收益率资料；已获取的黄金指标继续展示。
                </p>
              )}

              {!!analysis.gaps.length && (
                <div style={{ marginTop: '18px', paddingTop: '14px', borderTop: '1px solid var(--gc-line)' }}>
                  <h4 style={{ margin: '0 0 8px' }}>当前覆盖缺口</h4>
                  <ul className="gc-small" style={{ margin: 0, paddingLeft: '18px' }}>
                    {analysis.gaps.map((g) => (
                      <li key={g}>{g}</li>
                    ))}
                  </ul>
                </div>
              )}
            </section>
          )}

          {/* TAB: Releases */}
          {tab === '经济发布' && (
            <section className="gc-panel">
              <div className="gc-panel-head">
                <div>
                  <h3>官方经济数据发布与预期差</h3>
                  <span className="gc-small">实际值、预期值与修订记录</span>
                </div>
                <span className="gc-badge gc-gold-badge">{releases.length} 项记录</span>
              </div>

              {pagedReleases.map((r) => (
                <ReleaseRow key={r.id} release={r} />
              ))}

              {!releases.length && (
                <p className="gc-small">官方正文取得后，正在核验可解析的数值。</p>
              )}

              <Pagination
                currentPage={releasePage}
                totalItems={releases.length}
                pageSize={6}
                onPageChange={setReleasePage}
              />
            </section>
          )}

          {/* TAB: Expectations */}
          {tab === '预期与覆盖' && (
            <section className="gc-panel">
              <div className="gc-panel-head">
                <div>
                  <h3>发布安排与市场一致预期</h3>
                  <span className="gc-small">近两天与未来一周预期安排</span>
                </div>
                <span className="gc-badge gc-gold-badge">{expectations.length} 项日程</span>
              </div>

              <div style={{ display: 'grid', gap: '8px' }}>
                {pagedExp.map((e) => (
                  <div
                    key={e.id}
                    style={{
                      padding: '10px 14px',
                      background: 'var(--gc-surface-raised)',
                      borderRadius: 'var(--gc-radius-sm)',
                      display: 'flex',
                      justifyContent: 'space-between',
                      alignItems: 'center',
                      flexWrap: 'wrap',
                      gap: '10px',
                    }}
                  >
                    <div>
                      <strong style={{ fontSize: '13px' }}>{e.title}{e.referencePeriod && ` · ${e.referencePeriod}`}</strong>
                      <span className="gc-small" style={{ marginLeft: '10px' }}>
                        {e.impact} · {time(e.scheduledAt)}
                      </span>
                      <small className="gc-small" style={{ display: 'block', marginTop: '6px' }}>{e.source === 'TRADING_ECONOMICS_SURVEY_CONSENSUS' ? 'Trading Economics 调查一致预期' : e.source === 'FOREX_FACTORY_PROVIDER_FORECAST' ? 'Forex Factory 提供方预期' : e.source} · 收到 {time(e.receivedAt)} · v{e.version}</small>
                    </div>
                    <div style={{ display: 'flex', gap: '14px', flexWrap: 'wrap', fontFamily: 'var(--gc-font-mono)' }}>
                      <span>{e.source === 'TRADING_ECONOMICS_SURVEY_CONSENSUS' ? '调查预期' : '提供方预期'} <b>{e.rawForecast || decimal(e.forecast)}</b></span>
                      <span>前值 <b>{e.rawPrevious || decimal(e.previous)}</b></span>
                      {e.rawProviderForecast && <span>TE 自有预测 <b>{e.rawProviderForecast}</b></span>}
                    </div>
                  </div>
                ))}
              </div>

              <Pagination
                currentPage={expPage}
                totalItems={expectations.length}
                pageSize={8}
                onPageChange={setExpPage}
              />
            </section>
          )}

          {/* TAB: Supplemental */}
          {tab === '补充数据' && <SupplementalPanel data={analysis.supplemental} />}
        </>
      )}
    </div>
  );
}
