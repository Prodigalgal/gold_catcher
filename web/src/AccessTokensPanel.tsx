import { useEffect, useRef, useState } from 'react';
import { call } from './api';
import type { Session } from './api';
import './AccessTokensPanel.css';

type Token = {
  id: string; name: string; prefix: string; scopes: string[]; requestsPerMinute: number;
  createdAt: string; expiresAt: string; revokedAt: string | null; lastUsedAt: string | null;
  useCount: number; state: 'ACTIVE' | 'EXPIRED' | 'REVOKED';
};
type Inventory = { tokens: Token[]; scopes: string[]; mcpPath: string; restPath: string; maximumActiveTokens: number };
type Created = { token: Token; secret: string };
const scopeNames: Record<string, [string, string]> = {
  'market:read': ['行情与指标', '报价、K 线、市场深度和 Java 计算指标'],
  'information:read': ['信息数据库', '新闻原文、压缩摘要、经济日历和宏观驱动'],
  'research:read': ['AI 研究结果', '辩论观点、研究状态和裁决结果'],
};
const errors: Record<string, string> = {
  INVALID_TOKEN_NAME: '请输入 1–80 个字符的令牌名称。',
  INVALID_TOKEN_SCOPES: '请至少选择一种读取权限。',
  INVALID_TOKEN_LIMITS: '有效期应为 1–365 天，请求频率应为每分钟 10–600 次。',
  ACTIVE_TOKEN_LIMIT_REACHED: '有效令牌已达上限，请先撤销不再使用的令牌。',
  TOKEN_NOT_FOUND: '令牌不存在，请刷新列表。',
  AUTH_REQUIRED: '登录已失效，请重新登录。',
};
const stamp = (value: string | null) => value ? new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false }) : '尚未使用';
const stateNames = { ACTIVE: '有效', EXPIRED: '已过期', REVOKED: '已撤销' };

