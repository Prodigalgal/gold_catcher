import { useEffect, useState } from 'react';
import { call } from './api';
import type { Session } from './api';
import LoginPage from './LoginPage';
import './AccessTokensPanel.css';

export const scopeLabels: Record<string, [string, string]> = {
  'market:read': ['行情与指标', '报价、K 线、市场深度和 Java 计算的技术指标'],
  'information:read': ['信息数据库', '新闻原文、压缩摘要、经济日历与宏观驱动'],
  'research:read': ['AI 研究结果', '研究历史、辩论观点与裁决结果'],
  'simulation:read': ['模拟账户与订单', '虚拟资金、订单、成交事件、盈亏复盘与导出'],
  'configuration:read': ['配置与模型状态', 'AI 端点、预算、路由、信源开关和模型目录；凭据不返回'],
  'system:read': ['系统运行与重放', '研究调度、压缩状态、行情归档、处理过程与操作回执'],
  'simulation:write': ['操作模拟交易', '创建、撤销、修改模拟订单，申请减仓和平仓；仅内部模拟'],
  'research:write': ['启动与调度研究', '启动、取消研究和修改自动调度；可能调用上游付费模型'],
  'information:write': ['重新压缩信息', '重新排队压缩新闻；可能调用上游模型'],
  'configuration:write': ['修改系统配置', '修改 AI 提供商、端点、模型、预算和信源开关，刷新目录'],
  'access:manage': ['管理外部访问', '创建和撤销令牌、设置永久有效、撤销 OAuth 授权；可创建凭据'],
};
export const defaultScopes = ['market:read', 'information:read', 'research:read'];
export const isWriteScope = (scope: string) => scope.endsWith(':write') || scope === 'access:manage';
type Consent = { clientName: string; redirectUri: string; scopes: string[]; resource: string; expiresAt: string; authorizationDays: number; authenticated: boolean };
const errors: Record<string, string> = {
  authorization_expired: '授权请求已失效。请回到 AI 客户端，重新发起连接。',
  authorization_limit_reached: '有效授权已达上限，请先在运行与配置中撤销不再使用的连接。',
  invalid_scope: '请选择至少一种请求的权限。',
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
      .then(value => { setConsent(value); setScopes(value.scopes.filter(scope => !isWriteScope(scope))); })
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
      <h1 id="gc-oauth-heading">授权连接 Gold Catcher？</h1>
      {error && <p className="gc-live-error" role="alert">{errors[error] ?? error}</p>}
      {!consent ? <p role="status">{error ? '尚未建立连接。' : '正在核对授权请求…'}</p> : <>
        <div className="gc-oauth-client"><span className="gc-small">请求连接的应用</span><strong>{consent.clientName}</strong><span>将以 {session?.username ?? '你的账号'} 的身份使用所选功能</span></div>
        <div className="gc-oauth-callback"><span>授权后返回</span><strong>{new URL(consent.redirectUri).host}</strong><code>{consent.redirectUri}</code><p>应用名称由客户端提供，请核对返回地址是否属于你要连接的 AI 服务。</p></div>
        {consent.scopes.some(isWriteScope) && <div className="gc-access-client-tabs"><button type="button" className="gc-live-button" disabled={!!busy} onClick={() => setScopes(consent.scopes)}>选择全部操作权限</button><button type="button" className="gc-live-button" disabled={!!busy} onClick={() => setScopes(consent.scopes.filter(scope => !isWriteScope(scope)))}>仅保留读取权限</button></div>}
        <fieldset className="gc-access-scopes"><legend>允许使用的功能</legend>{consent.scopes.map(scope => <label className="gc-access-scope" key={scope}><input type="checkbox" checked={scopes.includes(scope)} disabled={!!busy} onChange={event => setScopes(current => event.target.checked ? [...current, scope] : current.filter(value => value !== scope))} /><span><strong>{scopeLabels[scope]?.[0] ?? scope}{isWriteScope(scope) ? ' · 操作权限' : ''}</strong><span>{scopeLabels[scope]?.[1]}</span></span></label>)}</fieldset>
        <p className="gc-oauth-boundary">{scopes.some(isWriteScope) ? '已选择操作权限，连接的 AI 可以修改对应状态。模拟订单只在系统内部记账，研究和压缩可能消耗上游模型额度。' : '当前只允许读取。勾选操作权限后，AI 才能执行对应操作。'}</p>
        <p className="gc-small">授权有效期 {consent.authorizationDays} 天。可随时在“运行与配置 → PAT 令牌与 MCP”撤销连接。访问令牌由客户端自动更新。</p>
        <div className="gc-oauth-actions"><button className="gc-live-button" disabled={!session || !!busy} onClick={() => void finish('deny')}>{busy === 'deny' ? '正在返回…' : '拒绝'}</button><button className="gc-live-button gc-live-primary" disabled={!session || !!busy || !scopes.length || error === 'authorization_expired'} onClick={() => void finish('approve')}>{busy === 'approve' ? '正在连接…' : '允许连接'}</button></div>
      </>}
      <a className="gc-oauth-home" href="/">返回工作台</a>
    </main>
  </div>;
}
