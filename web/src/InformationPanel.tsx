import { useEffect, useState } from 'react';
import { call } from './api';
import type { CalendarEvent, Dashboard, Direction, News, NewsCompression, NewsDetail, Source } from './api';
import Pagination from './Pagination';
import AnalysisPanel from './AnalysisPanel';
import SourceReliabilityPanel from './SourceReliabilityPanel';
import './NewsCompression.css';

const names: Record<string, string> = {
  CNBC_TOP: 'CNBC 财经头条',
  CNBC_ECONOMY: 'CNBC 经济新闻',
  CNBC_WORLD: 'CNBC 全球新闻',
  FED_PRESS: '美联储公告',
  FED_SPEECH: '美联储讲话',
  ECB_PRESS: '欧洲央行',
  BEA_NEWS: 'BEA 当前发布',
  BEA_CALENDAR: 'BEA 经济日历',
  BLS_CPI: 'BLS 消费者物价',
  BLS_EMPLOYMENT: 'BLS 就业报告',
  BLS_PPI: 'BLS 生产者物价',
  BLS_JOLTS: 'BLS 职位空缺',
  BLS_CALENDAR: 'BLS 经济日历',
  FOMC_CALENDAR: 'FOMC 会议日期',
};

const states: Record<string, string> = {
  PROVIDER_REPORTED_TIME: '出版方报告时间',
  PUBLIC_FEED_SUMMARY: '公开 RSS 摘要',
  CURRENT: '采集成功',
  FETCH_FAILED: '获取失败',
  DISABLED: '未启用',
  AVAILABLE: '正文已取得',
  TRUNCATED: '正文已截取',
  PENDING: '等待正文',
  WAITING_PROXY: '等待代理就绪',
  BACKGROUND_FEED_ONLY: '历史来源摘要',
  CONTENT_INVALID: '响应内容无法使用',
  CACHED_AFTER_FAILURE: '正文暂用缓存',
  HEADLINES_ONLY: '仅标题或摘要',
  TEXT_AVAILABLE: '正文已取得',
  PARTIAL_TEXT: '部分正文已取得',
  SCHEDULE_ONLY: '日程资料',
  VERIFIED: '发布时间已核验',
  PARTIAL: '部分时间未核验',
  UNKNOWN: '发布时间未知',
  RECENT_FETCH: '检查及时',
  DELAYED: '检查延迟',
  UNVERIFIED: '尚未核验',
  RECENT: '近两天发布',
  BACKGROUND: '背景资料',
  TIME_UNKNOWN: '时间未知',
  TIME_UNVERIFIED: '公布时间待核验',
  FEED_TIMESTAMP: 'Feed 时间，待核验',
  DECLARED_RELEASE_TIME: '正文声明的公布时间',
  EXACT: '官方明确的时间',
  QUARANTINED: '旧源隔离',
  FUTURE: '发布时间在未来',
  READY: '代理核心就绪',
  VALIDATING: '筛选节点中',
  PROCESS_EXITED: '代理进程退出',
  DEGRADED: '连接异常，等待切换',
  ACCESSIBLE: '目标可访问',
  TARGET_ACCESS_DENIED: '目标站拒绝访问',
  TARGET_HTTP_ERROR: '目标站 HTTP 错误',
  NETWORK_FAILED: '节点连接失败',
};

const directions: Record<Direction, string> = {
  BULLISH: '支持上涨',
  BEARISH: '形成压力',
  MIXED: '多空并存',
  UNKNOWN: '尚无判断',
};

