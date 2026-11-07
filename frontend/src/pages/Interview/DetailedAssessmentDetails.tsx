import type { InterviewTraining } from '@/types/api.d';
import type { Schemas } from '@/types/generated/openapi';
import { answerEvidence, referenceEvidence } from './answerEvidence';
import styles from './agent.module.css';

type Detail = Schemas['cn.edu.whu.zhizhida.evaluation.api.DetailedAssessment'];
const names = { EVIDENCE_SUFFICIENCY: '证据充分程度', TECHNICAL_CORRECTNESS: '技术正确性',
  CAUSAL_EXPLANATION: '因果解释', TRADEOFFS: '方案权衡', RESPONSIBILITY: '职责边界', EXPRESSION: '表达清晰度' };
const states = { OBSERVED: '已观察 · 待复核', NOT_OBSERVED: '未观察到', NOT_APPLICABLE: '不适用' };
const relations = { SELF_REPORT: '回答自述', SUPPORT: '对照依据', COUNTEREVIDENCE: '冲突依据' };

export function DetailedAssessmentDetails({ detail, sessionId, round, answer, training, mode }: {
  detail: Detail; sessionId: string; round: number; answer?: string; training?: InterviewTraining | null; mode?: string;
}) {
  return <section className={styles.assessment} aria-label={`第 ${round} 轮六维观察`}>
    <p className={styles.note}>六项独立观察 · {detail.rubric?.version} · {mode === 'mock' ? '规则演示' : '模型提出'}</p>
    {detail.items?.map(item => <article key={item.dimension}>
      <div className={styles.assessmentHeading}><strong>{item.dimension ? names[item.dimension] : '未知维度'}</strong>
        <span>{item.claim?.semanticCheck === 'SOURCE_REVOKED' ? '来源已撤销' : item.status ? states[item.status] : '未观察到'}</span></div>
      <p>{item.claim?.claimText}</p>
      {item.claim?.status === 'DISPUTED' && <p role="status">来源对照有争议，保留待复核。</p>}
      {item.majorCorrection && <p>待复核的技术纠错提议，尚未形成正式正确／错误裁定。</p>}
      {item.technicalReview === 'REVIEW_REQUIRED' && <p className={styles.note}>技术判断尚未独立验证；找到资料和引文不等于结论正确。</p>}
      <details className={styles.evidenceSource}><summary>本维度观察标准</summary>
        <p>{detail.rubric?.anchors?.find(a => a.dimension === item.dimension)?.behavior || '未保存观察标准，保持未知。'}</p>
      </details>
      <p className={styles.note}>限制：{item.claim?.limitation}</p>
      {item.citations?.map((link, index) => {
        const evidence = detail.evidence?.find(e => e.evidenceId === link.evidenceId);
        const self = evidence?.sourceType === 'ANSWER_SELF_REPORT';
        const source = evidence && (self ? answerEvidence(evidence, `${sessionId}:answer:${round}`, answer) : referenceEvidence(evidence, training));
        if (!evidence || !source) return <p key={index} role="status">来源已失效或无法定位，不能据此确认观察。</p>;
        return <details key={`${link.evidenceId}-${index}`} className={styles.evidenceSource}>
          <summary>查看{self ? '回答' : '参考资料'}来源 · {link.relation ? relations[link.relation] : '关系未知'} · [{evidence.offsetStart}, {evidence.offsetEnd})</summary>
          <pre>{source.before}<mark>{source.quote}</mark>{source.after}</pre>
          {!self && <p className={styles.note}>这是本场归档的对照资料，不能证明候选人已实际实施。</p>}
          <p className={styles.note}>来源：{evidence.sourceId} · {evidence.normalizationVersion} · 版本 {evidence.sourceVersion}</p>
          <p className={styles.note}>SHA-256 {evidence.sourceHash}</p>
        </details>;
      })}
    </article>)}
    <p className={styles.note}>{detail.limitation}</p>
  </section>;
}
