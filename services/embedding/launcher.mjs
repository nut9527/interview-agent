import { spawn } from 'node:child_process';
import { mkdir, readFile, writeFile, open } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { PROJECT_ROOT, assertPortFree, capture, findExecutable, stopChild } from '../../scripts/dev.mjs';

export const EMBEDDING = Object.freeze({
  model: 'bge-small-zh-v1.5-q', dimension: 512, url: 'http://127.0.0.1:18092/v1',
  service: 'zhizhida-local-embedding-v1',
});
const runtime = join(PROJECT_ROOT, '.runtime', 'embedding-service');
const build = join(PROJECT_ROOT, '.runtime', 'embedding-build');

export async function embeddingHealth() {
  try {
    const response = await fetch('http://127.0.0.1:18092/health', { signal: AbortSignal.timeout(1000) });
    if (!response.ok) return null;
    const value = await response.json();
    return value.status === 'UP' && value.service === EMBEDDING.service && value.model === EMBEDDING.model
      && value.dimension === EMBEDDING.dimension ? value : null;
  } catch { return null; }
}

/** Start only this project's loopback service; existing foreign listeners are never stopped. */
export async function ensureEmbedding({ offline = false } = {}) {
  if (await embeddingHealth()) return { ...EMBEDDING, reused: true };
  await assertPortFree(18092);
  await mkdir(runtime, { recursive: true });
  await mkdir(build, { recursive: true });
  const nativeCache = join(PROJECT_ROOT, '.cache', 'embedding-native');
  const privateHome = join(runtime, 'home');
  await mkdir(nativeCache, { recursive: true });
  await mkdir(privateHome, { recursive: true });
  const env = { ...process.env, TMP: runtime, TEMP: runtime, DJL_CACHE_DIR: nativeCache, ENGINE_CACHE_DIR: nativeCache };
  const args = [...(offline ? ['-o'] : []), '-B', '-ntp', '-f', join(PROJECT_ROOT, 'services/embedding/pom.xml'),
    '-Dmaven.repo.local=' + join(PROJECT_ROOT, '.m2/repository'), 'compile', 'dependency:build-classpath',
    '-Dmdep.outputFile=' + join(build, 'classpath.txt')];
  console.log('[embedding] Preparing project-local model and runtime dependencies.');
  const result = await capture(findExecutable('mvn'), args, { cwd: PROJECT_ROOT, env, timeout: 290000 });
  if (result.code !== 0) throw new Error('Embedding service build failed: ' + result.output.slice(-2200));
  const classpath = (await readFile(join(build, 'classpath.txt'), 'utf8')).trim();
  const stdout = await open(join(runtime, 'stdout.log'), 'a');
  const stderr = await open(join(runtime, 'stderr.log'), 'a');
  let child;
  try {
    child = spawn(findExecutable('java'), ['-Xmx768m', '-XX:ActiveProcessorCount=2',
      '-Djava.io.tmpdir=' + runtime, '-Duser.home=' + privateHome,
      '-cp', join(build, 'classes') + (process.platform === 'win32' ? ';' : ':') + classpath,
      'cn.edu.whu.zhizhida.embedding.EmbeddingServer'],
    { cwd: PROJECT_ROOT, env, detached: true, windowsHide: true, stdio: ['ignore', stdout.fd, stderr.fd] });
  } finally { await stdout.close(); await stderr.close(); }
  let launchError;
  child.on('error', error => { launchError = error; });
  try {
    const deadline = Date.now() + 60000;
    while (Date.now() < deadline) {
      if (launchError) throw launchError;
      if (child.exitCode !== null || child.signalCode !== null) throw new Error('Embedding process exited before readiness; see .runtime/embedding-service/stderr.log');
      if (await embeddingHealth()) {
        await writeFile(join(runtime, 'process.json'), JSON.stringify({ pid: child.pid, service: EMBEDDING.service,
          model: EMBEDDING.model, dimension: EMBEDDING.dimension, startedAt: new Date().toISOString() }, null, 2) + '\n');
        child.unref();
        return { ...EMBEDDING, pid: child.pid, reused: false };
      }
      await new Promise(done => setTimeout(done, 250));
    }
    throw new Error('Embedding model did not become ready within 60 seconds; see .runtime/embedding-service/stderr.log');
  } catch (error) {
    await stopChild(child);
    throw error;
  }
}

if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  if (process.argv.slice(2).some(arg => arg !== '--offline')) throw new Error('Usage: node services/embedding/launcher.mjs [--offline]');
  const result = await ensureEmbedding({ offline: process.argv.includes('--offline') });
  console.log(JSON.stringify(result));
}