export default function AccessTokensPanel({ session }: { session: Session }) {
  const [inventory, setInventory] = useState<Inventory | null>(null);
  const [name, setName] = useState('');
  const [scopes, setScopes] = useState(Object.keys(scopeNames));
  const [days, setDays] = useState(90), [rate, setRate] = useState(120);
  const [created, setCreated] = useState<Created | null>(null);
  const [loading, setLoading] = useState(true), [busy, setBusy] = useState('');
  const [error, setError] = useState(''), [notice, setNotice] = useState('');
  const [confirm, setConfirm] = useState('');
  const [clientKind, setClientKind] = useState<'mcp' | 'responses' | 'functions'>('mcp');
  const secretInput = useRef<HTMLInputElement>(null);
  const nameInput = useRef<HTMLInputElement>(null);
  const endpoint = window.location.origin + (inventory?.mcpPath ?? '/api/mcp');
  const config = JSON.stringify({ mcpServers: { 'gold-catcher': { type: 'http', url: endpoint, headers: { Authorization: 'Bearer <YOUR_PAT>' } } } }, null, 2);
  const responsesConfig = JSON.stringify({ tools: [{ type: 'mcp', server_label: 'gold_catcher', server_url: endpoint, authorization: '<YOUR_PAT>', allowed_tools: ['get_quote', 'get_candles', 'get_indicators', 'search_news', 'get_news'], require_approval: 'never' }] }, null, 2);

  useEffect(() => {
    const controller = new AbortController();
    call<Inventory>('access/tokens', session, undefined, controller.signal)
      .then(setInventory)
      .catch(failure => { if (!controller.signal.aborted) setError(failure.message); })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [session]);
  useEffect(() => { if (created) secretInput.current?.focus(); }, [created]);
  const refresh = async () => {
    setBusy('refresh'); setError('');
    try { setInventory(await call<Inventory>('access/tokens', session)); }
    catch (failure) { setError((failure as Error).message); }
    finally { setBusy(''); }
  };
  const create = async () => {
    setBusy('create'); setError(''); setNotice('');
    try {
      const value = await call<Created>('access/tokens', session, { name, scopes, expiresInDays: days, requestsPerMinute: rate });
      setCreated(value); setName('');
      setInventory(current => current ? { ...current, tokens: [value.token, ...current.tokens] } : current);
    } catch (failure) { setError((failure as Error).message); }
    finally { setBusy(''); }
  };
  const revoke = async (token: Token) => {
    setBusy(token.id); setError(''); setNotice('');
    try {
      const value = await call<Token>(`access/tokens/${token.id}/revoke`, session, {});
      setInventory(current => current ? { ...current, tokens: current.tokens.map(row => row.id === value.id ? value : row) } : current);
      if (created?.token.id === value.id) setCreated(null);
      setConfirm(''); setNotice(`“${token.name}”已撤销，后续请求将被拒绝。`);
    } catch (failure) { setError((failure as Error).message); }
    finally { setBusy(''); }
  };
  const copy = async (text: string, message: string) => {
    try { await navigator.clipboard.writeText(text); setNotice(message); }
    catch { setError('浏览器未允许剪贴板访问，请选中文本后手动复制。'); }
  };
  return <div className="gc-access">
    <section className="gc-panel gc-access-intro">
      <div><span className="gc-access-eyebrow">EXTERNAL AI ACCESS</span><h3>PAT 令牌与 MCP 接入</h3><p>让其他 AI 读取同一份黄金信息库与市场数据。按应用分别创建令牌，独立管理权限、有效期和使用情况。</p></div>
      <span className="gc-badge gc-gold-badge">只读数据服务 · 12 项工具</span>
    </section>
    {error && <p className="gc-live-error" role="alert">{errors[error] ?? error}</p>}
    <p className="gc-access-notice" role="status" aria-live="polite">{notice}</p>
    {created && <section className="gc-panel gc-access-secret" aria-labelledby="gc-token-secret-title">
      <div className="gc-panel-head"><h3 id="gc-token-secret-title">保存新令牌 · 仅显示这一次</h3><span className="gc-badge">{created.token.name}</span></div>
      <p id="gc-token-secret-hint">关闭此区域后无法再次查看完整令牌。请保存到调用方的密钥配置中。</p>
      <label htmlFor="gc-token-secret">完整 PAT</label>
      <div className="gc-access-copy"><input id="gc-token-secret" ref={secretInput} type="text" value={created.secret} readOnly spellCheck={false} autoComplete="off" aria-describedby="gc-token-secret-hint" onFocus={event => event.target.select()} /><button className="gc-live-button gc-live-primary" onClick={() => void copy(created.secret, '令牌已复制。')}>复制令牌</button></div>
      <button className="gc-live-button" onClick={() => { setCreated(null); setNotice('完整令牌已从页面移除。'); nameInput.current?.focus(); }}>已保存，关闭显示</button>
    </section>}
    <div className="gc-access-grid">
      <section className="gc-panel">
        <div className="gc-panel-head"><h3>创建访问令牌</h3><span className="gc-small">每个应用一枚</span></div>
        <form className="gc-control-form" onSubmit={event => { event.preventDefault(); void create(); }}>
          <label htmlFor="gc-token-name">令牌名称<input ref={nameInput} id="gc-token-name" required maxLength={80} value={name} placeholder="例如：个人 AI 研究助手" onChange={event => setName(event.target.value)} /></label>
          <fieldset className="gc-access-scopes"><legend>读取权限</legend>{Object.entries(scopeNames).map(([scope, [title, description]]) => <label key={scope} className="gc-access-scope"><input type="checkbox" checked={scopes.includes(scope)} onChange={event => setScopes(current => event.target.checked ? [...current, scope] : current.filter(s => s !== scope))} /><span><strong>{title}</strong><span>{description}</span></span></label>)}</fieldset>
          <div className="gc-form-grid"><label htmlFor="gc-token-days">有效期（天）<input id="gc-token-days" type="number" min={1} max={365} required value={days} onChange={event => setDays(Number(event.target.value))} /></label><label htmlFor="gc-token-rate">每分钟请求上限<input id="gc-token-rate" type="number" min={10} max={600} required value={rate} onChange={event => setRate(Number(event.target.value))} /></label></div>
          <p className="gc-small">仅可查询所选数据；不授予配置修改、启动研究或订单操作权限。有效期届满自动失效。</p>
          <button className="gc-live-button gc-live-primary" disabled={!!busy || !scopes.length || !name.trim() || !!created}>{busy === 'create' ? '创建中…' : created ? '请先保存当前令牌' : '创建 PAT 令牌'}</button>
        </form>
      </section>
      <section className="gc-panel gc-access-connect">
        <div className="gc-panel-head"><h3>连接其他 AI</h3><span className="gc-badge">Streamable HTTP</span></div>
        <div className="gc-access-client-tabs" role="group" aria-label="AI 接入方式">{([['mcp', 'MCP 客户端'], ['responses', 'GPT / Responses'], ['functions', 'OpenAI 兼容 API']] as const).map(([key, title]) => <button key={key} type="button" className="gc-live-button" aria-pressed={clientKind === key} onClick={() => setClientKind(key)}>{title}</button>)}</div>
        {clientKind === 'mcp' && <>
        <ol><li>在 AI 客户端中添加远程 MCP 服务。</li><li>填写下方地址，并设置 Bearer 令牌。</li><li>连接后会自动发现该令牌有权使用的工具。</li></ol>
        <label htmlFor="gc-mcp-url">MCP 服务地址</label><div className="gc-access-copy"><input id="gc-mcp-url" value={endpoint} readOnly onFocus={event => event.target.select()} /><button className="gc-live-button" onClick={() => void copy(endpoint, 'MCP 地址已复制。')}>复制地址</button></div>
        <p className="gc-small">认证请求头：<code>Authorization: Bearer &lt;YOUR_PAT&gt;</code></p>
        <details><summary>查看客户端配置示例</summary><pre>{config}</pre><button className="gc-live-button" onClick={() => void copy(config, '配置模板已复制，请替换 YOUR_PAT。')}>复制配置模板</button><p className="gc-small">请将 YOUR_PAT 替换为令牌，具体配置字段以客户端支持为准。完整令牌不会自动填入模板。</p></details>
        </>}
        {clientKind === 'responses' && <><p>使用支持远程 MCP 的 OpenAI Responses API。在请求的 tools 中配置以下内容，authorization 直接填写 PAT 原值。</p><pre>{responsesConfig}</pre><button className="gc-live-button" onClick={() => void copy(responsesConfig, 'GPT MCP 配置已复制，请替换 YOUR_PAT。')}>复制 GPT 配置</button><p className="gc-small">本地 PAT 用于数据授权；OpenAI API Key 用于模型调用，二者分别配置。此示例允许模型自动读取所列数据，可按需调整审批方式和工具列表。</p></>}
        {clientKind === 'functions' && <><p>对于支持 Function Calling 的 OpenAI 兼容接口，应用先获取工具定义，再把模型返回的工具调用交给本服务执行。</p><ol><li>GET <code>/api/external/v1/openai/tools?format=chat</code></li><li>将返回的 tools 传入模型请求。</li><li>POST <code>/api/external/v1/openai/tool</code>，请求体为 <code>{'{"tool_call": 模型返回的单个工具调用}'}</code>。</li><li>将返回的 message 加入对话后继续模型请求。</li></ol><p className="gc-small">以上数据接口均设置 Authorization: Bearer PAT。也支持 format=responses 和 function_call_output。流式输出需要先组装完整工具参数；普通纯文本接口不会自动获得工具能力。</p></>}
        <div className="gc-access-coverage"><strong>查询内容</strong><p>新闻与压缩摘要 · 官方日历 · 美元与利率等驱动 · 实时报价 · 1m / 5m / 15m / 1h / 4h / 日 K · 技术指标 · AI 辩论与裁决</p><p className="gc-small">所有查询返回实际采集时间和覆盖状态。K 线最多覆盖当前采集窗口的 200 根；原文和研究消息支持分段读取。</p></div>
      </section>
    </div>
    <section className="gc-panel">
      <div className="gc-panel-head"><div><h3>令牌列表</h3><span className="gc-small">{inventory ? `${inventory.tokens.filter(token => token.state === 'ACTIVE').length} 枚有效 · 最多 ${inventory.maximumActiveTokens} 枚` : '正在读取'}</span></div><button className="gc-live-button" disabled={!!busy || loading} onClick={() => void refresh()}>{busy === 'refresh' ? '刷新中…' : '刷新使用情况'}</button></div>
      {loading ? <p role="status">正在读取访问令牌…</p> : !inventory?.tokens.length ? <div className="gc-access-empty"><strong>尚未创建访问令牌</strong><p>创建第一枚令牌，即可让其他 AI 使用已授权的数据。</p></div> : <div className="gc-live-table-wrap"><table className="gc-live-table gc-access-table"><thead><tr><th>名称 / 标识</th><th>读取权限</th><th>状态 / 到期时间</th><th>最近使用 / 请求数</th><th>操作</th></tr></thead><tbody>{inventory.tokens.map(token => <tr key={token.id}><td><strong>{token.name}</strong><code>{token.prefix}</code><span className="gc-small">创建 {stamp(token.createdAt)}</span></td><td>{token.scopes.map(scope => <span className="gc-access-scope-tag" key={scope}>{scopeNames[scope]?.[0] ?? scope}</span>)}<span className="gc-small">{token.requestsPerMinute} 次 / 分钟</span></td><td><span className={`gc-badge ${token.state === 'ACTIVE' ? 'gc-positive' : ''}`}>{stateNames[token.state]}</span><span className="gc-small">{stamp(token.expiresAt)}</span></td><td>{stamp(token.lastUsedAt)}<span className="gc-small">{token.useCount.toLocaleString()} 次已认证请求</span></td><td>{token.state === 'ACTIVE' && (confirm === token.id ? <div className="gc-access-revoke"><span>确认撤销？</span><button className="gc-live-button gc-negative" disabled={!!busy} onClick={() => void revoke(token)}>{busy === token.id ? '撤销中…' : '确认撤销'}</button><button className="gc-live-button" disabled={!!busy} onClick={() => setConfirm('')}>保留</button></div> : <button className="gc-live-button" disabled={!!busy} onClick={() => setConfirm(token.id)}>撤销令牌</button>)}</td></tr>)}</tbody></table></div>}
    </section>
  </div>;
}
