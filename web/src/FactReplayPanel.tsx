import {useState} from 'react';
import {call} from './api';
import './DataFlowPanel.css';

type IndicatorReplay={complete:boolean;recorded:number;expected:number;checks:{frame:string;supported:boolean;matches:boolean;candles:number;cutoff:string}[]};
type AnalysisReplay={supported:boolean;matches?:boolean;selection?:{indicator:string;source:string;candidates:number;disagreement:boolean;ambiguous:boolean}[]};
type NewsReplay={supported:boolean;matches:boolean;completed:number;omitted:number};
export default function FactReplayPanel({runId}:{runId:string}){
  const [busy,setBusy]=useState(false),[indicators,setIndicators]=useState<IndicatorReplay|null>(null),[analysis,setAnalysis]=useState<AnalysisReplay|null>(null),[errors,setErrors]=useState<string[]>([]);
  const [news,setNews]=useState<NewsReplay|null>(null);
  async function replay(){setBusy(true);setErrors([]);setIndicators(null);setAnalysis(null);setNews(null);
    const results=await Promise.allSettled([call<IndicatorReplay>(`data-flows/indicators/${runId}/replay`),call<AnalysisReplay>(`data-flows/analysis/${runId}/replay`),call<NewsReplay>(`data-flows/news/${runId}/replay`)]);
    if(results[0].status==='fulfilled')setIndicators(results[0].value);if(results[1].status==='fulfilled')setAnalysis(results[1].value);
    if(results[2].status==='fulfilled')setNews(results[2].value);
    setErrors(results.flatMap((result,index)=>result.status==='rejected'?[`${['技术指标','宏观事实','摘要选择'][index]}：${result.reason instanceof Error&&result.reason.message.endsWith('NOT_RECORDED')?'此历史研究未保存该版本的重放输入':result.reason instanceof Error?result.reason.message:'读取失败'}`]:[]));setBusy(false);
  }
  return <details className="gc-data-flow"><summary>事实计算与选值核验</summary><p>从保存的 K 线重新计算指标，并从宏观快照重新整理事实；不读取当前行情，不改动本轮研究结果。</p>
    <button type="button" className="gc-text-button" disabled={busy} onClick={replay}>{busy?'正在核验…':'重放本轮事实处理'}</button>
    {errors.map(error=><p role="status" key={error}>{error}</p>)}
    {indicators&&<div className="gc-flow-replay"><b>指标输入已保存 {indicators.recorded}/{indicators.expected} 个周期{!indicators.complete?' · 覆盖不完整':''}</b>
      {indicators.checks.map(check=><span key={check.frame}>{check.frame==='D'?'日线':`${check.frame}分钟`}：{!check.supported?'版本不支持':check.matches?'数值一致':'数值不一致'} · {check.candles} 根 K 线</span>)}</div>}
    {analysis&&<div className="gc-flow-replay"><b>宏观事实：{!analysis.supported?'版本不支持':analysis.matches?'重放一致':'重放不一致'}</b>
      {analysis.selection?.map((item,index)=><span key={index}>{item.indicator} · {item.candidates} 份候选 → {item.source}{item.ambiguous?' · 同质量冲突，相应值未知':item.disagreement?' · 来源有分歧':''}</span>)}</div>}
    {news&&<div className="gc-flow-replay"><b>摘要选择：{!news.supported?'版本不支持':news.matches?'重放一致':'重放不一致'}</b><span>已完成 {news.completed??0} 条 · 未采用 {news.omitted??0} 条</span></div>}
  </details>;
}
