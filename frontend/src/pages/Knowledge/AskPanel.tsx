import { useEffect, useRef, useState } from 'react';
import { Alert, Button, Input, Select, Space, Tag } from 'antd';
import { Link, useSearchParams } from 'react-router-dom';
import { knowledgeService } from '@/api/modules/knowledge';
import { captureAccountScope } from '@/api/accountScope';
import type { KnowledgeSummary, KnowledgeAnswer, KnowledgeAnswerRecord } from '@/types/knowledge';
import AnswerView from './AnswerView';
import styles from './index.module.css';
export default function AskPanel({ documents, current, selectedIds }: { documents: KnowledgeSummary[]; current?: string; selectedIds: string[] }) {
  const [params, setParams] = useSearchParams(), parent = params.get('parent');
  const [ids, setIds] = useState<string[]>([]), [question, setQuestion] = useState('');
  const [turns, setTurns] = useState<Array<{ question: string; answer: KnowledgeAnswer }>>([]);
  const [restored, setRestored] = useState<KnowledgeAnswerRecord>();
  const [error, setError] = useState(''), [busy, setBusy] = useState(false), [restoring, setRestoring] = useState(false);
  const request = useRef<AbortController>();
  useEffect(() => () => request.current?.abort(), []);
  useEffect(() => { if (!turns.length) setIds(selectedIds.length <= 5 ? selectedIds.length ? selectedIds : current ? [current] : [] : []); }, [selectedIds, current, turns.length]);
  useEffect(() => {
    request.current?.abort(); request.current = undefined; setBusy(false); setTurns([]); setRestored(undefined); setError('');
  }, [current]);
  useEffect(() => {
    setRestored(undefined); setTurns([]); if (!parent) { setRestoring(false); return; }
    const controller = new AbortController(), scope = captureAccountScope(); setRestoring(true);
    void knowledgeService.answer(parent, controller.signal).then(record => {
      if (scope.isCurrent() && !controller.signal.aborted) { setRestored(record); setIds(record.documents.map(d => d.documentId)); setTurns([{ question: record.question, answer: record.answer }]); }
    }).catch(cause => { if (scope.isCurrent() && !controller.signal.aborted) setError(cause instanceof Error ? cause.message : '前文读取失败'); })
      .finally(() => { if (scope.isCurrent() && !controller.signal.aborted) setRestoring(false); });
    return () => controller.abort();
  }, [parent]);
  const ask = async () => {
    if (request.current) return;
    const controller = new AbortController(), scope = captureAccountScope(); request.current = controller;
    setBusy(true); setError(''); const submitted = question;
    try {
      const result = await knowledgeService.ask(submitted, ids, controller.signal, turns[turns.length - 1]?.answer.historyId);
      if (scope.isCurrent() && !controller.signal.aborted) { setTurns(previous => [...previous, { question: submitted, answer: result }]); setQuestion(''); }
    } catch (cause) { if (scope.isCurrent() && !controller.signal.aborted) setError(cause instanceof Error ? cause.message : '问答失败，请重试'); }
    finally { if (scope.isCurrent() && !controller.signal.aborted) setBusy(false); if (request.current === controller) request.current = undefined; }
  };
  return <section className={styles.results} aria-label="基于资料问答"><div className={styles.sectionHeading}><div><h2>基于资料问答</h2></div><Space wrap><Tag>每轮回答附来源</Tag><Link to="/knowledge/history">问答历史</Link><Link to="/knowledge/feedback">反馈样例</Link></Space></div>
    <Space direction="vertical" style={{ width: '100%' }}>
      <Select mode="multiple" aria-label="问答资料" style={{ width: '100%' }} placeholder="选择资料（最多 5 份）" maxCount={5} value={ids} disabled={busy || restoring || turns.length > 0} onChange={setIds} options={[...documents.map(d => ({ value: d.id, label: d.title })), ...(restored?.documents.filter(d => !documents.some(v => v.id === d.documentId)).map(d => ({ value: d.documentId, label: d.title })) || [])]} />
      {turns.map((turn, i) => <div className={styles.turn} key={turn.answer.historyId || i}><h3>{turn.question}</h3><AnswerView answer={turn.answer} sources={restored?.documents} /><Link to={'/knowledge/history?answer=' + encodeURIComponent(turn.answer.historyId || '')}>已保存 · 查看这次问答</Link></div>)}
      {!!turns.length && <Alert type="info" message="可继续追问“为什么”“它有什么边界”。沿用原资料版本，理解最近 6 轮前文；更换资料需开始新问题。" />}
      <Input.TextArea aria-label="资料问题" rows={2} maxLength={300} placeholder={turns.length ? '继续追问，例如：它有什么边界？' : '例如：笔记中的缓存过期策略是什么？'} value={question} disabled={busy || restoring} onChange={e => setQuestion(e.target.value)} />
      <Space wrap><Button type="primary" loading={busy || restoring} disabled={!ids.length || !question.trim() || !!(parent && !restored)} onClick={() => void ask()}>{turns.length ? '继续追问' : '检索并回答'}</Button>{!!turns.length && <Button disabled={busy} onClick={() => { const next = new URLSearchParams(params); next.delete('parent'); setParams(next); setTurns([]); setRestored(undefined); setError(''); }}>开始新问题</Button>}</Space>
      {error && <Alert type="error" message={error} />}
    </Space>
  </section>;
}
