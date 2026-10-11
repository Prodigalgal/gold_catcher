import { useState } from 'react';
import { call } from './api';
import type { Answer, CompressionResult, CompressionStatus, Preprocessing, Session } from './api';
import ContextBudget from './ContextBudget';

const names: Record<string,string> = { EXTRACTED: '摘要已生成', PARTIAL: '部分文本已压缩', DEFERRED: '等待提供商空闲后继续', FAILED: '本次压缩失败', COMPRESSION_PENDING: '等待压缩，本轮未使用', COMPRESSION_DISABLED: '压缩停用，本轮未使用', DETERMINISTIC_FALLBACK: '历史版本使用原文摘录', VERIFIED: '摘要格式通过' };
const states: Record<string,string> = { DISABLED: '已停用', CREDENTIAL_MISSING: '待配置凭据', RUNNING: '提炼摘要中', UPSTREAM_BACKOFF: '等待上游恢复', PAUSED_PROVIDER_RESEARCH: '提供商正在研究，压缩稍后继续', SCANNING: '检查来源文本中', QUEUED: '来源文本已排队', ARTICLE_RETRY_WAIT: '等待文本重试', NO_ELIGIBLE_BODY: '暂无可压缩文本', UP_TO_DATE: '当前来源摘要已完成' };
const formats: Record<string,string> = { HEADLINE_ONLY: '仅标题', FEED_SUMMARY: 'RSS 摘要', HTML: '网页正文', PDF: 'PDF 正文' };
const stamp = (value?: string | null) => value ? new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false }) : '—';
function CompressionAudit({ id, session }: { id: string; session: Session }) {
  const [queued,setQueued]=useState(false);
  const [detail, setDetail] = useState<{ result: CompressionResult; chunks: { id: string; state: string; error: string | null; summary?: string; input?: { task?: string; text?: string; summaries?: string[] }; answer?: Omit<Answer,'output'> & { output: { summary?: string } | null } }[] } | null>(null), [error,setError] = useState(''), [busy,setBusy] = useState(false);
  const load = async () => { setBusy(true); try { setDetail(await call(`compression/${id}`, session)); setError(''); } catch (failure) { setError((failure as Error).message); } finally { setBusy(false); } };
  return <div className="gc-compression-audit">
    <button className="gc-text-button" disabled={busy} onClick={() => void load()}>{busy ? '读取中…' : '查看压缩过程'}</button>
    {error && <p className="gc-live-error" role="alert">{error}</p>}
    {detail && <details className="gc-live-audit" open>
      <summary>{detail.result.title} · {names[detail.result.state] ?? detail.result.state}</summary>
      <p className="gc-live-prose">{detail.result.excerpt}</p>
      <p><a href={detail.result.link} target="_blank" rel="noreferrer">来源原文 ↗</a> · {formats[detail.result.sourceFormat??'']??detail.result.sourceFormat??'正文'} {detail.result.sourceCharacters} 字符 · 分块 {detail.result.processedChunks}/{detail.result.totalChunks}{detail.result.sourceTruncated && ' · 下载正文曾被截断'}</p>
      <p className="gc-live-hash">来源文本 SHA-256 {detail.result.sourceTextHash??detail.result.bodyHash}</p>
      <p className="gc-small">{detail.result.provider} · {detail.result.model} · {stamp(detail.result.completedAt)}</p>
      <button className="gc-text-button" disabled={busy} onClick={()=>{setBusy(true);void call(`compression/news/${detail.result.newsId}/reprocess`,session,{}).then(()=>{setQueued(true);setError('');}).catch(failure=>setError((failure as Error).message)).finally(()=>setBusy(false));}}>使用当前压缩配置重新生成</button>{queued&&<span role="status">已排队，完成前保留现有摘要</span>}
      {detail.chunks.map(chunk => <details key={chunk.id}>
        <summary>{chunk.input?.task === 'MERGE' ? '合并摘要' : '压缩正文'} · {names[chunk.state] ?? chunk.state} · {chunk.answer?.durationMs ?? '—'}ms</summary>
        {chunk.error && <p>{chunk.error}</p>}
        <p className="gc-live-prose">{chunk.summary}</p>
        {chunk.answer?.budget && <ContextBudget budget={chunk.answer.budget} audit/>}
        <details><summary>本次输入</summary><pre>{chunk.input?.text ?? chunk.input?.summaries?.join('\n\n')}</pre></details>
        <details><summary>模型原始输出</summary><pre>{chunk.answer?.raw}</pre></details>
        <small>usage 来源 {chunk.answer?.usageSource ?? '未知'} · finish {chunk.answer?.finishReason ?? '未知'}</small>
      </details>)}
    </details>}
  </div>;
}
export function FrozenCompression({ data, session }: { data: Preprocessing; session: Session }) {
  return <article className="gc-live-message gc-compression-message"><div className="gc-live-avatar">P</div><div className="gc-live-bubble">
    <div className="gc-live-message-meta"><b>{data.provider} · 前置信息压缩</b><span>本次压缩不计票</span><time>{stamp(data.cutoff)}</time></div>
    <p className="gc-small">各位研究成员收到同一份已完成摘要。待压缩文本不进入本轮研究；后续更新不改写当前研究。{data.omittedArticles!==undefined&&` 本轮完成 ${data.completedArticles} 篇，未使用 ${data.omittedArticles} 篇。`}</p>
    {data.articles.map(article => <div className="gc-compression-row" key={article.newsId}><b>{article.title && `${article.title} · `}{names[article.state] ?? article.state}</b><small>原文 {article.originalCharacters ?? '未取得'} → 摘要 {article.sentCharacters} 字符{article.totalChunks && ` · ${article.processedChunks}/${article.totalChunks} 分块`}</small>{article.resultId && <CompressionAudit id={article.resultId} session={session}/>}</div>)}
    {!data.articles.length && <p className="gc-small">本轮没有入选新闻正文。</p>}
  </div></article>;
}
export default function CompressionPanel({ data, session }: { data?: CompressionStatus; session: Session }) {
  if(!data) return null;
  return <section className="gc-panel">
    <div className="gc-panel-head"><div><h3>前置信息压缩</h3><span className="gc-small">{data.provider} · {data.model} · 原文 → 精华摘要 → 多模型研究</span></div><span className="gc-badge">{states[data.state ?? ''] ?? (!data.enabled ? '已停用' : data.running ? '提炼摘要中' : data.retryAt ? '等待上游恢复' : '等待新文本')}</span></div>
    <p className="gc-live-prose">{data.coverage}</p>
    {data.queue?.scannedAt && <p className="gc-small">来源候选 {data.queue.candidateArticles} 篇 · 可压缩文本 {data.queue.eligibleBodies} 篇 · 摘要完成 {data.queue.completedSummaries} 篇 · 处理中 {data.queue.processingArticles} 篇 · 排队 {data.queue.queuedArticles} 篇 · 待重试 {data.queue.retryingArticles} 篇<br/>未取得可用文本 {data.queue.missingBodies} 篇 · 不适用文本或表格 {data.queue.unsuitableBodies} 篇 · 检查时间 {stamp(data.queue.scannedAt)}</p>}
    <p className="gc-small">正文、RSS 摘要与仅标题分别标明。长文章分块压缩后合并，原文和每次处理记录可查看。压缩调用不计票；可在上方切换模型，再重新生成摘要。</p>
    {data.error && <p className="gc-live-error">{data.error}</p>}
    {data.retryAt && <p className="gc-small">连续上游失败 {data.consecutiveFailures ?? 1} 次 · 最早重试：{stamp(data.retryAt)}。未完成的结果不送入研究，已有成功版本保留。</p>}
    {data.lastSuccessfulAt && <p className="gc-small">最近一次摘要请求成功：{stamp(data.lastSuccessfulAt)}</p>}
    {data.recent.map(result => <div className="gc-compression-row" key={result.id}><div><b>{result.title}</b><small>{result.source} · {formats[result.sourceFormat??'']??result.sourceFormat??'正文'} · {result.provider} · {stamp(result.completedAt)} · {names[result.state] ?? result.state} · {result.processedChunks}/{result.totalChunks} 分块</small></div><p className="gc-live-prose">{result.excerpt || '本次结果未进入研究；已有成功版本保留。'}</p><CompressionAudit id={result.id} session={session}/></div>)}
    {!data.recent.length && <p className="gc-small">尚无压缩记录；未完成摘要的新闻暂不送入研究。</p>}
  </section>;
}
