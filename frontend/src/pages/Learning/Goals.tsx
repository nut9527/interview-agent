import { useEffect, useRef, useState } from 'react';
import { Alert, Button, Checkbox, Empty, Form, Input, InputNumber, Modal, Select, Space, Spin, Tag } from 'antd';
import { Link } from 'react-router-dom';
import { PlusOutlined, UpOutlined } from '@ant-design/icons';
import { captureAccountScope } from '@/api/accountScope';
import { isRequestCanceled } from '@/api/ApiError';
import { learningGoals, type Goal, type GoalPlan, type GoalReview, type PlanItem } from '@/api/modules/learningGoals';
import { questionBankService } from '@/api/modules/questionBank';
import { learningMemory, type LearningObservation } from '@/api/modules/learningMemory';
import { TrainingTasks } from '@/pages/Interview/InterviewLearning';
import type { QuestionSummary } from '@/types/questionBank';
import styles from './goals.module.css';

const states: Record<string, string> = { ACTIVE: '进行中', PAUSED: '已暂停', CANCELLED: '已取消', ACHIEVED: '已达成', PENDING_CONFIRMATION: '等待确认', APPLIED: '已建立任务', REJECTED: '已拒绝', SUPERSEDED: '已被替代', EXPIRED: '已过期' };
const criterion = '至少完成两个不同题目的训练。每个场景需有 DeepSeek 对同一轮回答给出的有效引用，覆盖职责和因果解释两个维度。最后由我核对原文并确认完成。';
const dimensions: Record<string, string> = { EVIDENCE_SUFFICIENCY: '证据充分程度', TECHNICAL_CORRECTNESS: '技术正确性', CAUSAL_EXPLANATION: '因果解释', TRADEOFFS: '方案权衡', RESPONSIBILITY: '职责', EXPRESSION: '表达' };
const outcomes: Record<string, string> = { AWAITING_HUMAN_REVIEW: '等待本人核对', INSUFFICIENT_EVIDENCE: '证据不足', USER_CONFIRMED: '本人已确认训练完成', EVIDENCE_CHANGED: '来源已变化，目标已暂停，请重新核对' };
const coachStates: Record<string, string> = { WAITING_PLAN: '等待制定计划', PLANNING: '正在检查证据并调整计划', PENDING_CONFIRMATION: '新计划待确认', WAITING_PRACTICE: '等待完成训练', AWAITING_HUMAN_REVIEW: '训练证据已齐，等待复核', BUDGET_EXHAUSTED: '目标预算已用完', FAILED: '本次规划未完成' };
const focusStates: Record<string, string> = { UNRESOLVED: '仍待验证', RESOLVED: '已补充证据', CONFLICTING: '证据冲突' };
const describeDimensions = (text: string) => Object.entries(dimensions).reduce((value, [key, label]) => value.split(key).join(label), text);

