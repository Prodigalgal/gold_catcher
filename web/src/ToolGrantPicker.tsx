import { useId } from 'react';
import { scopeLabels } from './OAuthConsentPage';

export type ToolGrant = { name: string; title: string; scope: string; readOnly: boolean };
export function scopedTools(names: string[] | null, scopes: string[], tools: ToolGrant[]) {
  return names === null ? null : tools.filter(tool => scopes.includes(tool.scope) && names.includes(tool.name)).map(tool => tool.name);
}

export default function ToolGrantPicker({ tools, scopes, value, onChange, disabled = false }: {
  tools: ToolGrant[]; scopes: string[]; value: string[] | null; onChange: (value: string[] | null) => void; disabled?: boolean;
}) {
  const id = useId();
  const available = tools.filter(tool => scopes.includes(tool.scope));
  const count = value === null ? available.length : available.filter(tool => value.includes(tool.name)).length;
  return <details className="gc-access-tool-picker">
    <summary>工具范围 · {value === null ? `全部 ${count} 项` : `指定 ${count} / ${available.length} 项`}</summary>
    <fieldset className="gc-access-scopes" disabled={disabled || !tools.length}>
      <legend>选择此 key 可用的工具</legend>
      <label className="gc-access-scope"><input type="radio" name={id} checked={value === null} onChange={() => onChange(null)} /><span><strong>所选权限内全部工具</strong><span>新增工具只要属于已授权权限，就会自动可见。</span></span></label>
      <label className="gc-access-scope"><input type="radio" name={id} checked={value !== null} onChange={() => onChange(available.map(tool => tool.name))} /><span><strong>仅指定工具</strong><span>新增工具需回到此处手动勾选；未勾选的调用会被服务端拒绝。</span></span></label>
      {value !== null && <>
        <div className="gc-access-client-tabs"><button type="button" className="gc-live-button" onClick={() => onChange(available.map(tool => tool.name))}>选择全部可用工具</button><button type="button" className="gc-live-button" onClick={() => onChange([])}>清空</button></div>
        <div className="gc-access-tool-list" role="group" aria-label="逐项授权工具">
          {scopes.map(scope => <div key={scope}><strong className="gc-access-tool-group">{scopeLabels[scope]?.[0] ?? scope}</strong>{available.filter(tool => tool.scope === scope).map(tool => <label key={tool.name} className="gc-access-scope"><input type="checkbox" checked={value.includes(tool.name)} onChange={event => onChange(event.target.checked ? [...value, tool.name] : value.filter(name => name !== tool.name))} /><span><strong>{tool.title}{tool.readOnly ? '' : ' · 操作'}</strong><code>{tool.name}</code></span></label>)}</div>)}
        </div>
        {!count && <p className="gc-small">尚未选择工具。保存后该 key 将无法调用任何工具。</p>}
      </>}
    </fieldset>
  </details>;
}
