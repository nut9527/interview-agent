import { StringDecoder } from 'node:string_decoder';

export function redactProgress(text, env = {}) {
  let result = text.replace(/\x1b\[[0-9;?]*[ -/]*[@-~]/g, '');
  for (const [key, value] of Object.entries(env)) {
    if (value && /password|secret|token|api_?key/i.test(key)) result = result.split(value).join('[已隐藏]');
  }
  result = result.replace(/(https?:\/\/)[^\s/@]+:[^\s/@]+@/gi, '$1[已隐藏]@');
  if (/password\s*[:=]|secret\s*[:=]|token\s*[:=]|api[_-]?key\s*[:=]/i.test(result)) return '[包含凭据的输出已隐藏，请检查本地日志]';
  return result.replace(/[\x00-\x08\x0b-\x1f\x7f]/g, '').trim().slice(-300);
}

export function createProgressTracker({ env = {}, now = Date.now } = {}) {
  const states = new Map();
  const streams = new Map();
  let latest = '等待命令输出';
  let changedAt = now();
  const line = (raw) => {
    const value = redactProgress(raw, env);
    if (!value) return;
    // Compose 会重复打印各层的相同字节数；这些输出不代表下载前进。
    const layer = /(?:^|\s)([a-f0-9]{12})(?::|\s)\s*(.*)/i.exec(value);
    const key = layer ? layer[1] : 'status';
    const state = layer ? layer[2].replace(/\[[=>\s]*\]/g, '').trim() : value;
    if (states.get(key) !== state) {
      states.set(key, state);
      changedAt = now();
      latest = value;
    }
  };
  return {
    write(chunk, source = 'stdout') {
      if (!streams.has(source)) streams.set(source, { decoder: new StringDecoder('utf8'), pending: '' });
      const stream = streams.get(source);
      const lines = (stream.pending + stream.decoder.write(chunk)).split(/[\r\n]+/);
      stream.pending = lines.pop().slice(-4096);
      for (const value of lines) line(value);
    },
    flush() { for (const stream of streams.values()) line(stream.pending + stream.decoder.end()); },
    get latest() { return latest; },
    get changedAt() { return changedAt; },
  };
}

export async function monitorStage(child, { label, logPath, env, stop, report = console.log,
  timeout = 240000, stallTimeout = 120000, interval = 1000, heartbeat = 15000 }) {
  const progress = createProgressTracker({ env });
  const start = Date.now();
  let lastReport = start;
  let failure;
  const stdout = (chunk) => progress.write(chunk, 'stdout');
  const stderr = (chunk) => progress.write(chunk, 'stderr');
  child.stdout.on('data', stdout);
  child.stderr.on('data', stderr);
  const timer = setInterval(() => {
    if (failure) return;
    const now = Date.now();
    if (now - progress.changedAt >= stallTimeout || now - start >= timeout) {
      failure = now - progress.changedAt >= stallTimeout
        ? `连续 ${Math.round(stallTimeout / 1000)} 秒没有新进展` : `超过 ${Math.round(timeout / 1000)} 秒总时限`;
      report(`[超时] ${label}：${failure}，正在停止本次命令。`);
      void stop(child);
    } else if (now - lastReport >= heartbeat) {
      lastReport = now;
      report(`[进度] ${label}（${Math.round((now - start) / 1000)} 秒）：${progress.latest}`);
    }
  }, interval);
  try {
    const code = await child.done;
    progress.flush();
    if (failure || code !== 0) throw new Error(`${label} ${failure || `退出码 ${code}`}；${progress.latest}；日志：${logPath}`);
    report(`[完成] ${label}（${Math.round((Date.now() - start) / 1000)} 秒）`);
  } finally {
    clearInterval(timer);
    child.stdout.off('data', stdout);
    child.stderr.off('data', stderr);
  }
}
