import { useEffect, useId, useState } from 'react';
import { call } from './api';
import type { AiConfiguration, AiHorizon, AiProvider, AiSelection, ResearchPolicy, Session } from './api';

const defaults: AiSelection = {
  provider: '',
  model: '',
  inputEstimateLimit: 10000,
  characterLimit: 16000,
  byteLimit: 30000,
  messageLimit: 2,
  outputReserve: 2200,
  timeoutSeconds: 300,
};

const errors: Record<string, string> = {
  INVALID_RESEARCH_RETRY_POLICY: '尝试次数、退避或执行期限不在允许范围内；整轮预算不能小于单阶段预算。',
  AI_CONFIG_REVISION_CONFLICT: '配置已被另一个页面更新，请重新读取后修改。',
  AI_CONFIG_ENCRYPTION_KEY_MISSING: '服务器尚未配置密钥加密主密钥，暂时不能保存。',
  THREE_RESEARCH_AND_JUDGE_MODELS_REQUIRED: '每个周期需要三个辩论模型和三个裁决模型。',
  DUPLICATE_AI_MODEL: '同一阶段的模型不能重复。',
  THREE_DISTINCT_AI_PROVIDERS_REQUIRED: '同一阶段需要三个不同提供商。',
  INVALID_OR_DISABLED_AI_PROVIDER: '提供商标识重复、格式无效或已禁用。',
  INVALID_AI_MODEL_SELECTION: '请填写模型并选择已配置的提供商。',
  INVALID_AI_FALLBACK: '兜底模型只能配置一层，且需要与主模型不同。',
  INVALID_AI_CONTEXT_BUDGET: '上下文预算或超时不在允许范围内。',
  INVALID_AI_ENDPOINT: 'API 端点需要完整 HTTP(S) 地址，不能包含密钥、查询参数或片段。',
  INVALID_AI_CREDENTIAL: 'API Key 格式无效。',
  INVALID_AI_PROVIDER_PROTOCOL: '提供商名称或协议配置无效。',
};

const states: Record<string, string> = {
  CURRENT: '目录已读取',
  MANUAL: '手动模型 · 未验证推理',
  FAILED: '目录读取失败',
  NOT_CHECKED: '目录尚未读取',
  CREDENTIAL_MISSING: '凭据缺失',
};

const budgetFields = [
  ['characterLimit', '字符上限', 1024, 200000],
  ['byteLimit', 'UTF-8 字节上限', 1024, 800000],
  ['inputEstimateLimit', '输入估算上限', 512, 200000],
  ['outputReserve', '输出预留', 128, 32000],
  ['timeoutSeconds', '请求总超时（秒，流式）', 1, 900],
] as const;

const retryFields = [
  ['maxAttempts', '每席最大尝试次数（含首次）', 1, 6],
  ['retryBaseSeconds', '初始重试间隔（秒）', 1, 60],
  ['retryMaxSeconds', '重试间隔上限（秒）', 1, 120],
  ['stageSeconds', '每轮辩论执行预算（秒）', 30, 1800],
  ['judgeSeconds', '裁决执行预算（秒）', 30, 1800],
  ['workflowSeconds', '整轮研究执行预算（秒）', 120, 7200],
  ['decisionSeconds', '冻结证据交易有效期（秒）', 30, 172800],
] as const;

const defaultPolicy = (longTerm: boolean): ResearchPolicy => ({
  maxAttempts: 3,
  retryBaseSeconds: 2,
  retryMaxSeconds: 30,
  stageSeconds: longTerm ? 900 : 30,
  judgeSeconds: longTerm ? 900 : 30,
  workflowSeconds: longTerm ? 3600 : 120,
  decisionSeconds: longTerm ? 3600 : 240,
});

