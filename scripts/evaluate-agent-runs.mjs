// 仅使用合成测试数据；凭据不输出到控制台，也不写入评估报告。
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { parseEnv } from './dev.mjs';
import { verifyBackend } from './verify-backend.mjs';
const route = process.argv[2] || 'TOOL';
if (!['LEGACY', 'FIXED', 'TOOL'].includes(route)) throw new Error('Usage: node scripts/evaluate-agent-runs.mjs LEGACY|FIXED|TOOL');
// 收尾入口已固定供应商配置且只给子进程本地代理令牌，不能再从 .env 补入真实凭据或开关。
if (process.env.CLOSEOUT_BUDGET_PROXY !== 'true') {
  const settings = parseEnv(await readFile(new URL('../.env', import.meta.url), 'utf8'));
  for (const [key, value] of Object.entries(settings)) if (key.startsWith('DEEPSEEK_') || key === 'AI_API_KEY') process.env[key] ??= value;
}
if (!(process.env.DEEPSEEK_API_KEY || process.env.AI_API_KEY)) throw new Error('Configure a model API key before real evaluation');
// 仅在此入口显式开启真实模型评估，其余后端验证默认使用离线模拟。
process.env.SPRING_PROFILES_ACTIVE = 'agent-evaluation';
process.env.INTERVIEW_RUNS_DECISION_PROTOCOL = 'phased-v2';
process.env.RUN_REAL_AGENT_EVAL = 'true';process.env.RUN_EVAL_ROUTE = route;
process.env.MAVEN_OPTS = `${process.env.MAVEN_OPTS || ''} -Dmaven.repo.local=${resolve('.m2/repository').replaceAll('\\', '/')}`;
process.env.MAVEN_ARGS = `${process.env.MAVEN_ARGS || ''} -Dtest=DeepSeekToolChatClientTest -Dit.test=InterviewDecisionEvaluationIT$RealEvaluation -Djacoco.skip=true`;
await verifyBackend(['--offline']);
