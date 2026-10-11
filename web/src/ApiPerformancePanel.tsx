import { useEffect, useState } from 'react';
import { call } from './api';
import type { Session } from './api';
type Performance = {
  cache: {redisEnabled: boolean;redisState: string;localHits: number;redisHits: number;loads: number;redisFailures: number;entries: number;retryAt?: string;lastFailure?: string;lastFailureAt?: string};
  http: {scope: string;routes: {route: string;calls: number;failures: number;samples: number;p50Ms: number;p95Ms: number;lastMs: number;lastBytes: number;lastStatus: number}[]};
};
export default function ApiPerformancePanel({session}: {session: Session}) {
  const [value,setValue]=useState<Performance|null>(null);
  const [error,setError]=useState('');
  useEffect(()=>{
    const controller=new AbortController();
    const load=()=>call<Performance>('performance',session,undefined,controller.signal).then(value=>{setValue(value);setError('');}).catch(failure=>{if(!controller.signal.aborted)setError(failure.message);});
    void load();const timer=setInterval(load,15000);
    return()=>{controller.abort();clearInterval(timer);};
  },[session]);
  return <section className="gc-panel"><div className="gc-panel-head"><div><h3>API 性能与缓存</h3><span className="gc-small">本进程各接口最近 128 次请求；每 15 秒刷新，SSE 长连接单独处理</span></div><span className="gc-badge">{value?.cache.redisState ?? '读取中'}</span></div>
    {error && <p className="gc-live-error" role="alert">{error}</p>}
    {value && <><div className="gc-factor"><span>缓存命中 / 实际加载</span><b>本地 {value.cache.localHits} · Redis {value.cache.redisHits} · 加载 {value.cache.loads}</b></div>
      <div className="gc-factor"><span>Redis 故障 / 本地条目</span><b>{value.cache.redisFailures} / {value.cache.entries}</b></div>
      {value.cache.lastFailure && <p className="gc-small">最近缓存故障：{value.cache.lastFailure}（{value.cache.lastFailureAt}）</p>}
      <p className="gc-small">行情、账户和执行直接读取；Redis 不可用时退回本地缓存和数据库。下表体积为压缩前的应用响应体，耗时包含后端读取与序列化。</p>
      <div className="gc-live-table-wrap"><table className="gc-live-table"><thead><tr><th>接口</th><th>调用 / 失败</th><th>P50 / P95</th><th>最近耗时</th><th>最近体积</th></tr></thead><tbody>{value.http.routes.map(row=><tr key={row.route}><td>{row.route}</td><td>{row.calls} / {row.failures}</td><td>{row.p50Ms.toFixed(1)} / {row.p95Ms.toFixed(1)} ms</td><td>{row.lastMs.toFixed(1)} ms</td><td>{(row.lastBytes/1024).toFixed(1)} KB</td></tr>)}</tbody></table></div></>}
  </section>;
}
