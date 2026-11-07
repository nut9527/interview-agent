import { useEffect, useRef, useState } from 'react';
import { Alert, Button, Empty, Pagination, Space, Spin, Tag, message } from 'antd';
import { Link, useNavigate } from 'react-router-dom';
import { interviewLearning, type InterviewResources, type TrainingTask } from '@/api/modules/interviewLearning';
import { captureAccountScope } from '@/api/accountScope';
import { isRequestCanceled } from '@/api/ApiError';
import type { InterviewSession } from '@/types/api.d';
import styles from './learning.module.css';

export function useAddTrainingTask(sessionId: string) {
  const [saving, setSaving] = useState(false);
  const scope = useRef(captureAccountScope());
  const lifetime = useRef<AbortController>();
  useEffect(() => { const request = new AbortController(); lifetime.current = request; return () => request.abort(); }, []);
  const add = async (round: number, questionId?: string) => {
    if (saving || !scope.current.isCurrent()) return;
    setSaving(true);
    try { await interviewLearning.add(sessionId, round, questionId, lifetime.current?.signal); if (scope.current.isCurrent() && !lifetime.current?.signal.aborted) message.success('已加入个人训练计划；重复添加会保留原任务。'); }
    catch (e) { if (scope.current.isCurrent() && !isRequestCanceled(e)) message.error(e instanceof Error ? e.message : '加入计划失败'); }
    finally { if (scope.current.isCurrent() && !lifetime.current?.signal.aborted) setSaving(false); }
  };
  return { add, saving };
}

export function WeaknessResources({ sessionId }: { sessionId: string }) {
  const [rows, setRows] = useState<InterviewResources[]>(), [error, setError] = useState(''), [reload, setReload] = useState(0);
  const { add, saving } = useAddTrainingTask(sessionId);
  useEffect(() => {
    const request = new AbortController(), scope = captureAccountScope(); setError(''); setRows(undefined);
    void interviewLearning.resources(sessionId, request.signal).then(data => { if (scope.isCurrent() && !request.signal.aborted) setRows(data); })
      .catch(e => { if (scope.isCurrent() && !request.signal.aborted && !isRequestCanceled(e)) setError(e instanceof Error ? e.message : '读取推荐失败'); });
    return () => request.abort();
  }, [sessionId, reload]);
  return <section className={styles.section} aria-label="薄弱点关联资料"><div className={styles.heading}><div><span className={styles.eyebrow}>从复盘到行动</span><h2>补上这次回答的缺口</h2></div><Button onClick={() => setReload(v => v + 1)}>刷新推荐</Button></div>
    <p>按待复练能力与回答缺口检索本人知识库，题目按相同能力匹配。资料只作学习参考，推荐随当前资料更新。</p>
    {error ? <Alert type="error" message={error} /> : !rows ? <Spin /> : rows.length === 0 ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="本场没有记录待复练的能力缺口" /> : rows.map(row => <article key={row.round} className={styles.resource}>
      <div className={styles.heading}><div><Tag>第 {row.round} 轮</Tag><h3>{row.competency}</h3></div><Button disabled={saving} onClick={() => void add(row.round)}>将此能力加入计划</Button></div><p><strong>回答缺口：</strong>{row.gap}</p>
      <div className={styles.columns}><div><h4>我的知识库 · 相关段落</h4>{row.passages.length ? row.passages.map(p => <article className={styles.passage} key={p.chunkId}>
        <Link to={'/knowledge?' + new URLSearchParams({ document: p.documentId, version: String(p.version), chunk: p.chunkId })}>{p.title} · v{p.version}</Link>
        <small>{p.heading || '正文'} · 第 {p.startLine}–{p.endLine} 行</small><blockquote>{p.content}</blockquote>
      </article>) : <p className={styles.empty}>本人知识库中未找到匹配段落。可补充相关笔记后刷新推荐。</p>}</div>
      <div><h4>题库 · 同能力练习</h4>{row.exercises.length ? row.exercises.map(q => <article key={q.id} className={styles.exercise}><Link to={'/questions/' + q.id}>{q.title}</Link><small>{q.reason}</small><Button size="small" disabled={saving} onClick={() => void add(row.round, q.id)}>将题目加入计划</Button></article>) : <p className={styles.empty}>题库中暂无匹配此能力的练习题。</p>}</div></div>
    </article>)}
  </section>;
}

