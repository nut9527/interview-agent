import { useEffect, useRef, useState } from 'react';
import { Alert, Button, Empty, Input, Modal, Pagination, Space, Spin, Switch, Tag } from 'antd';
import { Link } from 'react-router-dom';
import { captureAccountScope } from '@/api/accountScope';
import { learningMemory, type LearningMemory, type LearningObservation } from '@/api/modules/learningMemory';
import styles from './memory.module.css';

const dimensions: Record<string, string> = { RESPONSIBILITY: '职责边界', CAUSAL_EXPLANATION: '因果解释', EVIDENCE_SUFFICIENCY: '证据充分程度', TECHNICAL_CORRECTNESS: '技术正确性', TRADEOFFS: '方案权衡', EXPRESSION: '表达清晰度' };
const states: Record<string, string> = { ACTIVE: '有效观察', CORRECTED: '已纠正', REVOKED: '来源已撤销', DELETED: '已删除' };

export default function MemoryReview() {
  const [data, setData] = useState<LearningMemory>(), [page, setPage] = useState(1), [reload, setReload] = useState(0);
  const [loading, setLoading] = useState(true), [busy, setBusy] = useState(false), [error, setError] = useState('');
  const [editing, setEditing] = useState<LearningObservation>(), [removing, setRemoving] = useState<LearningObservation>(), [text, setText] = useState('');
  const action = useRef(false), exported = useRef<AbortController>();
  useEffect(() => {
    const controller = new AbortController(), scope = captureAccountScope();
    setLoading(true); setError(''); setData(undefined);
    void learningMemory.list(page, controller.signal).then(value => {
      if (!scope.isCurrent() || controller.signal.aborted) return;
      if (page > 1 && !value.observations?.length) { setPage(Math.max(1, page - 1)); return; }
      setData(value);
    }).catch(cause => { if (scope.isCurrent() && !controller.signal.aborted) setError(cause instanceof Error ? cause.message : '观察加载失败'); })
      .finally(() => { if (scope.isCurrent() && !controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [page, reload]);
  useEffect(() => () => exported.current?.abort(), []);
  const mutate = async (work: () => Promise<unknown>) => {
    if (action.current) return;
    action.current = true; setBusy(true); setError(''); const scope = captureAccountScope();
    try { await work(); if (scope.isCurrent()) { setEditing(undefined); setRemoving(undefined); setReload(n => n + 1); } }
    catch (cause) { if (scope.isCurrent()) setError(cause instanceof Error ? cause.message : '操作失败，请刷新重试'); }
    finally { action.current = false; if (scope.isCurrent()) setBusy(false); }
  };
  const download = () => mutate(async () => {
    const scope = captureAccountScope(), controller = new AbortController(); exported.current = controller;
    const observations: LearningObservation[] = []; let cursor = '', preferences, revision: number | undefined;
    // 导出保持有界，账号数据超出浏览器下载上限时明确停止。
    for (let i = 0; i < 100; i++) {
      const chunk = await learningMemory.export(cursor, controller.signal);
      if (!scope.isCurrent() || controller.signal.aborted) return;
      if (revision !== undefined && revision !== chunk.preferences?.revision) throw new Error('导出期间记录已变化，请重新导出。');
      revision = chunk.preferences?.revision;
      observations.push(...chunk.observations || []); preferences = chunk.preferences;
      if (!chunk.hasMore) {
        const url = URL.createObjectURL(new Blob([JSON.stringify({ formatVersion: chunk.formatVersion, exportedAt: chunk.exportedAt, preferences, observations }, null, 2)], { type: 'application/json' }));
        const anchor = document.createElement('a'); anchor.href = url; anchor.download = '我的学习观察.json'; anchor.click(); URL.revokeObjectURL(url); return;
      }
      if (!chunk.nextCursor || chunk.nextCursor === cursor) throw new Error('导出游标异常，请重试');
      cursor = chunk.nextCursor;
    }
    throw new Error('观察超过一万条，请通过分页导出接口分批导出；本次未下载不完整文件。');
  });
  return <main className={styles.page}>
    <header className={styles.header}><div><h1>学习观察</h1><p>核对回答留下的证据，决定哪些记录可以继续使用。</p></div><Space wrap><Button onClick={() => setReload(n => n + 1)} disabled={busy}>刷新记录</Button><Button onClick={() => void download()} loading={busy}>导出 JSON</Button></Space></header>
    <section className={styles.preferences} aria-label="个性化使用许可"><div><h2>允许使用学习观察</h2><p>默认关闭。关闭后不授权新命令使用跨场观察，已有记录仍可审阅、纠正和删除。</p><small>服务端默认关闭自动记忆；通过质量验收并启用后，新命令才可按此许可读取有限历史。纠正或删除会阻止在途命令继续使用相关观察。</small></div><Switch aria-label="允许使用学习观察" checked={data?.preferences?.personalizationEnabled ?? false} disabled={!data || busy || loading} onChange={enabled => void mutate(() => learningMemory.preferences(enabled, data?.preferences?.revision ?? 0))} /></section>
    <Alert type="info" showIcon message="观察是待复核的定性提议，不是能力认证。" description="回答自述、资料引用和用户纠正分别保留来源。未观察到不等于表现差；Mock 演示与真实模型、不同评价标准分开统计。来源变化或撤销后，旧观察不再作为有效记录。" />
    {error && <Alert type="error" showIcon message={error} />}
    {!!data?.sync?.failed && <Alert type="warning" showIcon message={`有 ${data.sync.failed} 个观察归档任务失败，部分记录尚未入账。请保留来源会话并联系维护者核查。`} />}
    {!!data?.sync?.pending && <Alert type="info" message={`${data.sync.pending} 个归档任务等待处理，稍后刷新可查看新记录。`} />}
    <div className={styles.summary}><strong>{data?.total ?? 0}<span>条可审阅记录</span></strong><p>概览按最近 1000 条未删除观察确定性汇总。纠正与撤销不计入有效样本，不生成总分。</p></div>
    {loading ? <Spin /> : !data?.observations?.length ? <Empty description="完成一场面试后，观察会自动归档到这里。" /> : <section aria-label="学习观察记录" className={styles.records}>
      {data.observations.map(row => <article key={row.id} className={styles.record}>
        <div className={styles.meta}><span>第 {row.sourceRound} 轮 · {row.competencyId}</span><Tag color={row.status === 'ACTIVE' ? 'blue' : 'default'}>{states[row.status || ''] || row.status}</Tag><Tag>{row.executionMode === 'mock' ? 'Mock 演示' : row.executionMode}</Tag></div>
        <h2>{dimensions[row.dimension || ''] || row.dimension}</h2>
        <p className={styles.claim}>{row.fact?.claim?.claimText || (row.status === 'CORRECTED' ? '旧观察已被你的纠正替代，不再计入有效样本。' : '来源已失效，观察与引用正文已撤销。')}</p>
        {row.fact && <><Tag>{row.fact.observation === 'OBSERVED' ? '已观察 · 模型提议' : row.fact.observation === 'NOT_APPLICABLE' ? '本轮不适用' : '本轮未观察到'}</Tag>{row.fact.claim?.status === 'DISPUTED' && <Tag color="warning">存在争议</Tag>}<p className={styles.limit}>{row.fact.claim?.limitation}</p>
          {!!row.fact.evidence?.length && <details><summary>核对引用与来源（{row.fact.evidence.length}）</summary>{row.fact.evidence.map(e => <div className={styles.source} key={e.evidenceId}><blockquote>{e.quote}</blockquote><dl><dt>来源</dt><dd>{e.sourceType} · {e.sourceId}</dd><dt>版本</dt><dd>{e.sourceVersion}</dd><dt>位置</dt><dd>{e.normalizationVersion} [{e.offsetStart}, {e.offsetEnd}) · UTF-16</dd></dl></div>)}</details>}</>}
        {row.correction && <aside className={styles.correction}><strong>你的纠正 · 尚未独立验证</strong><p>{row.correction}</p></aside>}
        <footer><small>{row.rubricVersion} · {row.createdAt ? new Date(row.createdAt).toLocaleString('zh-CN') : ''}</small><Space wrap><Link to={'/interview?sessionId=' + encodeURIComponent(row.sourceSessionId || '')}>查看来源会话</Link>{row.status === 'ACTIVE' && <Button size="small" disabled={busy} onClick={() => { setText(''); setEditing(row); }}>纠正观察</Button>}<Button size="small" danger disabled={busy} onClick={() => setRemoving(row)}>删除记录</Button></Space></footer>
      </article>)}
    </section>}
    {(data?.total || 0) > 20 && <Pagination current={page} total={data?.total} pageSize={20} showSizeChanger={false} onChange={setPage} />}
    <Modal title="纠正这条观察" open={!!editing} onCancel={() => { if (!busy) setEditing(undefined); }} onOk={() => { if (editing?.id) void mutate(() => learningMemory.correct(editing.id!, editing.revision!, text)); }} okText="保存纠正" cancelText="取消" confirmLoading={busy} okButtonProps={{ disabled: !text.trim() }}><p>旧观察将停止生效。你的意见会标为“用户纠正”，不会自动成为已验证能力。</p><Input.TextArea aria-label="纠正内容" value={text} onChange={e => setText(e.target.value)} maxLength={1000} showCount rows={5} /></Modal>
    <Modal title="删除这条学习观察？" open={!!removing} onCancel={() => { if (!busy) setRemoving(undefined); }} onOk={() => { if (removing?.id) void mutate(() => learningMemory.remove(removing.id!, removing.revision!)); }} okText="删除观察" cancelText="取消" confirmLoading={busy} okButtonProps={{ danger: true }}><p>清除这条观察的内容、引用和纠正；原面试回答保留。重复处理旧面试不会把它重新加入记忆。</p></Modal>
  </main>;
}
