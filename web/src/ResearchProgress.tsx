import type { Run } from './api';
import FactReplayPanel from './FactReplayPanel';

const names=['独立研究','交叉质疑','修订观点','三方裁决'];
const labels:Record<string,string>={SUBMITTED:'已完成',WAITING_RESPONSE:'等待返回',RETRY_WAITING:'等待重试',FAILED:'本次失败',TIMED_OUT:'阶段超时',INTERRUPTED:'重启中断',LATE_ARCHIVED:'迟到归档'};
export default function ResearchProgress({run,time}:{run:Run;time:(value?:string|null)=>string}){
  if(!run.retryPolicy)return null;
  const actors=run.actors.filter(actor=>actor.id.startsWith('J')===(run.stage===3));
  const count=(stage:number)=>new Set(run.messages.filter(message=>message.stage===stage&&message.state==='SUBMITTED').map(message=>message.actorId)).size;
  return <section className="gc-panel gc-research-progress" aria-label="研究推进与重试">
    <div className="gc-panel-head"><h3>研究推进与重试</h3><span className="gc-badge">{count(run.stage)}/{actors.length} 席完成 · 重试 {run.messages.filter(message=>(message.attempt??1)>1).length} 次</span></div>
    <div className="gc-research-steps">{names.map((name,index)=><div className={index===run.stage?'gc-research-step active':'gc-research-step'} key={name}><b>{index+1} · {name}</b><small>{run.sealedStages?.includes(index)?`已封存 · ${count(index)} 份有效输出`:index===run.stage?'当前阶段':'待推进'}</small></div>)}</div>
    <div className="gc-research-attempts">{actors.map(actor=>{const messages=run.messages.filter(message=>message.stage===run.stage&&message.actorId===actor.id);const message=messages.find(item=>item.state==='SUBMITTED')??messages.at(-1);return <article key={actor.id}><b>{actor.id} · {actor.model.split('/').pop()}</b><span>{message?labels[message.state]??message.state:'尚未请求'} · 第 {message?.attempt||1}/{run.retryPolicy?.maxAttempts} 次</span>{message?.retryAt&&<small>下次尝试 {time(message.retryAt)}</small>}{message?.error&&<small>{message.error}</small>}</article>;})}</div>
    <p className="gc-small">研究执行截止 {time(run.workflowExpiresAt)} · 交易有效截止 {time(run.decisionExpiresAt)}{run.recoveryCount?` · 重启恢复 ${run.recoveryCount} 次`:''}</p>
    {run.decisionExpired&&<p className="gc-research-expired" role="status">交易有效期已结束，继续完成讨论与裁决；本轮结论不产生内部订单。</p>}
    <FactReplayPanel key={run.id} runId={run.id}/>
  </section>;
}
