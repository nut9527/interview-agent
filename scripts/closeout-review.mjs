// 只核对已保存的证据与逐项检查，不调用模型，也不代替人工质量判断。
import { canonical } from './release-fixtures.mjs';
import { sha256 } from './release-candidate.mjs';

const fail = message => { throw new Error(message); };
const hash = value => sha256(canonical(value));
const nonempty = value => typeof value === 'string' && value.trim().length > 0;
const fields = {
  citationSupport: ['满足', '部分满足', '不满足', '不适用'],
  followUpRelevance: ['满足', '部分满足', '不满足', '不适用'],
  repeatedQuestion: ['有', '无', '不适用'],
  ungroundedCorrection: ['有', '无', '不适用'],
  retryContinuity: ['满足', '部分满足', '不满足', '不适用'],
};

export function reviewTemplate(plan, batch, report) {
  return { schemaVersion: 'closeout-review-v1', metadata: {
    candidateFingerprint: plan.sourceManifestSha256, batchId: plan.batchId,
    configurationSha256: plan.registrationSha256, planSha256: hash(plan),
    reportSha256: batch.evidence?.reportSha256, wireSha256: batch.evidence?.wireSha256,
    reviewMethod: null, reviewedAt: null,
  }, rows: plan.samples.map(sample => ({ sampleId: sample.id,
    runIdOrReason: report.find(row => row.caseId === sample.id)?.runId || '未运行',
    ...Object.fromEntries(Object.keys(fields).map(key => [key, null])),
    blockingIssue: null, conclusion: '未完成', evidence: [], reviewer: null,
    notApplicableReasons: {}, notes: null,
  })) };
}

// 证据必须与结束批次时登记的字节一致；失败样本与未知用量都不能被移除。
export function verifyReviewEvidence(plan, batch, reportBytes, wireBytes) {
  if (batch.status !== 'REVIEW_PENDING' || batch.planSha256 !== hash(plan)
      || batch.sourceManifestSha256 !== plan.sourceManifestSha256 || batch.id !== plan.batchId)
    fail('Review requires the matching frozen REVIEW_PENDING batch');
  if (!batch.evidence || sha256(reportBytes) !== batch.evidence.reportSha256
      || sha256(wireBytes) !== batch.evidence.wireSha256) fail('Saved evidence changed or is missing');
  const parseLines = bytes => bytes.toString('utf8').trim().split('\n').map(JSON.parse);
  const report = parseLines(reportBytes), wire = parseLines(wireBytes);
  if (canonical(report.map(row => row.caseId)) !== canonical(plan.samples.map(row => row.id)))
    fail('Every registered sample must appear exactly once in frozen order');
  if (!batch.requests.length || batch.requests.some(r => !['REPORTED_CONSERVATIVE', 'UNKNOWN'].includes(r.status)))
    fail('Attempt usage state is incomplete');
  if (wire.some(row => row.planSha256 !== batch.planSha256
      || row.sourceManifestSha256 !== batch.sourceManifestSha256
      || !['REQUEST', 'RESPONSE'].includes(row.kind)
      || !batch.requests.some(request => request.id === row.attemptId))) fail('Wire evidence has unrelated attempts');
  for (const request of batch.requests) {
    const sent = wire.filter(row => row.kind === 'REQUEST' && row.attemptId === request.id);
    const received = wire.filter(row => row.kind === 'RESPONSE' && row.attemptId === request.id);
    if (sent.length !== 1 || sent[0].requestSha256 !== request.requestSha256
        || sha256(sent[0].body) !== request.requestSha256 || received.length > 1)
      fail('Wire request does not match durable reservation');
    if (request.responseSha256) {
      if (received.length !== 1 || sha256(received[0].body) !== request.responseSha256
          || received[0].requestSha256 !== request.requestSha256 || received[0].status !== request.responseStatus)
        fail('Wire response does not match durable settlement');
    } else if (received.length || request.status !== 'UNKNOWN') fail('Missing response must retain unknown usage');
  }
  for (const [index, row] of report.entries()) {
    const sample = plan.samples[index];
    if (!nonempty(row.runId) || row.status !== 'SUCCEEDED' || row.config?.loopPolicy !== 'B0'
        || row.config?.model?.model !== plan.price.model || hash(row.fixture) !== sample.inputSha256)
      fail('Sample result or input differs from frozen registration');
    if (sample.toolExpectation === 'REQUIRED' && (!(row.toolExecutions > 0) || !row.receipts?.length))
      fail('Required tool evidence is missing');
    if (!row.evaluation || !Array.isArray(row.attemptLedger) || row.attemptLedger.length !== row.attempts)
      fail('Committed evaluation or attempt evidence is missing');
  }
  if (report.reduce((sum, row) => sum + row.attempts, 0) !== batch.requests.length)
    fail('Report attempts differ from reserved requests');
  return report;
}

