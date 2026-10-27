import { useEffect, useState } from 'react';
import { call } from './api';
import type { Session } from './api';
import LoginPage from './LoginPage';
import './AccessTokensPanel.css';

export const scopeLabels: Record<string, [string, string]> = {
  'market:read': ['行情与指标', '报价、K 线、市场深度和 Java 计算的技术指标'],
  'information:read': ['信息数据库', '新闻原文、压缩摘要、经济日历与宏观驱动'],
  'research:read': ['AI 研究结果', '研究历史、辩论观点与裁决结果'],
};
type Consent = { clientName: string; redirectUri: string; scopes: string[]; resource: string; expiresAt: string; authorizationDays: number; authenticated: boolean };
const errors: Record<string, string> = {
  authorization_expired: '授权请求已失效。请回到 AI 客户端，重新发起连接。',
  authorization_limit_reached: '有效授权已达上限，请先在运行与配置中撤销不再使用的连接。',
  invalid_scope: '请选择至少一种请求的读取权限。',
  AUTH_REQUIRED: '登录已失效，请返回 AI 客户端重新连接。',
};
export default function OAuthConsentPage() {
  const request = new URLSearchParams(window.location.search).get('request') ?? '';
  const [session, setSession] = useState<Session | null>(null), [restoring, setRestoring] = useState(true);
  const [consent, setConsent] = useState<Consent | null>(null), [scopes, setScopes] = useState<string[]>([]);
  const [error, setError] = useState(''), [busy, setBusy] = useState('');
  useEffect(() => {
    const controller = new AbortController();
    void call<Consent>(`oauth/consent?request=${encodeURIComponent(request)}`, {}, undefined, controller.signal)
      .then(value => { setConsent(value); setScopes(value.scopes); })
      .catch(failure => { if (!controller.signal.aborted) setError(failure.message); });
    void call<Session>('session', {}, undefined, controller.signal).then(setSession).catch(() => {})
      .finally(() => { if (!controller.signal.aborted) setRestoring(false); });
    return () => controller.abort();
  }, [request]);
  const finish = async (decision: 'approve' | 'deny') => {
    if (!session) return;
    setBusy(decision); setError('');
    try {
      const result = await call<{ redirectUrl: string }>('oauth/consent', session, { request, decision, scopes });
      const target = new URL(result.redirectUrl);
      // Only the exact callback already validated and displayed by the server can be used.
      if (!consent || target.origin !== new URL(consent.redirectUri).origin || target.pathname !== new URL(consent.redirectUri).pathname) throw new Error('回调地址不匹配，请重新发起连接。');
      window.location.assign(result.redirectUrl);
    } catch (failure) { setError((failure as Error).message); setBusy(''); }
  };
  if (!error && !session && consent) return <LoginPage restoring={restoring} preservePath onLogin={setSession} />;
  return <div className="gc-app gc-oauth-page" style={{ colorScheme: 'dark' }}>
    <header className="gc-oauth-brand"><div className="gc-brand-mark">Au</div><div><strong>GOLD CATCHER</strong><span>黄金研究 · 数据授权</span></div></header>
    <main className="gc-access gc-oauth-card gc-panel" aria-labelledby="gc-oauth-heading">
      <span className="gc-access-eyebrow">CONNECT YOUR AI</span>
      <h1 id="gc-oauth-heading">允许访问黄金研究数据？</h1>
      {error && <p className="gc-live-error" role="alert">{errors[error] ?? error}</p>}
      {!consent ? <p role="status">{error ? '尚未建立连接。' : '正在核对授权请求…'}</p> : <>
        <div className="gc-oauth-client"><span className="gc-small">请求连接的应用</span><strong>{consent.clientName}</strong><span>将以 {session?.username ?? '你的账号'} 的身份读取所选数据</span></div>
        <div className="gc-oauth-callback"><span>授权后返回</span><strong>{new URL(consent.redirectUri).host}</strong><code>{consent.redirectUri}</code><p>应用名称由客户端提供，请核对返回地址是否属于你要连接的 AI 服务。</p></div>
        <fieldset className="gc-access-scopes"><legend>允许读取的内容</legend>{consent.scopes.map(scope => <label className="gc-access-scope" key={scope}><input type="checkbox" checked={scopes.includes(scope)} disabled={!!busy} onChange={event => setScopes(current => event.target.checked ? [...current, scope] : current.filter(value => value !== scope))} /><span><strong>{scopeLabels[scope]?.[0] ?? scope}</strong><span>{scopeLabels[scope]?.[1]}</span></span></label>)}</fieldset>
        <p className="gc-oauth-boundary">仅可读取上述数据。不会授予配置修改、启动研究或订单操作权限。</p>
        <p className="gc-small">授权有效期 {consent.authorizationDays} 天。可随时在“运行与配置 → PAT 令牌与 MCP”撤销连接。访问令牌由客户端自动更新。</p>
        <div className="gc-oauth-actions"><button className="gc-live-button" disabled={!session || !!busy} onClick={() => void finish('deny')}>{busy === 'deny' ? '正在返回…' : '拒绝'}</button><button className="gc-live-button gc-live-primary" disabled={!session || !!busy || !scopes.length || error === 'authorization_expired'} onClick={() => void finish('approve')}>{busy === 'approve' ? '正在连接…' : '允许连接'}</button></div>
      </>}
      <a className="gc-oauth-home" href="/">返回工作台</a>
    </main>
  </div>;
}
