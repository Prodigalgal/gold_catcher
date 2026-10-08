import { useEffect, useState } from 'react';

type Impact = '偏多' | '偏空' | '待判断' | '存在分歧';
type Evidence = {
  id: string; category: string; title: string; fact: string;
  short: Impact; long: Impact; reason: string; counter: string;
  source: string; freshness: string; verification: string; reaction: string; target?: string;
};

const evidence: Evidence[] = [
  {id: 'dollar', category: '行情', title: '美元指数回落', fact: '示例：美元较前一观察窗口走弱。', short: '偏多', long: '待判断',
    reason: '美元走弱可能支持美元计价黄金；持续性仍需观察。', counter: '若走弱短暂，或黄金已反映变化，新增空间可能有限。',
    source: '美元行情源（待接入）', freshness: '示例：获取于 14:32:10', verification: '行情事实待接入', reaction: '需对比事件前后报价'},
  {id: 'real-yield', category: '宏观', title: '美国实际利率上行', fact: '示例：实际收益率相对上一数据点上行。', short: '偏空', long: '偏空',
    reason: '持有无息黄金的机会成本可能上升，对金价形成压力。', counter: '风险需求增强时，这种关系可能被其他因素抵消。',
    source: '实际利率数据源（待接入）', freshness: '示例：日频背景数据', verification: '日频，不能当作实时信号', reaction: '分钟级反应暂无依据'},
  {id: 'technical', category: '行情', title: '黄金价格位于趋势均线上方', fact: '示例：价格高于 EMA 20 / 60，短线结构偏强。', short: '偏多', long: '待判断',
    reason: '动量结构支持短线延续，入场仍需报价与波动确认。', counter: '均线有滞后，突发事件可能使既有结构失效。',
    source: 'Java 指标快照（待实现）', freshness: '示例：5m 已闭合窗口', verification: '指标样例，非实时计算', reaction: '趋势本身已体现在价格中'},
  {id: 'inflation', category: '官方', title: '通胀发布窗口临近', fact: '示例：即将进入重要数据窗口，实际值尚未发布。', short: '待判断', long: '存在分歧',
    reason: '需同时比较实际值、预期差以及利率和美元的反应。', counter: '“通胀更高”不能直接等同金价上涨；政策预期可能改变方向。',
    source: 'BLS / 授权预期源（待接入）', freshness: '示例日历，不是实际日期', verification: '实际值与预期缺失', reaction: '尚无事件后报价'},
  {id: 'risk', category: '快讯', title: '避险相关突发快讯', fact: '示例：一条风险事件快讯尚未得到独立核实。', short: '存在分歧', long: '存在分歧',
    reason: '分析者 A 认为可能增加避险需求，B 认为信息不足。', counter: '消息真假、美元流动性和市场既有定价均会改变影响。',
    source: '快讯与官方核实源（待接入）', freshness: '示例：首次获取时间已记录', verification: '待核实', reaction: '价格同向变动不证明因果'},
  {id: 'cost', category: '产品', title: '交易与持有成本变化', fact: '示例：成本扩大，扣费后的盈利空间变小。', short: '待判断', long: '待判断',
    reason: '费用影响净收益，需要重新评估持有时长与计划。', counter: '资金费或点差变化本身不能确定黄金价格方向。',
    source: '具体产品规则（待接入）', freshness: '以规则生效时间为准', verification: '实际成本配置待取得', reaction: '影响净收益，不直接判断涨跌', target: '净收益'},
];