export function assessReview(plan, batch, review, reportBytes, wireBytes) {
  const report = verifyReviewEvidence(plan, batch, reportBytes, wireBytes);
  const expected = reviewTemplate(plan, batch, report).metadata;
  if (review.schemaVersion !== 'closeout-review-v1'
      || Object.entries(expected).some(([key, value]) => value !== null && review.metadata?.[key] !== value))
    fail('Review metadata does not match frozen evidence');
  if (!['开发者人工检查', '独立人工检查'].includes(review.metadata.reviewMethod)
      || !Number.isFinite(Date.parse(review.metadata.reviewedAt))
      || Date.parse(review.metadata.reviewedAt) > Date.now()) fail('Reviewer method/time is required');
  if (!Array.isArray(review.rows)
      || canonical(review.rows.map(row => row.sampleId)) !== canonical(plan.samples.map(row => row.id)))
    fail('Review must retain every sample in frozen order');
  let failed = false, severe = false;
  for (const [index, row] of review.rows.entries()) {
    if (row.runIdOrReason !== report[index].runId || !nonempty(row.reviewer)
        || !['通过', '有非阻断问题', '阻断'].includes(row.conclusion)) fail('Review row is incomplete');
    for (const [field, values] of Object.entries(fields)) {
      if (!values.includes(row[field])) fail(`Missing review judgment: ${field}`);
      if (row[field] === '不适用' && !nonempty(row.notApplicableReasons?.[field]))
        fail(`Not-applicable judgment needs a reason: ${field}`);
    }
    if (row.retryContinuity !== '不适用') fail('Single-turn samples cannot claim retry flow acceptance');
    if (!Array.isArray(row.evidence) || !row.evidence.length) fail('Evidence locators are required');
    for (const locator of row.evidence) {
      // JSON Pointer 相对该样本的原始报告；拒绝只有文件名或不存在字段的泛泛依据。
      if (typeof locator !== 'string' || !/^\/(fixture|evaluation|receipts|nextQuestion|validationFailures)(\/|$)/.test(locator))
        fail('Evidence must locate a transcript, evaluation, receipt or validation failure');
      let value = report[index];
      for (const part of locator.slice(1).split('/')) {
        const key = part.replaceAll('~1', '/').replaceAll('~0', '~');
        if (value === null || typeof value !== 'object' || !Object.hasOwn(value, key)) fail('Evidence locator does not exist');
        value = value[key];
      }
      if (value === null || value === '' || (Array.isArray(value) && !value.length)) fail('Evidence locator is empty');
    }
    if (row.blockingIssue !== null) {
      if (!nonempty(row.blockingIssue?.id) || !nonempty(row.blockingIssue?.category)) fail('Blocking issue needs ID and category');
      failed = true;
      severe ||= ['错误引用落库', '越权', '迟到提交'].includes(row.blockingIssue.category);
      if (row.conclusion !== '阻断') fail('Blocking issue must have a blocking conclusion');
    } else if (row.conclusion === '阻断') fail('Blocking conclusion needs an issue');
    if (row.citationSupport === '不满足' || row.ungroundedCorrection === '有') {
      if (!row.blockingIssue) fail('Unsupported citation/correction needs a blocking issue');
      failed = true;
    }
    const qualityIssue = row.citationSupport === '部分满足' || ['部分满足', '不满足'].includes(row.followUpRelevance)
      || row.repeatedQuestion === '有';
    if ((qualityIssue && row.conclusion === '通过')
        || (row.conclusion === '有非阻断问题' && !nonempty(row.notes))) fail('Quality issues require an explicit conclusion and notes');
  }
  const rejected = report.filter(row => row.validationFailures?.some(value => /引文|引用|证据|anchor|偏移/.test(value))).length;
  const stop = report.length >= 5 && rejected / report.length > 0.2;
  return { status: failed || stop ? 'FAIL' : 'PASS', stop: severe || stop,
    reviewedSamples: report.length, citationRejectedSamples: rejected, reviewSha256: hash(review) };
}
