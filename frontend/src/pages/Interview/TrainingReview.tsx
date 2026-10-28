import { useEffect, useState } from 'react';
import { Alert, Button, Pagination, Space } from 'antd';
import { Link } from 'react-router-dom';
import { interviewService } from '@/api/modules/interview';
import { captureAccountScope } from '@/api/accountScope';
import { isRequestCanceled } from '@/api/ApiError';
import type { InterviewHistoryItem, InterviewSession } from '@/types/api.d';
import styles from './agent.module.css';

const levels = { INSUFFICIENT: '证据不足', PARTIAL: '部分体现', STRONG: '充分体现' };

export function TrainingComparison({ session }: { session: InterviewSession }) {
  const original = session.agent?.training?.retry;
  if (!original) return null;
  const evaluations = session.agent?.evaluations.filter(e => e.competencyId === original.competencyId) ?? [];
  const answers = session.turns.filter(turn => turn.speaker === 'user');
  return <section className={styles.report} aria-label="复练前后对照">
    <div className={styles.reportHeading}><div><span className={styles.kicker}>薄弱点复练</span><h2>{original.competencyName} · 前后对照</h2></div>
      <Link to={`/interview?${new URLSearchParams({ sessionId: original.sourceSessionId })}`}>打开原会话</Link></div>
    <p className={styles.note}>原记录为发起复练时保存的快照。逐轮核对新证据和仍待补充的内容；两次问题可能不同，证据等级仅供练习参考。</p>
    <div className={styles.reportGrid}>
      <article className={styles.evidenceCard}><h3>原回答 · 第 {original.sourceRound} 轮</h3><p>{original.question}</p>
        <small>{levels[original.level]} · 回答全文</small><p className={styles.answerText}>{original.answer}</p>
        <small>原证据</small>{original.evidence ? <blockquote>{original.evidence}</blockquote> : <p>无可引用证据</p>}
        <p><strong>原回答缺口：</strong>{original.gap}</p></article>
      <div>{evaluations.length ? evaluations.map(evaluation => <article key={evaluation.round} className={styles.evidenceCard}>
        <h3>复练 · 第 {evaluation.round} 轮 · {levels[evaluation.decision.level]}</h3>
        <small>回答全文</small><p className={styles.answerText}>{answers[evaluation.round - 1]?.content}</p>
        <small>本次证据</small>{evaluation.decision.evidence ? <blockquote>{evaluation.decision.evidence}</blockquote> : <p>无可引用证据</p>}
        <p><strong>仍待补充 / 验证：</strong>{evaluation.decision.gap}</p>
      </article>) : <p>完成回答后，这里会显示本次证据与仍待补充的内容。</p>}</div>
    </div>
  </section>;
}

export function RelatedRetries({ sessionId }: { sessionId: string }) {
  const [items, setItems] = useState<InterviewHistoryItem[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [reload, setReload] = useState(0);
  const [error, setError] = useState('');
  useEffect(() => {
    const request = new AbortController(); const scope = captureAccountScope();
    setError(''); setItems([]);
    void interviewService.getRetries(sessionId, page, request.signal).then(result => {
      if (!request.signal.aborted && scope.isCurrent()) { setItems(result.items); setTotal(result.total); }
    }).catch(cause => {
      if (!request.signal.aborted && scope.isCurrent() && !isRequestCanceled(cause)) setError(cause instanceof Error ? cause.message : '读取关联复练失败');
    });
    return () => request.abort();
  }, [sessionId, page, reload]);
  if (!error && !total) return null;
  return <section className={styles.report} aria-label="关联复练记录"><h3>从本场发起的复练</h3>
    {error && <Alert type="warning" message={error} action={<Button onClick={() => setReload(value => value + 1)}>重试</Button>} />}
    <Space direction="vertical">{items.map(item => <Link key={item.sessionId} to={`/interview?${new URLSearchParams({ sessionId: item.sessionId })}`}>
      {item.targetRole} · {item.state === 'FINISHED' ? '查看复练与对照' : '继续复练'} · {item.answeredCount}/{item.totalRounds} 轮 · {new Date(item.createdAt).toLocaleString()}
    </Link>)}</Space>
    {total > 20 && <Pagination current={page} pageSize={20} total={total} showSizeChanger={false} onChange={setPage} />}
  </section>;
}
