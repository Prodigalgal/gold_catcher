import type { Run } from './api';

const panelStates: Record<string, string> = { MAJORITY: '形成多数', WAIT_MAJORITY: '多数选择等待', NO_MAJORITY: '未形成多数' };
const messageStates: Record<string, string> = { WAITING_RESPONSE: '等待返回', SUBMITTED: '有效裁决', FAILED: '裁决失败', TIMED_OUT: '裁决超时', LATE_ARCHIVED: '迟到归档' };

export default function JudgePanel({ run, showMessage }: { run: Run; showMessage: () => void }) {
  const judges = run.actors.filter(actor => actor.id.startsWith('J'));
  const panel = run.adjudication;
  const minimum = run.modelDiversity?.minimumJudges ?? 1;
  const submitted = run.messages.filter(message => message.stage === 3 && message.state === 'SUBMITTED').length;
  const reused = judges.filter(judge=>run.actors.some(actor=>!actor.id.startsWith('J')&&actor.model===judge.model));
  const candidate = (id: string) => id === 'WAIT' ? '等待' : `候选 ${run.messages.find(message => message.id === id)?.actorId ?? id}`;
  return <section className="gc-panel gc-judge-panel" aria-label="裁决投票">
    <div className="gc-panel-head"><h3>{minimum >= 3 ? '三方裁决' : '历史裁决'}</h3><span className="gc-badge">{panel?.validJudges ?? submitted}/{minimum} 有效</span></div>
    <p className="gc-small">{minimum >= 3 ? `本轮以新上下文同时盲评，彼此看不到裁决稿。${reused.length ? `${reused.length} 个裁决模型也参与了前面的辩论。` : '裁决使用另行配置的模型。'}` : '此记录采用历史的单模型裁决配置。'}</p>
    {judges.map(judge => {
      const vote = panel?.votes.find(item => item.actorId === judge.id);
      const message = run.messages.find(item => item.stage === 3 && item.actorId === judge.id);
      const choice = vote?.selectedCandidateId ?? message?.answer?.output?.selectedCandidateId;
      return <article className="gc-judge-vote" key={judge.id}>
        <div className="gc-panel-head"><b>{judge.id} · {judge.model.split('/').pop()}</b><span className={`gc-badge ${message?.state === 'FAILED' || message?.state === 'TIMED_OUT' ? 'gc-negative' : ''}`}>{message ? messageStates[message.state] ?? message.state : '尚未裁决'}</span></div>
        {choice && <p className="gc-judge-choice">选择：{candidate(choice)}{choice !== 'WAIT' && <a className="gc-text-button" href={`#message-${choice}`} onClick={showMessage}>查看候选 ↗</a>}</p>}
        {message?.error && <p className="gc-live-error">{message.error}</p>}
        {(vote?.reason || message?.answer?.output?.output) && <details><summary>裁决理由</summary><p className="gc-live-prose">{vote?.reason ?? message?.answer?.output?.output}</p></details>}
      </article>;
    })}
    {panel ? <><div className="gc-factor"><span>{panelStates[panel.state] ?? panel.state}</span><b>{panel.winningVotes}/{panel.validJudges} 票</b></div><p className="gc-small">多数门槛 {panel.majorityThreshold} 票。{panel.state === 'NO_MAJORITY' ? '未达门槛，保持 WAIT。' : `最终选择：${candidate(panel.selectedCandidateId)}。`}原方案数值完整保留，少数意见可在每条裁决中查看。</p><details className="gc-live-audit"><summary>完整投票记录</summary><pre>{JSON.stringify(panel, null, 2)}</pre></details></>
      : minimum >= 3 && <p className="gc-small">至少 {minimum} 个不同模型的有效裁决齐全后才汇总；三份裁决需至少两票选择同一方案。</p>}
  </section>;
}
