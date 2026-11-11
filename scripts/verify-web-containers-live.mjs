import { randomBytes } from 'node:crypto';
import { mkdir, writeFile } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { capture, findExecutable } from './dev.mjs';

const root = resolve(fileURLToPath(new URL('..', import.meta.url)));
const run = randomBytes(8).toString('hex');
const output = join(root, 'outputs', 'web-container-acceptance', run);
const reportPath = join(root, 'docs', 'evidence', `web-container-smoke-${run}.json`);
const docker = findExecutable('docker');
const label = 'zhizhida.web.acceptance';
const owned = [];
const report = { run, startedAt: new Date().toISOString(), builds: [], checks: [], cleanup: [] };

async function command(args, timeout = 30000) {
  const result = await capture(docker, args, { timeout });
  if (result.code !== 0) throw new Error(`Docker ${args[0]} failed: ${result.output.slice(-2000)}`);
  return result.output.trim();
}
async function build(service, context) {
  const tag = `zhizhida-${service}:acceptance-20261111`;
  for (let attempt = 1; attempt <= 2; attempt++) {
    let log = '';
    const started = Date.now();
    try {
      const result = await capture(docker, ['build', '--progress=plain', '--label', `${label}=${run}`, '-t', tag, context],
        { timeout: 290000, onOutput: data => { log += data; process.stdout.write(data); } });
      await writeFile(join(output, `${service}-build-${attempt}.log`), log);
      report.builds.push({ service, attempt, exitCode: result.code, seconds: (Date.now() - started) / 1000 });
      if (result.code !== 0) throw new Error(`${service} build failed; see ${output}`);
      return { tag, imageId: await command(['image', 'inspect', '--format', '{{.Id}}', tag]) };
    } catch (error) {
      await writeFile(join(output, `${service}-build-${attempt}.log`), log);
      if (!error.message.includes('超时')) throw error;
      report.builds.push({ service, attempt, timeout: true, seconds: (Date.now() - started) / 1000 });
      if (attempt === 2) throw new Error(`${service} build exceeded 290 seconds twice`);
      console.log(`[web-acceptance] Retrying ${service} once after timeout.`);
    }
  }
}
async function start(service, image, port, options = []) {
  const name = `zhizhida-web-acceptance-${service}-${run}`;
  owned.push(name);
  await command(['run', '--detach', '--pull=never', '--name', name, '--label', `${label}=${run}`,
    '--publish', `127.0.0.1::${port}`, ...options, image]);
  const mapping = await command(['port', name, `${port}/tcp`]);
  const match = /^127\.0\.0\.1:(\d+)$/.exec(mapping);
  if (!match) throw new Error('Smoke endpoint must be loopback-only');
  return { name, url: `http://127.0.0.1:${match[1]}` };
}
async function http(url, options = {}) {
  const response = await fetch(url, { ...options, signal: AbortSignal.timeout(10000) });
  if (!response.ok) throw new Error(`${url} returned HTTP ${response.status}`);
  return response;
}
async function ready(url) {
  const deadline = Date.now() + 30000;
  while (Date.now() < deadline) {
    try { return await http(url); } catch { await new Promise(done => setTimeout(done, 250)); }
  }
  throw new Error(`Container endpoint did not become ready: ${url}`);
}
function check(name, passed, details = {}) {
  report.checks.push({ name, passed, ...details });
  if (!passed) throw new Error(`Smoke assertion failed: ${name}`);
}
try {
  await mkdir(output, { recursive: true });
  report.mcpImage = await build('mcp-reference', 'services/mcp-reference');
  report.frontendImage = await build('frontend', 'frontend');
  const mcp = await start('mcp', report.mcpImage.tag, 18091);
  check('mcp.health', (await (await ready(`${mcp.url}/health`)).json()).status === 'UP');
  async function rpc(id, method, params = {}) {
    const response = await http(`${mcp.url}/mcp`, { method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ jsonrpc: '2.0', id, method, params }) });
    return response.json();
  }
  const initialize = await rpc(1, 'initialize');
  check('mcp.initialize', initialize.result?.protocolVersion === '2025-06-18');
  const tools = await rpc(2, 'tools/list');
  check('mcp.tools', tools.result?.tools?.some(tool => tool.name === 'reference_search'));
  const search = await rpc(3, 'tools/call', { name: 'reference_search', arguments: { query: 'Redis TTL', topK: 3 } });
  const references = JSON.parse(search.result.content[0].text);
  check('mcp.reference-search', Array.isArray(references) && references.length > 0, { hits: references.length });
  check('mcp.non-root', await command(['exec', mcp.name, 'id', '-u']) !== '0');
  // 此 smoke 验证静态镜像及 SPA 路由；容器内固定解析 backend，避免接触已有后端。
  const frontend = await start('frontend', report.frontendImage.tag, 80, ['--add-host', 'backend:127.0.0.1']);
  check('frontend.health', (await (await ready(`${frontend.url}/healthz`)).text()).trim() === 'OK');
  const response = await http(frontend.url);
  const html = await response.text();
  check('frontend.entry', html.includes('<div id="root"></div>'));
  const asset = html.match(/src="(\/assets\/[^\"]+\.js)"/)?.[1];
  check('frontend.asset-reference', Boolean(asset));
  const javascript = await (await http(frontend.url + asset)).text();
  check('frontend.javascript', javascript.length > 1000, { bytes: Buffer.byteLength(javascript) });
  check('frontend.spa-route', (await (await http(frontend.url + '/knowledge')).text()) === html);
  check('frontend.security-headers', response.headers.get('x-content-type-options') === 'nosniff'
    && response.headers.get('content-security-policy')?.includes("default-src 'self'"));
  report.passed = true;
  report.scope = 'Real Docker builds and HTTP checks; frontend static assets/SPA only, no backend API integration.';
} catch (error) {
  report.passed = false;
  report.error = error.message;
  process.exitCode = 1;
  console.error(error.message);
} finally {
  for (const name of owned.reverse()) {
    try {
      const actual = await command(['inspect', '--format', `{{index .Config.Labels "${label}"}}`, name]);
      if (actual !== run) throw new Error(`Refusing to remove unowned container ${name}`);
      await command(['rm', '--force', '--volumes', name]);
      report.cleanup.push({ name, removed: true });
    } catch (error) { report.cleanup.push({ name, error: error.message }); process.exitCode = 1; }
  }
  report.finishedAt = new Date().toISOString();
  await writeFile(reportPath, JSON.stringify(report, null, 2) + '\n');
  console.log(`[web-acceptance] Report: ${reportPath}`);
}
