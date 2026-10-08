import { useEffect, useState } from 'react';
import InformationBoard from './InformationBoard';
import ResearchRoom from './ResearchRoom';

const pages = [
  ['overview', '市场总览', '先看形势，再看机会'],
  ['sources', '信息集合', '哪些信息支持金价，哪些形成压力'],
  ['research', 'AI 研究', '多 AI 交叉研究、辩论与修订'],
  ['trades', '模拟交易', '从计划到成交，再到净收益'],
  ['review', '策略复盘', '判断是否有效，成本是否覆盖'],
  ['operations', '运行与配置', '数据、模型与系统各自的状态'],
] as const;
type Page = typeof pages[number][0];
type Product = 'XAU_USDT' | 'GATE:XAUUSD';
type WidgetState = { modelContent?: Record<string, unknown>; privateContent?: { page?: Page; product?: Product; round?: number; [key: string]: unknown } };
type Host = { widgetState?: WidgetState; setWidgetState?: (v: unknown) => Promise<void> };
declare global { interface Window { openai?: Host; Tweak?: new (v: unknown) => { addSlider: (o: unknown, p: string, v: unknown) => void; dispose: () => void }; } }

function PriceChart({ product, compact = false }: { product: Product; compact?: boolean }) {
  const [frame, setFrame] = useState('5m');
  const [width, setWidth] = useState(650);
  const [node, setNode] = useState<HTMLDivElement | null>(null);
  useEffect(() => {
    if (!node) return;
    const observer = new ResizeObserver(([entry]) => setWidth(Math.max(200, entry.contentRect.width)));
    observer.observe(node);
    return () => observer.disconnect();
  }, [node]);
  const chartHeight = compact ? 180 : 238;
  const values = frame === '1h'
    ? [54, 66, 48, 57, 45, 37, 49, 40, 27, 35, 29, 17, 24, 16, 25, 11, 22, 15, 19, 9, 14, 8]
    : [70, 58, 64, 59, 75, 67, 53, 60, 56, 43, 49, 37, 47, 39, 46, 31, 37, 30, 25, 37, 26, 21, 31, 26, 16, 24, 20, 26, 19, 15];
  values[values.length - 1] = product === 'XAU_USDT' ? 24.6667 : 27.3333;
  const left = 12, right = width - 65, top = 18, bottom = chartHeight - 28;
  const x = (i: number) => left + (i / (values.length - 1)) * (right - left);
  const y = (v: number) => top + (v / 100) * (bottom - top);
  const line = values.map((v, i) => `${i ? 'L' : 'M'}${x(i).toFixed(1)},${y(v).toFixed(1)}`).join(' ');
  return <div className="gc-chart-wrap" ref={setNode}>
    <div className="gc-panel-head"><div><h3>{product} <span className="gc-secondary">价格走势</span></h3><span className="gc-small">示例曲线 · USD / 金衡盎司</span></div>
      <div className="gc-segment" aria-label="图表周期">{['1m', '5m', '15m', '1h'].map(v => <button type="button" key={v} aria-pressed={frame === v} onClick={() => setFrame(v)}>{v}</button>)}</div></div>
    <svg className="gc-price-chart" width="100%" height={chartHeight} viewBox={`0 0 ${width} ${chartHeight}`} role="img" aria-label={`${product} 设计示例价格曲线，周期 ${frame}，包含候选计划线，非实时行情`}>
      <defs><linearGradient id={`gc-fill-${product}`} x1="0" y1="0" x2="0" y2="1"><stop offset="0%" stopColor="var(--gc-gold)" stopOpacity="0.19"/><stop offset="100%" stopColor="var(--gc-gold)" stopOpacity="0"/></linearGradient></defs>
      {[0, 1, 2, 3].map((v) => <g key={v}><line x1={left} x2={right} y1={top + v * (bottom - top) / 3} y2={top + v * (bottom - top) / 3} stroke="var(--gc-line)"/><text x={right + 8} y={top + v * (bottom - top) / 3 + 4}> {4740 - v * 10}</text></g>)}
      <path d={`${line} L${right},${bottom} L${left},${bottom} Z`} fill={`url(#gc-fill-${product})`}/><path d={line} fill="none" stroke="var(--gc-gold)" strokeWidth="2.3" strokeLinejoin="round"/>
      <line x1={left} x2={right} y1={y(38)} y2={y(38)} stroke="var(--gc-blue)" strokeDasharray="5 5"/>
      <text x={left + 4} y={y(38) - 7}>候选计划 · 尚未成交</text>
      <circle cx={right} cy={y(values.at(-1)!)} r="4" fill="var(--gc-gold)"/>
      {(width < 400 ? [0, 2] : [0, 1, 2]).map((i) => <text key={i} x={left + i * (right - left) / 2} y={chartHeight - 6} textAnchor={i === 0 ? 'start' : i === 2 ? 'end' : 'middle'}>{['13:30', '14:00', '14:30'][i]}</text>)}
    </svg>
    <div className="gc-chart-foot"><span>计划线 ≠ 模拟成交</span><span>成交标记与事件可点击追溯</span></div>
  </div>;
}