export default function InformationBoard({ product, onResearch }: { product: string; onResearch: () => void }) {
  type Preferences = {view?: string; horizon?: 'short' | 'long'; filter?: string; selected?: string | null};
  const restored = window.openai?.widgetState?.privateContent?.information as Preferences | undefined;
  const views = ['影响看板', '消息流', '经济日历'];
  const filters = ['全部', '偏多', '偏空', '待判断', '存在分歧'];
  const [view, setView] = useState(restored?.view && views.includes(restored.view) ? restored.view : '影响看板');
  const [horizon, setHorizon] = useState<'short' | 'long'>(restored?.horizon === 'long' ? 'long' : 'short');
  const [filter, setFilter] = useState(restored?.filter && filters.includes(restored.filter) ? restored.filter : '全部');
  const [selected, setSelected] = useState<string | null>(evidence.some(item => item.id === restored?.selected) ? restored!.selected! : null);
  const remember = (changes: Preferences) => {
    const saved = window.openai?.widgetState;
    const preferences = {view,horizon,filter,selected,...changes};
    window.openai?.setWidgetState?.({modelContent: {...saved?.modelContent, page:'sources', product,
      informationView: preferences.view, impactHorizon: preferences.horizon, impactFilter: preferences.filter,
      selectedEvidence: preferences.selected, demoData:true}, privateContent: {...saved?.privateContent,
      page:'sources', product, information: preferences}}).catch(() => {});
  };
  useEffect(() => {
    const apply = (event: Event) => {
      const preferences = (event as CustomEvent<{globals?: {widgetState?: {privateContent?: {information?: Preferences}}}}>).detail?.globals?.widgetState?.privateContent?.information;
      if (!preferences) return;
      if (preferences.view && views.includes(preferences.view)) setView(preferences.view);
      if (preferences.horizon === 'short' || preferences.horizon === 'long') setHorizon(preferences.horizon);
      if (preferences.filter && filters.includes(preferences.filter)) setFilter(preferences.filter);
      if (preferences.selected === null || evidence.some(item => item.id === preferences.selected)) setSelected(preferences.selected ?? null);
    };
    window.addEventListener('openai:set_globals', apply);
    return () => window.removeEventListener('openai:set_globals', apply);
  }, []);
  const direction = (item: Evidence) => item[horizon];
  const visible = evidence.filter(item => filter === '全部' || direction(item) === filter);
  const active = evidence.find(item => item.id === selected);
  const tone = (impact: Impact) => impact === '偏多' ? 'gc-bull' : impact === '偏空' ? 'gc-bear' : impact === '存在分歧' ? 'gc-conflict' : 'gc-neutral';
  return <>
    <div className="gc-tabs" aria-label="信息集合视图">{views.map(name => <button type="button" key={name} aria-pressed={view === name} onClick={() => { setView(name); setSelected(null); remember({view:name,selected:null}); }}>{name}</button>)}</div>
    {view === '影响看板' && <>
    <section className="gc-panel gc-information-summary">
      <div><span className="gc-eyebrow">{product} · 信息影响看板</span><h2>哪些信息支持上涨，哪些形成压力</h2><p>事实、AI 解读与市场已发生的反应分别展示。</p></div>
      <label className="gc-horizon-label">影响周期<select value={horizon} onChange={e => { const next=e.target.value as 'short' | 'long'; setHorizon(next); remember({horizon:next}); }}><option value="short">短线 · 15m – 1h</option><option value="long">形势 · 4h – 2d</option></select></label>
    </section>
    <div className="gc-impact-summary" aria-live="polite">
      <div><span className="gc-impact-arrow gc-bull">↑</span><div><b>{evidence.filter(item => direction(item) === '偏多').length} 条支持因素</b><span>当前周期的偏多解读</span></div></div>
      <div><span className="gc-impact-arrow gc-bear">↓</span><div><b>{evidence.filter(item => direction(item) === '偏空').length} 条压制因素</b><span>当前周期的偏空解读</span></div></div>
      <div><span className="gc-impact-arrow gc-neutral">?</span><div><b>{evidence.filter(item => !['偏多', '偏空'].includes(direction(item))).length} 条待判断 / 分歧</b><span>保留反证与缺失信息</span></div></div>
    </div>
    <div className="gc-impact-toolbar"><div className="gc-tabs" aria-label="影响方向筛选">{filters.map(value => <button type="button" key={value} aria-pressed={filter === value} onClick={() => { setFilter(value); setSelected(null); remember({filter:value,selected:null}); }}>{value}</button>)}</div><span className="gc-small">去重事件 · 条目数不代表综合强度</span></div>
    <div className="gc-evidence-grid">{visible.map(item => <section className="gc-panel gc-evidence-card" key={item.id}>
      <div className="gc-panel-head"><span className="gc-badge gc-muted-badge">{item.category}</span><span className={`gc-impact-badge ${tone(direction(item))}`}>{direction(item) === '偏多' ? '↑ ' : direction(item) === '偏空' ? '↓ ' : ''}{direction(item)}{item.target ? ' · 价格' : ''}</span></div>
      <h3>{item.id === 'cost' ? product === 'XAU_USDT' ? '资金费与交易成本变化' : 'CFD 点差与隔夜成本变化' : item.title}</h3><p className="gc-evidence-fact">{item.fact}</p>
      <div className="gc-evidence-reason"><span>对黄金的解读</span><p>{item.reason}</p></div>
      <div className="gc-evidence-footer"><div><span>{item.source}</span><span>{item.freshness}</span></div><button className="gc-text-button" type="button" onClick={() => { const next=selected === item.id ? null : item.id; setSelected(next); remember({selected:next}); }} aria-expanded={selected === item.id}>{selected === item.id ? '收起依据' : '依据与反证'} ↗</button></div>
    </section>)}</div>
    {visible.length === 0 && <div className="gc-panel"><p>当前周期没有这个方向的示例信息。</p></div>}
    {active && <section className="gc-panel gc-evidence-detail"><div className="gc-panel-head"><h3>{active.title} · 影响依据</h3><span className={`gc-impact-badge ${tone(direction(active))}`}>{direction(active)}</span></div><div className="gc-field-list"><div><span>判断依据</span><b>{active.reason}</b></div><div><span>反证与失效条件</span><b>{active.counter}</b></div><div><span>来源与核实状态</span><b>{active.verification}</b></div><div><span>市场是否已反映</span><b>{active.reaction}</b></div><div><span>影响对象</span><b>{active.target ?? '黄金价格方向'} · 产品执行条件另行核验</b></div></div><button type="button" className="gc-text-button" onClick={onResearch}>进入关联 AI 研究 ↗</button></section>}
    </>}
    {view === '消息流' && <section className="gc-panel"><div className="gc-panel-head"><h3>消息与事实流</h3><span className="gc-badge gc-muted-badge">设计示例</span></div>{evidence.map(item => <div className="gc-news-item" key={item.id}><div><span className="gc-badge gc-muted-badge">{item.category}</span><span className={`gc-impact-badge ${tone(item.short)}`}>{item.short} · 短线解读</span></div><h3>{item.title}</h3><p>{item.fact}</p><div className="gc-news-tags"><span>{item.source}</span><span>{item.verification}</span></div></div>)}</section>}
    {view === '经济日历' && <section className="gc-panel"><div className="gc-panel-head"><h3>官方日历与数据公布</h3><span className="gc-small">UTC+8 · 实际安排待接入</span></div>{[['美国通胀数据', 'BLS 官方日历', '前值 / 预期 / 实际 / 修订'], ['美国就业数据', 'BLS 官方日历', '新增就业 / 失业率 / 薪资'], ['议息与政策声明', '美联储官方日历', '决议 / 声明 / 记者会']].map(([name,source,fields]) => <div className="gc-calendar-event" key={name}><span className="gc-calendar-time">待接入</span><div><b>{name}</b><p>{source}</p><span className="gc-small">{fields}</span></div></div>)}<div className="gc-callout">实际值在发布后填写，一致预期需要独立授权来源。未知字段保持未知。</div></section>}
    <section className="gc-panel gc-event-window"><div><span className="gc-eyebrow">官方新闻与经济日历</span><h3>重要事件窗口同时纳入看板</h3><p>议息、通胀、就业、官方讲话；前值、预期、实际值与修订分别保存。</p></div><span className="gc-badge gc-muted-badge">具体发布安排待接入</span></section>
    <div className="gc-info-foot">全部为设计示例。方向是当前周期的模型判断，实际反应来自行情；同一消息可能已经被价格消化。</div>
  </>;
}