const text = (state?: string | null) => (state ? states[state] ?? state : '尚未核验');
const compressionStates: Record<string,string> = { EXTRACTED: '摘要已生成', RUNNING: '正在压缩', QUEUED: '等待压缩', PENDING: '等待压缩', PARTIAL: '部分文本已压缩', FAILED: '压缩失败，等待重试', DEFERRED: '等待模型空闲', DISABLED: '压缩已停用', STALE_CONFIGURATION: '已有摘要，当前模型待处理', NO_SOURCE_TEXT: '等待可用来源文本', UNSUITABLE_SOURCE: '此格式暂不压缩', NEWS_NOT_FOUND: '消息已不可用' };
function SummaryPreview({ item }: { item?: NewsCompression }) {
  return <div className="gc-news-summary">
    <div className="gc-news-summary-label"><b>压缩摘要</b><span>{item ? compressionStates[item.state] ?? item.state : '读取摘要中…'}</span></div>
    {item?.excerpt && <p className="gc-live-prose">{item.excerpt}</p>}
    {item && <small>{item.provider} · {item.model}{item.completedAt && ` · ${time(item.completedAt)}`}{!item.configurationCurrent && ' · 较早压缩配置'}</small>}
    {item?.error && <small className="gc-live-error">{item.error}{item.ready && ' · 保留上次完整摘要'}</small>}
    {item?.state === 'PARTIAL' && <small>分块 {item.processedChunks}/{item.totalChunks}，完整摘要完成后供下游使用</small>}
  </div>;
}
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

function DirectionBadge({ direction = 'UNKNOWN' }: { direction?: Direction }) {
  return (
    <span
      className={`gc-badge ${
        direction === 'BULLISH'
          ? 'gc-positive'
          : direction === 'BEARISH'
          ? 'gc-negative'
          : direction === 'MIXED'
          ? 'gc-gold-badge'
          : ''
      }`}
    >
      {directions[direction]}
    </span>
  );
}

function Empty({ title, description }: { title: string; description: string }) {
  return (
    <div className="gc-live-empty">
      <span>◇</span>
      <h3>{title}</h3>
      <p>{description}</p>
    </div>
  );
}

function eventTime(event: CalendarEvent) {
  if (event.scheduledAt) return time(event.scheduledAt);
  return `${event.scheduledDate ?? '日期未知'}${
    event.endDate && event.endDate !== event.scheduledDate ? ` 至 ${event.endDate}` : ''
  } · 具体时刻待公布`;
}

export function SourceCard({ source }: { source: Source }) {
  const quality = source.quality;
  return (
    <article className="gc-source-card">
      <div className="gc-panel-head">
        <a href={source.url} target="_blank" rel="noreferrer">
          {names[source.id] ?? source.id} ↗
        </a>
        <span className={`gc-badge ${source.error ? 'gc-negative' : 'gc-positive'}`}>
          {text(source.state)}
        </span>
      </div>
      <div className="gc-source-quality">
        <span>
          连接<b>{source.error ?? text(quality?.connection)}</b>
        </span>
        <span>
          内容<b>{text(quality?.content)}</b>
        </span>
        <span>
          时间<b>{text(quality?.publication)}</b>
        </span>
      </div>
      <p className="gc-small">
        当前视图 {quality?.records ?? '—'} 条 · 正文 {quality?.bodies ?? '—'} 条 ·
        隔离/未核验 {quality?.excludedRecords ?? '—'} 条
      </p>
      <div className="gc-source-times">
        <span>最近检查 {time(source.lastSuccess)}</span>
        <span>最近发布 {time(quality?.lastPublishedAt)}</span>
        <span>下次检查 {time(source.nextPoll)}</span>
        <span>
          {source.intervalSeconds}s ·{' '}
          {source.route === 'CF_PROXY'
            ? 'CF 代理'
            : source.route === 'DIRECT_WITH_CF_FALLBACK'
            ? '直接 · CF 备用'
            : '直连'}
        </span>
      </div>
      {quality?.note && <p className="gc-small gc-source-note">{quality.note}</p>}
    </article>
  );
}

export function SourceHealth({ data }: { data: Dashboard }) {
  const [page, setPage] = useState(1);
  const pageSize = 8;
  const sources = data.sources;
  const pagedSources = sources.slice((page - 1) * pageSize, page * pageSize);

  return (
    <section className="gc-panel">
      <div className="gc-panel-head">
        <div>
          <h3>信源覆盖与内容质量</h3>
          <span className="gc-small">
            连接、正文与时间分别核验 · 共 {sources.length} 个采集通道
          </span>
        </div>
        <span className="gc-badge gc-gold-badge">{sources.length} 个通道</span>
      </div>
      <div className="gc-source-grid">
        {pagedSources.map((source) => (
          <SourceCard key={source.id} source={source} />
        ))}
      </div>
      <Pagination
        currentPage={page}
        totalItems={sources.length}
        pageSize={pageSize}
        onPageChange={setPage}
      />
    </section>
  );
}

