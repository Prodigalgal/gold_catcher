import { useEffect, useState } from 'react';
import { call } from './api';
import type { CalendarEvent, Dashboard, Direction, News, NewsDetail, Source } from './api';

const names: Record<string, string> = { CNBC_TOP: 'CNBC 财经头条', CNBC_ECONOMY: 'CNBC 经济新闻', CNBC_WORLD: 'CNBC 全球新闻', FED_PRESS: '美联储公告', FED_SPEECH: '美联储讲话', ECB_PRESS: '欧洲央行', BEA_NEWS: 'BEA 当前发布', BEA_CALENDAR: 'BEA 经济日历', BLS_CPI: 'BLS 消费者物价', BLS_EMPLOYMENT: 'BLS 就业报告', BLS_PPI: 'BLS 生产者物价', BLS_JOLTS: 'BLS 职位空缺', BLS_CALENDAR: 'BLS 经济日历', FOMC_CALENDAR: 'FOMC 会议日期' };
const states: Record<string, string> = { PROVIDER_REPORTED_TIME: '出版方报告时间', PUBLIC_FEED_SUMMARY: '公开 RSS 摘要', CURRENT: '采集成功', FETCH_FAILED: '获取失败', DISABLED: '未启用', AVAILABLE: '正文已取得', TRUNCATED: '正文已截取', PENDING: '等待正文', WAITING_PROXY: '等待代理就绪', BACKGROUND_FEED_ONLY: '历史来源摘要', CONTENT_INVALID: '响应内容无法使用', CACHED_AFTER_FAILURE: '正文暂用缓存', HEADLINES_ONLY: '仅标题或摘要', TEXT_AVAILABLE: '正文已取得', PARTIAL_TEXT: '部分正文已取得', SCHEDULE_ONLY: '日程资料', VERIFIED: '发布时间已核验', PARTIAL: '部分时间未核验', UNKNOWN: '发布时间未知', RECENT_FETCH: '检查及时', DELAYED: '检查延迟', UNVERIFIED: '尚未核验', RECENT: '近两天发布', BACKGROUND: '背景资料', TIME_UNKNOWN: '时间未知', TIME_UNVERIFIED: '公布时间待核验', FEED_TIMESTAMP: 'Feed 时间，待核验', DECLARED_RELEASE_TIME: '正文声明的公布时间', EXACT: '官方明确的时间', QUARANTINED: '旧源隔离', FUTURE: '发布时间在未来', READY: '代理核心就绪', VALIDATING: '筛选节点中', PROCESS_EXITED: '代理进程退出', DEGRADED: '连接异常，等待切换', ACCESSIBLE: '目标可访问', TARGET_ACCESS_DENIED: '目标站拒绝访问', TARGET_HTTP_ERROR: '目标站 HTTP 错误', NETWORK_FAILED: '节点连接失败' };
const directions: Record<Direction, string> = { BULLISH: '支持上涨', BEARISH: '形成压力', MIXED: '多空并存', UNKNOWN: '尚无判断' };
const text = (state?: string | null) => state ? states[state] ?? state : '尚未核验';
const time = (value?: string | null) => value ? new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false, month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit' }) : '未知';
function DirectionBadge({ direction = 'UNKNOWN' }: { direction?: Direction }) { return <span className={`gc-badge ${direction === 'BULLISH' ? 'gc-positive' : direction === 'BEARISH' ? 'gc-negative' : ''}`}>{directions[direction]}</span>; }
function Empty({ title, description }: { title: string; description: string }) { return <div className="gc-live-empty"><span>◇</span><h3>{title}</h3><p>{description}</p></div>; }
function eventTime(event: CalendarEvent) {
  if (event.scheduledAt) return time(event.scheduledAt);
  return `${event.scheduledDate ?? '日期未知'}${event.endDate && event.endDate !== event.scheduledDate ? ` 至 ${event.endDate}` : ''} · 具体时刻待公布`;
}
function SourceCard({ source }: { source: Source }) {
  const quality = source.quality;
  return <article className="gc-source-card"><div className="gc-panel-head"><a href={source.url} target="_blank" rel="noreferrer">{names[source.id] ?? source.id} ↗</a><span className={`gc-badge ${source.error ? 'gc-negative' : ''}`}>{text(source.state)}</span></div>
    <div className="gc-source-quality"><span>连接<b>{source.error ?? text(quality?.connection)}</b></span><span>内容<b>{text(quality?.content)}</b></span><span>时间<b>{text(quality?.publication)}</b></span></div>
    <p className="gc-small">当前视图 {quality?.records ?? '—'} 条 · 正文 {quality?.bodies ?? '—'} 条 · 时间待核验或隔离 {quality?.excludedRecords ?? '—'} 条</p>
    <div className="gc-source-times"><span>最近检查 {time(source.lastSuccess)}</span><span>最近发布 {time(quality?.lastPublishedAt)}</span><span>下次检查 {time(source.nextPoll)}</span><span>{source.intervalSeconds}s · {source.route === 'CF_PROXY' ? 'CF 代理' : source.route === 'DIRECT_WITH_CF_FALLBACK' ? '直接连接 · CF 备用' : '直接连接'}</span></div>
    <p className="gc-small gc-source-note">{quality?.note}</p></article>;
}
export function SourceHealth({ data }: { data: Dashboard }) {
  return <section className="gc-panel"><div className="gc-panel-head"><div><h3>信源覆盖与内容质量</h3><span className="gc-small">连接、正文和时间分别核验；按各通道记录计数，消息展示另行去重</span></div><span className="gc-badge">{data.sources.length} 个通道</span></div><div className="gc-source-grid">{data.sources.map(source => <SourceCard key={source.id} source={source}/>)}</div></section>;
}
export function ProxyHealth({ data }: { data: Dashboard }) {
  const proxy = data.sourceProxy;
  if (!proxy) return null;
  return <section className="gc-panel"><div className="gc-panel-head"><h3>信源代理池</h3><span className="gc-badge">{text(proxy.state)}</span></div><div className="gc-factor"><span>订阅配置</span><b>{proxy.configured ? '已配置私密订阅' : '尚未配置'} · {proxy.enabled ? '已启用' : '未启用'}</b></div><div className="gc-factor"><span>节点筛选</span><b>{proxy.candidates} 个候选 · 本轮检查 {proxy.tested} · 未通过 {proxy.rejected}</b></div><div className="gc-factor"><span>最近验证</span><b>{time(proxy.lastVerified)}</b></div>{proxy.targets.map(target => <div className="gc-factor" key={target.source}><span>{names[target.source] ?? target.source}</span><b>{text(target.state)} · {target.route === 'CF_PROXY' ? 'CF 代理' : '直接连接'}{target.status ? ` · HTTP ${target.status}` : ''}</b></div>)}{proxy.error && <p className="gc-live-error">{proxy.error}</p>}<p className="gc-small">节点先验证实际 HTTPS 内容，再用于指定信源。目标站是否允许访问另行记录；交易行情与模型接口使用各自连接。</p></section>;
}

