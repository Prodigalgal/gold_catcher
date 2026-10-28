import { useEffect, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { call } from './api';
import type { LoginChallenge, Session } from './api';

const errors: Record<string,string> = {
  LOGIN_FAILED: '账号或密码不正确，请重新登录。', POW_RATE_LIMITED: '尝试次数较多，请稍候一分钟再试。',
  POW_BUSY: '验证服务暂忙，请稍后重试。', POW_EXPIRED: '安全验证已过期，请重新登录。',
  POW_INVALID: '安全验证未通过，请重新登录。', CSRF_OR_ACCESS_DENIED: '登录验证已失效，请重新登录。',
};
export default function LoginPage({ onLogin, restoring, preservePath = false }: { onLogin: (session: Session) => void; restoring: boolean; preservePath?: boolean }) {
  const [username,setUsername] = useState('admin'); const [password,setPassword] = useState('');
  const [busy,setBusy] = useState(false); const [progress,setProgress] = useState(''); const [error,setError] = useState('');
  const worker = useRef<Worker | null>(null); const controller = useRef<AbortController | null>(null);
  useEffect(() => () => { worker.current?.terminate(); controller.current?.abort(); }, []);
  const login = async (event: FormEvent) => {
    event.preventDefault(); setBusy(true); setError(''); setProgress('正在获取安全挑战…');
    const attempt = new AbortController(); controller.current = attempt;
    try {
      const packet = await call<LoginChallenge>('auth/challenge',{},undefined,attempt.signal);
      const solution = await new Promise<string>((resolve,reject) => {
        const active = new Worker(new URL('./pow.worker.ts',import.meta.url),{type:'module'}); worker.current = active;
        active.onmessage = ({ data }) => {
          if (data.error) { active.terminate(); reject(new Error(data.error)); }
          else if (data.solution !== undefined) { active.terminate(); resolve(data.solution); }
          else setProgress(`正在完成安全验证 · ${Number(data.attempts).toLocaleString('zh-CN')} 次计算`);
        };
        active.onerror = () => { active.terminate(); reject(new Error('安全验证无法运行，请刷新页面重试。')); };
        active.postMessage(packet.challenge);
      });
      setProgress('验证完成，正在登录…');
      await call('auth/login',packet,{username,password,challengeId:packet.challenge.id,solution},attempt.signal);
      setPassword(''); const session = await call<Session>('session',{},undefined,attempt.signal);
      if (!preservePath) history.replaceState(null,'','/'); onLogin(session);
    } catch (failure) {
      if (!attempt.signal.aborted) { const code=(failure as Error).message;setError(errors[code] ?? code); }
    } finally { if (!attempt.signal.aborted) { setBusy(false);setProgress(''); } }
  };
  return <div className="gc-app gc-auth-page gc-login-page" style={{colorScheme:'dark'}}>
    <div className="gc-login-brand"><div className="gc-brand-mark">Au</div><strong>GOLD CATCHER</strong><span>黄金研究工作台</span></div>
    <main className="gc-login-layout"><section className="gc-login-intro"><div className="gc-eyebrow">SEE THE SIGNAL. UNDERSTAND THE MARKET.</div>
      <h1>从信息到判断，<br/><em>看清每一次机会。</em></h1><p>汇集市场变化与官方信息，让不同 AI 独立研究、交叉质疑。用持续的内部模拟，检验每一个判断。</p>
      <div className="gc-login-visual" aria-hidden="true"><span>Au</span><i/><i/><i/><b>79</b><small>GOLD / MARKET INTELLIGENCE</small></div>
      <div className="gc-login-features"><span>实时市场</span><span>多方研究</span><span>内部模拟</span></div>
    </section><section className="gc-panel gc-login-card"><div className="gc-eyebrow">YOUR PRIVATE WORKSPACE</div><h2>登录工作台</h2><p>进入你的黄金市场研究空间</p>
      <form onSubmit={login}><label>账号<input name="username" autoComplete="username" required maxLength={120} disabled={busy || restoring} value={username} onChange={event=>setUsername(event.target.value)}/></label>
      <label>密码<input name="password" type="password" autoComplete="current-password" required maxLength={256} disabled={busy || restoring} value={password} onChange={event=>setPassword(event.target.value)}/></label>
      {error && <div className="gc-auth-error" role="alert">{error}</div>}
      <button className="gc-auth-button gc-auth-button--primary" disabled={busy || restoring}>{restoring ? '正在确认登录状态…' : busy ? '正在验证…' : '登录工作台'}<span aria-hidden="true">→</span></button></form>
      <div className="gc-login-security" role="status"><span aria-hidden="true">◇</span>{progress || 'PoW 安全防护 · 登录时自动完成验证'}</div>
    </section></main><footer className="gc-login-footer"><span>GOLD CATCHER</span><span>黄金研究 · 仅内部模拟</span></footer>
  </div>;
}
