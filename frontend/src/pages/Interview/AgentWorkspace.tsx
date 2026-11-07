import { Alert, Button, Tag } from 'antd';
import { Link } from 'react-router-dom';
import type { InterviewAgentState } from '@/types/api.d';
import styles from './agent.module.css';
import { AssessmentDetails } from './AssessmentDetails';
import { answerEvidence } from './answerEvidence';
import { orderedAgentPlan } from './agentPlan';

const levelLabels = { INSUFFICIENT: '证据不足', PARTIAL: '部分体现', STRONG: '充分体现' };
const actionLabels = { FOLLOW_UP: '继续追问', ADVANCE: '切换能力点', FINISH: '完成复盘' };
const probeLabels = { OPENING: '能力首问', EVIDENCE: '核对证据', QUANTIFY: '量化结果', COUNTEREXAMPLE: '检验反例', TRANSFER: '场景迁移', TRADEOFF: '比较取舍', CLARIFY: '澄清职责', NONE: '综合复盘' };
const last = <T,>(items: T[]): T | undefined => items[items.length - 1];
const difficultyLabels = { BEGINNER: '入门', STANDARD: '标准', ADVANCED: '挑战' };
const dimensionLabels = { EVIDENCE_SUFFICIENCY: '证据充分程度', TECHNICAL_CORRECTNESS: '技术正确性', CAUSAL_EXPLANATION: '因果解释', TRADEOFFS: '方案权衡', RESPONSIBILITY: '职责边界', EXPRESSION: '表达清晰度' };

function PlanningHistory({ agent, sessionId, answers }: { agent: InterviewAgentState; sessionId: string; answers: string[] }) {
  if (!agent.planningRevisions?.length) return null;
  return <details className={styles.anchors}><summary>追问依据与计划修订（{agent.planningRevisions.length} 轮）</summary>
    <p className={styles.note}>围绕本轮回答选择待核对的问题，未观察到不代表不会。问题选择不改变本场能力覆盖与总轮次。</p>
    {agent.planningRevisions.map(revision => {
      const selected = revision.candidates?.find(candidate => candidate.intentKey === revision.selectedIntentKey);
      const source = selected?.gap && answerEvidence({ ...selected.gap, sourceType: 'ANSWER_SELF_REPORT', validity: 'VALID' },
        `${sessionId}:answer:${revision.afterRound}`, answers[(revision.afterRound ?? 0) - 1]);
      return <article key={revision.revision}><h4>第 {revision.afterRound} 轮后 · {revision.fallback ? '固定策略追问' : selected ? '依据缺口选择追问' : '按覆盖计划推进'}</h4>
        <p>{revision.reason}</p>
        {selected?.intent && <><strong>{selected.intent.dimension && dimensionLabels[selected.intent.dimension]} · {selected.intent.probe && probeLabels[selected.intent.probe]}</strong>
          <p>待核对：{selected.intent.claimToVerify}</p><p>期待补充：{selected.intent.expectedEvidence}</p>
          <small>本场难度：{revision.baseDifficulty && difficultyLabels[revision.baseDifficulty]} · 本题深度：{selected.intent.effectiveDifficulty && difficultyLabels[selected.intent.effectiveDifficulty]}</small>
          {selected.gap?.quote ? source ? <details className={styles.evidenceSource}><summary>核对追问来源 · 本场第 {revision.afterRound} 轮回答</summary>
            <pre>{source.before}<mark>{source.quote}</mark>{source.after}</pre></details> : <p role="status">回答来源无法定位，本项待重新核对。</p>
            : <p className={styles.note}>没有直接引用片段，本项保留为待核对目标。</p>}
        </>}
      </article>;
    })}
  </details>;
}

