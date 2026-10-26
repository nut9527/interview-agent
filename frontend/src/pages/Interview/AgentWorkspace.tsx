import { Alert, Tag } from 'antd';
import type { InterviewAgentState } from '@/types/api.d';
import styles from './agent.module.css';

const levelLabels = { INSUFFICIENT: '证据不足', PARTIAL: '部分体现', STRONG: '充分体现' };
const actionLabels = { FOLLOW_UP: '继续追问', ADVANCE: '切换能力点', FINISH: '完成复盘' };
const probeLabels = { OPENING: '能力首问', EVIDENCE: '核对证据', QUANTIFY: '量化结果', COUNTEREXAMPLE: '检验反例', TRANSFER: '场景迁移', TRADEOFF: '比较取舍', CLARIFY: '澄清职责', NONE: '综合复盘' };
const last = <T,>(items: T[]): T | undefined => items[items.length - 1];

export function AgentPlan({ agent, finished }: { agent: InterviewAgentState; finished: boolean }) {
  const active = agent.plan.find(item => item.id === agent.activeCompetencyId);
  const latest = last(agent.evaluations);
  return <section className={styles.plan} aria-label="面试 Agent 考察计划">
    <div className={styles.planHeading}><h3>本场考察计划</h3><Tag>
      {agent.executionMode === 'mock' ? '规则演示' : '模型驱动'}</Tag></div>
    <p className={styles.intro}>沿能力目标提问，依据回答调整下一步。</p>
    {!finished && latest?.decision.probe && <div className={styles.objective}><div><span className={styles.nextProbe}>下一问 · {probeLabels[latest.decision.probe]}</span><p>{latest.decision.intent}</p></div></div>}
    <ol className={styles.rail}>{agent.plan.map((item, index) => {
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
      {agent.version !== 'interview-agent-v3' && <details className={styles.anchors}><summary>完整考察目标</summary><p>{active.objective}</p></details>}</>}
    {!!agent.practiceMemory?.length && <small className={styles.note}>已参考最近同模式练习，优先核对曾经证据不足的能力。</small>}
    {latest && <div className={styles.decision} aria-live="polite"><span>第 {latest.round} 轮 · {actionLabels[latest.decision.action]}</span><p>{latest.decision.reason}</p></div>}
    <small className={styles.note}>评估仅基于面试自述，引用原文可核对，经历尚未独立验证。</small>
  </section>;
}

export function AgentReport({ agent }: { agent: InterviewAgentState }) {
  const rows = agent.plan.map(item => ({ ...item, evaluation: last(agent.evaluations.filter(e => e.competencyId === item.id)) }));
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
        <p><strong>待验证：</strong>{row.evaluation.decision.gap}</p></> : <p>本场尚未考察此能力，不能据此推断掌握程度。</p>}
    </article>)}</div>
    <div className={styles.practice}><h3>下一次练什么</h3><ol>{priorities.map(row => <li key={row.id}>
      <strong>{row.name}</strong><p>{row.evaluation!.decision.practice}</p>
    </li>)}</ol></div>
    {probes.length > 0 && <div className={styles.practice}><h3>哪类追问需要练习</h3><p className={styles.note}>按本轮回答实际接受的策略统计；样本较少，用于选择练习方向。</p>
      <ul>{probes.map(row => <li key={row.probe}><strong>{row.label}</strong><p>{row.total} 次回答 · {row.insufficient} 次证据不足 · {row.partial} 次部分体现</p></li>)}</ul></div>}
    <details className={styles.history}><summary>展开逐轮评估与追问依据（{agent.evaluations.length} 轮）</summary>
      {agent.evaluations.map(e => <article key={e.round}><strong>第 {e.round} 轮 · {agent.plan.find(p => p.id === e.competencyId)?.name} · {actionLabels[e.decision.action]}</strong>
        {e.answeredProbe && <p>本轮策略：{probeLabels[e.answeredProbe]} · {e.answeredIntent}</p>}
        <p>{e.decision.reason}</p>{e.decision.evidence && <blockquote>{e.decision.evidence}</blockquote>}<p>待验证：{e.decision.gap}</p></article>)}
    </details>
    <p className={styles.note}>能力描述基于候选人自述与本场提问，未覆盖能力保持未知；本报告用于练习，不代表招聘结论。</p>
  </section>;
}
