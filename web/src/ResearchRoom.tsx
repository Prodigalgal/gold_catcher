import { useState } from 'react';

type Message = {
  id: string; actor: string; role: string; stage: number; time: string;
  title: string; analysis: string; output: string; reference?: string; quote?: string;
};
const stages = ['独立判断', '匿名辩论', '同步修订', '综合裁决'];
const messages: Message[] = [
  {id:'a-draft',actor:'A',role:'趋势分析',stage:0,time:'14:30',title:'倾向做多，等待回踩确认',
    analysis:'趋势结构偏强，但入场时间与方向是两个问题。需要确认回踩后报价恢复，同时估算开平成本。',
    output:'候选方向：做多。操作：等待回踩，不立即生成可执行订单。失效：支撑破坏或新证据改变判断。'},
  {id:'b-draft',actor:'B',role:'宏观与事件',stage:0,time:'14:30',title:'事件风险尚未排除，我选择等待',
    analysis:'已有背景利率信息对黄金有压力，但时效较慢。当前事件窗口缺少实际值与预期差，无法据此确认方向。',
    output:'当前选择：等待。需要补充：实际发布值、美元与利率反应、最新报价。'},
  {id:'c-draft',actor:'C',role:'成交与成本',stage:0,time:'14:30',title:'先核对计划能否覆盖成本',
    analysis:'短线方向正确并不保证净收益。点差、手续费、持有成本与可成交数量都会影响候选计划。',
    output:'候选计划需要成本与实际报价支持。费用未知时保留未知结果，不输出精确盈利数字。'},
  {id:'b-review',actor:'B',role:'宏观与事件',stage:1,time:'14:31',title:'A 的方向依据还不足以支持入场',reference:'a-draft',quote:'A：倾向做多，等待回踩确认。',
    analysis:'均线反映既有价格结构，不能替代事件后的新增信息。A 的方案应说明事件改变方向时如何失效。',
    output:'对 A 的反证：事件公布后若美元和利率同向施压，回踩可能变成趋势反转。建议加入更新证据与失效条件。'},
  {id:'a-review',actor:'A',role:'趋势分析',stage:1,time:'14:31',title:'接受 B 的风险提醒，但等待也应有条件',reference:'b-draft',quote:'B：事件风险尚未排除，我选择等待。',
    analysis:'完全等待可能遗漏已有价格确认。我认为应记录重新评估的触发条件，避免判断长期停留在等待。',
    output:'对 B 的补充：等待到新证据或报价条件成立时立即重新研究；不能把较慢背景数据当作刚发生的事件。'},
  {id:'c-review',actor:'C',role:'成交与成本',stage:1,time:'14:31',title:'A 的提案还需要成交和费用条件',reference:'a-draft',quote:'A：候选方向做多，等待回踩后确认。',
    analysis:'触及计划价格只能证明触价。实际买卖报价、深度、订单生效时间与费用必须能支撑内部成交评价。',
    output:'对 A 的核验要求：说明触发与成交的区别；在费用和报价不足时，不将候选计划显示成已成交或有确定净利润。'},
  {id:'a-revision',actor:'A',role:'趋势分析',stage:2,time:'14:32',title:'我修改提案，保留方向，暂时等待',reference:'b-review',quote:'B：事件可能使回踩转为趋势反转。',
    analysis:'收到同一批反证后，我保留趋势观察，并增加事件更新、实际报价和成本确认条件。',
    output:'修订：偏多观察 → 候选做多但等待确认。任何报价、事件或成本条件不满足时，暂不成为可执行内部订单。'},
  {id:'b-revision',actor:'B',role:'宏观与事件',stage:2,time:'14:32',title:'条件满足后可以重新评估，保留事件分歧',reference:'a-review',quote:'A：应说明等待结束后重新研究的触发条件。',
    analysis:'这一补充合理。我同意新增信息出现时及时复核，仍保留事件尚未明确的观点。',
    output:'修订：等待并监听事件 / 行情更新。对较长周期与短线分别判断，不要求它们得出同一方向。'},
  {id:'c-revision',actor:'C',role:'成交与成本',stage:2,time:'14:32',title:'保留成交核验，补充等待时的成本复核',reference:'a-review',quote:'A：新证据或报价条件成立时，应立即重新研究。',
    analysis:'我接受及时复核的建议。行情条件变化时，成交价、可成交数量与持有成本也需要同步更新，不能沿用旧估算。',
    output:'修订：重新评估触发时，由 Java 提供最新报价、成本与账户快照，再判断净收益和风险。证据不足时继续等待。'},
  {id:'judge',actor:'J',role:'综合裁决',stage:3,time:'14:33',title:'保留偏多候选，当前结论为等待确认',reference:'a-revision',quote:'A：保留方向，增加事件、报价和成本条件。',
    analysis:'趋势观点提供候选方向，B 的事件反证与 C 的成交核验限制其可执行性。不能仅按发言数量或篇幅裁决。',
    output:'最终示例：等待回踩与新证据确认，再评估候选计划。采纳 B 的事件失效条件和 C 的成本核验；保留宏观方向分歧。'},
];