export default function Goals() {
  const [goals, setGoals] = useState<Goal[]>([]), [questions, setQuestions] = useState<QuestionSummary[]>([]);
  const [showCreate, setShowCreate] = useState<boolean>();
  const [loading, setLoading] = useState(true), [busy, setBusy] = useState(false), [error, setError] = useState(''), [reload, setReload] = useState(0);
  const [title, setTitle] = useState(''), [selected, setSelected] = useState<string[]>([]), [maxRuns, setMaxRuns] = useState(16), [agreed, setAgreed] = useState(false);
  const [autoAdjust, setAutoAdjust] = useState(false), [maxTraining, setMaxTraining] = useState(3);
  const [editing, setEditing] = useState<GoalPlan>(), [items, setItems] = useState<PlanItem[]>([]);
  const [review, setReview] = useState<{ id: string; value: GoalReview }>();
  const [criteriaGoal, setCriteriaGoal] = useState<Goal>(), [criteriaDimensions, setCriteriaDimensions] = useState<string[]>([]), [scenarios, setScenarios] = useState(2), [criteriaAgreed, setCriteriaAgreed] = useState(false);
  const [reviewNote, setReviewNote] = useState(''), [reviewAgreed, setReviewAgreed] = useState(false);
  const [observations, setObservations] = useState<LearningObservation[]>([]), [memoryEnabled, setMemoryEnabled] = useState(false);
  const [selectedObservations, setSelectedObservations] = useState<Record<string, string[]>>({});
  const action = useRef(false), lifetime = useRef<AbortController>(), keys = useRef(new Map<string, string>());
  // 同一操作参数在本页面复用幂等键，响应丢失后的重试不会再次建立目标或预占预算。
  const keyFor = (key: string) => { let value = keys.current.get(key); if (!value) { value = crypto.randomUUID(); keys.current.set(key, value); } return value; };
  useEffect(() => { const controller = new AbortController(); lifetime.current = controller; return () => controller.abort(); }, []);
  useEffect(() => {
    const controller = new AbortController(), scope = captureAccountScope(); setLoading(true); setError('');
    void Promise.all([learningGoals.list(controller.signal), questionBankService.page({ page: 1, size: 100 }, controller.signal), learningMemory.list(1, controller.signal).catch(e => { if (isRequestCanceled(e)) throw e; return undefined; })])
      .then(([rows, qs, memory]) => { if (scope.isCurrent() && !controller.signal.aborted) { setGoals(rows); setQuestions(qs.items); setObservations(memory?.observations || []); setMemoryEnabled(memory?.preferences?.personalizationEnabled === true); } })
      .catch(e => { if (scope.isCurrent() && !controller.signal.aborted && !isRequestCanceled(e)) setError(e instanceof Error ? e.message : '目标加载失败'); })
      .finally(() => { if (scope.isCurrent() && !controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [reload]);
  const planning = goals.some(goal => goal.coachState === 'PLANNING');
  useEffect(() => {
    if (!planning) return;
    const controller = new AbortController(), scope = captureAccountScope();
    let timer: ReturnType<typeof setTimeout>;
    const refresh = async () => {
      try {
        const rows = await learningGoals.list(controller.signal);
        if (!scope.isCurrent() || controller.signal.aborted) return;
        setGoals(rows);
        if (rows.some(goal => goal.coachState === 'PLANNING')) timer = setTimeout(() => void refresh(), 2000);
      } catch (e) {
        if (scope.isCurrent() && !controller.signal.aborted && !isRequestCanceled(e)) setError(e instanceof Error ? e.message : '规划进度读取失败');
      }
    };
    timer = setTimeout(() => void refresh(), 2000);
    return () => { clearTimeout(timer); controller.abort(); };
  }, [planning]);
  useEffect(() => {
    const refresh = () => setReload(n => n + 1);
    window.addEventListener('focus', refresh);
    return () => window.removeEventListener('focus', refresh);
  }, []);
  const act = async (work: (signal: AbortSignal, current: () => boolean) => Promise<unknown>) => {
    const signal = lifetime.current?.signal, scope = captureAccountScope(); if (action.current || !signal || signal.aborted) return;
    action.current = true; setBusy(true); setError('');
    const current = () => scope.isCurrent() && !signal.aborted;
    try { await work(signal, current); if (current()) { setEditing(undefined); setReload(n => n + 1); } }
    catch (e) { if (current() && !isRequestCanceled(e)) setError(e instanceof Error ? e.message : '操作未完成，请刷新核对后重试'); }
    finally { action.current = false; if (current()) setBusy(false); }
  };
  const propose = (goal: Goal) => act(async signal => {
    const refs = memoryEnabled ? selectedObservations[goal.id] || [] : [];
    await learningGoals.propose(goal.id, goal.revision, keyFor(`plan:${goal.id}:${goal.revision}:${goal.reservedRuns}:${refs.join(',')}`), signal, refs);
  });
  const competency = questions.find(q => q.id === selected[0])?.competencyId;
  const createOpen = showCreate ?? (!loading && goals.length === 0);
  return <section className={styles.page} aria-label="训练目标与确认计划">
    <header className={styles.header}><div><h1>训练目标</h1><p>围绕证据缺口制定计划，每场记录证据，按批次调整训练。</p></div>
      <Space wrap><Link to="/learning/memory">查看学习观察</Link>
        <Button type={createOpen ? 'default' : 'primary'} icon={createOpen ? <UpOutlined aria-hidden="true" /> : <PlusOutlined aria-hidden="true" />}
          aria-expanded={createOpen} aria-controls="goal-create-form" onClick={() => setShowCreate(!createOpen)}>{createOpen ? '收起表单' : '新建目标'}</Button>
      </Space>
    </header>
    {error && <Alert type="error" showIcon message={error} action={<Button onClick={() => setReload(n => n + 1)}>刷新</Button>} />}
    <section id="goal-create-form" className={styles.create} hidden={!createOpen} aria-label="设置训练目标"><div><h2>设置训练目标</h2><p>选择同一能力的 2–3 道不同题目。题目和参考资料只界定训练范围，不能证明你已掌握相应能力。</p></div>
      <Form layout="vertical" onFinish={() => void act(async (signal, current) => {
        const body = { title: title.trim(), questionIds: selected, requiredScenarios: 2, maxRuns, maxTraining, maxModelAttempts: maxRuns * 6, maxTools: maxRuns * 4, autoAdjust };
        await learningGoals.create({ ...body, requestId: keyFor(`create:${JSON.stringify(body)}`) }, signal);
        if (current()) { setTitle(''); setSelected([]); setAgreed(false); setAutoAdjust(false); setShowCreate(false); }
      })}>
        <Form.Item label="目标名称" required><Input aria-label="目标名称" value={title} onChange={e => setTitle(e.target.value)} maxLength={160} placeholder="例如：解释数据库幂等与失败恢复" /></Form.Item>
        <Form.Item label="允许练习的题目 · 同一能力" required><Select aria-label="允许练习的题目" mode="multiple" showSearch optionFilterProp="label" value={selected} onChange={setSelected} maxCount={3} options={questions.map(q => ({ value: q.id, label: q.title, disabled: !!competency && q.competencyId !== competency }))} /></Form.Item>
        <div className={styles.budget}><Form.Item label="总运行次数"><InputNumber aria-label="总运行次数" min={2} max={30} value={maxRuns} onChange={n => setMaxRuns(n || 16)} /></Form.Item><Form.Item label="最多训练次数"><InputNumber aria-label="最多训练次数" min={2} max={6} value={maxTraining} onChange={n => setMaxTraining(n || 3)} /></Form.Item><p>运行包括规划、训练开场和每轮作答。最多预占 {maxRuns * 6} 次模型尝试与 {maxRuns * 4} 次工具请求；失败、取消不返还。</p></div>
        <Form.Item><Checkbox checked={autoAdjust} onChange={e => setAutoAdjust(e.target.checked)}>完成当前批次后，允许 Agent 提出下一批草稿；经我确认后再建立任务。开启学习观察个性化后，可结合本目标的历史证据调整。</Checkbox></Form.Item>
        <Checkbox checked={agreed} onChange={e => setAgreed(e.target.checked)}>我确认完成标准：{criterion}</Checkbox>
        <Button className={styles.submit} type="primary" htmlType="submit" loading={busy} disabled={busy || !agreed || !title.trim() || selected.length < 2}>建立目标</Button>
      </Form>
    </section>
    <section aria-label="训练目标列表"><div className={styles.sectionHeading}><h2>我的训练目标</h2><Button disabled={busy || loading} onClick={() => setReload(n => n + 1)}>刷新目标</Button></div>
      {loading ? <Spin /> : goals.length === 0 ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="建立一个目标，从有边界的练习开始" /> : goals.map(goal => <article className={styles.goal} key={goal.id}>
        <div className={styles.goalHeading}><div><Tag>{states[goal.status] || goal.status}</Tag><h3>{goal.title}</h3></div><span>修订 {goal.revision} · {new Date(goal.expiresAt).toLocaleDateString('zh-CN')} 到期</span></div>
        <p>{describeDimensions(goal.completionCriterion)}</p>
        <p>标准修订 {goal.criteriaRevision} · 至少 {goal.requiredScenarios} 个场景 · {(goal.dimensions || []).map(d => dimensions[d] || d).join(' / ')}</p>
        {goal.status === 'ACTIVE' && <div className={styles.coach} role="status" aria-live="polite">
          <strong>{goal.coachState === 'PLANNING' && <Spin size="small" />} {coachStates[goal.coachState]}</strong>
          <p>{goal.autoAdjust ? '完成当前批次后，根据训练证据提出下一批草稿，确认后建立任务。' : '完成训练后可手动提出新的训练草稿。'}</p>
          {goal.planning?.trigger === 'TRAINING_COMPLETED' && goal.planning.sourceSessionId && <Link to={`/interview?sessionId=${encodeURIComponent(goal.planning.sourceSessionId)}`}>查看触发本次调整的训练</Link>}
          {goal.coachState === 'FAILED' && <><p>本次未生成训练草稿，可以重新发起规划。</p>{goal.planning?.error && <details><summary>查看失败详情</summary>{goal.planning.error}</details>}</>}
          {goal.coachState === 'BUDGET_EXHAUSTED' && <p>可以继续查看已有草稿和练习记录。</p>}
        </div>}
        {goal.status === 'ACTIVE' && <Form layout="vertical"><Form.Item label="本次规划使用的学习观察 · 可选"><Select aria-label={`为${goal.title}选择学习观察`} mode="multiple" maxCount={20} disabled={busy || !memoryEnabled} value={memoryEnabled ? selectedObservations[goal.id] || [] : []} onChange={value => setSelectedObservations(rows => ({ ...rows, [goal.id]: value }))} placeholder={memoryEnabled ? '显式选择有效来源，未选择则仅按题目范围规划' : '在学习观察中开启个性化后可选择来源'} options={observations.filter(o => o.status === 'ACTIVE' && o.executionMode === 'deepseek' && o.competencyId === goal.competencyId).map(o => ({ value: o.id!, label: `${dimensions[o.dimension || ''] || o.dimension} · 第 ${o.sourceRound} 轮 · ${o.fact?.observation === 'OBSERVED' ? '待复核观察' : '尚未充分观察'}` }))} /></Form.Item></Form>}
        <dl className={styles.ledger}><div><dt>运行预占</dt><dd>{goal.reservedRuns} / {goal.maxRuns}</dd></div><div><dt>训练预占</dt><dd>{goal.reservedTraining} / {goal.maxTraining}</dd></div><div><dt>模型尝试预占</dt><dd>{goal.reservedModelAttempts} / {goal.maxModelAttempts}</dd></div><div><dt>工具请求预占</dt><dd>{goal.reservedTools} / {goal.maxTools}</dd></div></dl>
        <Space wrap>{goal.status === 'ACTIVE' && <Button disabled={busy || goal.coachState === 'PLANNING' || goal.coachState === 'BUDGET_EXHAUSTED'} onClick={() => void propose(goal)}>提出训练草稿</Button>}
          {goal.status === 'ACTIVE' && !goal.plans?.some(p => p.status === 'APPLIED') && <Button disabled={busy} onClick={() => { setCriteriaGoal(goal); setCriteriaDimensions(goal.dimensions || ['CAUSAL_EXPLANATION', 'RESPONSIBILITY']); setScenarios(goal.requiredScenarios); setCriteriaAgreed(false); }}>修改完成标准</Button>}
          {['ACTIVE', 'PAUSED'].includes(goal.status) && <><Button disabled={busy} onClick={() => void act(signal => learningGoals.status(goal.id, goal.revision, goal.status === 'PAUSED' ? 'RESUME' : 'PAUSE', signal))}>{goal.status === 'PAUSED' ? '继续目标' : '暂停目标'}</Button><Button disabled={busy} onClick={() => void act(signal => learningGoals.status(goal.id, goal.revision, 'CANCEL', signal))}>取消目标</Button></>}
          <Button disabled={busy} onClick={() => void act(async (signal, current) => { const value = await learningGoals.review(goal.id, signal); if (current()) { setReview({ id: goal.id, value }); setReviewNote(''); setReviewAgreed(false); } })}>核对跨场证据</Button>
        </Space>
        {(goal.plans || []).map(raw => { const plan = raw as GoalPlan; return <section className={styles.plan} key={plan.id} aria-label="训练计划草稿">
          <div className={styles.goalHeading}><h4>训练草稿 · 修订 {plan.revision}</h4><Tag color={plan.status === 'APPLIED' ? 'success' : undefined}>{states[plan.status] || plan.status}</Tag></div>
          <p>{plan.limitation}</p><ol>{(plan.items || []).map((item, i) => <li key={item.itemKey}><span className={styles.itemNumber}>{String(i + 1).padStart(2, '0')}</span><div><Link to={'/questions/' + item.questionId}>{item.title} · v{item.questionVersion}</Link><p>{describeDimensions(item.acceptance)}</p></div></li>)}</ol>
          {!!plan.focus?.length && <div className={styles.focus}><h5>本次证据变化与训练重点</h5>{plan.focus.map((focus, index) => <article key={index}>
            <Tag color={focus.status === 'RESOLVED' ? 'success' : focus.status === 'CONFLICTING' ? 'warning' : undefined}>{focus.status && focusStates[focus.status]}</Tag>
            <strong>{focus.dimension && dimensions[focus.dimension]}</strong><p>{focus.claim}</p>
            {!!focus.observationIds?.length && <small>关联 {focus.observationIds.length} 条学习观察</small>}
          </article>)}</div>}
          {!!plan.observationRefs?.length && <p>诊断来源：{plan.observationRefs.map(ref => <Link key={ref.id} to={'/interview?sessionId=' + ref.sourceSessionId}>查看原场次 · 观察修订 {ref.revision}{' '}</Link>)}<Link to="/learning/memory">审阅或纠正观察</Link></p>}
          {plan.status === 'PENDING_CONFIRMATION' && <Space wrap><Button disabled={busy || goal.status !== 'ACTIVE'} onClick={() => { setEditing(plan); setItems((plan.items || []) as PlanItem[]); }}>编辑草稿</Button><Button disabled={busy} onClick={() => void act(signal => learningGoals.reject(plan.id, plan.revision, signal))}>拒绝草稿</Button><Button type="primary" disabled={busy || goal.status !== 'ACTIVE'} onClick={() => void act(signal => learningGoals.confirm(plan.id, plan.revision, keyFor(`confirm:${plan.id}:${plan.revision}`), signal))}>确认并建立 {plan.items?.length} 项任务</Button></Space>}
          {plan.status === 'APPLIED' && <p role="status">已按本次修订建立 {plan.taskIds?.length} 项任务。<a href="#goal-training-tasks">继续实际训练 ↓</a></p>}
        </section>; })}
        {review?.id === goal.id && <Alert className={styles.review} type="info" showIcon message={`有效场景 ${review.value.validScenarios} / ${review.value.requiredScenarios} · ${outcomes[review.value.outcome] || review.value.outcome}`} description={<><p>{review.value.limitation}</p>{review.value.results?.map(r => <p key={r.sessionId}><Link to={'/interview?sessionId=' + r.sessionId}>查看实际作答</Link> · {r.limitation}</p>)}
          {review.value.outcome === 'AWAITING_HUMAN_REVIEW' && goal.status === 'ACTIVE' && <Form layout="vertical"><Form.Item label="逐场复核记录"><Input.TextArea aria-label="逐场复核记录" maxLength={1000} value={reviewNote} onChange={e => setReviewNote(e.target.value)} placeholder="记录每个场景的实际表现、反例和仍存在的限制" /></Form.Item><Checkbox checked={reviewAgreed} onChange={e => setReviewAgreed(e.target.checked)}>我已核对各场原回答及结论，确认本次训练目标完成；这不是独立专业认证。</Checkbox><Button className={styles.submit} type="primary" disabled={busy || !reviewAgreed || !reviewNote.trim() || review.value.goalRevision !== goal.revision} onClick={() => void act(async (signal, current) => { await learningGoals.complete(goal.id, review.value, keyFor(`complete:${goal.id}:${review.value.evidenceHash}`), reviewNote.trim(), signal); if (current()) setReview(undefined); })}>确认训练目标完成</Button></Form>}
        </>} />}
      </article>)}
    </section>
    <div id="goal-training-tasks"><TrainingTasks key={reload} /></div>
    <Modal title="确认新的完成标准" open={!!criteriaGoal} onCancel={() => !busy && setCriteriaGoal(undefined)} confirmLoading={busy} okText="确认并保存新标准" okButtonProps={{ disabled: !criteriaAgreed || criteriaDimensions.length < 2 }} onOk={() => criteriaGoal && void act(async (signal, current) => { await learningGoals.criteria(criteriaGoal.id, criteriaGoal.revision, scenarios, criteriaDimensions, signal); if (current()) { setCriteriaGoal(undefined); setReview(undefined); } })}>
      <p>修改将使待确认草稿失效，已预占预算保持累计。已有确认任务的目标需沿用原标准。</p>
      <Form layout="vertical"><Form.Item label="需要核对的不同场景数"><InputNumber aria-label="需要核对的不同场景数" min={2} max={criteriaGoal?.questionIds.length || 3} value={scenarios} onChange={n => setScenarios(n || 2)} /></Form.Item><Form.Item label="逐场复核维度 · 至少选择两项"><Checkbox.Group value={criteriaDimensions} onChange={setCriteriaDimensions} options={Object.entries(dimensions).map(([value, label]) => ({ value, label }))} /></Form.Item></Form>
      <Checkbox checked={criteriaAgreed} onChange={e => setCriteriaAgreed(e.target.checked)}>我确认这些是本次训练的完成条件，结束时仍须逐场复核。</Checkbox>
    </Modal>
    <Modal title={`编辑训练草稿 · 修订 ${editing?.revision || ''}`} open={!!editing} onCancel={() => !busy && setEditing(undefined)} confirmLoading={busy} okText="保存为新修订" onOk={() => editing && void act(signal => learningGoals.edit(editing.id, editing.revision, items, signal))}>
      <p>保存后需重新确认，旧修订无法应用。验收要求用于本次出题与追问，不改变目标的固定复核标准。</p>
      {items.map((item, index) => <div className={styles.editItem} key={item.itemKey}><Form layout="vertical"><Form.Item label={`第 ${index + 1} 项标题`}><Input aria-label={`第 ${index + 1} 项标题`} maxLength={160} value={item.title} onChange={e => setItems(rows => rows.map((r, i) => i === index ? { ...r, title: e.target.value } : r))} /></Form.Item><Form.Item label="训练验收备注"><Input.TextArea aria-label={`第 ${index + 1} 项验收备注`} maxLength={1000} value={item.acceptance} onChange={e => setItems(rows => rows.map((r, i) => i === index ? { ...r, acceptance: e.target.value } : r))} /></Form.Item></Form><Space><Button disabled={index === 0} onClick={() => setItems(rows => { const next = [...rows]; [next[index - 1], next[index]] = [next[index], next[index - 1]]; return next; })}>上移</Button><Button disabled={items.length <= 1} onClick={() => setItems(rows => rows.filter((_, i) => i !== index))}>移除此项</Button></Space></div>)}
    </Modal>
  </section>;
}