export function ProxyHealth({ data }: { data: Dashboard }) {
  const proxy = data.sourceProxy;
  if (!proxy) return null;
  return (
    <section className="gc-panel">
      <div className="gc-panel-head">
        <h3>信源代理池节点</h3>
        <span className={`gc-badge ${proxy.state === 'READY' ? 'gc-positive' : 'gc-gold-badge'}`}>
          {text(proxy.state)}
        </span>
      </div>
      <div className="gc-factor">
        <span>订阅配置</span>
        <b>
          {proxy.configured ? '已配置订阅' : '未配置'} · {proxy.enabled ? '已启用' : '未启用'}
        </b>
      </div>
      <div className="gc-factor">
        <span>节点筛选</span>
        <b>
          {proxy.candidates} 个候选 · 检查 {proxy.tested} · 未通过 {proxy.rejected}
        </b>
      </div>
      <div className="gc-factor">
        <span>最近验证</span>
        <b>{time(proxy.lastVerified)}</b>
      </div>
      {proxy.targets.map((target) => (
        <div className="gc-factor" key={target.source}>
          <span>{names[target.source] ?? target.source}</span>
          <b>
            {text(target.state)} · {target.route === 'CF_PROXY' ? 'CF 代理' : '直接连接'}
            {target.status ? ` · HTTP ${target.status}` : ''}
          </b>
        </div>
      ))}
      {proxy.error && <p className="gc-live-error">{proxy.error}</p>}
    </section>
  );
}

