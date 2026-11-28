import { createServer } from 'node:net';
import { mkdir, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { capture, findExecutable } from './dev.mjs';

const root = fileURLToPath(new URL('..', import.meta.url));

export function testCluster(run) {
  if (!/^[0-9a-f]{16}$/.test(run)) throw new Error('Invalid isolated cluster run ID');
  // 同一运行编号用于隔离 Compose 网络和卷，以及 RocketMQ 的 Topic 和消费组。
  const project = 'zdmq' + run;
  const args = ['compose', '-p', project, '--env-file', '.env.example', '-f', 'docker-compose.yml'];
  const env = { ...process.env, ROCKETMQ_RESOURCE_PREFIX: 'it-' + run };
  const docker = findExecutable('docker');
  let created = false;

  async function command(params, timeout = 60000) {
    const result = await capture(docker, params, { cwd: root, env, timeout });
    if (result.code !== 0) throw new Error(result.output.slice(-1800));
    return result.output.trim();
  }

  return {
    async start() {
      const available = createServer();
      await new Promise((resolve, reject) => { available.once('error', reject); available.listen(0, '127.0.0.1', resolve); });
      env.ROCKETMQ_PROXY_PORT = String(available.address().port);
      await new Promise((resolve, reject) => available.close(error => error ? reject(error) : resolve()));
      if (await command([...args, 'ps', '--all', '-q'])) throw new Error('Refusing to reuse existing test project');
      // up 可能在创建部分容器后失败，提前登记以便 close 回收本次资源。
      created = true;
      try {
        await command([...args, 'up', '-d', '--pull', 'never', '--wait', '--wait-timeout', '160', 'mqproxy'], 180000);
      } catch (error) {
        const logs = await command([...args, 'logs', '--tail', '120', 'mqnamesrv', 'mqbroker', 'mqproxy']);
        const failures = logs.split('\n').filter(line => /error|exception|caused by|boot success|failed/i.test(line)).slice(-35);
        throw new Error(error.message + '\n' + failures.join('\n'));
      }
      await command([...args, 'run', '--rm', '--no-deps', '-T', '-e', 'ROCKETMQ_INIT_TEST_RESOURCES=true', 'mqinit'], 60000);
      const broker = await command([...args, 'ps', '-q', 'mqbroker']);
      const proxy = await command([...args, 'ps', '-q', 'mqproxy']);
      if (![broker, proxy].every(id => /^[a-f0-9]{12,64}$/.test(id))) throw new Error('Cannot identify owned broker and proxy');
      const image = await command(['inspect', '--format', '{{.Config.Image}}', broker]);
      return { ROCKETMQ_TEST_IMAGE: image, ROCKETMQ_ENDPOINTS: '127.0.0.1:' + env.ROCKETMQ_PROXY_PORT,
        ROCKETMQ_RESOURCE_PREFIX: env.ROCKETMQ_RESOURCE_PREFIX, MQ_TEST_CONTAINER: broker,
        MQ_PROXY_TEST_CONTAINER: proxy, MQ_ACCEPTANCE_RUN: run };
    },
    async verifyContainerClient() {
      if (!created) throw new Error('Start the isolated MQ cluster first');
      const fixture = join(root, 'scripts', 'fixtures', 'rocketmq', 'ProxySmoke.java');
      const sdk = join(root, '.m2', 'repository', 'org', 'apache', 'rocketmq', 'rocketmq-client-java',
        '5.2.2', 'rocketmq-client-java-5.2.2.jar');
      // 重跑初始化以验证幂等性，再从 Compose 网络内通过服务名收发消息。
      await command([...args, 'run', '--rm', '--no-deps', '-T', '-e', 'ROCKETMQ_INIT_TEST_RESOURCES=true', 'mqinit']);
      const output = await command([...args, 'run', '--rm', '--no-deps', '-T',
        '-v', fixture + ':/check/ProxySmoke.java:ro', '-v', sdk + ':/check/sdk.jar:ro',
        'mqinit', 'sh', '-c', 'javac -proc:none -cp /check/sdk.jar -d /tmp /check/ProxySmoke.java && java -Xmx128m -cp /tmp:/check/sdk.jar ProxySmoke'], 90000);
      if (!output.includes('Verified normal and delayed delivery and ACK from mqproxy:8081')) throw new Error(output.slice(-1500));
      return { endpoint: 'mqproxy:8081', sdkVersion: '5.2.2', repeatedInitialization: true, normalAck: true, delayedAck: true };
    },
    async saveDiagnostics() {
      if (!created) return;
      const directory = join(root, 'backend', 'target', 'middleware-live', env.ROCKETMQ_RESOURCE_PREFIX);
      await mkdir(directory, { recursive: true });
      for (const [service, filenames] of [['mqbroker', ['broker', 'store', 'storeerror', 'pop']], ['mqproxy', ['proxy']]]) {
        for (const log of filenames) {
          const path = '/home/rocketmq/logs/rocketmqlogs/' + log + '.log';
          const result = await capture(docker, [...args, 'exec', '-T', service, 'tail', '-n', '250', path],
            { cwd: root, env, timeout: 10000 });
          if (result.code === 0) await writeFile(join(directory, log + '.log'), result.output);
        }
      }
    },
    async close() {
      if (created) await command([...args, '--profile', 'middleware', 'down', '--volumes', '--timeout', '5']);
    },
  };
}