export function AgentPlan({ agent, finished, sessionId, answers }: { agent: InterviewAgentState; finished: boolean; sessionId: string; answers: string[] }) {
  const active = agent.plan.find(item => item.id === agent.activeCompetencyId);
  const latest = last(agent.evaluations);
  return <section className={styles.plan} aria-label="面试 Agent 考察计划">
    <div className={styles.planHeading}><h3>本场考察计划</h3><Tag>
      {agent.executionMode === 'mock' ? '规则演示' : '模型驱动'}</Tag></div>
    <p className={styles.intro}>{agent.version === 'interview-agent-v5'
      ? '依据回答选择下一项能力。已考察项按实际顺序展示，尚未考察项的顺序仍可调整。'
      : '沿能力目标提问，依据回答调整下一步。'}</p>
    {!finished && latest?.decision.probe && <div className={styles.objective}><div><span className={styles.nextProbe}>下一问 · {probeLabels[latest.decision.probe]}</span><p>{latest.decision.intent}</p></div></div>}
    <ol className={styles.rail}>{orderedAgentPlan(agent).map((item, index) => {
      const evaluations = agent.evaluations.filter(e => e.competencyId === item.id);
      const current = !finished && item.id === agent.activeCompetencyId;
      return <li key={item.id} className={current ? styles.active : ''} aria-current={current ? 'step' : undefined}>
        <span className={styles.number}>{String(index + 1).padStart(2, '0')}</span>
        <div><strong>{item.name}</strong><small>{current ? '正在考察' : evaluations.length ? `已考察 · ${levelLabels[last(evaluations)!.decision.level]}` : '尚未考察'}</small></div>
      </li>;
    })}</ol>
    {!finished && active && <><div className={styles.objective}><p className={styles.objectiveText}>{active.objective}</p></div>
      {!!active.materialAnchors?.length && <details className={styles.anchors}><summary>本题参考材料 · {active.materialAnchors.length} 条</summary>
        <small>以下是材料原文引用，仅用于取材，经历尚未独立验证。</small>
        {active.materialAnchors.map((anchor, index) => <blockquote key={`${anchor.source}-${index}`}><small>{anchor.kind === 'PROJECT' ? '简历项目' : anchor.kind === 'JOB' ? '岗位要求' : '简历材料'}</small><p>{anchor.quote}</p></blockquote>)}
      </details>}
      {!['interview-agent-v3', 'interview-agent-v4', 'interview-agent-v5'].includes(agent.version) && <details className={styles.anchors}><summary>完整考察目标</summary><p>{active.objective}</p></details>}</>}
    <PlanningHistory agent={agent} sessionId={sessionId} answers={answers} />
    {!!agent.practiceMemory?.length && <small className={styles.note}>已参考最近同模式练习，优先核对曾经证据不足的能力。</small>}
    {!!agent.memoryReferences?.length && <details className={styles.anchors}><summary>本场读取过的历史观察（{agent.memoryReferences.length} 条）</summary>
      <p className={styles.note}>这些来源在生成当时有效，均为待复核提议。当前状态以学习观察页为准，不能据此认定能力已验证。</p>
      {agent.memoryReferences.map(ref => <article key={`${ref.usedAtRound}-${ref.observationId}`}><strong>{ref.usedAtRound === 0 ? '开场' : `第 ${ref.usedAtRound} 轮`} · {ref.competencyId}</strong>
        <p>来源第 {ref.sourceRound} 轮 · {ref.dimension} · {ref.rubricVersion} · {ref.executionMode} · 修订 {ref.revision}</p>
        <Link to={`/interview?sessionId=${encodeURIComponent(ref.sourceSessionId)}`}>查看来源会话</Link>{' · '}<Link to="/learning/memory">审阅或纠正观察</Link>
        <small style={{ display: 'block', overflowWrap: 'anywhere' }}>观察 ID：{ref.observationId}</small></article>)}
    </details>}
    {!!agent.training?.knowledge?.length && <details className={styles.anchors}><summary>本场知识库版本快照（{agent.training.knowledge.length} 份）</summary>{agent.training.knowledge.map(doc => <article key={doc.id}><h4>{doc.title} · v{doc.version}</h4><small>来源：{doc.sourceName} · SHA-256 {doc.sha256}</small><details><summary>核对保存的完整原文</summary><pre style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{doc.content}</pre></details>{doc.excerpts.map(chunk => <blockquote key={chunk.chunkId}><small>{chunk.heading} · 第 {chunk.startLine}–{chunk.endLine} 行 · 本场参考片段</small><p>{chunk.content}</p></blockquote>)}</article>)}</details>}
    {!!agent.training?.knowledge?.length && !!agent.evaluations.length && <details className={styles.anchors}><summary>逐轮资料来源（{agent.evaluations.length} 轮）</summary>
      <p className={styles.note}>从本场保存的原文按本轮问题检索；片段供核对，不能据此推断资料足够。</p>
      {agent.evaluations.map(e => <article key={e.round}><h4>第 {e.round} 轮</h4>{e.knowledgeSources == null ? <p>此历史轮次未记录资料来源。</p> : !e.knowledgeSources.length ? <p>未检索到相关片段，资料不足的细节需补充或验证。</p> : e.knowledgeSources.map(doc => <div key={doc.id}><strong>{doc.title} · v{doc.version}</strong>{doc.excerpts.map(c => <blockquote key={c.chunkId}><small>{c.heading} · 第 {c.startLine}–{c.endLine} 行</small><p style={{ whiteSpace: 'pre-wrap' }}>{c.content}</p></blockquote>)}</div>)}</article>)}
    </details>}
    {latest && <div className={styles.decision} aria-live="polite"><span>第 {latest.round} 轮 · {actionLabels[latest.decision.action]}</span><p>{latest.decision.reason}</p></div>}
    <small className={styles.note}>评估仅基于面试自述，引用原文可核对，经历尚未独立验证。</small>
  </section>;
}

