// S0 登记器只处理合成输入；不读取密钥、不调用模型、不修改历史基线。
import { readFile, writeFile } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { sha256, jsonBytes, projectRoot } from './release-candidate.mjs';

export const baselinePath = 'backend/src/test/resources/agent-evaluation.json';
export const baselineHash = 'e7a22a75b678dfdff47e9ffd4318a494b33419e9e85b3703eefcfc3822debd49';
export const derivedPath = 'backend/src/test/resources/agent-evaluation-derived.json';
export const configPath = 'contracts/release-v1.0.json';
export function canonical(value) {
  if (Array.isArray(value)) return '[' + value.map(canonical).join(',') + ']';
  if (value !== null && typeof value === 'object') return '{' + Object.keys(value).sort()
    .map(key => JSON.stringify(key) + ':' + canonical(value[key])).join(',') + '}';
  return JSON.stringify(value);
}

export async function expectedFixtures(root = projectRoot) {
  const bytes = await readFile(join(root, baselinePath));
  if (sha256(bytes) !== baselineHash) throw new Error('Frozen baseline changed');
  const baseline = JSON.parse(bytes);
  const ids = ['redis-order', 'mysql-unique', 'mq-ack', 'adequate-owner', 'adequate-cas', 'snapshot', 'injection', 'http-retry'];
  const selected = ids.map(id => {
    const fixture = baseline.find(row => row.id === id && ['development', 'validation'].includes(row.partition));
    if (!fixture) throw new Error(`Missing non-heldout fixture: ${id}`);
    return fixture;
  });
  const owner = selected.find(row => row.id === 'adequate-owner'), redis = selected[0];
  const derive = (id, base, transformation, overrides) => {
    const input = { ...base, ...overrides, id, partition: 'derived' };
    return { id, baseId: base.id, basePartition: base.partition, transformationVersion: 'closeout-transform-v1',
      transformation, input, inputSha256: sha256(canonical(input)) };
  };
  const derived = [
    derive('close-owner-long-v1', owner, '原回答后追加 LF 和 50 行编号补充；不裁剪原回答', {
      answer: owner.answer + '\n' + Array.from({ length: 50 }, (_, index) =>
        `补充${String(index + 1).padStart(2, '0')}：我负责接口归属检查，测试同时覆盖本人访问和跨账号访问；记录请求号、影响行数与返回状态，未知结果保持待核实。`).join('\n') }),
    derive('close-owner-repeat-v1', owner, '回答不变；资料替换为两个标题及两个相同短引文，LF 换行', {
      material: '# 查询归属\n查询入口：归属检查必须限定本人范围。\n# 写入归属\n写入入口：归属检查必须限定本人范围。',
      evidenceQuote: '归属检查必须限定本人范围。' }),
    derive('close-redis-no-reference-v1', redis, '保留问题与回答；移除固定对照资料及资料引文，普通 JD 场景', {
      material: '', evidenceQuote: '', reasonableTools: ['read_material_section', 'read_answer_evidence'] }),
    derive('close-owner-unicode-v1', owner, '原回答后追加固定 CRLF、emoji 与中文，不规范化换行', {
      answer: owner.answer + '\r\n核对记录😀：本人查询成功；跨账号请求保持拒绝。\r\n补充说明：未知写入结果先核对请求号。' })
  ];
  if (derived.some(row => row.input.answer.length > 4000)) throw new Error('Derived answer exceeds API limit');
  const derivedBytes = jsonBytes({ schemaVersion: 'agent-evaluation-derived-v1', baselinePath, baselineSha256: baselineHash, cases: derived });
  const samples = [...selected.map(input => ({ id: input.id, family: input.family, partition: input.partition,
    source: baselinePath, inputSha256: sha256(canonical(input)) })), ...derived.map(row => ({
    id: row.id, baseId: row.baseId, family: row.input.family, partition: 'derived', source: derivedPath, inputSha256: row.inputSha256 }))];
  const toolExpectations = ['REQUIRED', 'ALLOWED', 'REQUIRED', 'ZERO_ALLOWED', 'ZERO_ALLOWED', 'ALLOWED', 'ALLOWED', 'ALLOWED', 'ZERO_ALLOWED', 'ALLOWED', 'ZERO_ALLOWED', 'ZERO_ALLOWED'];
  const checks = ['核对 Redis 写入与数据库失败窗口', '区分唯一键冲突与业务成功，不无依据纠错', '核对 ACK 时序与失败窗口',
    '充分回答允许零工具结束', '下一问不重复已说明的冲突处理', '仅引用本场固定快照', '工具文本不改变预算或权限',
    '未知调用结果不清零计数或无限重试', '长回答不丢失引用原文', '跨片段重复按 anchor 片段唯一定位',
    '无对照资料的技术维度保持未观察', 'CRLF 与 emoji 的原文和 UTF-16 边界正确'];
  const configuration = {
    schemaVersion: 'closeout-registration-v1', status: 'CANDIDATE_NOT_ACCEPTED',
    hashEncoding: 'UTF-8; recursively sorted object keys; array order retained; compact JSON; no newline',
    datasets: [{ path: baselinePath, sha256: baselineHash }, { path: derivedPath, sha256: sha256(derivedBytes) }],
    candidate: { sourceManifestSha256: null, freezeStatus: 'PENDING_FINAL_FREEZE',
      adapter: 'langchain4j', route: 'TOOL', loopPolicy: 'B0', decisionProtocol: 'phased-v2',
      citationProtocol: 'anchor-v1', legacyCitationCompatibility: 'STRICT_RAW_UTF16_OFFSETS',
      promptVersion: 'interview-run-contract-v6', outputContract: 'interview-output-v2', toolset: 'evidence-v2', fingerprintFormat: 'sorted-json-v1',
      model: 'deepseek-flash', modelAvailability: 'NOT_CHECKED', thinking: 'disabled',
      tools: ['read_material_section', 'read_answer_evidence', 'search_knowledge'],
      optionalFeatures: { streaming: false, adaptiveRouting: false, plannerV4: false, history: false, redis: false, rocketmq: false, vector: false, mcp: false },
      runLimits: { seconds: 90, attempts: 4, toolBatches: 2, toolExecutions: 3, repairs: 1 },
      generation: { temperature: 'PROVIDER_DEFAULT', openingMaxTokens: 3000, reviewMaxTokens: 3000 } },
    samples: samples.map((sample, index) => ({ order: index + 1, ...sample, toolExpectation: toolExpectations[index], check: checks[index],
      expectedTerminal: 'SUCCEEDED_WITH_VALID_EVIDENCE', safeRejectionCountsAsCompletion: false })),
    flow: { id: 'flow-jd-6-retry-3', interviewRounds: 6, retryRounds: 3, countsInTwelveSamples: false, inputFreeze: 'PENDING' },
    paidExecution: { authorization: 'NOT_GRANTED', executable: false, currency: 'CNY', maximumMicroCny: 30000000,
      maximumBatches: 3, maximumAttempts: 112, priceBook: 'contracts/closeout-price-v1.json',
      requestReservationGuard: 'BATCH1_IMPLEMENTED_OFFLINE_VERIFIED',
      executableAfterApproval: ['closeout-1'], reviewGate: 'BATCH1_IMPLEMENTED_OFFLINE_VERIFIED',
      remainingRunnerWork: ['6+3 HTTP 流程及输入冻结', '12 项派生样本接入', '后两批运行证据与检查入口对接'],
      batches: [
        { id: 'closeout-1', maximumMicroCny: 6000000, maximumAttempts: 8, sampleOrder: ['redis-order', 'adequate-owner'],
          required: ['两个短场景全部符合预期终态', '必要取证有工具回执', '充分材料不强制工具', '无阻断问题', '证据和用量完整'] },
        { id: 'closeout-2', maximumMicroCny: 12000000, maximumAttempts: 56, sampleOrder: ['flow-jd-6-retry-3', 'redis-order'],
          required: ['6 轮面试及关联 3 轮复练全部完成', '必要工具对照完成', '回合引用与复练关系可核对', '检查记录完整', '阻断问题为零'] },
        { id: 'closeout-3', maximumMicroCny: 12000000, maximumAttempts: 48, sampleOrder: samples.map(s => s.id),
          required: ['前批受影响复核预先登记并全部通过', '同一最终候选完成 12/12 样本及检查表', '无未解决阻断问题', '证据和用量完整'] }
      ], stopRules: ['相邻两个已启动批次 FAIL', '已启动三批', '金额或次数先达上限', '严重错误引用或越权',
        '至少五个已受理样本中出现引用拒绝的比例超过 20%'],
      unknownUsage: 'RETAIN_RESERVATION', transferBetweenBatches: false, resetFailureCountOnCandidateChange: false },
    review: { columns: ['样本ID', 'RunID或未受理原因', '引用支持', '追问相关', '重复提问', '无依据纠错', '复练衔接', '阻断问题', '结论', '依据定位', '检查人'],
      supportValues: ['满足', '部分满足', '不满足', '不适用'], booleanValues: ['有', '无', '不适用'],
      conclusionValues: ['通过', '有非阻断问题', '阻断', '未完成'],
      rules: ['不适用须说明原因', '单轮样本复练衔接不适用，完整链路单列', '依据须定位 transcript、引文范围或工具回执',
        '失败、取消、未受理、未运行不得从分母删除', '无独立评审人时标为开发者人工检查'] }
  };
  return { derivedBytes, configuration };
}

export async function checkFixtures(root = projectRoot) {
  const { derivedBytes, configuration } = await expectedFixtures(root);
  if ((await readFile(join(root, derivedPath), 'utf8')) !== derivedBytes) throw new Error('Derived fixtures differ from registered transformation');
  if ((await readFile(join(root, configPath), 'utf8')) !== jsonBytes(configuration)) throw new Error('Registration changed: version transformations and configuration together');
  return { samples: configuration.samples.length, families: new Set(configuration.samples.map(s => s.family)).size,
    baselineSha256: baselineHash, configSha256: sha256(jsonBytes(configuration)), paidExecution: 'DISABLED', heldoutExecuted: false };
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  if (process.argv[2] === 'init') {
    const { derivedBytes, configuration } = await expectedFixtures();
    await writeFile(join(projectRoot, derivedPath), derivedBytes, { flag: 'wx' });
    await writeFile(join(projectRoot, configPath), jsonBytes(configuration), { flag: 'wx' });
  } else if (process.argv[2] !== 'check') throw new Error('Usage: node scripts/release-fixtures.mjs init | check');
  console.log(await checkFixtures());
}
