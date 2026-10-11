import { useState } from 'react';
import { call } from './api';
import type { Answer, CompressionResult, CompressionStatus, Preprocessing, Session } from './api';
import ContextBudget from './ContextBudget';

const names: Record<string,string> = { EXTRACTED: '摘要已生成', PARTIAL: '部分正文已压缩', FAILED: '处理失败，使用摘录', DETERMINISTIC_FALLBACK: '使用原文摘录', VERIFIED: '摘要格式通过' };
const stamp = (value?: string | null) => value ? new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false }) : '—';
function CompressionAudit({ id, session }: { id: string; session: Session }) {
  const [detail, setDetail] = useState<{ result: CompressionResult; chunks: { id: string; state: string; error: string | null; summary?: string; input?: { task?: string; text?: string; summaries?: string[] }; answer?: Omit<Answer,'output'> & { output: { summary?: string } | null } }[] } | null>(null), [error,setError] = useState(''), [busy,setBusy] = useState(false);
  const load = async () => { setBusy(true); try { setDetail(await call(`compression/${id}`, session)); setError(''); } catch (failure) { setError((failure as Error).message); } finally { setBusy(false); } };
  return <div className="gc-compression-audit">
    <button className="gc-text-button" disabled={busy} onClick={() => void load()}>{busy ? '读取中…' : '查看压缩过程'}</button>
    {error && <p className="gc-live-error" role="alert">{error}</p>}
    {detail && <details className="gc-live-audit" open>
      <summary>{detail.result.title} · {names[detail.result.state] ?? detail.result.state}</summary>
      <p className="gc-live-prose">{detail.result.excerpt}</p>
      <p><a href={detail.result.link} target="_blank" rel="noreferrer">官方原文 ↗</a> · 正文 {detail.result.sourceCharacters} 字符 · 分块 {detail.result.processedChunks}/{detail.result.totalChunks}{detail.result.sourceTruncated && ' · 下载正文曾被截断'}</p>
      <p className="gc-live-hash">正文 SHA-256 {detail.result.bodyHash}</p>
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
    <div className="gc-live-message-meta"><b>MIMO · 前置信息压缩</b><span>本次压缩不计票</span><time>{stamp(data.cutoff)}</time></div>
    <p className="gc-small">各位研究成员收到同一份摘要。此处显示本轮使用的压缩版本，后续更新不改写当前研究。</p>
    {data.articles.map(article => <div className="gc-compression-row" key={article.newsId}><b>{names[article.state] ?? article.state}</b><small>原文 {article.originalCharacters ?? '未取得'} → 摘要 {article.sentCharacters} 字符{article.totalChunks && ` · ${article.processedChunks}/${article.totalChunks} 分块`}</small>{article.resultId && <CompressionAudit id={article.resultId} session={session}/>}</div>)}
    {!data.articles.length && <p className="gc-small">本轮没有入选新闻正文。</p>}
  </div></article>;
}
export default function CompressionPanel({ data, session }: { data?: CompressionStatus; session: Session }) {
  if(!data) return null;
  return <section className="gc-panel">
    <div className="gc-panel-head"><div><h3>MIMO 前置信息压缩</h3><span className="gc-small">{data.model} · 原文 → 精华摘要 → 多模型研究</span></div><span className="gc-badge">{!data.enabled ? '已停用' : data.running ? '提炼摘要中' : data.retryAt ? '等待上游恢复' : '等待新正文'}</span></div>
    <p className="gc-live-prose">{data.coverage}</p>
    <p className="gc-small">摘要保留关键事实、条件和不确定性。长文章分块压缩后合并；原文和处理记录可展开查看。压缩调用不计票，MIMO 另行参与辩论和裁决。</p>
    {data.error && <p className="gc-live-error">{data.error}</p>}
    {data.retryAt && <p className="gc-small">连续上游失败 {data.consecutiveFailures ?? 1} 次 · 最早重试：{stamp(data.retryAt)}。未完成摘要的文章继续使用原文摘录。</p>}
    {data.lastSuccessfulAt && <p className="gc-small">最近一次摘要请求成功：{stamp(data.lastSuccessfulAt)}</p>}
    {data.recent.map(result => <div className="gc-compression-row" key={result.id}><div><b>{result.title}</b><small>{result.source} · {stamp(result.completedAt)} · {names[result.state] ?? result.state} · {result.processedChunks}/{result.totalChunks} 分块</small></div><p className="gc-live-prose">{result.excerpt || '未取得可用摘要，继续使用原文摘录。'}</p><CompressionAudit id={result.id} session={session}/></div>)}
    {!data.recent.length && <p className="gc-small">尚无摘要；当前研究使用原文摘录。</p>}
  </section>;
}