export function AgentReport({ agent, onRetry, busy, onAddTask, sessionId, answers }: { agent: InterviewAgentState; onRetry: (round: number) => void; busy: boolean; onAddTask?: (round: number) => void; sessionId: string; answers: string[] }) {
  const rows = orderedAgentPlan(agent).map(item => ({ ...item, evaluation: last(agent.evaluations.filter(e => e.competencyId === item.id)) }));
  const assessed = rows.filter(row => row.evaluation);
  const probes = Object.entries(probeLabels).filter(([probe]) => probe !== 'OPENING' && probe !== 'NONE').map(([probe, label]) => {
    const answers = agent.evaluations.filter(e => e.answeredProbe === probe);
    return { probe, label, total: answers.length, insufficient: answers.filter(e => e.decision.level === 'INSUFFICIENT').length,
      partial: answers.filter(e => e.decision.level === 'PARTIAL').length };
  }).filter(row => row.total > 0).sort((a, b) => b.insufficient / b.total - a.insufficient / a.total || b.partial / b.total - a.partial / a.total);
  const priorities = [...assessed].sort((a, b) => {
    const rank = { INSUFFICIENT: 0, PARTIAL: 1, STRONG: 2 };
    return rank[a.evaluation!.decision.level] - rank[b.evaluation!.decision.level];
  });
  return <section id="interview-agent-report" className={styles.report} aria-label="面试能力复盘">
    <div className={styles.reportHeading}><div><span className={styles.kicker}>面试复盘</span><h2>回答证据与练习建议</h2></div>
      <div className={styles.coverage}><strong>{assessed.length}<span> / {rows.length}</span></strong><small>能力点已考察</small></div></div>
    {agent.executionMode === 'mock' && <Alert type="info" showIcon message="当前为规则演示，评估用于体验流程，不代表真实模型判断。" />}
    <div className={styles.reportGrid}>{rows.map(row => <article key={row.id} className={styles.evidenceCard}>
      <div><h3>{row.name}</h3><span className={styles.evidenceLevel}>{row.evaluation ? levelLabels[row.evaluation.decision.level] : '未覆盖'}</span></div>
      {row.evaluation ? <><small>第 {row.evaluation.round} 轮 · 回答原文</small>
        {row.evaluation.decision.evidence ? <blockquote>{row.evaluation.decision.evidence}</blockquote> : <p>本轮尚无可引用的回答证据。</p>}
        <p><strong>待验证：</strong>{row.evaluation.decision.gap}</p>
        <AssessmentDetails assessment={row.evaluation.assessment} round={row.evaluation.round} sessionId={sessionId} answer={answers[row.evaluation.round - 1]} training={agent.training} />
        <Button disabled={busy} onClick={() => onRetry(row.evaluation!.round)}>{agent.training?.question ? '复练这道题 · 3 轮' : '复练此能力 · 3 轮'}</Button>
        {onAddTask && <Button disabled={busy} onClick={() => onAddTask(row.evaluation!.round)}>加入训练计划</Button>}</> : <p>本场尚未考察此能力，不能据此推断掌握程度。</p>}
    </article>)}</div>
    <div className={styles.practice}><h3>下一次练什么</h3><ol>{priorities.map(row => <li key={row.id}>
      <strong>{row.name}</strong><p>{row.evaluation!.decision.practice}</p>
    </li>)}</ol></div>
    {probes.length > 0 && <div className={styles.practice}><h3>哪类追问需要练习</h3><p className={styles.note}>按本轮回答实际接受的策略统计；样本较少，用于选择练习方向。</p>
      <ul>{probes.map(row => <li key={row.probe}><strong>{row.label}</strong><p>{row.total} 次回答 · {row.insufficient} 次证据不足 · {row.partial} 次部分体现</p></li>)}</ul></div>}
    <details className={styles.history}><summary>展开逐轮评估与追问依据（{agent.evaluations.length} 轮）</summary>
      {agent.evaluations.map(e => <article key={e.round}><strong>第 {e.round} 轮 · {agent.plan.find(p => p.id === e.competencyId)?.name} · {actionLabels[e.decision.action]}</strong>
        {e.answeredProbe && <p>本轮策略：{probeLabels[e.answeredProbe]} · {e.answeredIntent}</p>}
        <p>{e.decision.reason}</p>{e.decision.evidence && <blockquote>{e.decision.evidence}</blockquote>}<p>待验证：{e.decision.gap}</p>
        <AssessmentDetails assessment={e.assessment} round={e.round} sessionId={sessionId} answer={answers[e.round - 1]} training={agent.training} /></article>)}
    </details>
    <p className={styles.note}>能力描述基于候选人自述与本场提问，未覆盖能力保持未知；本报告用于练习，不代表招聘结论。</p>
  </section>;
}
