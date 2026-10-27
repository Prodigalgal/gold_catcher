import { useEffect, useState } from 'react';
import { call } from './api';
import type { Session } from './api';
import { scopeLabels } from './OAuthConsentPage';

type Grant = { id: string; clientName: string; redirectUri: string; scopes: string[]; createdAt: string; expiresAt: string; lastUsedAt: string | null; useCount: number; state: string };
type Connections = { authorizations: Grant[] };
const stamp = (value: string | null) => value ? new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false }) : '尚未使用';
export default function OAuthConnectionsPanel({ session }: { session: Session }) {
  const [value, setValue] = useState<Connections | null>(null), [error, setError] = useState('');
  const [busy, setBusy] = useState(''), [confirm, setConfirm] = useState(''), [notice, setNotice] = useState('');
  useEffect(() => {
    const controller = new AbortController();
    call<Connections>('access/oauth', session, undefined, controller.signal).then(setValue).catch(failure => { if (!controller.signal.aborted) setError(failure.message); });
    return () => controller.abort();
  }, [session]);
  const refresh = async () => {
    setBusy('refresh'); setError('');
    try { setValue(await call<Connections>('access/oauth', session)); }
    catch (failure) { setError((failure as Error).message); } finally { setBusy(''); }
  };
  const revoke = async (grant: Grant) => {
    setBusy(grant.id); setError('');
    try { await call(`access/oauth/${grant.id}/revoke`, session, {}); setConfirm(''); setNotice(`已撤销“${grant.clientName}”的 OAuth 授权。`); setValue(await call<Connections>('access/oauth', session)); }
    catch (failure) { setError((failure as Error).message); } finally { setBusy(''); }
  };
  return <section className="gc-panel" aria-labelledby="gc-oauth-connections-title">
    <div className="gc-panel-head"><div><h3 id="gc-oauth-connections-title">OAuth 已授权连接</h3><span className="gc-small">{value ? `${value.authorizations.filter(row => row.state === 'ACTIVE').length} 个有效连接` : '正在读取'} · 与 PAT 分别管理</span></div><button className="gc-live-button" disabled={!!busy} onClick={() => void refresh()}>刷新连接</button></div>
    <p className="gc-small">在 ChatGPT 等客户端选择 OAuth 连接本服务，使用现有账号登录并确认读取权限，无需粘贴 PAT。</p>
    {error && <p className="gc-live-error" role="alert">{error}</p>}<p role="status" className="gc-access-notice">{notice}</p>
    {!value ? <p role="status">正在读取授权连接…</p> : !value.authorizations.length ? <div className="gc-access-empty"><strong>还没有 OAuth 连接</strong><p>在 AI 客户端添加 MCP 服务并完成登录授权后，连接会显示在这里。</p></div> : <div className="gc-live-table-wrap"><table className="gc-live-table gc-access-table"><thead><tr><th>应用 / 返回地址</th><th>读取权限</th><th>状态 / 到期时间</th><th>最近使用 / 请求数</th><th>操作</th></tr></thead><tbody>{value.authorizations.map(grant => <tr key={grant.id}><td><strong>{grant.clientName}</strong><span className="gc-small">{new URL(grant.redirectUri).host}</span><span className="gc-small">创建 {stamp(grant.createdAt)}</span></td><td>{grant.scopes.map(scope => <span key={scope} className="gc-access-scope-tag">{scopeLabels[scope]?.[0] ?? scope}</span>)}</td><td><span className={`gc-badge ${grant.state === 'ACTIVE' ? 'gc-positive' : ''}`}>{({ ACTIVE: '有效', REVOKED: '已撤销', EXPIRED: '已过期' } as Record<string,string>)[grant.state] ?? grant.state}</span><span className="gc-small">{stamp(grant.expiresAt)}</span></td><td>{stamp(grant.lastUsedAt)}<span className="gc-small">{grant.useCount.toLocaleString()} 次已认证请求</span></td><td>{grant.state === 'ACTIVE' && (confirm === grant.id ? <div className="gc-access-revoke"><span>撤销此应用的全部令牌？</span><button className="gc-live-button gc-negative" disabled={!!busy} onClick={() => void revoke(grant)}>确认撤销</button><button className="gc-live-button" disabled={!!busy} onClick={() => setConfirm('')}>保留</button></div> : <button className="gc-live-button" disabled={!!busy} onClick={() => setConfirm(grant.id)}>撤销授权</button>)}</td></tr>)}</tbody></table></div>}
  </section>;
}
