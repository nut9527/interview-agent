// 仅使用官方来源；显式指定的镜像/仓库不自动替换。
export function mysqlImageCandidates(env) {
  if (env.MYSQL_IMAGE) return [env.MYSQL_IMAGE];
  if (env.DOCKER_REGISTRY) return [`${env.DOCKER_REGISTRY.replace(/\/+$/, '')}/mysql:8.4`];
  return ['docker.io/library/mysql:8.4', 'public.ecr.aws/docker/library/mysql:8.4'];
}

export async function prepareMysqlImage({ env, offline, inspect, pull, report, cancelled = () => false }) {
  const candidates = mysqlImageCandidates(env);
  for (const image of candidates) {
    if (cancelled()) throw new Error('启动已取消。');
    if (await inspect(image)) {
      report(`[镜像] 复用本地 ${image}`);
      return image;
    }
  }
  if (offline) throw new Error('离线启动缺少 MySQL 镜像；请联网启动一次，或配置已有 MySQL 并使用 --external-db。');
  const failures = [];
  for (const [index, image] of candidates.entries()) {
    if (cancelled()) throw new Error('启动已取消。');
    report(`[镜像] ${index ? '切换备用官方来源：' : '首次下载（尚未启动数据库）：'}${image}`);
    try {
      await pull(image, index);
      return image;
    } catch (error) {
      if (cancelled()) throw new Error('启动已取消。');
      failures.push(error.message);
      report(`[镜像] ${error.message}`);
    }
  }
  throw new Error(`MySQL 镜像下载失败，数据库尚未启动。请检查 Docker Desktop 的网络/代理，或用 MYSQL_IMAGE 指定已缓存镜像，或配置已有 MySQL 后使用 --external-db。\n${failures.join('\n')}`);
}
