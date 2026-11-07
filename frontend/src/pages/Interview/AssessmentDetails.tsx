import type { Schemas } from '@/types/generated/openapi';
import { answerEvidence } from './answerEvidence';
import styles from './agent.module.css';
import type { InterviewTraining } from '@/types/api.d';
import { DetailedAssessmentDetails } from './DetailedAssessmentDetails';

type Assessment = Schemas['cn.edu.whu.zhizhida.evaluation.api.AssessmentResult'];
const dimensions = { RESPONSIBILITY: '职责边界', CAUSAL_EXPLANATION: '因果解释' };
const states = { OBSERVED: '已观察 · 待复核', NOT_OBSERVED: '未观察到', NOT_APPLICABLE: '不适用' };

export function AssessmentDetails({ assessment, round, sessionId, answer, training }: {
  assessment?: Assessment | null; round: number; sessionId: string; answer?: string; training?: InterviewTraining | null;
}) {
  if (!assessment) return <p className={styles.note}>此历史轮次未记录独立分项评价，不反推评分。</p>;
  return <details className={styles.anchors}>
    <summary>查看第 {round} 轮分项观察与来源</summary>
    <AssessmentContent assessment={assessment} round={round} sessionId={sessionId} answer={answer} training={training} />
  </details>;
}

function AssessmentContent({ assessment, round, sessionId, answer, training }: {
  assessment: Assessment; round: number; sessionId: string; answer?: string; training?: InterviewTraining | null;
}) {
  if (assessment.detail) return <DetailedAssessmentDetails detail={assessment.detail} round={round} sessionId={sessionId}
    answer={answer} training={training} mode={assessment.executionMode} />;
  return <section className={styles.assessment} aria-label={`第 ${round} 轮分项观察`}>
    <p className={styles.note}>独立观察 · {assessment.rubric?.version} · {assessment.executionMode === 'mock' ? '规则演示' : '模型提出'}</p>
    {assessment.items?.map(item => <article key={item.dimension}>
      <div className={styles.assessmentHeading}><strong>{item.dimension ? dimensions[item.dimension] : '未知维度'}</strong>
        <span>{item.status ? states[item.status] : '未观察到'}</span></div>
      <p>{item.claim?.claimText}</p>
      <details className={styles.evidenceSource}><summary>本维度观察标准</summary>
        <p>{assessment.rubric?.anchors?.find(anchor => anchor.dimension === item.dimension)?.behavior || '此版本未保存行为标准，保持未知。'}</p>
      </details>
      {item.claim?.status === 'DISPUTED' && <p>存在争议，保留待复核。</p>}
      <p className={styles.note}>限制：{item.claim?.limitation}</p>
      {item.claim?.evidenceIds?.map(id => {
        const evidence = assessment.evidence?.find(e => e.evidenceId === id);
        const source = evidence && answerEvidence(evidence, `${sessionId}:answer:${round}`, answer);
        if (!source || !evidence) return <p key={id} role="status">来源已失效或无法定位，不能据此确认观察。</p>;
        return <details key={id} className={styles.evidenceSource}>
          <summary>查看回答来源 · 第 {round} 轮 · [{evidence.offsetStart}, {evidence.offsetEnd})</summary>
          <pre>{source.before}<mark>{source.quote}</mark>{source.after}</pre>
          <p className={styles.note}>来源：回答自述 · {evidence.normalizationVersion} · 版本 {evidence.sourceVersion}</p>
          <p className={styles.note}>SHA-256 {evidence.sourceHash}</p>
        </details>;
      })}
    </article>)}
    <p className={styles.note}>{assessment.limitation}</p>
  </section>;
}
