import { randomBytes } from 'node:crypto';
import { capture, findExecutable } from './dev.mjs';
import { verifyMiddleware } from './verify-middleware.mjs';

// 显式运行的中间件验收：仅使用本地镜像、随机回环端口和本次创建的容器。
// 镜像需提前单独拉取，避免 --offline 验收隐式下载到 Docker 镜像存储。
const args = process.argv.slice(2);
if (args.some(arg => !['--offline', '--knowledge-only', '--benchmark', '--split-load', '--help'].includes(arg))) throw new Error('Usage: node scripts/verify-middleware-live.mjs [--offline] [--knowledge-only | --benchmark | --split-load]');
if (args.filter(arg => ['--knowledge-only', '--benchmark', '--split-load'].includes(arg)).length > 1) throw new Error('Select one middleware suite');
if (args.includes('--help')) {
  console.log('Requires local redis:7.4.2 and rabbitmq:4.1.0-management images, Docker, MySQL binaries and Maven.');
  console.log('Creates isolated temporary containers, runs application tests, then removes containers and their volumes. Images remain.');
  console.log('--benchmark: verify middleware faults and write measured HTTP/cache/queue/recovery samples to backend/target/middleware-benchmark.json.');
  console.log('--split-load: one API and one/two independent Workers, synthetic JSON upstream, shared concurrency, durable SSE replay and in-flight cancellation; writes backend/target/split-runtime-load.json. No paid model calls.');
  console.log('Benchmark controls: BENCHMARK_PUBLISHER_INTERVAL_MS (1..500, default 500), BENCHMARK_PUBLISHER_BATCH_SIZE (1..32, default 1), BENCHMARK_PUBLISHER_BATCH_BUDGET_MS (1..1000, default 100).');
} else {
  const docker = findExecutable('docker');
  const suffix = randomBytes(8).toString('hex');
  const names = { redis: `zhizhida-it-redis-${suffix}`, cache: `zhizhida-it-cache-${suffix}`, rabbit: `zhizhida-it-rabbit-${suffix}` };
  const owned = [];
  const environment = {
    RUN_MIDDLEWARE_TESTS: 'true', REDIS_HOST: '127.0.0.1', REDIS_PASSWORD: '',
    RUN_MIDDLEWARE_BENCHMARK: args.includes('--benchmark') ? 'true' : 'false',
    RUN_SPLIT_LOAD_TESTS: args.includes('--split-load') ? 'true' : 'false',
    RUN_REDIS_PRESSURE_TESTS: 'true',
    REDIS_RATE_HOST: '127.0.0.1', REDIS_RATE_PASSWORD: '',
    REDIS_CACHE_HOST: '127.0.0.1', REDIS_CACHE_PASSWORD: '',
    RABBITMQ_HOST: '127.0.0.1', RABBITMQ_USERNAME: 'it_user',
    RABBITMQ_PASSWORD: randomBytes(24).toString('hex'), RABBITMQ_VHOST: '/zhizhida-test',
  };
  const saved = {};
  async function command(arguments_, env = process.env, timeout = 30000) {
    const result = await capture(docker, arguments_, { env, timeout });
    if (result.code !== 0) throw new Error(`Docker ${arguments_[0]} failed: ${result.output.slice(-1000)}`);
    return result.output.trim();
  }
  async function ready(name, check, user) {
    const until = Date.now() + 120000;
    while (Date.now() < until) {
      if (await command(['inspect', '--format', '{{.State.Running}}', name]) !== 'true') {
        throw new Error(`Temporary container ${name} exited before readiness`);
      }
      // 诊断须使用 RabbitMQ 服务用户，避免 root 提前创建 cookie 并与启动脚本的权限调整竞争。
      const result = await capture(docker, ['exec', ...(user ? ['--user', user] : []), name, ...check], { timeout: 10000 });
      if (result.code === 0) return;
      await new Promise(done => setTimeout(done, 1000));
    }
    throw new Error(`Temporary container ${name} did not become ready`);
  }
  async function port(name, containerPort) {
    const value = await command(['port', name, `${containerPort}/tcp`]);
    const match = /^127\.0\.0\.1:(\d+)$/.exec(value);
    if (!match) throw new Error('Expected a single loopback-only port mapping');
    return match[1];
  }
  try {
    await command(['image', 'inspect', 'redis:7.4.2', 'rabbitmq:4.1.0-management']);
    // 创建前登记名称，使中断后的清理仍能精确定位本次容器。
    owned.push(names.redis);
    await command(['run', '--detach', '--pull=never', '--name', names.redis,
      '--label', `zhizhida.test.run=${suffix}`, '--publish', '127.0.0.1::6379',
      'redis:7.4.2', 'redis-server', '--maxmemory', '128mb', '--maxmemory-policy', 'noeviction']);
    owned.push(names.cache);
    await command(['run', '--detach', '--pull=never', '--name', names.cache,
      '--label', `zhizhida.test.run=${suffix}`, '--publish', '127.0.0.1::6379',
      'redis:7.4.2', 'redis-server', '--save', '', '--appendonly', 'no',
      '--maxmemory', '8mb', '--maxmemory-policy', 'allkeys-lru']);
    owned.push(names.rabbit);
    await command(['run', '--detach', '--pull=never', '--name', names.rabbit,
      '--label', `zhizhida.test.run=${suffix}`, '--publish', '127.0.0.1::5672',
      '--env', 'RABBITMQ_DEFAULT_USER', '--env', 'RABBITMQ_DEFAULT_PASS', '--env', 'RABBITMQ_DEFAULT_VHOST',
      'rabbitmq:4.1.0-management'], { ...process.env,
      RABBITMQ_DEFAULT_USER: environment.RABBITMQ_USERNAME,
      RABBITMQ_DEFAULT_PASS: environment.RABBITMQ_PASSWORD,
      RABBITMQ_DEFAULT_VHOST: environment.RABBITMQ_VHOST });
    console.log('[middleware] Waiting for separate cache/rate Redis instances and RabbitMQ.');
    await ready(names.redis, ['redis-cli', 'ping']);
    await ready(names.cache, ['redis-cli', 'ping']);
    await ready(names.rabbit, ['rabbitmq-diagnostics', '-q', 'check_port_connectivity'], 'rabbitmq');
    environment.REDIS_PORT = await port(names.redis, 6379);
    environment.REDIS_RATE_PORT = environment.REDIS_PORT;
    environment.REDIS_CACHE_PORT = await port(names.cache, 6379);
    environment.RABBITMQ_PORT = await port(names.rabbit, 5672);
    for (const [key, value] of Object.entries(environment)) { saved[key] = process.env[key]; process.env[key] = value; }
    console.log('[middleware] Real middleware ready; running application and recovery verification.');
    await verifyMiddleware(args);
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  } finally {
    for (const [key, value] of Object.entries(saved)) {
      if (value === undefined) delete process.env[key]; else process.env[key] = value;
    }
    for (const name of owned.reverse()) {
      try {
        // 删除容器及其匿名测试卷前复核本次运行标签，避免清理其他任务的资源。
        const label = await command(['inspect', '--format', '{{index .Config.Labels "zhizhida.test.run"}}', name]);
        if (label !== suffix) throw new Error(`Refusing to remove unowned container ${name}`);
        await command(['rm', '--force', '--volumes', name]);
        console.log(`[middleware] Removed owned test container ${name}.`);
      } catch (error) { console.error(`Cleanup: ${error.message}`); process.exitCode = 1; }
    }
  }
}