function ModelSlot({
  selection,
  providers,
  catalogs,
  name,
  update,
  allowFallback = true,
}: {
  selection: AiSelection;
  providers: AiProvider[];
  catalogs: AiConfiguration['catalogs'];
  name: string;
  update: (s: AiSelection) => void;
  allowFallback?: boolean;
}) {
  const catalog = catalogs.find((item) => item.provider === selection.provider);
  const native = selection.model.startsWith(selection.provider + '/')
    ? selection.model.slice(selection.provider.length + 1)
    : selection.model;
  const match = catalog?.models.find(
    (model) =>
      model.id === selection.model || model.id === selection.provider + '/' + native
  );
  const list = `ai-model-${useId().replace(/:/g, '')}`;

  return (
    <div
      className="gc-ai-slot"
      style={{
        background: 'var(--gc-surface-raised)',
        padding: '14px',
        borderRadius: 'var(--gc-radius-sm)',
        border: '1px solid var(--gc-line)',
      }}
    >
      <div className="gc-ai-slot-head" style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '8px' }}>
        <b style={{ color: 'var(--gc-text)' }}>{name}</b>
        <span className="gc-badge">
          {match?.available === false
            ? '目录不可用'
            : match?.available === true
            ? '目录可用'
            : states[catalog?.state ?? 'NOT_CHECKED']}
        </span>
      </div>
      <div className="gc-ai-slot-fields" style={{ display: 'grid', gridTemplateColumns: 'minmax(0, 1fr) minmax(0, 1.2fr)', gap: '8px' }}>
        <label>
          提供商
          <select
            aria-label={`${name}提供商`}
            required
            value={selection.provider}
            onChange={(event) =>
              update({ ...selection, provider: event.target.value, model: '' })
            }
          >
            <option value="">请选择</option>
            {providers
              .filter((p) => !p.disabled)
              .map((provider) => (
                <option value={provider.id} key={provider.id}>
                  {provider.label} ({provider.id})
                </option>
              ))}
          </select>
        </label>
        <label>
          模型 ID
          <input
            aria-label={`${name}模型`}
            required
            list={list}
            value={native}
            placeholder="模型 ID"
            onChange={(event) => update({ ...selection, model: event.target.value })}
          />
          <datalist id={list}>
            {catalog?.models.map((model) => (
              <option
                value={
                  model.id.startsWith(selection.provider + '/')
                    ? model.id.slice(selection.provider.length + 1)
                    : model.id
                }
                key={model.id}
              >
                {model.name}
                {model.available === false ? ' · 不可用' : ''}
              </option>
            ))}
          </datalist>
        </label>
      </div>
      <details className="gc-ai-slot-budget" style={{ marginTop: '10px' }}>
        <summary style={{ cursor: 'pointer', fontSize: '11px', color: 'var(--gc-muted)' }}>
          上下文预算与超时 · {selection.characterLimit} 字符 / {selection.timeoutSeconds}s ↗
        </summary>
        <div className="gc-ai-budget-grid" style={{ marginTop: '8px' }}>
          {budgetFields.map(([key, title, min, max]) => (
            <label key={key}>
              {title}
              <input
                aria-label={`${name}${title}`}
                type="number"
                min={min}
                max={max}
                required
                value={selection[key]}
                onChange={(event) =>
                  update({ ...selection, [key]: Number(event.target.value) })
                }
              />
            </label>
          ))}
        </div>
        {match && (
          <p className="gc-small" style={{ margin: '6px 0 0' }}>
            目录声明：上下文 {match.contextTokens ?? '未知'} / 输入{' '}
            {match.inputTokens ?? '未知'} / 输出 {match.outputTokens ?? '未知'} tokens。
          </p>
        )}
      </details>
      {allowFallback && <details className="gc-ai-slot-fallback" style={{ marginTop: '10px' }}>
        <summary style={{ cursor: 'pointer', fontSize: '12px' }}>失败兜底 · {selection.fallback?.model || '未启用'}</summary>
        <label style={{ display: 'flex', alignItems: 'center', gap: '8px', margin: '10px 0' }}>
          <input aria-label={`${name}启用兜底`} type="checkbox" style={{ width: 'auto' }} checked={Boolean(selection.fallback)} onChange={event => update({ ...selection, fallback: event.target.checked ? { ...selection, provider: 'arena', model: 'arena/Max', fallback: null } : null })} />启用兜底模型
        </label>
        {selection.fallback && <ModelSlot selection={selection.fallback} providers={providers} catalogs={catalogs} name={`${name}兜底`} allowFallback={false} update={fallback => update({ ...selection, fallback })} />}
        <p className="gc-small">主模型不可用、超时或输出校验失败时切换。Arena Max 使用随机模型路由；每席独立请求，仍受阶段与整轮期限约束。</p>
      </details>}
    </div>
  );
}

