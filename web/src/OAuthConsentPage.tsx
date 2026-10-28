import { useEffect, useRef, useState } from 'react';
import { call } from './api';

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
type Consent = { clientName: string; redirectUri: string; resource: string; expiresAt: string; authorizationDays: number; authenticationMethod: 'PAT'; csrfHeader: string; csrfToken: string };
type Preview = { tokenName: string; tokenPrefix: string; tokenExpiresAt: string | null; scopes: string[]; tools: { name: string; title: string; scope: string; readOnly: boolean }[] };
const errors: Record<string, string> = {
  authorization_expired: '授权请求已失效，请返回 AI 客户端重新连接。',
  authorization_limit_reached: '有效连接已达上限，请先撤销不再使用的连接。',
  invalid_personal_access_token: 'PAT 无效、已过期或已撤销，请检查后重试。',
  personal_access_token_scope_mismatch: '该 PAT 没有客户端请求的权限，请在令牌管理页调整或更换 PAT。',
  consent_rate_limited: '验证失败次数过多，请返回 AI 客户端重新发起连接。',
  CSRF_OR_ACCESS_DENIED: '页面验证已失效，请返回 AI 客户端重新连接。',
};
export default function OAuthConsentPage() {
  const request = new URLSearchParams(window.location.search).get('request') ?? '';
  const [consent, setConsent] = useState<Consent | null>(null), [preview, setPreview] = useState<Preview | null>(null);
  const [token, setToken] = useState(''), [showToken, setShowToken] = useState(false);
  const [error, setError] = useState(''), [busy, setBusy] = useState('');
  const previewHeading = useRef<HTMLHeadingElement>(null);
  useEffect(() => {
    const controller = new AbortController();
    void call<Consent>('oauth/consent?request=' + encodeURIComponent(request), {}, undefined, controller.signal)
      .then(setConsent).catch(failure => { if (!controller.signal.aborted) setError(failure.message); });
    return () => controller.abort();
  }, [request]);
  useEffect(() => { if (preview) previewHeading.current?.focus(); }, [preview]);
  const inspectToken = async () => {
    if (!consent || !token.trim()) return;
    setBusy('inspect'); setError(''); setPreview(null);
    try { setPreview(await call<Preview>('oauth/consent', consent, { request, decision: 'inspect', token })); }
    catch (failure) { setError((failure as Error).message); } finally { setBusy(''); }
  };
  const finish = async (decision: 'approve' | 'deny') => {
    if (!consent || decision === 'approve' && (!preview || !token.trim())) return;
    setBusy(decision); setError('');
    try {
      const result = await call<{ redirectUrl: string }>('oauth/consent', consent, { request, decision, ...(decision === 'approve' ? { token } : {}) });
      const target = new URL(result.redirectUrl), expected = new URL(consent.redirectUri);
      if (target.origin !== expected.origin || target.pathname !== expected.pathname) throw new Error('回调地址不匹配，请重新连接。');
      setToken(''); window.location.assign(result.redirectUrl);
    } catch (failure) { setError((failure as Error).message); setBusy(''); }
  };
  return <div className="gc-app gc-auth-page gc-oauth-page" style={{ colorScheme: 'dark' }}>
    <header className="gc-oauth-brand"><div className="gc-brand-mark">Au</div><div><strong>GOLD CATCHER</strong><span>黄金研究 · Token 授权</span></div></header>
    <main className="gc-oauth-card" aria-labelledby="gc-oauth-heading">
      <span className="gc-access-eyebrow">CONNECT YOUR AI</span><h1 id="gc-oauth-heading">使用 PAT 授权连接</h1>
      {error && <p className="gc-auth-error" role="alert">{errors[error] ?? error}</p>}
      {!consent ? <p role="status">{error ? '尚未建立连接。' : '正在核对授权请求…'}</p> : <>
        <div className="gc-oauth-client"><span className="gc-auth-hint">请求连接的应用</span><strong>{consent.clientName}</strong><span>填写已创建的 PAT，连接仅获得该令牌允许的工具。</span></div>
        <div className="gc-oauth-callback"><span>授权后返回</span><strong>{new URL(consent.redirectUri).host}</strong><code>{consent.redirectUri}</code><p className="gc-auth-hint">应用名称由客户端提供，请核对返回地址是否属于你要连接的 AI 服务。</p></div>
        <form onSubmit={event => { event.preventDefault(); void inspectToken(); }}>
          <label htmlFor="gc-oauth-pat">个人访问令牌（PAT）</label>
          <div className="gc-auth-token-input"><input id="gc-oauth-pat" name="pat" type={showToken ? 'text' : 'password'} value={token} maxLength={256} autoComplete="off" spellCheck={false} required disabled={!!busy} aria-describedby="gc-oauth-pat-hint" placeholder="gc_pat_…" onChange={event => { setToken(event.target.value); setPreview(null); setError(''); }} /><button className="gc-auth-button" type="button" aria-pressed={showToken} onClick={() => setShowToken(!showToken)}>{showToken ? '隐藏' : '显示'}</button></div>
          <p id="gc-oauth-pat-hint" className="gc-auth-hint">在“运行与配置 → PAT 令牌与 MCP”创建和管理令牌。这里核对已有授权，不修改令牌权限。</p>
          <button className="gc-auth-button" type="submit" disabled={!!busy || !token.trim()}>{busy === 'inspect' ? '正在核对…' : preview ? '重新核对 Token' : '核对 Token 权限'}</button>
        </form>
        {preview && <section className="gc-auth-preview" aria-labelledby="gc-token-preview-title">
          <h2 id="gc-token-preview-title" ref={previewHeading} tabIndex={-1}>将使用此 Token 的授权范围</h2>
          <dl><div><dt>令牌名称</dt><dd>{preview.tokenName}</dd></div><div><dt>令牌标识</dt><dd>{preview.tokenPrefix}</dd></div><div><dt>当前可用工具</dt><dd>{preview.tools.length} 项 · {preview.tools.filter(tool => !tool.readOnly).length} 项操作工具</dd></div><div><dt>令牌有效期</dt><dd>{preview.tokenExpiresAt ? new Date(preview.tokenExpiresAt).toLocaleString('zh-CN') : '永久有效，直到撤销'}</dd></div></dl>
          <p className="gc-auth-hint">{preview.scopes.map(scope => scopeLabels[scope]?.[0] ?? scope).join(' · ')}</p>
          <details><summary>查看可用工具</summary><ul className="gc-auth-tools">{preview.tools.map(tool => <li key={tool.name}>{tool.title}{tool.readOnly ? '' : ' · 操作'}<code>{tool.name}</code></li>)}</ul>{!preview.tools.length && <p>此令牌当前没有启用工具，可在后台调整工具范围。</p>}</details>
        </section>}
        <p className="gc-auth-hint">连接权限随该 PAT 的配置更新；撤销或过期后连接立即失效。OAuth 授权最长 {consent.authorizationDays} 天。PAT 仅由本站验证，ChatGPT 使用另行签发的 OAuth 凭据。</p>
        <div className="gc-oauth-actions"><button className="gc-auth-button" disabled={!!busy} onClick={() => void finish('deny')}>{busy === 'deny' ? '正在返回…' : '取消连接'}</button><button className="gc-auth-button gc-auth-button--primary" disabled={!!busy || !preview || error === 'authorization_expired'} onClick={() => void finish('approve')}>{busy === 'approve' ? '正在连接…' : '确认授权并返回'}</button></div>
      </>}
      <a className="gc-oauth-home" href="/">返回工作台管理令牌</a>
    </main>
  </div>;
}
