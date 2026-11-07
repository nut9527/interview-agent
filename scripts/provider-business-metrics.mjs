// 固定的小样本代理指标保留失败请求分母，不从 token 推断实际账单。
export function summarizeBusiness(report) {
  const rows = report.attempts;
  if (rows?.length !== 4 || new Set(rows.map(r => r.caseId)).size !== 4)
    throw new Error('Expected four distinct planned cases, including failures');
  const known = rows.filter(r => r.usage?.status === 'REPORTED' &&
    ['inputTokens','outputTokens','totalTokens'].every(k => Number.isSafeInteger(r.usage[k]) && r.usage[k]>=0) &&
    r.usage.inputTokens + r.usage.outputTokens === r.usage.totalTokens);
  const total = key => known.length === rows.length ? known.reduce((n,r)=>n+r.usage[key],0) : null;
  return {metricVersion:'business-json-proxy-v2',
    planned: rows.length, returned: rows.filter(r=>r.status==='RETURNED').length,
    qualityProxy: {passed: rows.filter(r=>r.status==='RETURNED' && r.qualityProxyPassed===true).length, denominator:rows.length},
    usage: {unknownAttempts:rows.length-known.length,inputTokens:total('inputTokens'),outputTokens:total('outputTokens'),totalTokens:total('totalTokens')},
    meanElapsedMillis:rows.every(r=>Number.isFinite(r.elapsedMillis)&&r.elapsedMillis>=0)?rows.reduce((n,r)=>n+r.elapsedMillis,0)/rows.length:null,
    monetaryCost:null,
    releaseDecision:'NOT_EVALUATED',
    limitations:['Four synthetic development families; no heldout or independent semantic review.',
      'Schema, verbatim support and keyword checks are quality proxies, not proof of correctness.',
      'Token totals remain null when any usage is unknown; monetary cost requires a frozen pricing/cache ledger.',
      'This acceptance does not execute the durable Worker or prove a quality improvement.']};
}
