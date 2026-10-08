import type { Budget } from './api';

const names: Record<string, string> = { WITHIN_BUDGET: '材料在安全预算内', COMPACTED_WITH_ARCHIVE: '已压缩摘要，原始材料归档', BLOCKED_BY_BUDGET: '预算不足，已阻止发送', LEGACY_NOT_RECORDED: '历史记录未保存余量' };
export default function ContextBudget({ budget, audit = false }: { budget: Budget; audit?: boolean }) {
  const limits = budget.limits;
  const utilization = budget.utilizationPercent;
  return <div className="gc-context-budget">
    <div className="gc-context-heading"><span>{names[budget.preparation ?? ''] ?? '历史输入记录'}</span>{limits && <b>{utilization}%</b>}</div>
    {limits && <><div className={`gc-context-meter ${budget.preparation === 'BLOCKED_BY_BUDGET' ? 'gc-context-blocked' : ''}`} role="meter" aria-label="输入预算占用" aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.min(100, utilization ?? 0)} aria-valuetext={`${utilization}% ，安全发送目标不超过${limits.targetPercent}%`}><i style={{ width: `${Math.min(100, utilization ?? 0)}%` }}/><b style={{ left: `${limits.targetPercent}%` }}/></div><small>安全目标 ≤{limits.targetPercent}% · 输出预留 {budget.outputReserve} · 额外上下文余量 {limits.safetyTokens}</small></>}
    {audit && <><div className="gc-context-counts"><span>字符 <b>{budget.characters} / {limits?.characters ?? '未记录'}</b></span><span>UTF-8字节 <b>{budget.bytes} / {limits?.bytes ?? '未记录'}</b></span><span>输入估算 <b>{budget.gatewayInputEstimate} / {limits?.inputEstimate ?? '未记录'}</b></span><span>消息 <b>{budget.messages} / {limits?.messageLimit ?? '未记录'}</b></span></div><p>输入估算使用完整请求UTF-8字节÷3向上取整；包含指令和函数schema。实际模型token计量可能不同。</p><p>目录声明：输入 {limits?.declaredInput || '未知'} · 上下文 {limits?.declaredContext || '未知'} · 输出 {limits?.declaredOutput || '未知'}。输出预留 {budget.outputReserve}，执行状态 {budget.outputEnforcement}。</p>{!!budget.changes?.length && <details><summary>压缩记录 · {budget.changes.length} 处</summary><p>原请求 {budget.originalCharacters} 字符 / {budget.originalBytes} 字节，保留价格、规则、账户、计划数值和全部引用。</p>{budget.changes.map(change => <div className="gc-context-change" key={change.path}><code>{change.path}</code><span>{change.originalCodePoints} → {change.sentCodePoints} 字符</span></div>)}</details>}{budget.sourcePacketHash && <details><summary>输入校验摘要</summary><p>原始材料 {budget.sourcePacketHash}</p><p>实际材料 {budget.sentPacketHash}</p><p>完整请求 {budget.inputHash}</p></details>}</>}
  </div>;
}
