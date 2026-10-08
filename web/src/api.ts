export type Product = 'XAUUSDT' | 'XAUUSD.s';
export type Direction = 'BULLISH' | 'BEARISH' | 'UNKNOWN' | 'MIXED';
export type Session = { username: string; csrfHeader: string; csrfToken: string; authorization: string };
export type Quote = { symbol: string; source: string; sequence: number; exchangeTime: string; receivedAt: string; bid: string; ask: string; bidSize: string; askSize: string; last: string | null; mark: string | null; markTime: string | null; fundingRate: string | null; nextFundingTime: string | null; continuous: boolean };
export type Indicators = { frame: string; samples: number; closedThrough: string | null; state: string; values: Record<string, string | null> };
export type Candle = { start: string; end: string; open: string; high: string; low: string; close: string; volume: string };
export type Instrument = { symbol: string; maxLeverage: string; tickSize: string; qtyStep: string; receivedAt: string; status: string };
export type Market = { symbol: Product; state: string; quote: Quote | null; rules: Instrument | null; indicators: Record<string, Indicators>; candles: Candle[]; retrievedAt: string; error: string | null };
export type Order = { id: string; decisionId: string; symbol: string; side: string; type: string; status: string; positionStatus: string; riskAssessment: string; invalidate: string; rejection: string | null; createdAt: string; activatedAt: string; expiresAt: string | null; holdUntil: string | null; closedAt: string | null; quantity: string | null; leverage: string | null; entryPrice: string | null; averageEntry: string; filled: string; openQuantity: string; margin: string; reservation: string; entryFees: string; exitFees: string; realizedGross: string; netRealized: string; costIncomplete: boolean; quoteGap: boolean; exitReason: string | null; version: number };
export type Account = { symbol: string; currency: string; state: string; balance: string | null; available: string | null; equity: string | null; frozenMargin: string | null; unrealized: string | null; estimatedExitFees: string | null; valuedAt: string | null; orders: Order[]; costStatus: string; liquidationMethod: string };
export type News = { id: string; source: string; title: string; excerpt: string; link: string; publishedAt: string | null; receivedAt: string; verification: string; version: number };
export type CalendarEvent = { id: string; source: string; title: string; scheduledAt: string; originalZone: string; link: string; receivedAt: string };
export type Source = { id: string; url: string; state: string; intervalSeconds: number; lastSuccess: string | null; nextPoll: string | null; error: string | null; entries: number };
export type Impact = { newsId: string; horizon: string; direction: Direction; reason: string; counter: string };
export type Output = { title: string; analysisSummary: string; output: string; shortDirection: Direction; longDirection: Direction; quotedMessages?: string[]; evidenceRefs?: string[]; changes?: string; impacts?: Impact[]; plan?: Record<string, unknown> };
export type Budget = { bytes: number; characters: number; gatewayInputEstimate: number; messages: number; outputReserve: number; inputHash: string; limitSource: string; outputEnforcement: string };
export type Answer = { model: string; analysis: string; analysisSource: string; raw: string; output: Output | null; usage: Record<string, unknown>; usageSource: string; finishReason: string; budget: Budget; durationMs: number };
export type Message = { id: string; actorId: string; role: string; stage: number; state: string; startedAt: string; completedAt: string | null; error: string | null; readingOrder: string[]; answer: Answer | null };
export type Run = { id: string; symbol: Product; horizon: string; status: string; error: string | null; evidenceHash: string; orderId: string | null; stage: number; seed: number; evidenceCutoff: string; startedAt: string; completedAt: string | null; actors: { id: string; role: string; routeId: string; provider: string; model: string }[]; messages: Message[]; verdict: Output | null; evidence: { news?: { id: string; version: number }[]; [key: string]: unknown } };
export type Model = { routeId: string; provider: string; model: string; credentialConfigured: boolean; catalog: { available?: boolean; runtime?: { status?: string }; capabilities?: Record<string, unknown> } | null; catalogRetrievedAt: string | null; lastInference: string; inferenceError: string | null };
export type Dashboard = { serverTime: string; market: Market; account: Account; news: News[]; calendar: CalendarEvent[]; sources: Source[]; models: Model[]; budgets: { id: string; inputEstimateLimit: number; characterLimit: number; byteLimit: number; outputReserve: number; timeoutSeconds: number; judge: boolean }[]; research: Run | null; researchHistory: { id: string; horizon: string; status: string; startedAt: string; stage: number }[]; scheduling: { automatic: boolean; running: boolean; lastAutomatic: string; queuedNews: string; error: string }; review: { closedOrders: number; gross: string; fees: string; netBeforeUnknownCosts: string; fullyCostedOrders: number; positiveRecordedNet: number; status: string }; clock: { state: string; source?: string; offsetMillis?: number; uncertaintyMillis?: number; calibratedAt?: string; error?: string } };
export type AuditEvent = { sequence: number; aggregateId: string; type: string; payload: Record<string, unknown>; occurredAt: string };

export function authorization(username: string, password: string) {
  return 'Basic ' + btoa(Array.from(new TextEncoder().encode(`${username}:${password}`), byte => String.fromCharCode(byte)).join(''));
}
export async function call<T>(path: string, session: Pick<Session, 'authorization'> & Partial<Session>, body?: unknown, signal?: AbortSignal): Promise<T> {
  const headers: Record<string, string> = { Authorization: session.authorization, Accept: 'application/json' };
  if (body !== undefined) { headers['Content-Type'] = 'application/json'; if (session.csrfHeader && session.csrfToken) headers[session.csrfHeader] = session.csrfToken; }
  const response = await fetch(`/api/${path}`, { headers, credentials: 'include', method: body === undefined ? 'GET' : 'POST', body: body === undefined ? undefined : JSON.stringify(body), signal });
  if (!response.ok) {
    let code = response.status === 401 ? '登录凭据不正确或登录已失效' : `HTTP_${response.status}`;
    try { code = (await response.json()).error ?? code; } catch { /* The response may have no JSON body. */ }
    throw new Error(code);
  }
  return response.json();
}
export async function watch(session: Session, changed: () => void, state: (connected: boolean) => void, signal: AbortSignal) {
  while (!signal.aborted) {
    try {
      const response = await fetch('/api/events', { headers: { Authorization: session.authorization, Accept: 'text/event-stream' }, credentials: 'include', signal });
      if (!response.ok || !response.body) throw new Error('EVENT_STREAM_UNAVAILABLE');
      state(true); const reader = response.body.getReader(); const decoder = new TextDecoder(); let buffer = '', revision = '';
      try {
        while (!signal.aborted) {
          const part = await reader.read(); if (part.done) break;
          buffer += decoder.decode(part.value, { stream: true }); buffer = buffer.replace(/\r\n/g, '\n');
          if (buffer.length > 65536) throw new Error('EVENT_FRAME_TOO_LARGE');
          let boundary: number;
          while ((boundary = buffer.indexOf('\n\n')) >= 0) {
            const frame = buffer.slice(0, boundary); buffer = buffer.slice(boundary + 2);
            const current = frame.split('\n').find(line => line.startsWith('data:'))?.slice(5).trim();
            if (current && current !== revision) { revision = current; changed(); }
          }
        }
      } finally { reader.releaseLock(); }
    } catch { /* A periodic snapshot refresh continues while the stream reconnects. */ }
    if (signal.aborted) return; state(false);
    await new Promise<void>(resolve => { const done = () => { clearTimeout(timer); signal.removeEventListener('abort', done); resolve(); }; const timer = setTimeout(done, 3000); signal.addEventListener('abort', done, { once: true }); });
  }
}
