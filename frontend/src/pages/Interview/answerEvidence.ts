/** 引用须匹配来源、规范化方式和精确偏移；不能搜索同文片段来修复失效引用。 */
type Evidence = {
  sourceId?: string; sourceType?: string; validity?: string; normalizationVersion?: string;
  sourceVersion?: string; offsetStart?: number; offsetEnd?: number; quote?: string;
};
function sourceEvidence(evidence: Evidence, sourceId: string, sourceType: string, answer: string | undefined) {
  if (answer === undefined || evidence.sourceId !== sourceId || evidence.sourceType !== sourceType
    || evidence.validity !== 'VALID' || !['RAW_UTF16', 'LF_V1'].includes(evidence.normalizationVersion ?? '')) return null;
  const text = evidence.normalizationVersion === 'LF_V1' ? answer.replace(/\r\n?/g, '\n') : answer;
  const start = evidence.offsetStart, end = evidence.offsetEnd;
  if (!Number.isInteger(start) || !Number.isInteger(end) || start === undefined || end === undefined
    || start < 0 || end <= start || end > text.length || text.slice(start, end) !== evidence.quote) return null;
  const boundary = (i: number) => i === 0 || i === text.length
    || !(text.charCodeAt(i - 1) >= 0xd800 && text.charCodeAt(i - 1) <= 0xdbff
      && text.charCodeAt(i) >= 0xdc00 && text.charCodeAt(i) <= 0xdfff);
  return boundary(start) && boundary(end) ? { before: text.slice(0, start), quote: text.slice(start, end), after: text.slice(end) } : null;
}

export function answerEvidence(evidence: Evidence, sourceId: string, answer: string | undefined) {
  return sourceEvidence(evidence, sourceId, 'ANSWER_SELF_REPORT', answer);
}

/** 参考答案和知识片段还须匹配训练快照版本；缺失、过期或定位不符时返回 null。 */
export function referenceEvidence(evidence: Evidence, training?: import('@/types/api.d').InterviewTraining | null) {
  if (evidence.normalizationVersion !== 'RAW_UTF16') return null;
  if (evidence.sourceType === 'QUESTION_REFERENCE') {
    const q = training?.question;
    return q && evidence.sourceVersion === String(q.version)
      ? sourceEvidence(evidence, q.id, 'QUESTION_REFERENCE', q.referenceAnswer) : null;
  }
  if (evidence.sourceType !== 'KNOWLEDGE_EXCERPT') return null;
  for (const doc of training?.knowledge ?? []) for (const excerpt of doc.excerpts) {
    const id = `knowledge/${doc.id}/${excerpt.chunkId}`;
    if (evidence.sourceId === id && evidence.sourceVersion === `${doc.version}@${doc.sha256}`
      && doc.content.includes(excerpt.content)) return sourceEvidence(evidence, id, 'KNOWLEDGE_EXCERPT', excerpt.content);
  }
  return null;
}
