import { useEffect, useState } from 'react';
import { Alert, Button, Spin } from 'antd';
import { interviewRuns, type InterviewRunTrace } from '@/api/modules/interviewRuns';
import { captureAccountScope } from '@/api/accountScope';
import { isRequestCanceled } from '@/api/ApiError';
import styles from './RunTrace.module.css';

const decisions: Record<string, string> = { RETRIEVE: '读取资料', VERIFY: '核对已有观察', REVISE: '调整检索', PROPOSE: '校验本轮结果', STOP: '停止执行' };
const observations: Record<string, string> = { FOUND: '找到资料', EMPTY: '未命中', MISSING: '来源不在范围内', INSUFFICIENT: '资料不足', VALIDATED: '结果通过校验', SUCCEEDED: '本轮已保存', FAILED: '本轮未完成', EXPIRED: '运行超时', CANCELLED: '已取消' };
const stops: Record<string, string> = { COMPLETED: '本轮处理完成', NO_PROGRESS: '重复取证或连续没有新资料', STEP_LIMIT: '已到本轮取证步数上限', BUDGET_LIMIT: '已保留剩余资源用于整理结果', CANCELLED: '用户已取消', EXPIRED: '运行超时', RUN_QUEUE_TIMEOUT: '任务排队超时', RUN_SOURCE_REVOKED: '训练来源已失效', RUN_MEMORY_REVOKED: '历史观察已撤销' };

export function RunTrace({ sessionId, turnCount }: { sessionId: string; turnCount: number }) {
  const [open, setOpen] = useState(false);
  const [traces, setTraces] = useState<InterviewRunTrace[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(false);
  const [retry, setRetry] = useState(0);
  useEffect(() => {
    setTraces([]);
    if (!open) return;
    const account = captureAccountScope(), request = new AbortController();
    const abort = () => request.abort();
    account.signal.addEventListener('abort', abort, { once: true });
    const current = () => account.isCurrent() && !request.signal.aborted;
    setLoading(true); setError(false);
    void interviewRuns.forSession(sessionId, request.signal).then(async runs => {
      // 公开列表保持有界，更早的 Run 仍可通过状态 URL 访问。
      const items = await Promise.all(runs.slice(0, 4).map(run => interviewRuns.trace(run.runId, request.signal)));
      if (current()) setTraces(items);
    }).catch(cause => { if (current() && !isRequestCanceled(cause)) setError(true); })
      .finally(() => { if (current()) setLoading(false); });
    return () => { request.abort(); account.signal.removeEventListener('abort', abort); };
  }, [sessionId, turnCount, open, retry]);
  return <details className={styles.trace} onToggle={event => setOpen(event.currentTarget.open)}>
    <summary>查看本场运行过程</summary>
    <p>展示最近四次运行。读到资料不代表已经证明经历或能力；未完成的运行不会保存为正式评价。</p>
    {loading && <Spin size="small" />}
    {error && <Alert type="warning" message="运行过程暂时无法读取" action={<Button onClick={() => setRetry(value => value + 1)}>重新读取</Button>} />}
    {!loading && !error && !traces.length && <p>本场没有可查看的异步运行记录。</p>}
    {traces.map(trace => <article key={trace.runId}>
      {trace.hidden ? <p>正式面试结束后可查看运行过程。</p> : <>
        <h4>{trace.resultCompleteness === 'VALIDATED' ? '本轮结果已校验' : trace.resultCompleteness === 'PENDING' ? '本轮执行中' : '本轮未形成正式结果'}</h4>
        <p>{trace.policyVersion === 'B0' ? '本次采用基础流程，未记录逐步观察' : `${trace.actionSteps ?? 0} 次取证观察`} · {trace.actualExecutions ?? 0} 次实际执行 · {trace.cacheHits ?? 0} 次缓存复用</p>
        {!!trace.unknownExecutions && <p>历史记录有 {trace.unknownExecutions} 次执行情况未知。</p>}
        <ol>{trace.steps?.map(step => <li key={step.stepNo}>
          <strong>{decisions[step.decisionType ?? ''] ?? '处理本轮'}</strong> · {observations[step.observation ?? ''] ?? '状态已记录'}
          {!!step.newEvidence && <span> · 新取得 {step.newEvidence} 项可核对资料</span>}
          {step.cacheHit && <span> · 复用已核对资料</span>}
        </li>)}</ol>
        {trace.stopReason && <p>停止原因：{stops[trace.stopReason] ?? '执行已停止，请核对本轮状态。'}</p>}
      </>}
    </article>)}
  </details>;
}