function App() {
  const restored = window.openai?.widgetState?.privateContent;
  const requestedPage = document.getElementById('gold-catcher-app')?.dataset.initialPage;
  const defaultPage = pages.find(item=>item[0]===requestedPage)?.[0]??'overview';
  const [page, setPage] = useState<Page>(restored?.page && pages.some(p => p[0] === restored.page) ? restored.page : defaultPage);
  const [product, setProduct] = useState<Product>(restored?.product === 'GATE:XAUUSD' ? 'GATE:XAUUSD' : 'XAU_USDT');
  const [round, setRound] = useState(restored?.round !== undefined && Number.isInteger(restored.round) && restored.round >= -1 && restored.round < 4 ? restored.round : -1);
  const [detail, setDetail] = useState(false);
  const [appNode, setAppNode] = useState<HTMLDivElement | null>(null);
  const title = pages.find(p => p[0] === page)!;
  const navigate = (next: Page) => { setPage(next); setDetail(false); persist(next, product, round); };
  const persist = (p: Page, instrument: Product, stage: number) => {
    const saved = window.openai?.widgetState;
    window.openai?.setWidgetState?.({ modelContent: { ...saved?.modelContent, page: p, product: instrument, researchStage: stage, design: 'six-page-terminal', demoData: true }, privateContent: { ...saved?.privateContent, page: p, product: instrument, round: stage } }).catch(() => {});
  };
  useEffect(() => {
    const handler = (event: Event) => {
      const saved = (event as CustomEvent<{globals?: {widgetState?: WidgetState}}>).detail?.globals?.widgetState?.privateContent;
      if (saved?.page && pages.some(p => p[0] === saved.page)) setPage(saved.page);
      if (saved?.product === 'GATE:XAUUSD' || saved?.product === 'XAU_USDT') setProduct(saved.product);
      if (saved?.round !== undefined && Number.isInteger(saved.round) && saved.round >= -1 && saved.round < 4) setRound(saved.round);
    };
    window.addEventListener('openai:set_globals', handler);
    return () => window.removeEventListener('openai:set_globals', handler);
  }, []);
  useEffect(() => {
    if (!appNode || !window.Tweak) return;
    const layout = { spacing: 20 };
    const tweak = new window.Tweak({ container: appNode, onChange: () => appNode.style.setProperty('--gc-space', `${layout.spacing}px`) });
    tweak.addSlider(layout, 'spacing', { label: '页面间距', min: 12, max: 28, step: 2, unit: 'px', reference: '--gc-space' });
    return () => tweak.dispose();
  }, [appNode]);
  const badge = (text: string, tone = '') => <span className={`gc-badge ${tone}`}>{text}</span>;
  const link = (label: string, next: Page) => <button type="button" className="gc-text-button" onClick={() => navigate(next)}>{label} <span aria-hidden="true">↗</span></button>;
  return <div className="gc-app" ref={setAppNode} aria-label="Gold Catcher 六页面设计">
    <aside className="gc-sidebar">
      <div className="gc-brand"><div className="gc-brand-mark" aria-hidden="true">G</div><div><strong>GOLD CATCHER</strong><span>黄金研究 · 内部模拟</span></div></div>
      <div className="gc-nav-caption">工作空间</div>
      <nav aria-label="主导航">{pages.map(([id, label], index) => <button type="button" key={id} aria-current={page === id ? 'page' : undefined} onClick={() => navigate(id)}><span className="gc-nav-index">0{index + 1}</span><span>{label}</span>{page === id && <span className="gc-nav-dot"/>}</button>)}</nav>
      <div className="gc-sidebar-bottom"><div className="gc-mode-dot"/><div>内部模拟模式<span>价格与记录均为设计示例</span></div></div>
    </aside>
    <div className="gc-workspace">
      <header className="gc-header"><div className="gc-breadcrumb">工作空间 <span>/</span> {title[1]}</div><div className="gc-header-meta"><span>UTC+8</span><span className="gc-avatar">Z</span></div></header>
      <main className="gc-main">
        <div className="gc-title-row"><div><span className="gc-eyebrow">GOLD INTELLIGENCE</span><h1>{title[1]}</h1><p>{title[2]}</p></div><div className="gc-title-actions"><span className="gc-badge gc-gold-badge">设计原型 · 示例数据</span><select aria-label="分析产品" value={product} onChange={e => { const v = e.target.value as Product; setProduct(v); persist(page, v, round); }}><option>XAU_USDT</option><option>GATE:XAUUSD</option></select></div></div>
        {page === 'overview' && <>
          <div className="gc-top-stats">
            <section className="gc-stat"><div className="gc-stat-label">{product} <span>{product === 'XAU_USDT' ? '黄金永续' : 'Gate 黄金 CFD'}</span></div><strong>{product === 'XAU_USDT' ? '4,732.60' : '4,731.80'} <small>{product === 'XAU_USDT' ? 'USDT' : 'USD'}</small></strong><div className="gc-stat-foot">Bid / Ask <span>{product === 'XAU_USDT' ? '4,732.50 / 4,732.70' : '4,731.40 / 4,732.20'}</span></div></section>
            <section className="gc-stat"><div className="gc-stat-label">较长周期形势 <span>4h – 2d</span></div><strong className="gc-text-value">偏多 · 等待确认</strong><div className="gc-stat-foot">核心分歧 <span>利率变化是否延续</span></div></section>
            <section className="gc-stat"><div className="gc-stat-label">短线机会 <span>5m – 1h</span></div><strong className="gc-text-value">等待回踩</strong><div className="gc-stat-foot">评估状态 <span>候选计划待复核</span></div></section>
          </div>
          <div className="gc-primary-grid"><section className="gc-panel gc-chart-panel"><PriceChart product={product}/><div className="gc-indicator-strip"><span>EMA 20 / 60 <b>上方</b></span><span>RSI 14 <b>58.2</b></span><span>ATR 14 <b>12.4</b></span><span>指标 <b>Java 计算</b></span></div></section>
            <section className="gc-panel gc-opportunity"><div className="gc-panel-head"><h3>当前判断</h3>{badge('示例结论', 'gc-muted-badge')}</div><div className="gc-direction">回踩确认后，评估做多</div><p>较长周期支持上行；短线追价的空间有限。等待报价、波动与成本条件同时满足。</p><div className="gc-factor"><span>支持因素</span><b>价格位于趋势均线上方</b></div><div className="gc-factor"><span>反对因素</span><b>临近事件，方向可能反转</b></div><div className="gc-factor"><span>失效条件</span><b>跌破支撑或新证据改变判断</b></div><div className="gc-opportunity-foot">{link('查看研究与分歧', 'research')}<span>观点带有效期</span></div></section></div>
          <div className="gc-secondary-grid"><section className="gc-panel"><div className="gc-panel-head"><h3>支持与压制因素</h3>{link('信息集合', 'sources')}</div><div className="gc-feed-row"><span className="gc-feed-time gc-bull">↑ 偏多</span><div><b>美元走弱 · 短线支持</b><p>示例解读：持续性与已反映程度需确认</p></div>{badge('行情')}</div><div className="gc-feed-row"><span className="gc-feed-time gc-bear">↓ 偏空</span><div><b>实际利率上行 · 背景压力</b><p>日频背景数据，不能当作分钟级新信号</p></div>{badge('宏观')}</div></section>
            <section className="gc-panel"><div className="gc-panel-head"><h3>内部订单</h3>{link('查看模拟交易', 'trades')}</div><div className="gc-order-preview"><div><b>回踩做多计划</b><p>{product} · 100X（示例档位）</p></div>{badge('等待条件')}</div><div className="gc-factor"><span>保证金参考</span><b>约 100U</b></div><div className="gc-factor"><span>成交状态</span><b>未成交 · 触价需验证</b></div></section></div>
          <div className="gc-info-foot">示例报价与指标尚未接入。交易策略、杠杆与风险由模型评估，收益扣除实际适用成本。</div>
        </>}
        {page === 'sources' && <InformationBoard product={product} onResearch={() => navigate('research')}/>}
        {page === 'research' && <ResearchRoom product={product} stage={round} onStageChange={value=>{setRound(value);persist(page,product,value);}} onTrades={()=>navigate('trades')}/>}
        {page === 'trades' && <>
          <div className="gc-top-stats"><section className="gc-stat"><div className="gc-stat-label">虚拟账户 <span>{product}</span></div><strong>1,037.50 <small>{product === 'XAU_USDT' ? 'USDT' : 'USD'}</small></strong><div className="gc-stat-foot">期初本金 <span>1,000.00</span></div></section><section className="gc-stat"><div className="gc-stat-label">已实现净收益</div><strong>+37.50 <small>结算币种</small></strong><div className="gc-stat-foot">扣除费用 <span>2.50</span></div></section><section className="gc-stat"><div className="gc-stat-label">当前模拟状态</div><strong className="gc-text-value">1 个候选计划</strong><div className="gc-stat-foot">持仓 <span>0 · 示例已平仓</span></div></section></div>
          <section className="gc-panel"><div className="gc-panel-head"><h3>计划与订单</h3>{badge('仅内部账本')}</div><div className="gc-table-container"><table><thead><tr><th>计划</th><th>状态</th><th>报价 / 目标</th><th>杠杆 / 保证金</th><th>结果</th></tr></thead><tbody><tr><td><button className="gc-text-button" type="button" onClick={() => setDetail(true)}>回踩确认做多</button><small>{product}</small></td><td>{badge('等待条件')}</td><td>入场区间待确认</td><td>100X / 约 100U</td><td>未成交</td></tr><tr><td><button className="gc-text-button" type="button" onClick={() => setDetail(true)}>已平仓示例</button><small>{product}</small></td><td>{badge('已结算')}</td><td>4,700 → 4,720</td><td>100X / 94U</td><td>+37.50 <small>净收益</small></td></tr></tbody></table></div></section>
          {detail && <section className="gc-panel gc-trade-detail"><div className="gc-panel-head"><h3>订单详情与成交依据</h3><button type="button" className="gc-text-button" onClick={() => setDetail(false)}>收起</button></div><div className="gc-field-list"><div><span>示例已平仓数量</span><b>2 金衡盎司</b></div><div><span>毛盈亏</span><b>+40.00</b></div><div><span>交易费用 / 持有成本</span><b>2.20 / 0.30（示例）</b></div><div><span>最终净收益</span><b>+37.50 · 结算币种</b></div></div><div className="gc-order-timeline"><span>判断完成</span><span>订单生效</span><span>触价验证</span><span>模拟成交</span><span>退出结算</span></div>{link('回到当时研究', 'research')}</section>}
          <div className="gc-info-foot">示例金额用于说明费用展示。实际费用和 CFD / 永续规则由 Java 按具体产品分别计算。</div>
        </>}
        {page === 'review' && <>
          <div className="gc-panel gc-research-summary"><div><span className="gc-eyebrow">前向模拟优先</span><h2>收益、成本与未知结果一起统计</h2><p>每个建议进入记录，包括等待、撤销、未成交和失败。</p></div>{badge('样本不足')}</div>
          <div className="gc-secondary-grid"><section className="gc-panel"><div className="gc-panel-head"><h3>账户净值</h3><span className="gc-small">结算币种 · 设计样例</span></div><div className="gc-equity-example"><div><span>期初</span><strong>1,000.00</strong></div><span aria-hidden="true">→</span><div><span>期末</span><strong>1,037.50</strong></div></div><div className="gc-factor"><span>资金净增长</span><b>+3.75% · 分母为期初本金</b></div><div className="gc-callout">单笔样例仅展示账务关系，无法判断策略或模型的有效性。</div></section><section className="gc-panel"><div className="gc-panel-head"><h3>模型与策略对比</h3>{badge('待积累样本')}</div><div className="gc-field-list"><div><span>对比维度</span><b>模型 / 组合 / 行情 / 周期</b></div><div><span>交易净收益</span><b>扣交易成本</b></div><div><span>系统综合收益</span><b>再扣数据与 LLM 成本</b></div><div><span>评估依据</span><b>独立前向样本</b></div></div></section></div>
          <section className="gc-panel"><div className="gc-panel-head"><h3>研究结果分布</h3>{link('追溯研究', 'research')}</div><div className="gc-outcome-list">{['实际成交与退出', '等待 / 主动撤销', '未触发 / 未成交', '超时 / 数据缺口', '无法确定结果'].map(v => <div key={v}><span>{v}</span><span className="gc-secondary">真实统计接入后显示</span></div>)}</div></section>
        </>}
        {page === 'operations' && <>
          <section className="gc-panel"><div className="gc-panel-head"><h3>功能状态</h3>{badge('工程骨架')}</div><div className="gc-health-grid">{[['行情', '待接入', 'Gate WebSocket / CFD REST'], ['信息', '待接入', '按源类型与事件窗口采集'], ['AI 研究', '待接入', 'any2api · 通道预算分别验证'], ['模拟账本', '待实现', 'Java · 可恢复 · 有限虚拟资金']].map(([name, state, desc]) => <div key={name}><h3>{name}</h3>{badge(state, 'gc-muted-badge')}<p>{desc}</p></div>)}</div></section>
          <div className="gc-secondary-grid"><section className="gc-panel"><div className="gc-panel-head"><h3>模型配置</h3><span className="gc-small">凭据仅服务端保存</span></div><div className="gc-field-list"><div><span>模型目录</span><b>从 any2api 按厂商读取</b></div><div><span>上下文</span><b>通道上限 / 输出保留 / 裁剪记录</b></div><div><span>讨论方式</span><b>独立首轮 → 匿名复核 → 同步修订</b></div><div><span>实际可用性</span><b>推理结果与探针分别展示</b></div></div></section><section className="gc-panel"><div className="gc-panel-head"><h3>运行与发布</h3>{badge('部署未启用')}</div><div className="gc-field-list"><div><span>发布链路</span><b>GitHub Actions → GitOps → Argo CD</b></div><div><span>目标域名</span><b>gold.mnnu.eu.org</b></div><div><span>配置生效</span><b>带版本与生效时间</b></div><div><span>重启恢复</span><b>恢复订单、持仓与游标</b></div></div></section></div>
          <div className="gc-info-foot">页面原型不执行配置写入。服务在线、模型探针成功与新增有效研究分别显示。</div>
        </>}
      </main>
      <footer className="gc-footer"><span>GOLD CATCHER <span className="gc-footer-dot">·</span> 设计稿 v0.1</span><span aria-live="polite">{product} · {title[1]} · 全部示例数据</span></footer>
    </div>
  </div>;
}

export default App;
