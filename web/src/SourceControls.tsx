import { useEffect, useState } from 'react';
import { call } from './api';
import type { Session } from './api';
type Selection = { configuration: { revision: number; updatedAt: string; disabledSourceIds: string[] }; channels: { id: string; enabled: boolean; reason: string }[]; scope: string };
export default function SourceControls({ session, changed }: { session: Session; changed: () => void }) {
  const [data,setData]=useState<Selection|null>(null),[disabled,setDisabled]=useState<string[]>([]),[error,setError]=useState(''),[notice,setNotice]=useState(''),[busy,setBusy]=useState(false);
  useEffect(()=>{const controller=new AbortController();call<Selection>('sources/configuration',session,undefined,controller.signal).then(value=>{setData(value);setDisabled(value.configuration.disabledSourceIds);}).catch(failure=>{if(!controller.signal.aborted)setError((failure as Error).message);});return ()=>controller.abort();},[session.username]);
  async function save(){if(!data)return;setBusy(true);setNotice('');try{const value=await call<Selection>('sources/configuration',session,{revision:data.configuration.revision,disabledSourceIds:disabled});setData(value);setDisabled(value.configuration.disabledSourceIds);setError('');setNotice('信源选择已保存，后续采集与新研究立即采用。');changed();}catch(failure){setError((failure as Error).message);}finally{setBusy(false);}}
  return <section className="gc-panel gc-source-selection">
    <div className="gc-panel-head"><h3>信源精选</h3>{data&&<span className="gc-badge">资讯通道启用 {data.channels.length-disabled.length}/{data.channels.length}</span>}</div>
    <p className="gc-small">{data?.scope??'读取当前信源选择…'}</p>
    {data&&<><p className="gc-small">已停用：{data.configuration.disabledSourceIds.join('、')||'无'} · 配置 v{data.configuration.revision}</p>
      <details><summary>调整资讯与官方日历来源</summary><div className="gc-source-choice-list">{data.channels.map(channel=><label key={channel.id} className="gc-source-choice"><input type="checkbox" disabled={busy} checked={!disabled.includes(channel.id)} onChange={event=>setDisabled(old=>event.target.checked?old.filter(id=>id!==channel.id):[...old,channel.id])}/><span><b>{channel.id}</b><small>{channel.reason}</small></span></label>)}</div></details>
      <button className="gc-live-button" disabled={busy} onClick={()=>void save()}>{busy?'保存中…':'保存信源选择'}</button></>}
    {error&&<p className="gc-live-error" role="alert">{error}</p>}{notice&&<p className="gc-small" role="status">{notice}</p>}
  </section>;
}
