import { readFile, writeFile, stat } from 'node:fs/promises';
import { join } from 'node:path';
import { createHash } from 'node:crypto';
import { PROJECT_ROOT, parseEnv, capture, findExecutable } from './dev.mjs';

const args = process.argv.slice(2);
if (args.includes('--help')) {
  console.log('Explicit paid opt-in: node scripts/verify-provider-acceptance.mjs --real --max-requests=3 --max-output-tokens=4500');
  console.log('3 synthetic requests: JSON, tool call, JSON continuation. Reads .env only with --real. No automatic retry.');
} else {
  if (args.length !== 3 || !args.includes('--real') || !args.includes('--max-requests=3') || !args.includes('--max-output-tokens=4500'))
    throw new Error('Explicit --real --max-requests=3 --max-output-tokens=4500 authorization required; use --help.');
  const local = await readFile(join(PROJECT_ROOT, '.env'), 'utf8').then(parseEnv).catch(error => {
    if (error.code === 'ENOENT') return {}; throw error;
  });
  const config = { ...local, ...process.env };
  const key = config.AI_API_KEY || config.DEEPSEEK_API_KEY;
  if (!key) throw new Error('Provider key is missing; no request sent.');
  const endpoint = config.DEEPSEEK_BASE_URL || 'https://api.deepseek.com';
  const uri = new URL(endpoint);
  if (uri.origin !== 'https://api.deepseek.com' || uri.username || uri.password || uri.search || uri.hash)
    throw new Error('This acceptance fixture only permits the official DeepSeek HTTPS endpoint.');
  const env = { ...process.env, RUN_PROVIDER_ACCEPTANCE: 'true', AI_API_KEY: key,
    ACCEPTANCE_BASE_URL: endpoint, ACCEPTANCE_MODEL: config.DEEPSEEK_MODEL || 'deepseek-flash',
    ACCEPTANCE_MAX_REQUESTS: '3', ACCEPTANCE_MAX_OUTPUT_TOKENS: '4500' };
  const started = Date.now();
  const result = await capture(findExecutable('mvn'), ['-o', '-B', '-ntp',
    `-Dmaven.repo.local=${join(PROJECT_ROOT, '.m2/repository')}`, '-Dtest=ProviderAcceptanceTest', '-Djacoco.skip=true', 'test'],
    { cwd: join(PROJECT_ROOT, 'backend'), env, timeout: 240000 });
  // 付费运行不打印 Maven 或供应商错误正文，仅保留白名单证据。
  const source = join(PROJECT_ROOT, 'backend/target/provider-acceptance.json');
  if (await stat(source).then(s => s.mtimeMs >= started).catch(() => false)) {
    const evidence = JSON.parse(await readFile(source, 'utf8'));
    delete evidence.recordedAt;
    evidence.result = result.code === 0 ? 'PASSED' : 'FAILED';
    evidence.testSha256 = createHash('sha256').update(await readFile(join(PROJECT_ROOT,
      'backend/src/test/java/cn/edu/whu/zhizhida/ai/ProviderAcceptanceTest.java'))).digest('hex');
    await writeFile(join(PROJECT_ROOT, 'docs/evidence/provider-acceptance.json'), JSON.stringify(evidence, null, 2) + '\n');
  }
  if (result.code !== 0) throw new Error('Provider acceptance failed or timed out; no automatic paid retry. Inspect sanitized evidence if produced.');
  console.log('[provider] Synthetic protocol acceptance passed. Business quality and production release remain pending.');
}
