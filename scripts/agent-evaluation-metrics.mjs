// v2: 缺少账本字段时报告未知，不能把预算预占、缓存和实际执行混为一类。
const terminal = new Set(['SUCCEEDED', 'FAILED', 'CANCELLED', 'EXPIRED', 'SUPERSEDED']);
const ratio = (numerator, denominator) => ({ numerator, denominator, value: denominator ? numerator / denominator : null });
const completeSum = (rows, field) => rows.every(r => Number.isSafeInteger(r[field]) && r[field] >= 0)
  ? rows.reduce((n, r) => n + r[field], 0) : null;

export function verifiedKnowledgeCitations(row) {
  const detail = row.evaluation?.assessment?.detail;
  const references = row.evaluation?.knowledgeSources ?? [];
  const cited = new Set(detail?.items.flatMap(item => item.citations.map(citation => citation.evidenceId)));
  return (detail?.evidence ?? []).filter(evidence => cited.has(evidence.evidenceId)
    && evidence.validity === 'VALID' && evidence.sourceType === 'KNOWLEDGE_EXCERPT'
    && references.some(ref => ref.sha256 === row.sourceHash && evidence.sourceVersion === ref.version + '@' + ref.sha256
      && ref.excerpts.some(excerpt => evidence.sourceId === 'knowledge/' + ref.id + '/' + excerpt.chunkId
        && Number.isInteger(evidence.offsetStart) && Number.isInteger(evidence.offsetEnd)
        && evidence.offsetStart >= 0 && evidence.offsetEnd <= excerpt.content.length
        && evidence.quote?.trim() && excerpt.content.slice(evidence.offsetStart, evidence.offsetEnd) === evidence.quote))).length;
}

// 检查已登记的单轮场景。运行成功与实际取得相关工具回执分别记录。
export function checkWorkerEvidence(row, sample) {
  if (sample?.id !== row.caseId || !['REQUIRED', 'ZERO_ALLOWED'].includes(sample.toolExpectation))
    throw new Error('Missing registered tool expectation for worker sample');
  if (row.status !== 'SUCCEEDED') return 'RUN_UNSUCCESSFUL';
  if (!row.evaluation || !row.nextQuestion?.trim()) return 'MISSING_COMMITTED_OUTPUT';
  if (sample.toolExpectation === 'ZERO_ALLOWED') return 'PASSED';
  const quote = row.fixture.evidenceQuote;
  if (typeof quote !== 'string' || !quote.trim()) throw new Error('Missing fixture evidence quote');
  const found = row.receipts?.some(receipt => {
    if (!['EXECUTED', 'CACHE'].includes(receipt.execution_kind)
        || !row.fixture.reasonableTools.includes(receipt.tool_name)) return false;
    const result = JSON.parse(receipt.result_json);
    if (result?.status !== 'FOUND') return false;
    if (receipt.tool_name === 'search_knowledge')
      return result.references?.some(reference => reference.sha256 === row.sourceHash
        && reference.excerpts?.some(excerpt => excerpt.content?.includes(quote)));
    return row.initialKnowledgeExcerpts !== 'WITHHELD' && result.content?.includes(quote);
  });
  if (!found) return 'MISSING_REQUIRED_TOOL_EVIDENCE';
  if (row.initialKnowledgeExcerpts === 'WITHHELD' && !verifiedKnowledgeCitations(row)) return 'MISSING_KNOWLEDGE_CITATION';
  return 'PASSED';
}

export function summarizeAgentRows(rows) {
  const statuses = {};
  for (const row of rows) statuses[row.status] = (statuses[row.status] || 0) + 1;
  const attempts = rows.flatMap(row => row.attemptLedger || []);
  const responses = rows.flatMap(row => row.attemptResponses || []);
  const responseCoverage = rows.filter(row => Array.isArray(row.attemptResponses)).length;
  const receipts = rows.flatMap(row => row.receipts || []);
  const executed = receipts.filter(r => r.execution_kind === 'EXECUTED');
  const parseResult = r => { try { return JSON.parse(r.result_json); } catch { return {}; } };
  const searchStatuses = executed.filter(r => r.tool_name === 'search_knowledge')
    .map(r => parseResult(r).status)
    .filter(status => ['FOUND', 'EMPTY', 'MISSING'].includes(status));
  const validationKnown = rows.filter(row => typeof row.firstFinalOutputValid === 'boolean');
  return {
    version: 'agent-metrics-v2', accepted: rows.length, statuses,
    unfinished: rows.filter(row => !terminal.has(row.status)).length,
    runSuccess: ratio(statuses.SUCCEEDED || 0, rows.length),
    attempts: { registered: completeSum(rows, 'attempts'), exported: attempts.length,
      returned: attempts.filter(a => a.status === 'SUCCEEDED').length,
      unknown: attempts.filter(a => a.status === 'UNKNOWN' || a.status === 'STARTED').length,
      failed: attempts.filter(a => a.status === 'FAILED').length,
      transportFailed: attempts.filter(a => /^MODEL_(NETWORK|TIMEOUT|HTTP_)/.test(a.error_code || '')).length,
      protocolFailed: attempts.filter(a => a.error_code === 'MODEL_PROTOCOL' || a.error_code?.startsWith('MODEL_FINISH_')).length },
    toolRequests: { calls: rows.every(r => Array.isArray(r.modelToolRequests))
      ? rows.reduce((n, r) => n + r.modelToolRequests.length, 0) : null,
      responseRate: responseCoverage === rows.length
        ? ratio(responses.filter(r => r.toolCallCount > 0).length, responses.length) : null,
      responseCoverageRuns: responseCoverage },
    firstFinalOutput: { legalityAmongKnown: ratio(validationKnown.filter(r => r.firstFinalOutputValid).length, validationKnown.length),
      scope: 'only exported validation booleans; not an overall rate when unknownRuns > 0',
      unknownRuns: rows.filter(r => typeof r.firstFinalOutputValid !== 'boolean' && r.firstFinalOutputValid !== null).length,
      noFinalOutputRuns: rows.filter(r => r.firstFinalOutputValid === null).length },
    tools: { reserved: completeSum(rows, 'toolExecutions'), completedExecutedReceipts: executed.length,
      cacheReceipts: receipts.filter(r => r.execution_kind === 'CACHE').length,
      skippedReceipts: receipts.filter(r => r.execution_kind === 'SKIPPED').length,
      unknownKindReceipts: receipts.filter(r => !['EXECUTED', 'CACHE', 'SKIPPED'].includes(r.execution_kind)).length,
      executionSuccessRate: null, // 完成回执不覆盖执行失败/中断，不存在可信的全部实际执行分母。
      retrievalHitAmongCompleted: ratio(searchStatuses.filter(status => status === 'FOUND').length, searchStatuses.length) },
    latency: { scope: 'worker execution only; historical elapsedMs excludes admission/queue',
      samples: rows.map(r => ({ status: r.status, elapsedMs: r.elapsedMs })) },
    limitations: ['Unexported first-output validation and response data remain unknown.',
      'Tool execution success cannot be inferred from completed receipts or reserved budgets.',
      'Hit rate and tool request rate do not measure semantic quality.']
  };
}