const labels = { TODO: '待练习', STARTING: '启动中', ACTIVE: '进行中', PAUSED: '已暂停', COMPLETED: '已完成' };
function TaskSource({ task }: { task: TrainingTask }) {
  if (task.goalId) return <Link to="/learning/goals">查看训练目标与确认计划</Link>;
  if (task.sourceType === 'QUESTION') return <Link to={'/questions/' + encodeURIComponent(task.questionId)}>题库 · v{task.questionVersion}</Link>;
  if (task.sourceType === 'KNOWLEDGE') return <Link to={'/knowledge?' + new URLSearchParams({ document: task.sourceDocumentId || '', version: String(task.sourceDocumentVersion) })}>知识资料 · v{task.sourceDocumentVersion}</Link>;
  return <Link to={'/interview?sessionId=' + task.sourceSessionId}>查看原复盘</Link>;
}
export function TrainingTasks() {
  const navigate = useNavigate();
  const [rows, setRows] = useState<TrainingTask[]>([]), [total, setTotal] = useState(0), [page, setPage] = useState(1);
  const [error, setError] = useState(''), [loading, setLoading] = useState(true), [busy, setBusy] = useState(''), [reload, setReload] = useState(0);
  const lifetime = useRef<AbortController>();
  useEffect(() => { const request = new AbortController(); lifetime.current = request; return () => request.abort(); }, []);
  useEffect(() => {
    const request = new AbortController(), scope = captureAccountScope(); setLoading(true); setError('');
    void interviewLearning.tasks(page, request.signal).then(data => { if (scope.isCurrent() && !request.signal.aborted) { setRows(data.items); setTotal(data.total); if (!data.items.length && page > 1) setPage(page - 1); } })
      .catch(e => { if (scope.isCurrent() && !request.signal.aborted && !isRequestCanceled(e)) setError(e instanceof Error ? e.message : '读取训练计划失败'); })
      .finally(() => { if (scope.isCurrent() && !request.signal.aborted) setLoading(false); });
    return () => request.abort();
  }, [page, reload]);
  const act = async (task: TrainingTask, action: 'START' | 'PAUSE' | 'RESUME' | 'REMOVE') => {
    const scope = captureAccountScope(), signal = lifetime.current?.signal; if (busy || signal?.aborted) return; setBusy(task.id);
    try {
      if (action === 'START') {
        if (task.activeSessionId) navigate('/interview?' + new URLSearchParams({ sessionId: task.activeSessionId }));
        else {
          const key = `training-start:${task.sourceSessionId}:${task.id}`;
          const requestId = sessionStorage.getItem(key) || crypto.randomUUID();
          sessionStorage.setItem(key, requestId);
          const run = await interviewLearning.startCommand(task.id, requestId, signal);
          if (['FAILED', 'CANCELLED', 'EXPIRED'].includes(run.status)) { sessionStorage.removeItem(key); throw new Error('本次启动未完成，请重新开始。'); }
          if (scope.isCurrent() && !signal?.aborted) navigate('/interview?' + new URLSearchParams({ runId: run.runId }));
        }
      }
      else if (action === 'REMOVE') await interviewLearning.remove(task.id, signal);
      else await interviewLearning.update(task.id, action, signal);
      if (scope.isCurrent() && !signal?.aborted) setReload(v => v + 1);
    } catch (e) { if (scope.isCurrent() && !signal?.aborted && !isRequestCanceled(e)) message.error(e instanceof Error ? e.message : '操作失败'); }
    finally { if (scope.isCurrent() && !signal?.aborted) setBusy(''); }
  };
  return <section className={styles.section} aria-label="个人训练计划"><div className={styles.heading}><div><span className={styles.eyebrow}>个人训练计划</span><h2>把待复练的能力逐项完成</h2></div><Button onClick={() => setReload(v => v + 1)} disabled={loading}>刷新计划</Button></div>
    <p>任务和已提交回答保存在账号中，换设备登录后可继续。完成情况按实际回答轮次统计；暂停保留进度。</p>
    {error ? <Alert type="error" message={error} /> : loading ? <Spin /> : !rows.length ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="从面试复盘、题库或知识资料加入训练计划" /> : rows.map(task => <article className={styles.task} key={task.id}>
      <div><Tag color={task.status === 'COMPLETED' ? 'success' : undefined}>{labels[task.status]}</Tag><h3>{task.title}</h3><p>{task.gap}</p><small>实际完成 {task.answeredCount} / {task.totalRounds} 轮回答 · <TaskSource task={task} /></small></div>
      <Space wrap><Button disabled={!!busy || task.status === 'STARTING'} onClick={() => void act(task, 'REMOVE')}>移出计划</Button>
        {task.status !== 'COMPLETED' && <Button disabled={!!busy} onClick={() => void act(task, task.status === 'PAUSED' ? 'RESUME' : 'PAUSE')}>{task.status === 'PAUSED' ? '继续任务' : '暂停任务'}</Button>}
        {task.status !== 'PAUSED' && <Button type="primary" loading={busy === task.id} disabled={!!busy} onClick={() => void act(task, 'START')}>{task.status === 'COMPLETED' ? '查看复练结果' : task.activeSessionId ? '继续练习' : '开始练习'}</Button>}
      </Space>
    </article>)}
    {total > 20 && <Pagination current={page} total={total} pageSize={20} showSizeChanger={false} onChange={setPage} />}
  </section>;
}

export function InterviewTimer({ session }: { session: InterviewSession }) {
  const [now, setNow] = useState(Date.now());
  useEffect(() => { if (session.state === 'FINISHED') return; const id = window.setInterval(() => setNow(Date.now()), 1000); return () => window.clearInterval(id); }, [session.state]);
  const experience = session.experience;
  if (!experience?.timeLimitMinutes || session.state === 'FINISHED') return null;
  const remaining = Math.ceil((Date.parse(experience.startedAt) + experience.timeLimitMinutes * 60000 - now) / 1000);
  return <div className={styles.timer} role="timer" aria-label="面试倒计时" data-expired={remaining <= 0}>{remaining <= 0 ? '计时已结束，可继续作答' : `剩余 ${Math.floor(remaining / 60)}:${String(remaining % 60).padStart(2, '0')}`}</div>;
}
