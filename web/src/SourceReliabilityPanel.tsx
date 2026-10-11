import { useState } from 'react';
import type { SourceReliability } from './api';

const status: Record<string,string> = { HEALTHY: '检查正常', DEGRADED: '最近获取失败', STALE: '采集检查过期', PENDING: '等待首次采集', DISABLED: '未启用' };
const category: Record<string,string> = { OFFICIAL: '官方新闻与日历', MEDIA: '公开财经媒体', DRIVER: '价格驱动与预期', SUPPLEMENTAL: '官方数据与微观行情' };
const official: Record<string,string> = { CNBC_TOP: 'CNBC 财经头条', CNBC_ECONOMY: 'CNBC 经济新闻', CNBC_WORLD: 'CNBC 全球新闻', FED_PRESS:'美联储公告',FED_SPEECH:'美联储讲话',ECB_PRESS:'欧洲央行公告',BEA_NEWS:'BEA 经济数据',BEA_CALENDAR:'BEA 发布日历',BLS_CPI:'BLS 消费者物价',BLS_EMPLOYMENT:'BLS 就业报告',BLS_PPI:'BLS 生产者物价',BLS_JOLTS:'BLS 职位空缺',BLS_CALENDAR:'BLS 发布日历',FOMC_CALENDAR:'FOMC 会议日期',BOE_NEWS:'英国央行公告',BOE_SPEECH:'英国央行讲话',BOJ_NEWS:'日本央行发布',EIA_PRESS:'EIA 新闻稿',EIA_ENERGY:'EIA 能源摘要',EIA_NEW:'EIA 产品更新' };
const time = (v?: string | null) => v ? new Date(v).toLocaleString('zh-CN', {timeZone:'Asia/Shanghai',hour12:false,month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit'}) : '尚未取得';
const duration = (ms?: number | null) => ms == null ? '尚未测量' : ms < 1000 ? `${ms} ms` : ms < 60000 ? `${(ms/1000).toFixed(1)} 秒` : ms < 3600000 ? `${(ms/60000).toFixed(1)} 分钟` : `${(ms/3600000).toFixed(1)} 小时`;
const problem = (s: SourceReliability['sources'][number]) => ['DEGRADED','STALE','PENDING'].includes(s.state) || !!s.bodies.lastError;

export default function SourceReliabilityPanel({ data }: { data?: SourceReliability }) {
  const [filter,setFilter]=useState('ALL');
  if (!data) return null;
  const rows=data.sources.filter(s=>filter==='ALL'||filter==='PROBLEMS'&&problem(s)||filter==='UNMEASURED'&&s.arrival?.scope!=='LIVE_PUBLICATION');
  return <section className="gc-panel gc-reliability" aria-label="信源稳定性与时效">
    <div className="gc-panel-head"><div><h3>信源稳定性与时效</h3><span className="gc-small">采集、正文和消息到达分别测量 · 北京时间</span></div><span className={`gc-badge ${data.degraded+data.stale?'gc-negative':''}`}>{data.healthy} / {data.channels} 采集检查正常</span></div>
    <div className="gc-reliability-stats"><span>最近获取失败<b>{data.degraded}</b></span><span>采集检查过期<b>{data.stale}</b></span><span>等待采集<b>{data.pending}</b></span><span>当前统计开始<b>{time(data.processStartedAt)}</b></span></div>
    <details className="gc-reliability-details"><summary>查看 {data.channels} 个通道的采集时效</summary>
    <p className="gc-small">成功率和耗时按当前进程最近最多128次完成的检查统计，重启后重新累计。耗时包含请求、解析与保存，P95包含失败检查。轮询间隔是调度频率，消息首发至收到的延迟另列。</p>
    <div className="gc-news-controls"><label>显示通道<select aria-label="信源时效筛选" value={filter} onChange={e=>setFilter(e.target.value)}><option value="ALL">全部通道 · {data.channels}</option><option value="PROBLEMS">采集或正文需要处理</option><option value="UNMEASURED">首发延迟尚未实测</option></select></label><span className="gc-small">{rows.length} 个通道 · 快照 {time(data.calculatedAt)}</span></div>
    <div className="gc-reliability-list">{rows.map(s=><article className="gc-source-card" key={s.id} data-source-id={s.id}>
      <div className="gc-panel-head"><a href={s.url} target="_blank" rel="noreferrer">{official[s.id]??s.label} ↗</a><span className={`gc-badge ${s.state==='STALE'||s.state==='DEGRADED'?'gc-negative':''}`}>{status[s.state]??s.state}</span></div>
      <small>{category[s.category]} · 当前正常轮询 {s.intervalSeconds} 秒</small>
      <div className="gc-source-quality"><span>近期成功率<b>{s.collection.successRatePct==null?'暂无样本':`${s.collection.successRatePct}% · ${s.collection.samples} 次`}</b></span><span>连续失败<b>{s.collection.consecutiveFailures} 次</b></span><span>采集耗时 P95<b>{duration(s.collection.p95DurationMillis)}</b></span></div>
      <div className="gc-source-times"><span>检查成功 {time(s.lastSuccess)}</span><span>下次尝试 {time(s.nextPoll)}</span><span>最近耗时 {duration(s.collection.lastDurationMillis)}</span><span>当前数据版本收到 {time(s.arrival?.receivedAt)}</span>{s.arrival?.sourceTime&&<span>{s.arrival.timestampMeaning==='SOURCE_RESPONSE_TIME_NOT_TICK'?'接口响应':s.arrival.timestampMeaning==='ORDER_BOOK_UPDATE_TIME'?'盘口更新':s.arrival.timestampMeaning==='RECENT_TRADES_SAMPLE_NOT_CONTINUOUS'?'最近样本成交':'来源发布时间'} {time(s.arrival.sourceTime)}</span>}</div>
      <p className="gc-small gc-reliability-lag">{!s.arrival?'当前进程尚未见到新数据版本，首发延迟未实测。':s.arrival.scope==='LIVE_PUBLICATION'?`本次正式发布 → 首次收到 ${duration(s.arrival.lagMillis)}`:s.arrival.scope==='STARTUP_OR_HISTORICAL_VERSION'?`启动或历史版本补录，公布 → 收到 ${duration(s.arrival.lagMillis)}；不计为实时首发延迟。`:s.arrival.timestampMeaning==='SOURCE_RESPONSE_TIME_NOT_TICK'?'源时间是接口响应时刻，最后 tick 与消息首发延迟未知。':s.arrival.timestampMeaning==='AS_OF_DATE_ONLY'?'日频统计资料，精确首发时刻未知。':s.arrival.scope==='FUTURE_TIME_EXCLUDED'?'来源时间在收到时刻之后，首发延迟未计入。':'精确首发时间未核验，不使用日程或轮询耗时代替。'}</p>
      {!!s.bodies.samples&&<small>正文最近 {s.bodies.samples} 次检查 · 成功 {s.bodies.successRatePct}% · P95 {duration(s.bodies.p95DurationMillis)}</small>}
      {(s.error||s.bodies.lastError)&&<p className="gc-live-error">{s.error||`正文最近检查：${s.bodies.lastError}`}</p>}
    </article>)}</div>{!rows.length&&<p className="gc-small">当前筛选没有通道。</p>}
    </details>
  </section>;
}