export default function ResearchRoom({ product, stage, onStageChange, onTrades }: {
  product: string; stage: number; onStageChange: (value:number)=>void; onTrades:()=>void;
}) {
  const [highlighted,setHighlighted] = useState<string|null>(null);
  const [raw,setRaw] = useState<string|null>(null);
  const [audit,setAudit] = useState(false);
  const [question,setQuestion] = useState('');
  const [notes,setNotes] = useState<string[]>([]);
  const shown = stage === -1 ? messages : messages.filter(message=>message.stage===stage);
  const quote = (id: string) => {
    onStageChange(-1);
    setHighlighted(id);
    requestAnimationFrame(()=>document.getElementById(`gc-message-${id}`)?.scrollIntoView({
      block:'center',behavior:'instant'
    }));
  };
  return <>
    <section className="gc-panel gc-room-header"><div><span className="gc-eyebrow">研究室 · {product} · 设计样例</span><h2>多 AI 交叉研究与辩论</h2><p>独立分析 → 引用与反驳 → 修订提案 → 综合裁决</p></div><div className="gc-room-count"><b>3 位研究 AI + 1 位裁决 AI</b><span>模型路由待配置 · 内容为示例</span></div></section>
    <div className="gc-rounds" aria-label="研究消息阶段">{[['完整对话',-1],...stages.map((name,index)=>[name,index] as const)].map(([name,value])=><button key={name} type="button" aria-pressed={stage===value} onClick={()=>onStageChange(value as number)}>{name}</button>)}</div>
    <div className="gc-room-layout"><section className="gc-chat-stream" aria-label="多 AI 讨论消息">
      <div className="gc-chat-system">同一材料截止快照 · 各阶段完成后，同批共享给下一阶段</div>
      <div className="gc-chat-status" role="status">{stage===-1?'完整对话':stages[stage]} · {shown.length} 条示例发言</div>
      {shown.map((message,index)=><div className="gc-chat-record" key={message.id}>
        {(index===0 || shown[index-1].stage!==message.stage)&&<div className="gc-chat-divider"><span>{stages[message.stage]}</span>{message.stage===0&&<small>独立稿在阶段截止后统一解封</small>}{message.stage===1&&<small>本轮引用上一轮材料</small>}</div>}
        <article className={`gc-chat-message ${highlighted===message.id?'gc-message-highlight':''}`} id={`gc-message-${message.id}`}>
          <div className={`gc-ai-avatar ${message.actor==='J'?'gc-judge-avatar':''}`} aria-hidden="true">{message.actor}</div>
          <div className="gc-chat-bubble"><div className="gc-message-meta"><b>AI {message.actor}</b><span>{message.role}</span><span className="gc-message-time">示例 {message.time} · {stages[message.stage]}</span></div>
            {message.reference&&<button type="button" className="gc-message-quote" onClick={()=>quote(message.reference!)}><span>引用上轮发言 ↗</span>{message.quote}</button>}
            <h3>{message.title}</h3><div className="gc-message-analysis"><span>思考 / 分析摘要 · 示例</span><p>{message.analysis}</p></div>
            <div className="gc-message-output"><span>正式输出 · 示例</span><p>{message.output}</p></div>
            <div className="gc-message-actions"><button className="gc-text-button" type="button" aria-expanded={raw===message.id} onClick={()=>setRaw(raw===message.id?null:message.id)}>{raw===message.id?'收起输出结构':'查看示例输出结构'} ↗</button><span>材料截止与所属阶段随消息保存</span></div>
            {raw===message.id&&<pre className="gc-message-json">{JSON.stringify({speaker:`AI ${message.actor}`,stage:stages[message.stage],analysisSummary:message.analysis,output:message.output,quotedMessageId:message.reference??null,source:'DESIGN_EXAMPLE'},null,2)}</pre>}
          </div>
        </article>
      </div>)}
      {notes.map((note,index)=><article className="gc-chat-message" key={`note-${index}`}><div className="gc-ai-avatar" aria-hidden="true">你</div><div className="gc-chat-bubble"><div className="gc-message-meta"><b>观察者关注点</b><span>本地演示 · 下一轮材料</span></div><p className="gc-observer-note">{note}</p></div></article>)}
      <div className="gc-chat-composer"><label htmlFor="gc-research-question">补充下一轮的关注点</label><textarea id="gc-research-question" value={question} onChange={event=>setQuestion(event.target.value)} placeholder="例如：请各方进一步讨论这条快讯是否已被价格消化。" rows={2}/><div><span>设计演示只记录本地消息</span><button type="button" disabled={!question.trim()} onClick={()=>{setNotes([...notes,question.trim()]);setQuestion('');}}>记录关注点</button></div></div>
    </section><aside className="gc-room-sidebar" aria-label="研究参与者与结论">
      <section className="gc-panel"><div className="gc-panel-head"><h3>参与者</h3><span className="gc-badge gc-muted-badge">路由待配置</span></div>{[['A','趋势分析'],['B','宏观与事件'],['C','成交与成本'],['J','综合裁决']].map(([actor,role])=><div className="gc-participant" key={actor}><div className="gc-ai-avatar" aria-hidden="true">{actor}</div><div><b>AI {actor}</b><span>{role}</span></div><span className="gc-participant-state">示例</span></div>)}</section>
      <section className="gc-panel gc-room-verdict"><span className="gc-eyebrow">置顶裁决 · 示例</span><h3>偏多候选，等待确认</h3><p>采纳事件风险与成本核验，保留不同意见。</p><div className="gc-factor"><span>短线</span><b>等待回踩与新证据</b></div><div className="gc-factor"><span>较长周期</span><b>保留宏观分歧</b></div><button type="button" className="gc-text-button" onClick={onTrades}>查看候选计划 ↗</button></section>
      <section className="gc-panel"><div className="gc-panel-head"><h3>讨论材料</h3></div><div className="gc-room-material"><span>价格与 Java 指标</span><span>消息影响与经济日历</span><span>产品、报价与交易成本</span><span>匿名观点与上一轮反馈</span></div><button type="button" className="gc-text-button" aria-expanded={audit} onClick={()=>setAudit(!audit)}>{audit?'收起':'展开'} 输入与讨论审计 ↗</button>{audit&&<div className="gc-room-audit"><p>相同截止快照，首轮相互不可见。</p><p>各通道分别核验上下文预算；阅读次序轮换。</p><p>迟到输出归档，不倒填已经结束的阶段。</p><p>界面完整记录与模型实际输入包分别保存。</p></div>}</section>
    </aside></div>
    <div className="gc-info-foot">分析区域展示接口提供的分析内容或显式分析摘要，并标注来源；接口未提供的内容显示缺失。此页全部为设计样例。</div>
  </>;
}