export default function AiConfigurationPanel({
  session,
  changed,
}: {
  session: Session;
  changed: () => void;
}) {
  const [config, setConfig] = useState<AiConfiguration | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [saved, setSaved] = useState(false);
  const [dirty, setDirty] = useState(false);

  const [activeTab, setActiveTab] = useState<'providers' | 'models' | 'compression' | 'policy'>('providers');
  const [horizon, setHorizon] = useState<'shortRoutes' | 'longRoutes'>('shortRoutes');

  const policyKey = horizon === 'shortRoutes' ? 'shortPolicy' : 'longPolicy';

  const load = async () => {
    setBusy(true);
    try {
      setConfig(await call<AiConfiguration>('ai/configuration', session));
      setDirty(false);
      setSaved(false);
      setError('');
    } catch (failure) {
      setError((failure as Error).message);
    } finally {
      setBusy(false);
    }
  };

  useEffect(() => {
    void load();
  }, [session.username]);

  const edit = (value: AiConfiguration) => {
    setConfig(value);
    setDirty(true);
    setSaved(false);
  };

  const save = async () => {
    if (!config) return;
    setBusy(true);
    setError('');
    try {
      const result = await call<AiConfiguration>('ai/configuration', session, {
        expectedRevision: config.revision,
        providers: config.providers.map(
          ({ id, label, baseUrl, protocol, catalogMode, apiKey, clearApiKey }) => ({
            id,
            label,
            baseUrl,
            protocol,
            catalogMode,
            apiKey: apiKey || null,
            clearApiKey: clearApiKey ?? false,
          })
        ),
        shortRoutes: config.shortRoutes,
        longRoutes: config.longRoutes,
        compressionEnabled: config.compressionEnabled,
        compression: config.compression,
        shortPolicy: config.shortPolicy,
        longPolicy: config.longPolicy,
      });
      setConfig(result);
      setDirty(false);
      setSaved(true);
      changed();
    } catch (failure) {
      setError((failure as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const refresh = async () => {
    setBusy(true);
    setError('');
    try {
      const result = await call<AiConfiguration>('ai/configuration/models', session, {});
      setConfig((previous) =>
        previous ? { ...previous, catalogs: result.catalogs } : result
      );
    } catch (failure) {
      setError((failure as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const updateProvider = (index: number, patch: Partial<AiProvider>) => {
    if (config)
      edit({
        ...config,
        providers: config.providers.map((p, i) => (i === index ? { ...p, ...patch } : p)),
      });
  };

  const updateSlot = (
    role: keyof AiHorizon,
    index: number,
    selection: AiSelection
  ) => {
    if (config)
      edit({
        ...config,
        [horizon]: {
          ...config[horizon],
          [role]: config[horizon][role].map((item, i) =>
            i === index ? selection : item
          ),
        },
      });
  };

  return (
    <section className="gc-panel gc-ai-config" aria-label="AI 提供商与角色配置">
      <div className="gc-panel-head">
        <div>
          <h3>AI 路由与大模型配置管理</h3>
          <span className="gc-small">
            持久化保存至服务端数据库 · 动态热重载于下一轮研究生效
          </span>
        </div>
        <div className="gc-title-actions">
          <span className="gc-badge gc-gold-badge">
            配置版本 v{config?.revision ?? '—'}
            {dirty ? ' · 待保存更改' : ''}
          </span>
        </div>
      </div>

      {error && (
        <p className="gc-live-error" role="alert">
          {errors[error] ?? error}
        </p>
      )}

      {!config ? (
        <p className="gc-small">正在读取服务端配置…</p>
      ) : (
        <form
          className="gc-control-form"
          onSubmit={(event) => {
            event.preventDefault();
            void save();
          }}
        >
          <p className="gc-small">短线与长线独立执行。短线整轮最多 120 秒，每阶段最多 30 秒，关闭支持的深度思考并跳过慢兜底；超时或缺票时放弃信号。长线请求可配置到 900 秒，仍受阶段、整轮预算和证据有效期限制。</p>
          {/* Sub-tab Switcher for 4 Config Areas */}
          <div className="gc-segment" role="tablist" style={{ marginBottom: '16px' }}>
            <button
              type="button"
              className={activeTab === 'providers' ? 'active' : ''}
              onClick={() => setActiveTab('providers')}
            >
              1. 提供商与 API 端点 ({config.providers.length})
            </button>
            <button
              type="button"
              className={activeTab === 'models' ? 'active' : ''}
              onClick={() => setActiveTab('models')}
            >
              2. 辩论与裁决模型路线
            </button>
            <button
              type="button"
              className={activeTab === 'compression' ? 'active' : ''}
              onClick={() => setActiveTab('compression')}
            >
              3. 前置信息压缩 (MIMO)
            </button>
            <button
              type="button"
              className={activeTab === 'policy' ? 'active' : ''}
              onClick={() => setActiveTab('policy')}
            >
              4. 容错重试与执行期限
            </button>
          </div>

          {/* TAB 1: Providers */}
          {activeTab === 'providers' && (
            <fieldset disabled={busy}>
              <legend>提供商与 API 端点</legend>
              <p className="gc-small" style={{ marginBottom: '14px' }}>
                支持 any2api 聚合网关与标准 OpenAI 兼容协议。API Key
                高强度加密保存于服务端，前端不直接回传明文。
              </p>
              <div
                style={{
                  display: 'grid',
                  gridTemplateColumns: 'repeat(auto-fill, minmax(min(100%, 320px), 1fr))',
                  gap: '14px',
                }}
              >
                {config.providers.map((provider, index) => (
                  <article
                    className="gc-panel"
                    key={index}
                    style={{
                      background: 'var(--gc-surface-raised)',
                      border: '1px solid var(--gc-line)',
                      padding: '16px',
                    }}
                  >
                    <div className="gc-panel-head">
                      <b>{provider.label || '新增提供商'}</b>
                      <button
                        className="gc-text-button"
                        type="button"
                        onClick={() =>
                          edit({
                            ...config,
                            providers: config.providers.filter((_, i) => i !== index),
                          })
                        }
                      >
                        移除
                      </button>
                    </div>
                    <div className="gc-form-grid">
                      <label>
                        标识
                        <input
                          required
                          pattern="[a-z][a-z0-9_-]{1,31}"
                          value={provider.id}
                          readOnly={provider.credentialConfigured}
                          placeholder="如 provider_a"
                          onChange={(e) => updateProvider(index, { id: e.target.value })}
                        />
                      </label>
                      <label>
                        显示名称
                        <input
                          required
                          maxLength={60}
                          value={provider.label}
                          onChange={(e) =>
                            updateProvider(index, { label: e.target.value })
                          }
                        />
                      </label>
                    </div>
                    <label style={{ display: 'block', margin: '8px 0' }}>
                      API 端点 URL
                      <input
                        required
                        type="url"
                        placeholder="https://example.com/v1"
                        value={provider.baseUrl}
                        onChange={(e) =>
                          updateProvider(index, { baseUrl: e.target.value })
                        }
                      />
                    </label>
                    <label style={{ display: 'block', margin: '8px 0' }}>
                      API Key
                      <input
                        type="password"
                        autoComplete="new-password"
                        value={provider.apiKey ?? ''}
                        placeholder={
                          provider.credentialConfigured
                            ? '已加密保存 · 留空保持原值'
                            : '未配置，请输入'
                        }
                        onChange={(e) =>
                          updateProvider(index, {
                            apiKey: e.target.value,
                            clearApiKey: false,
                          })
                        }
                      />
                    </label>
                    {provider.credentialConfigured && (
                      <label
                        style={{
                          display: 'flex',
                          alignItems: 'center',
                          gap: '6px',
                          fontSize: '11px',
                          margin: '6px 0',
                        }}
                      >
                        <input
                          type="checkbox"
                          checked={provider.clearApiKey ?? false}
                          onChange={(e) =>
                            updateProvider(index, {
                              clearApiKey: e.target.checked,
                              apiKey: '',
                            })
                          }
                        />
                        清除已保存密钥
                      </label>
                    )}
                    <div className="gc-form-grid" style={{ marginTop: '8px' }}>
                      <label>
                        协议
                        <select
                          value={provider.protocol}
                          onChange={(e) =>
                            updateProvider(index, {
                              protocol: e.target.value as AiProvider['protocol'],
                              catalogMode:
                                e.target.value === 'ANY2API'
                                  ? 'DISCOVERED'
                                  : provider.catalogMode,
                            })
                          }
                        >
                          <option value="ANY2API">any2api</option>
                          <option value="OPENAI_COMPATIBLE">OpenAI 兼容</option>
                        </select>
                      </label>
                      <label>
                        目录模式
                        <select
                          value={provider.catalogMode}
                          onChange={(e) =>
                            updateProvider(index, {
                              catalogMode: e.target.value as AiProvider['catalogMode'],
                            })
                          }
                        >
                          <option value="DISCOVERED">端点自动查询</option>
                          {provider.protocol === 'OPENAI_COMPATIBLE' && (
                            <option value="MANUAL">手动填写模型</option>
                          )}
                        </select>
                      </label>
                    </div>
                  </article>
                ))}
              </div>
              <div
                style={{
                  display: 'flex',
                  gap: '10px',
                  marginTop: '16px',
                  flexWrap: 'wrap',
                }}
              >
                <button
                  className="gc-live-button"
                  type="button"
                  disabled={config.providers.length >= 20}
                  onClick={() =>
                    edit({
                      ...config,
                      providers: [
                        ...config.providers,
                        {
                          id: '',
                          label: '',
                          baseUrl: '',
                          protocol: 'OPENAI_COMPATIBLE',
                          catalogMode: 'MANUAL',
                          credentialConfigured: false,
                          disabled: false,
                        },
                      ],
                    })
                  }
                >
                  + 添加提供商
                </button>
                <button
                  className="gc-live-button"
                  type="button"
                  onClick={() => void refresh()}
                >
                  刷新已保存端点的模型目录
                </button>
              </div>
            </fieldset>
          )}

          {/* TAB 2: Models & Routes */}
          {activeTab === 'models' && (
            <fieldset disabled={busy}>
              <legend>辩论与裁决模型路线</legend>
              <div className="gc-chart-frames">
                {(['shortRoutes', 'longRoutes'] as const).map((key) => (
                  <button
                    className="gc-live-button"
                    type="button"
                    key={key}
                    aria-pressed={horizon === key}
                    onClick={() => setHorizon(key)}
                  >
                    {key === 'shortRoutes' ? '短线周期配置' : '长线周期配置'}
                  </button>
                ))}
                <button
                  className="gc-live-button"
                  type="button"
                  onClick={() =>
                    edit({
                      ...config,
                      [horizon === 'shortRoutes' ? 'longRoutes' : 'shortRoutes']:
                        structuredClone(config[horizon]),
                    })
                  }
                >
                  同步复制至{horizon === 'shortRoutes' ? '长线' : '短线'}
                </button>
              </div>

              {(['researchers', 'judges'] as const).map((role) => (
                <div key={role} style={{ marginTop: '16px' }}>
                  <div className="gc-panel-head">
                    <h4>
                      {role === 'researchers'
                        ? '三方辩论席位 (3 Distinct Models)'
                        : '三方独立裁决席位 (3 Blind Judges)'}
                    </h4>
                    {role === 'judges' && (
                      <button
                        className="gc-text-button"
                        type="button"
                        onClick={() =>
                          edit({
                            ...config,
                            [horizon]: {
                              ...config[horizon],
                              judges: structuredClone(config[horizon].researchers),
                            },
                          })
                        }
                      >
                        快捷复用辩论席位的三个模型 ↗
                      </button>
                    )}
                  </div>
                  <div
                    style={{
                      display: 'grid',
                      gridTemplateColumns: 'repeat(auto-fill, minmax(min(100%, 300px), 1fr))',
                      gap: '12px',
                    }}
                  >
                    {Array.from({ length: 3 }, (_, index) => (
                      <ModelSlot
                        key={`${horizon}-${role}-${index}`}
                        name={`${horizon === 'shortRoutes' ? '短线' : '长线'}${
                          role === 'researchers' ? '辩论' : '裁决'
                        } #${index + 1}`}
                        selection={config[horizon][role][index] ?? defaults}
                        providers={config.providers}
                        catalogs={config.catalogs}
                        update={(selection) => {
                          if (config[horizon][role].length < 3) {
                            const slots = Array.from(
                              { length: 3 },
                              (_, i) => config[horizon][role][i] ?? { ...defaults }
                            );
                            slots[index] = selection;
                            edit({
                              ...config,
                              [horizon]: { ...config[horizon], [role]: slots },
                            });
                          } else updateSlot(role, index, selection);
                        }}
                      />
                    ))}
                  </div>
                </div>
              ))}
            </fieldset>
          )}

          {/* TAB 3: Compression */}
          {activeTab === 'compression' && (
            <fieldset disabled={busy}>
              <legend>前置信息提炼与压缩</legend>
              <label
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: '8px',
                  marginBottom: '14px',
                  fontWeight: 600,
                }}
              >
                <input
                  type="checkbox"
                  checked={config.compressionEnabled}
                  onChange={(e) =>
                    edit({ ...config, compressionEnabled: e.target.checked })
                  }
                />
                启用前置新闻全文自动提炼与压缩
              </label>
              <div style={{ maxWidth: '500px' }}>
                <ModelSlot
                  selection={config.compression}
                  providers={config.providers}
                  catalogs={config.catalogs}
                  name="信息压缩席位"
                  update={(selection) => edit({ ...config, compression: selection })}
                />
              </div>
              <p className="gc-small" style={{ marginTop: '12px' }}>
                默认采用轻量高效模型（如 MIMO），将多篇长资讯全文提炼为关键事实要点。
                压缩席位不占用交易投票权。
              </p>
            </fieldset>
          )}

          {/* TAB 4: Retry Policy & Timeouts */}
          {activeTab === 'policy' && (
            <fieldset disabled={busy}>
              <legend>{horizon === 'shortRoutes' ? '短线' : '长线'}容错与执行期限</legend>
              <p className="gc-small" style={{ marginBottom: '14px' }}>
                单席位失败重试隔离；超时退避自动避让限流。
              </p>
              <div className="gc-ai-budget-grid">
                {retryFields.map(([key, title, min, max]) => (
                  <label key={key}>
                    {title}
                    <input
                      aria-label={`${horizon === 'shortRoutes' ? '短线' : '长线'}${title}`}
                      type="number"
                      min={min}
                      max={max}
                      required
                      value={
                        (
                          config[policyKey] ??
                          defaultPolicy(horizon === 'longRoutes')
                        )[key]
                      }
                      onChange={(event) =>
                        edit({
                          ...config,
                          [policyKey]: {
                            ...(config[policyKey] ??
                              defaultPolicy(horizon === 'longRoutes')),
                            [key]: Number(event.target.value),
                          },
                        })
                      }
                    />
                  </label>
                ))}
              </div>
            </fieldset>
          )}

          {/* Footer Save & Refresh Actions */}
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: '12px',
              marginTop: '16px',
              paddingTop: '16px',
              borderTop: '1px solid var(--gc-line)',
            }}
          >
            <button
              className="gc-live-button gc-live-primary"
              disabled={busy || !dirty || !config.encryptionConfigured}
            >
              {busy ? '正在保存…' : '保存 AI 路由配置'}
            </button>
            <button
              className="gc-live-button"
              type="button"
              disabled={busy}
              onClick={() => void load()}
            >
              重载配置
            </button>
            {saved && (
              <span
                style={{
                  color: 'var(--gc-positive)',
                  fontSize: '12px',
                  fontWeight: 600,
                }}
              >
                ✓ 配置已保存，将在下一轮研究时自动生效
              </span>
            )}
          </div>
        </form>
      )}
    </section>
  );
}
