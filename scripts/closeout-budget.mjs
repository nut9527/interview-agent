// 收尾实验的本地费用闸门；账本先落盘再发请求，不改变生产计费或旧实验额度。
import { open, readFile, lstat, rm } from 'node:fs/promises';
import { createServer } from 'node:http';
import { sha256 } from './release-candidate.mjs';
import { assessReview } from './closeout-review.mjs';

export const campaign = 'v1-closeout';
export const limits = [
  { id: 'closeout-1', money: 6000000, attempts: 8 },
  { id: 'closeout-2', money: 12000000, attempts: 56 },
  { id: 'closeout-3', money: 12000000, attempts: 48 },
];
const integer = value => Number.isSafeInteger(value) && value >= 0;
const fail = message => { throw new Error(message); };

export function validatePrice(price, now = Date.now()) {
  if (price?.model !== 'deepseek-flash' || price.endpoint !== 'https://api.deepseek.com/chat/completions'
      || price.currency !== 'CNY' || price.contextTokenUpperBound !== 1048576
      || price.inputMicroCnyPerToken !== 2 || price.outputMicroCnyPerToken !== 8
      || price.source !== 'https://api-docs.deepseek.com/zh-cn/quick_start/pricing/'
      || !Number.isFinite(Date.parse(price.verifiedAt)) || !Number.isFinite(Date.parse(price.validUntil))
      || Date.parse(price.verifiedAt) > now || Date.parse(price.validUntil) <= now
      || Date.parse(price.validUntil) - Date.parse(price.verifiedAt) > 86400000)
    fail('Price book absent, changed or expired; recheck official prices before execution');
}

export function reservationFor(body, price, now = Date.now()) {
  validatePrice(price, now);
  if (!Buffer.isBuffer(body) || body.length > 480000) fail('Request byte limit exceeded');
  const request = JSON.parse(body.toString('utf8'));
  if (request.model !== price.model || request.thinking?.type !== 'disabled' || request.stream !== false
      || !integer(request.max_tokens) || request.max_tokens < 1 || request.max_tokens > 2000
      || !Array.isArray(request.messages) || request.messages.length === 0
      || request.messages.some(m => typeof m.content !== 'string' && m.content !== null && m.content !== undefined)
      || request.n !== undefined || request.max_completion_tokens !== undefined)
    fail('Only the frozen, non-streaming text request is admitted');
  // 1M 上下文按更大的 2^20 计费预占，包含工具和聊天模板开销；字节限制只控制传输大小。
  // 不声称精确计数。每次调用均使用此保守上界，余额不够就拒绝发送。
  const input = price.contextTokenUpperBound, output = request.max_tokens;
  return { requestSha256: sha256(body), input, output,
    microCny: input * price.inputMicroCnyPerToken + output * price.outputMicroCnyPerToken };
}

function settlement(usage, reservation, price) {
  if (!usage || !integer(usage.prompt_tokens) || !integer(usage.completion_tokens)
      || !integer(usage.total_tokens) || usage.total_tokens !== usage.prompt_tokens + usage.completion_tokens
      || usage.prompt_tokens > reservation.input || usage.completion_tokens > reservation.output)
    return { status: 'UNKNOWN', retained: reservation.microCny };
  // 即使实际发生缓存命中或空闲时段折扣，也按已核对的峰值未命中价保守结算。
  return { status: 'REPORTED_CONSERVATIVE', retained: usage.prompt_tokens * price.inputMicroCnyPerToken
    + usage.completion_tokens * price.outputMicroCnyPerToken, usage };
}

function totals(batches) {
  const requests = batches.flatMap(b => b.requests);
  return { attempts: requests.length, money: requests.reduce((sum, r) => sum + r.retained, 0) };
}