export default function InformationPanel({ data }: { data: Dashboard }) {
  const [mainTab, setMainTab] = useState<'news' | 'drivers' | 'calendar' | 'health'>('news');
  const [query, setQuery] = useState('');
  const [filter, setFilter] = useState('ALL');
  const [scope, setScope] = useState('RECENT');
  const [horizon, setHorizon] = useState('SHORT');

  const [newsPage, setNewsPage] = useState(1);
  const newsPageSize = 10;

  const [calPage, setCalPage] = useState(1);
  const calPageSize = 8;

  const [selected, setSelected] = useState<News | null>(null);
  const [detail, setDetail] = useState<NewsDetail | null>(null);
  const [detailError, setDetailError] = useState('');
  const [summaries, setSummaries] = useState<Record<string,NewsCompression>>({});
  const [summaryError, setSummaryError] = useState('');
  const [contentTab, setContentTab] = useState<'summary' | 'original'>('summary');

  useEffect(() => {
    setDetail(null);
    setDetailError('');
    setContentTab('summary');
    if (!selected) return;
    const controller = new AbortController();
    call<NewsDetail>(`news/${encodeURIComponent(selected.id)}`, {}, undefined, controller.signal)
      .then(setDetail)
      .catch((error) => {
        if (!controller.signal.aborted)
          setDetailError(error instanceof Error ? error.message : '正文读取失败');
      });
    return () => controller.abort();
  }, [selected?.id]);

  useEffect(() => {
    setNewsPage(1);
  }, [scope, query, filter, horizon]);

  const run = data.research;
  const impacts = run?.verdict?.impacts ?? [];
  const impact = (article: News) =>
    impacts.find((item) => item.newsId === article.id && item.horizon === horizon);

  const isRecent = (article: News) =>
    article.freshness === 'RECENT' ||
    (!article.freshness &&
      !!article.publishedAt &&
      new Date(article.publishedAt).getTime() >
        new Date(data.serverTime).getTime() - 172800000);

  const filteredNews = data.news
    .filter((article) =>
      scope === 'RECENT'
        ? isRecent(article)
        : scope === 'BACKGROUND'
        ? !isRecent(article) &&
          !!article.publishedAt &&
          !article.archived &&
          article.freshness !== 'FUTURE' &&
          article.freshness !== 'TIME_UNVERIFIED'
        : article.archived ||
          !article.publishedAt ||
          article.freshness === 'FUTURE' ||
          article.freshness === 'TIME_UNVERIFIED'
    )
    .filter((article) =>
      (article.title + article.excerpt + article.source)
        .toLowerCase()
        .includes(query.toLowerCase())
    )
    .filter((article) => !impacts.some(item=>item.newsId) || filter === 'ALL' || (impact(article)?.direction ?? 'UNKNOWN') === filter);

  const pagedNews = filteredNews.slice(
    (newsPage - 1) * newsPageSize,
    newsPage * newsPageSize
  );
  const summaryIds = Array.from(new Set([...pagedNews.map(article => article.id), ...(selected ? [selected.id] : [])])).join(',');
  useEffect(() => {
    if (!summaryIds || mainTab !== 'news') return;
    const controller = new AbortController();
    let fetching = false;
    const load = async () => { if (fetching || controller.signal.aborted) return; fetching = true;
      try { const rows=await call<NewsCompression[]>(`compression/news?ids=${encodeURIComponent(summaryIds)}`, {}, undefined, controller.signal); if (!controller.signal.aborted) { setSummaries(Object.fromEntries(rows.map(row => [row.newsId,row]))); setSummaryError(''); } }
      catch (error) { if (!controller.signal.aborted) setSummaryError(error instanceof Error ? error.message : '摘要读取失败'); }
      finally { fetching = false; }
    };
    void load(); const timer=window.setInterval(()=>void load(),5000);
    return () => { window.clearInterval(timer); controller.abort(); };
  }, [summaryIds, mainTab]);

  const pagedCalendar = data.calendar.slice(
    (calPage - 1) * calPageSize,
    calPage * calPageSize
  );

  const current = detail?.news ?? selected;
  const body = detail?.body;

  return (
    <>
      {/* 4 Top KPI Cards */}
      <div className="gc-stat-grid">
        <div className="gc-stat">
          <div className="gc-stat-label">近期宏观情报</div>
          <strong>{data.news.filter(isRecent).length}</strong>
          <div className="gc-stat-foot">近48小时来源 · 媒体首发</div>
        </div>
        <div className="gc-stat">
          <div className="gc-stat-label">已核验正文</div>
          <strong>{data.news.filter((a) => (a.contentCharacters ?? 0) > 0).length}</strong>
          <div className="gc-stat-foot">包含官方 HTML / PDF 全文</div>
        </div>
        <div className="gc-stat">
          <div className="gc-stat-label">待核验与归档</div>
          <strong>
            {
              data.news.filter(
                (a) =>
                  a.archived ||
                  !a.publishedAt ||
                  a.freshness === 'FUTURE' ||
                  a.freshness === 'TIME_UNVERIFIED'
              ).length
            }
          </strong>
          <div className="gc-stat-foot">时间待校验 · 隔离于输入外</div>
        </div>
        <div className="gc-stat">
          <div className="gc-stat-label">信源通道健康</div>
          <strong>
            {data.sources.filter((s) => !s.error).length}{' '}
            <small>/ {data.sources.length}</small>
          </strong>
          <div className="gc-stat-foot">
            {data.sources.filter((s) => s.error).length ? '存在需处理通道' : '所有通道正常'}
          </div>
        </div>
      </div>

      {/* Main Intelligence Tabs */}
      <div className="gc-tabs" role="tablist" style={{ marginTop: '4px' }}>
        <button
          type="button"
          aria-pressed={mainTab === 'news'}
          onClick={() => setMainTab('news')}
        >
          资讯与宏观情绪 ({filteredNews.length})
        </button>
        <button
          type="button"
          aria-pressed={mainTab === 'drivers'}
          onClick={() => setMainTab('drivers')}
        >
          四周期趋势因子与宏观大势
        </button>
        <button
          type="button"
          aria-pressed={mainTab === 'calendar'}
          onClick={() => setMainTab('calendar')}
        >
          经济发布与日历 ({data.calendar.length})
        </button>
        <button
          type="button"
          aria-pressed={mainTab === 'health'}
          onClick={() => setMainTab('health')}
        >
          信源稳定性与代理池
        </button>
      </div>

      {/* TAB 1: News & Macro Sentiments */}
      {mainTab === 'news' && (
        <section className="gc-panel">
          <div className="gc-panel-head">
            <div>
              <h3>宏观资讯流与模型影响评估</h3>
              <span className="gc-small">
                来源报道事实与 AI 条件判断分别呈现 · 点击卡片查看正文与反证
              </span>
            </div>
            <div className="gc-segment">
              <button
                type="button"
                aria-pressed={horizon === 'SHORT'}
                onClick={() => setHorizon('SHORT')}
              >
                短线影响
              </button>
              <button
                type="button"
                aria-pressed={horizon === 'LONG'}
                onClick={() => setHorizon('LONG')}
              >
                两天影响
              </button>
            </div>
          </div>

          {impacts.some(item=>!item.newsId && item.horizon===horizon) && <div className="gc-news-impact-summary" aria-label="模型事件判断">
            <h4>模型事件判断</h4><p className="gc-small">基于已压缩资讯的综合判断；消息按来源展示，模型判断按事件展示。</p>
            {impacts.filter(item=>!item.newsId && item.horizon===horizon).map((item,index)=><article key={index} className="gc-factor">
              <b>{item.topic} · {item.direction==='BULLISH'?'偏多':item.direction==='BEARISH'?'偏空':item.direction==='MIXED'?'分歧':'待确认'}</b>
              <p>{item.reason}</p><small>反证：{item.counter}</small>
            </article>)}
          </div>}

          {/* Search and Filters Toolbar */}
          <div className="gc-live-toolbar">
            <input
              aria-label="搜索信息"
              placeholder="搜索标题、正文摘要、信源机构…"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              style={{ minWidth: '240px' }}
            />
            <div className="gc-segment">
              <button
                type="button"
                aria-pressed={scope === 'RECENT'}
                onClick={() => setScope('RECENT')}
              >
                近期资讯
              </button>
              <button
                type="button"
                aria-pressed={scope === 'BACKGROUND'}
                onClick={() => setScope('BACKGROUND')}
              >
                背景资料
              </button>
              <button
                type="button"
                aria-pressed={scope === 'ARCHIVE'}
                onClick={() => setScope('ARCHIVE')}
              >
                待核验与归档
              </button>
            </div>
          </div>

          {/* Direction Filter Pills */}
          {impacts.some(item=>item.newsId) && <div className="gc-segment" style={{ marginBottom: '16px' }}>
            {[
              ['ALL', '全部情绪'],
              ['BULLISH', '支持上涨'],
              ['BEARISH', '形成压力'],
              ['MIXED', '多空并存'],
              ['UNKNOWN', '尚无判断'],
            ].map(([key, label]) => (
              <button
                key={key}
                type="button"
                aria-pressed={filter === key}
                onClick={() => setFilter(key)}
              >
                {label}
              </button>
            ))}
          </div>}

          {/* News List */}
          {summaryError && <p className="gc-live-error" role="alert">压缩摘要读取失败：{summaryError}</p>}
          <div className="gc-live-news-list">
            {pagedNews.map((article) => {
              const judgment = impact(article);
              const used = run?.evidence.news?.find((item) => item.id === article.id);
              const revised =
                used &&
                (used.version !== article.version ||
                  (used.bodyHash ?? null) !== (article.bodyHash ?? null));

              return (
                <button
                  type="button"
                  className="gc-live-news"
                  key={article.id}
                  onClick={() => setSelected(article)}
                >
                  <div className="gc-live-news-meta">
                    <span>
                      {names[article.source] ?? article.source} · v{article.version}
                    </span>
                    <time>{time(article.publishedAt)}</time>
                  </div>
                  <div className="gc-live-news-title">
                    <h3>{article.title}</h3>
                    <DirectionBadge direction={judgment?.direction} />
                  </div>
                  <p>
                    {judgment?.reason ??
                      (article.excerpt || '标题已取得，正文正在核验。')}
                  </p>
                  <SummaryPreview item={summaries[article.id]} />
                  <div className="gc-news-quality-tags">
                    <span>{text(article.contentState)}</span>
                    <span>{text(article.freshness)}</span>
                    <span>{text(article.publishedPrecision)}</span>
                    {!!article.contentCharacters && (
                      <span>{article.contentCharacters.toLocaleString()} 字</span>
                    )}
                  </div>
                  {judgment && (
                    <small style={{ marginTop: '8px', display: 'block' }}>
                      AI 判断 · {time(run?.evidenceCutoff)}
                      {revised ? ' · 内容已修订，判断使用较早版本' : ''}
                    </small>
                  )}
                </button>
              );
            })}
          </div>

          {!filteredNews.length && (
            <Empty
              title="没有符合条件的资讯"
              description="可尝试切换搜索词，或切换至背景资料/待核验归档查看。"
            />
          )}

          {/* Pagination Controls */}
          <Pagination
            currentPage={newsPage}
            totalItems={filteredNews.length}
            pageSize={newsPageSize}
            onPageChange={setNewsPage}
          />
        </section>
      )}

      {/* TAB 2: Drivers & Horizons */}
      {mainTab === 'drivers' && <AnalysisPanel analysis={data.analysis} />}

      {/* TAB 3: Economic Calendar */}
      {mainTab === 'calendar' && (
        <section className="gc-panel">
          <div className="gc-panel-head">
            <div>
              <h3>官方经济日历与会议日程</h3>
              <span className="gc-small">北京时间统一校准 · 日期范围另标</span>
            </div>
            <span className="gc-badge gc-gold-badge">共 {data.calendar.length} 个日程</span>
          </div>

          <div
            style={{
              display: 'grid',
              gridTemplateColumns: 'repeat(auto-fill, minmax(320px, 1fr))',
              gap: '12px',
              margin: '16px 0',
            }}
          >
            {pagedCalendar.map((event) => (
              <div
                key={event.id}
                style={{
                  padding: '14px 16px',
                  background: 'var(--gc-surface-raised)',
                  border: '1px solid var(--gc-line)',
                  borderRadius: 'var(--gc-radius-sm)',
                  display: 'flex',
                  flexDirection: 'column',
                  justifyContent: 'space-between',
                  gap: '8px',
                }}
              >
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline' }}>
                  <time style={{ color: 'var(--gc-gold)', fontWeight: 600, fontSize: '12px' }}>
                    {eventTime(event)}
                  </time>
                  <span className="gc-badge">{names[event.source] ?? event.source}</span>
                </div>
                <a
                  href={event.link}
                  target="_blank"
                  rel="noreferrer"
                  style={{
                    fontSize: '13px',
                    fontWeight: 500,
                    color: 'var(--gc-text)',
                    lineHeight: '1.4',
                  }}
                >
                  {event.title} ↗
                </a>
                <span className="gc-small">原时区 {event.originalZone}</span>
              </div>
            ))}
          </div>

          {!data.calendar.length && (
            <Empty title="日历暂未取得" description="计划时间、实际数据和市场一致预期正在核验中。" />
          )}

          <Pagination
            currentPage={calPage}
            totalItems={data.calendar.length}
            pageSize={calPageSize}
            onPageChange={setCalPage}
          />
        </section>
      )}

      {/* TAB 4: Source Reliability & Proxies */}
      {mainTab === 'health' && (
        <div style={{ display: 'grid', gap: '20px' }}>
          <SourceReliabilityPanel data={data.sourceReliability} />
          <div className="gc-secondary-grid">
            <ProxyHealth data={data} />
            <SourceHealth data={data} />
          </div>
        </div>
      )}

      {/* Article Detail Drawer / Modal */}
      {current && (
        <div className="gc-live-overlay" onClick={() => setSelected(null)}>
          <section
            className="gc-live-dialog gc-panel gc-article-dialog"
            role="dialog"
            aria-modal="true"
            aria-label="信息详情"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="gc-panel-head">
              <span className="gc-badge gc-gold-badge">
                {names[current.source] ?? current.source} · v{current.version}
              </span>
              <button
                type="button"
                className="gc-live-button"
                onClick={() => setSelected(null)}
              >
                ✕ 关闭
              </button>
            </div>
            <h2>{current.title}</h2>
            <div className="gc-news-quality-tags">
              <span>{text(current.freshness)}</span>
              <span>{text(current.contentState)}</span>
              {current.contentCharacters && <span>{current.contentCharacters} 字符</span>}
            </div>
            <div className="gc-factor" style={{ marginTop: '12px' }}>
              <span>公布时间</span>
              <b>{time(current.publishedAt)}</b>
            </div>
            {current.sourcePublishedAt &&
              current.sourcePublishedAt !== current.publishedAt && (
                <div className="gc-factor">
                  <span>原 Feed 时间</span>
                  <b>{time(current.sourcePublishedAt)}</b>
                </div>
              )}
            <div className="gc-factor">
              <span>系统首次收到</span>
              <b>{time(current.receivedAt)}</b>
            </div>
            {body?.receivedAt && (
              <div className="gc-factor">
                <span>正文取得时刻</span>
                <b>{time(body.receivedAt)}</b>
              </div>
            )}
            <div style={{ margin: '12px 0', display: 'flex', gap: '12px' }}>
              <a
                className="gc-live-button"
                href={current.link}
                target="_blank"
                rel="noreferrer"
              >
                打开来源原文 ↗
              </a>
              {body?.link && body.link !== current.link && (
                <a
                  className="gc-live-button"
                  href={body.link}
                  target="_blank"
                  rel="noreferrer"
                >
                  打开正文来源 ↗
                </a>
              )}
            </div>

            <div className="gc-segment" style={{ margin: '16px 0' }}>
              <button type="button" aria-pressed={contentTab === 'summary'} onClick={() => setContentTab('summary')}>压缩后结果</button>
              <button type="button" aria-pressed={contentTab === 'original'} onClick={() => setContentTab('original')}>来源原文</button>
            </div>
            {contentTab === 'summary' ? <SummaryPreview item={summaries[current.id]} /> : detailError ? (
              <p className="gc-live-error">{detailError}</p>
            ) : !detail ? (
              <p className="gc-live-prose">正在读取保存的正文…</p>
            ) : body?.text ? (
              <>
                <h3 className="gc-live-section-label">
                  官方正文{body.format === 'PDF_TEXT' ? '（PDF 文字提取）' : ''} · 版本{' '}
                  {body.version}
                </h3>
                {body.feedHash !== current.contentHash && (
                  <p className="gc-live-error">
                    来源已修订，以下正文属于较早版本；新正文正在核验。
                  </p>
                )}
                {body.truncated && (
                  <p className="gc-small">正文超过保存上限，已截取；完整内容请访问官方原文。</p>
                )}
                <pre className="gc-article-body">{body.text}</pre>
              </>
            ) : (
              <p className="gc-live-prose">
                {current.excerpt || '目前仅有标题，尚未取得可验证的正文。'}
              </p>
            )}

            {impact(current) && (
              <div
                style={{
                  marginTop: '16px',
                  padding: '14px',
                  background: 'var(--gc-surface-raised)',
                  borderRadius: 'var(--gc-radius-sm)',
                  border: '1px solid var(--gc-line)',
                }}
              >
                <div
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'space-between',
                    marginBottom: '8px',
                  }}
                >
                  <h4 style={{ margin: 0 }}>AI 条件影响判断</h4>
                  <DirectionBadge direction={impact(current)?.direction} />
                </div>
                <p className="gc-live-prose" style={{ margin: '6px 0' }}>
                  {impact(current)?.reason}
                </p>
                {impact(current)?.counter && (
                  <p className="gc-small" style={{ color: 'var(--gc-gold)' }}>
                    反证或失效条件：{impact(current)?.counter}
                  </p>
                )}
                <small className="gc-small" style={{ display: 'block', marginTop: '6px' }}>
                  证据快照截止 {time(run?.evidenceCutoff)}
                </small>
              </div>
            )}
          </section>
        </div>
      )}
    </>
  );
}
