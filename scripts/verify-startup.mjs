import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';

// 显式运行的真实 API 冒烟检查；不访问公网，不使用现有账号，不触发真实 AI。
const base = new URL(process.argv[2] || 'http://127.0.0.1:5173');
if (base.protocol !== 'http:' || !['127.0.0.1', 'localhost', '[::1]'].includes(base.hostname)
  || base.username || base.password || base.pathname !== '/' || base.search || base.hash) {
  throw new Error('仅允许本机 HTTP 地址，例如 http://127.0.0.1:5174。');
}
const username = `startup_${randomUUID().replaceAll('-', '').slice(0, 12)}`;
const password = randomUUID();
let token;
let created = false;

async function request(path, { method = 'GET', body, authenticated = true, status = 200 } = {}) {
  const response = await fetch(new URL(path, base), {
    method, redirect: 'error', signal: AbortSignal.timeout(10000),
    headers: { ...(body ? { 'Content-Type': 'application/json' } : {}),
      ...(authenticated && token ? { Authorization: `Bearer ${token}` } : {}) },
    ...(body ? { body: JSON.stringify(body) } : {}),
  });
  assert.equal(response.status, status, `${method} ${path} HTTP 状态异常`);
  const result = await response.json();
  assert.equal(result.success, status < 400, `${method} ${path} 业务状态异常`);
  return result.data;
}

try {
  const page = await fetch(base, { redirect: 'error', signal: AbortSignal.timeout(10000) });
  assert.equal(page.status, 200);
  assert.match(await page.text(), /id="root"/);
  const health = await request('/api/health/ready');
  assert.equal(health.service, 'zhizhida-backend');
  assert.equal(health.database, 'up');
  await request('/api/resumes', { authenticated: false, status: 401 });
  console.log('[通过] 页面、前端代理、数据库就绪与未登录访问保护');

  const registered = await request('/api/auth/register', { method: 'POST', authenticated: false,
    body: { username, password, displayName: '启动冒烟临时账号' } });
  token = registered.token;
  created = true;
  assert.ok(token);
  const profile = await request('/api/users/me');
  assert.equal(profile.username, username);
  const resume = { ownerName: '启动验证', targetRole: 'Java 后端开发', summary: '验证 MySQL 读写',
    skills: ['Java', 'MySQL'], educationExperiences: [], projectExperiences: [],
    internshipExperiences: [], certificates: [], templateKey: 'classic' };
  const saved = await request('/api/resumes', { method: 'POST', body: resume });
  const updated = await request(`/api/resumes/${saved.id}`, { method: 'PUT',
    body: { ...resume, summary: '已更新并持久化', revision: saved.revision } });
  assert.equal(updated.revision, saved.revision + 1);
  await request(`/api/resumes/${saved.id}`, { method: 'PUT',
    body: { ...resume, revision: saved.revision }, status: 409 });
  await request('/api/auth/logout', { method: 'POST' });
  await request('/api/users/me', { status: 401 });
  const loggedIn = await request('/api/auth/login', { method: 'POST', authenticated: false, body: { username, password } });
  token = loggedIn.token;
  const reloaded = await request(`/api/resumes/${saved.id}`);
  assert.equal(reloaded.summary, updated.summary);
  assert.equal(reloaded.revision, updated.revision);
  const versions = await request(`/api/resumes/${saved.id}/versions`);
  assert.ok(versions.length >= 2);
  await request(`/api/resumes/${saved.id}`, { method: 'DELETE' });
  await request(`/api/resumes/${saved.id}`, { status: 404 });
  console.log('[通过] 注册、登录、简历创建/更新/重新登录读取/删除、版本历史与过期修订冲突');
} finally {
  if (created) {
    // 只清理本次随机生成的账号；注销接口会同时清理其关联测试数据。
    try {
      const auth = await request('/api/auth/login', { method: 'POST', authenticated: false, body: { username, password } });
      token = auth.token;
      await request('/api/users/me', { method: 'DELETE', body: { currentPassword: password } });
      await request('/api/users/me', { status: 401 });
      console.log('[清理] 本次临时账号和关联测试数据已删除');
    } catch {
      throw new Error(`测试账号 ${username} 自动清理失败，请在本地确认；未操作其他用户数据。`);
    }
  }
}