// 追加快照与哈希链使中断、半行写入和错误覆盖均拒绝恢复；不自动修复或释放未知费用。
export async function openBudget(path) {
  for (const name of [path, path + '.lock']) {
    try { if ((await lstat(name)).isSymbolicLink()) fail('Budget file must not be a link'); }
    catch (error) { if (error.code !== 'ENOENT') throw error; }
  }
  const lock = await open(path + '.lock', 'wx');
  let journal;
  try {
    let raw = '';
    try { raw = await readFile(path, 'utf8'); } catch (error) { if (error.code !== 'ENOENT') throw error; }
    let state = { campaign, batches: [], stopped: false }, previous = '0'.repeat(64), sequence = 0;
    if (raw && !raw.endsWith('\n')) fail('Incomplete budget journal; retain reservations and audit manually');
    for (const line of raw.split('\n').filter(Boolean)) {
      const row = JSON.parse(line);
      if (row.sequence !== ++sequence || row.previous !== previous
          || row.sha256 !== sha256(JSON.stringify({ sequence, previous, state: row.state }))) fail('Budget journal hash mismatch');
      state = row.state; previous = row.sha256;
    }
    if (state.campaign !== campaign || !Array.isArray(state.batches)) fail('Invalid campaign ledger');
    journal = await open(path, 'a');
    let poisoned = false, busy = false;
    const save = async () => {
      if (poisoned) fail('Budget journal write previously failed');
      const row = { sequence: ++sequence, previous, state };
      const hash = sha256(JSON.stringify(row));
      try { await journal.writeFile(JSON.stringify({ ...row, sha256: hash }) + '\n'); await journal.sync(); }
      catch (error) { poisoned = true; throw error; }
      previous = hash;
    };
    return {
      snapshot: () => structuredClone(state),
      async begin(plan) {
        if (busy || poisoned || state.stopped) fail('Campaign stopped or unavailable');
        if (state.batches.some(b => ['RUNNING', 'REVIEW_PENDING'].includes(b.status))) fail('Prior batch needs review or recovery');
        const started = state.batches.filter(b => b.requests.length > 0);
        if (started.length >= 3 || started.slice(-2).filter(b => b.status === 'FAIL').length === 2)
          fail('Campaign batch/failure limit reached');
        const limit = limits[started.length];
        if (plan.batchId !== limit.id || !/^[a-f0-9]{64}$/.test(plan.sha256)
            || !/^[a-f0-9]{64}$/.test(plan.sourceManifestSha256)) fail('Unexpected frozen batch');
        validatePrice(plan.price);
        state.batches.push({ id: plan.batchId, planSha256: plan.sha256, sourceManifestSha256: plan.sourceManifestSha256,
          price: plan.price, status: 'RUNNING', deadline: Date.now() + 300000, requests: [] });
        await save();
      },
      async exchange(id, body, send) {
        if (poisoned || state.stopped || busy) fail('Request gate busy or stopped');
        const batch = state.batches.at(-1);
        if (!batch || batch.status !== 'RUNNING' || Date.now() >= batch.deadline) fail('Batch not running or deadline reached');
        if (!/^[A-Za-z0-9_-]{1,128}$/.test(id || '')) fail('Missing stable attempt ID');
        if (state.batches.some(b => b.requests.some(r => r.id === id))) fail('Duplicate attempt; never resend or charge twice');
        const reservation = reservationFor(body, batch.price), limit = limits.find(l => l.id === batch.id);
        const all = totals(state.batches), current = totals([batch]);
        if (current.attempts >= limit.attempts || all.attempts >= 112
            || current.money + reservation.microCny > limit.money || all.money + reservation.microCny > 30000000)
          fail('Insufficient reserved budget or attempt allowance');
        busy = true;
        const record = { id, ...reservation, retained: reservation.microCny, status: 'RESERVED', startedAt: new Date().toISOString() };
        batch.requests.push(record);
        try {
          await save(); // 此后即使进程崩溃，也保留预占并计为已启动批次。
          const response = await send(body, id);
          let usage;
          try { if (response.status >= 200 && response.status < 300) usage = JSON.parse(response.body.toString('utf8')).usage; }
          catch { /* 响应不可解析时保留全部预占。 */ }
          Object.assign(record, settlement(usage, reservation, batch.price), { responseStatus: response.status,
            responseSha256: sha256(response.body) });
          if (usage && ((integer(usage.prompt_tokens) && usage.prompt_tokens > reservation.input)
              || (integer(usage.completion_tokens) && usage.completion_tokens > reservation.output))) state.stopped = true;
          await save();
          return response;
        } catch (error) {
          if (!poisoned && record.status === 'RESERVED') { record.status = 'UNKNOWN'; await save(); }
          throw error;
        } finally { busy = false; }
      },
      async finish(status, evidence) {
        if (busy || poisoned) fail('Cannot finish active or damaged journal');
        const batch = state.batches.at(-1);
        if (!batch || batch.status !== 'RUNNING' || !['FAIL', 'REVIEW_PENDING'].includes(status)) fail('Invalid batch finish');
        batch.status = batch.requests.length ? status : 'NOT_STARTED';
        if (evidence) batch.evidence = structuredClone(evidence);
        await save();
      },
      async review(plan, document, reportBytes, wireBytes) {
        if (busy || poisoned || state.stopped) fail('Cannot review active, damaged or stopped journal');
        const batch = state.batches.at(-1);
        if (!batch) fail('No batch to review');
        const result = assessReview(plan, batch, document, reportBytes, wireBytes);
        batch.status = result.status;
        batch.review = { ...result, reviewedAt: document.metadata.reviewedAt,
          method: document.metadata.reviewMethod, document: structuredClone(document) };
        if (result.stop) state.stopped = true;
        await save();
        return result;
      },
      async recover() {
        if (busy || poisoned) fail('Cannot recover active gate');
        const batch = state.batches.at(-1);
        if (!batch || batch.status !== 'RUNNING') fail('No interrupted batch');
        batch.status = batch.requests.length ? 'FAIL' : 'NOT_STARTED';
        await save();
      },
      async close() { await journal.close(); await lock.close(); await rm(path + '.lock'); },
    };
  } catch (error) { await journal?.close(); await lock.close(); await rm(path + '.lock'); throw error; }
}

// 只监听本机；调用者持有一次性令牌，真实 API 密钥只在上游发送函数中使用。
export async function budgetProxy(budget, token, send) {
  const pending = new Set();
  const handle = async (request, response) => {
    try {
      if (request.method !== 'POST' || request.url !== '/chat/completions'
          || request.headers.authorization !== `Bearer ${token}`) fail('Local gate authorization/path rejected');
      const chunks = []; let size = 0;
      for await (const chunk of request) {
        size += chunk.length;
        if (size > 480000) fail('Request byte limit exceeded');
        chunks.push(chunk);
      }
      const result = await budget.exchange(request.headers['x-client-request-id'], Buffer.concat(chunks), send);
      response.writeHead(result.status, { 'Content-Type': 'application/json' }); response.end(result.body);
    } catch {
      // 不透传异常中的请求、响应、令牌或供应商细节。
      if (!response.destroyed) { response.writeHead(502, { 'Content-Type': 'application/json' }); response.end('{"error":"CLOSEOUT_GATE_REJECTED"}'); }
    }
  };
  const server = createServer((request, response) => {
    const promise = handle(request, response);
    pending.add(promise);
    void promise.finally(() => pending.delete(promise));
  });
  server.requestTimeout = 10000;
  server.headersTimeout = 5000;
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve); });
  return { url: `http://127.0.0.1:${server.address().port}`, close: async () => {
    await new Promise(resolve => { server.close(resolve); server.closeAllConnections(); });
    await Promise.allSettled([...pending]);
  } };
}