export default function InformationPanel({ data }: { data: Dashboard }) {
  const [query, setQuery] = useState(''), [filter, setFilter] = useState('ALL'), [scope, setScope] = useState('RECENT'), [horizon, setHorizon] = useState('SHORT');
  const [selected, setSelected] = useState<News | null>(null), [detail, setDetail] = useState<NewsDetail | null>(null), [detailError, setDetailError] = useState('');
  const [shown, setShown] = useState(30);
  useEffect(() => {
    setDetail(null); setDetailError(''); if (!selected) return;
    const controller = new AbortController();
    call<NewsDetail>(`news/${encodeURIComponent(selected.id)}`, {}, undefined, controller.signal).then(setDetail).catch(error => { if (!controller.signal.aborted) setDetailError(error instanceof Error ? error.message : '正文读取失败'); });
    return () => controller.abort();
  }, [selected?.id]);
  useEffect(() => setShown(30), [scope, query, filter]);
  const run = data.research, impacts = run?.verdict?.impacts ?? [];
  const impact = (article: News) => impacts.find(item => item.newsId === article.id && item.horizon === horizon);
  const isRecent = (article: News) => article.freshness === 'RECENT' || (!article.freshness && !!article.publishedAt && new Date(article.publishedAt).getTime() > new Date(data.serverTime).getTime() - 172800000);
  const rows = data.news.filter(article => scope === 'RECENT' ? isRecent(article) : scope === 'BACKGROUND' ? !isRecent(article) && !!article.publishedAt && !article.archived && article.freshness !== 'FUTURE' && article.freshness !== 'TIME_UNVERIFIED' : article.archived || !article.publishedAt || (article.freshness === 'FUTURE' || article.freshness === 'TIME_UNVERIFIED'))
    .filter(article => (article.title + article.excerpt + article.source).toLowerCase().includes(query.toLowerCase()))
    .filter(article => filter === 'ALL' || (impact(article)?.direction ?? 'UNKNOWN') === filter);
  const current = detail?.news ?? selected, body = detail?.body;
  return <><div className="gc-information-stats">{[['近期信息', data.news.filter(isRecent).length, '按来源时间；媒体首发未独立核验'], ['已取得正文', data.news.filter(article => (article.contentCharacters ?? 0) > 0).length, '点开消息阅读已保存的正文'], ['档案与待核验', data.news.filter(article => article.archived || !article.publishedAt || (article.freshness === 'FUTURE' || article.freshness === 'TIME_UNVERIFIED')).length, '不会作为近期消息交给模型'], ['失败通道', data.sources.filter(source => source.error).length, '保留最近成功和当前错误']].map(([title,value,note]) => <div className="gc-stat" key={String(title)}><span className="gc-stat-label">{title}</span><strong>{value}</strong><small>{note}</small></div>)}</div>
    <section className="gc-panel"><div className="gc-panel-head"><div><h3>信息影响看板</h3><span className="gc-small">事实与条件判断各有出处；AI 尚未裁决时显示尚无判断</span></div><div className="gc-segment">{[['SHORT','短线'],['LONG','未来两天']].map(([key,name]) => <button key={key} aria-pressed={horizon === key} onClick={() => setHorizon(key)}>{name}</button>)}</div></div>
      <div className="gc-live-toolbar"><input aria-label="搜索信息" placeholder="搜索标题、正文摘录、信源…" value={query} onChange={event => setQuery(event.target.value)}/><div className="gc-tabs">{[['RECENT','近期信息'],['BACKGROUND','背景资料'],['ARCHIVE','待核验与档案']].map(([key,name]) => <button key={key} aria-pressed={scope === key} onClick={() => setScope(key)}>{name}</button>)}</div></div>
      <div className="gc-tabs gc-information-filters">{[['ALL','全部'],['BULLISH','支持上涨'],['BEARISH','形成压力'],['MIXED','多空并存'],['UNKNOWN','尚无判断']].map(([key,name]) => <button key={key} aria-pressed={filter === key} onClick={() => setFilter(key)}>{name}</button>)}</div>
      <div className="gc-live-news-list">{rows.slice(0, shown).map(article => {
        const judgment = impact(article), used = run?.evidence.news?.find(item => item.id === article.id), revised = used && (used.version !== article.version || (used.bodyHash ?? null) !== (article.bodyHash ?? null));
        return <button className="gc-live-news" key={article.id} onClick={() => setSelected(article)}><div className="gc-live-news-meta"><span>{names[article.source] ?? article.source} · v{article.version}</span><time>{time(article.publishedAt)}</time></div><div className="gc-live-news-title"><h3>{article.title}</h3><DirectionBadge direction={judgment?.direction}/></div><p>{judgment?.reason ?? (article.excerpt || '标题已取得，正文正在核验。')}</p><div className="gc-news-quality-tags"><span>{text(article.contentState)}</span><span>{text(article.freshness)}</span><span>{text(article.publishedPrecision)}</span>{!!article.contentCharacters && <span>{article.contentCharacters.toLocaleString()} 字</span>}</div>{judgment && <small>AI 判断 · {time(run?.evidenceCutoff)}{revised ? ' · 内容已更新，判断使用较早版本' : ''}</small>}</button>;
      })}</div>{!rows.length && <Empty title="没有符合条件的信息" description="可切换背景资料或待核验与档案；未验证发布时间的旧消息不会混入近期列表。"/>}{rows.length > shown && <button className="gc-live-button gc-news-more" onClick={() => setShown(count => count + 30)}>查看更多 · 还有 {rows.length - shown} 条</button>}
    </section>
    <div className="gc-secondary-grid"><section className="gc-panel"><div className="gc-panel-head"><h3>官方日历与会议安排</h3><span className="gc-badge">北京时间 · 日期范围另标</span></div>{data.calendar.slice(0, 16).map(event => <div className="gc-live-calendar" key={event.id}><time>{eventTime(event)}</time><a href={event.link} target="_blank" rel="noreferrer">{event.title}</a><small>{names[event.source] ?? event.source} · 原时区 {event.originalZone}</small></div>)}{!data.calendar.length && <Empty title="日历暂未取得" description="计划时间、实际数据和市场一致预期需要分别核验。"/>}<p className="gc-small">只有会议日期时保留日期范围。实际值、一致预期和前值的结构化采集仍待接入。</p></section><ProxyHealth data={data}/></div>
    <SourceHealth data={data}/>
    {current && <div className="gc-live-overlay" onClick={() => setSelected(null)}><section className="gc-live-dialog gc-panel gc-article-dialog" role="dialog" aria-modal="true" aria-label="信息详情" onClick={event => event.stopPropagation()}><div className="gc-panel-head"><span className="gc-badge">{names[current.source] ?? current.source} · v{current.version}</span><button className="gc-live-button" onClick={() => setSelected(null)}>关闭</button></div><h2>{current.title}</h2><div className="gc-news-quality-tags"><span>{text(current.freshness)}</span><span>{text(current.contentState)}</span></div><div className="gc-factor"><span>{current.publishedPrecision === 'FEED_TIMESTAMP' ? 'Feed 时间（公布时刻待核验）' : current.publishedPrecision === 'PROVIDER_REPORTED_TIME' ? '出版方报告时间（首发未独立核验）' : '公布时间'}</span><b>{time(current.publishedAt)}</b></div>{current.sourcePublishedAt && current.sourcePublishedAt !== current.publishedAt && <div className="gc-factor"><span>原 Feed 时间</span><b>{time(current.sourcePublishedAt)}</b></div>}<div className="gc-factor"><span>系统首次收到当前版本</span><b>{time(current.receivedAt)}</b></div><div className="gc-factor"><span>当前正文取得</span><b>{time(body?.receivedAt)}</b></div><a className="gc-live-link" href={current.link} target="_blank" rel="noreferrer">打开来源原文 ↗</a>{body?.link && body.link !== current.link && <a className="gc-live-link" href={body.link} target="_blank" rel="noreferrer">打开正文来源 ↗</a>}
      {detailError ? <p className="gc-live-error">{detailError}</p> : !detail ? <p className="gc-live-prose">正在读取保存的正文…</p> : body?.text ? <><h3 className="gc-live-section-label">官方正文{body.format === 'PDF_TEXT' ? '（PDF 文字层）' : ''} · 正文版本 {body.version}</h3>{body.feedHash !== current.contentHash && <p className="gc-live-error">来源已修订，以下正文属于较早版本；新正文正在核验。</p>}{body.truncated && <p className="gc-small">正文超过保存上限，已截取；完整内容见官方原文。</p>}{body.error && <p className="gc-live-error">最近正文检查失败：{body.error}，以下保留此前内容。</p>}<pre className="gc-article-body">{body.text}</pre></> : <p className="gc-live-prose">{current.excerpt || '目前只有标题，尚未取得可验证的正文。'}{body?.error && ` · ${body.error}`}</p>}
      {impact(current) && <><h3 className="gc-live-section-label">AI 的条件判断</h3><DirectionBadge direction={impact(current)?.direction}/><p className="gc-live-prose">{impact(current)?.reason}</p><p className="gc-live-prose">反证：{impact(current)?.counter || '未提供'}</p><small>研究证据截止 {time(run?.evidenceCutoff)}</small></>}
    </section></div>}
  </>;
}
