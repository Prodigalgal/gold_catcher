import {useEffect, useState} from 'react';
import {call} from './api';
import './DataFlowPanel.css';

type Step={name:string;rule:string;inputCharacters:number;outputCharacters:number;inputHash:string;outputHash:string;changedPaths:string[]};
type Detail={snapshot:{id:string;version:string;kind:string;state:string;error:string|null;input:{version:string;packet:unknown};flow:{output:unknown;steps:Step[]};body:string;bodyHash:string;accepted:unknown};exchanges:{number:number;status:number|null;error:string|null;requestId:string|null}[]};
type Replay={supported:boolean;inputMatches:boolean;requestMatches:boolean;stepsMatch:boolean;responseState:string;responseMatches:boolean|null;responseError:string|null};
type StageSnapshot={step:Step;input:unknown;output:unknown};
const labels:Record<string,string>={boundary:'输入边界', 'effective-facts':'有效事实', 'exact-duplicates':'精确去重','numeric-duplicates':'数值去重','peer-prose':'观点去重','scope-notes':'适用口径','table-common':'公共字段','driver-groups':'驱动分组','release-schema':'发布表结构'};
export default function DataFlowPanel({id}:{id:string}){
  const [open,setOpen]=useState(false),[detail,setDetail]=useState<Detail|null>(null),[replay,setReplay]=useState<Replay|null>(null),[error,setError]=useState(''),[busy,setBusy]=useState(false);
  const [stages,setStages]=useState<Record<string,StageSnapshot>>({}),[loadingStage,setLoadingStage]=useState('');
  useEffect(()=>{setDetail(null);setReplay(null);setError('');if(!open)return;const controller=new AbortController();
    call<Detail>(`data-flows/${encodeURIComponent(id)}`,{},undefined,controller.signal).then(setDetail).catch(e=>{if(!controller.signal.aborted)setError(e instanceof Error?e.message:'读取失败');});
    return()=>controller.abort();},[id,open]);
  async function runReplay(){setBusy(true);setError('');try{setReplay(await call<Replay>(`data-flows/${encodeURIComponent(id)}/replay`));}catch(e){setError(e instanceof Error?e.message:'重放失败');}finally{setBusy(false);}}
  async function inspectStage(name:string){setLoadingStage(name);setError('');try{const value=await call<StageSnapshot>(`data-flows/${encodeURIComponent(id)}/stages/${encodeURIComponent(name)}`);setStages(previous=>({...previous,[name]:value}));}catch(e){setError(e instanceof Error?e.message:'读取失败');}finally{setLoadingStage('');}}
  return <details className="gc-data-flow" open={open} onToggle={e=>setOpen(e.currentTarget.open)}><summary>数据流明细与重放</summary>
    <p>读取本次保存的输入、处理规则和输出。重放复用归档响应，不发起模型请求或修改模拟订单。</p>
    {error&&<p role="alert" className="gc-flow-error">{error}</p>}{!detail&&!error&&<p role="status">正在读取记录…</p>}
    {detail&&<><div className="gc-flow-meta"><span>{detail.snapshot.kind==='COMPRESSION'?'信息压缩':'AI研究'} · {detail.snapshot.version} · {detail.snapshot.input.version}</span><b>{detail.snapshot.state==='VALIDATED'?'输出校验通过':detail.snapshot.state==='FAILED'?'调用失败':'等待终态'}</b></div>
      <ol className="gc-flow-steps">{detail.snapshot.flow.steps.map(step=><li key={step.name}><details><summary><b>{labels[step.name]??step.name}</b><span>{step.inputCharacters.toLocaleString()} → {step.outputCharacters.toLocaleString()} 字符</span></summary>
        <p>{step.rule}</p><p>{step.changedPaths.length?`变更 ${step.changedPaths.length} 个字段位置`:'本步未改变数据'}</p>
        {step.changedPaths.map(path=><code key={path}>{path}</code>)}<small>输入校验 {step.inputHash}<br/>输出校验 {step.outputHash}</small>
        {!stages[step.name]?<button type="button" className="gc-text-button" disabled={!!loadingStage} onClick={()=>inspectStage(step.name)}>{loadingStage===step.name?'正在读取…':'查看本步流入与流出'}</button>:<><details><summary>本步流入</summary><pre>{JSON.stringify(stages[step.name].input,null,2)}</pre></details><details><summary>本步流出</summary><pre>{JSON.stringify(stages[step.name].output,null,2)}</pre></details></>}
        </details></li>)}</ol>
      <p>步骤字符数按规范 JSON 分别计量；实际请求另含指令、协议及传输编码，以预算统计为准。</p>
      <button type="button" className="gc-text-button" disabled={busy} onClick={runReplay}>{busy?'正在核验…':'重放并核验一致性'}</button>
      {replay&&<div role="status" className="gc-flow-replay">{!replay.supported?<b>当前版本不支持重放此规则版本</b>:<>
        <span>处理输出：{replay.inputMatches?'一致':'不一致'}</span><span>请求字节：{replay.requestMatches?'一致':'不一致'}</span><span>逐步记录：{replay.stepsMatch?'一致':'不一致'}</span>
        <span>响应重放：{replay.responseMatches===true?'与原结果一致':replay.responseMatches===false?'与原结果不一致':replay.responseState==='TRANSPORT_WITHOUT_COMPLETE_RESPONSE'?'无完整响应，只核验输入':'尚无可重放响应'}</span>
        {replay.responseError&&<code>{replay.responseError}</code>}</>}</div>}
      {replay?.supported&&!replay.requestMatches&&detail.snapshot.input.version==='INFERENCE_REQUEST_V1'&&<p>旧版记录未保留 Java 数值类型，可能导致 YAML 数字表示不同；字节核验仍按不一致显示，原始请求可在下方查看。</p>}
      <details><summary>流入数据</summary><pre>{JSON.stringify(detail.snapshot.input.packet,null,2)}</pre></details>
      <details><summary>处理后的模型事实</summary><pre>{JSON.stringify(detail.snapshot.flow.output,null,2)}</pre></details>
      <details><summary>实际请求内容</summary><pre>{detail.snapshot.body}</pre></details>
      <details><summary>校验后的输出</summary><pre>{JSON.stringify(detail.snapshot.accepted,null,2)}</pre>{detail.snapshot.error&&<p>{detail.snapshot.error}</p>}</details>
      {!!detail.exchanges.length&&<details><summary>传输记录 · {detail.exchanges.length} 次</summary>{detail.exchanges.map(row=><p key={row.number}>第 {row.number} 次 · HTTP {row.status??'未取得'} · {row.error??'已收到响应'}{row.requestId&&<small>上游请求 {row.requestId}</small>}</p>)}</details>}
    </>}
  </details>;
}
