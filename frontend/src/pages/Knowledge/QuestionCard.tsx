import { useState } from 'react';
import { Alert, Button, Checkbox, Input, Popconfirm, Space, Tag } from 'antd';
import { Link } from 'react-router-dom';
import { knowledgeService } from '@/api/modules/knowledge';
import { captureAccountScope } from '@/api/accountScope';
import type { KnowledgeSelfTest } from '@/types/knowledge';
import styles from './index.module.css';

export default function QuestionCard({ value, onChanged }: { value: KnowledgeSelfTest; onChanged: () => void }) {
  const [question, setQuestion] = useState(value.question), [checked, setChecked] = useState(false);
  const [busy, setBusy] = useState(false), [error, setError] = useState('');
  const run = async (remove = false) => {
    if (busy) return;
    const scope = captureAccountScope(); setBusy(true); setError('');
    try { if (remove) await knowledgeService.deleteQuestion(value.id); else await knowledgeService.confirmQuestion(value.id, question); if (scope.isCurrent()) onChanged(); }
    catch (cause) { if (scope.isCurrent()) setError(cause instanceof Error ? cause.message : '保存失败'); }
    finally { if (scope.isCurrent()) setBusy(false); }
  };
  const references = <><p><strong>考察点：</strong>{question.point}</p><ul>{question.referencePoints.map((point, i) => <li key={i}>{point}</li>)}</ul>
    {question.citations.map((source, i) => <blockquote key={i}>{value.sources.some(s => s.documentId === source.documentId && s.version === source.version && s.available)
      ? <Link to={'/knowledge?' + new URLSearchParams({ document: source.documentId, version: String(source.version), chunk: source.chunkId })}>{source.title} · v{source.version} · 第 {source.startLine}–{source.endLine} 行</Link>
      : <span>{source.title} · v{source.version} · 来源不可用</span>}<p>{source.quote}</p></blockquote>)}</>;
  return <article className={styles.questionCard}>
    <Space wrap><Tag color={value.confirmed ? 'green' : 'gold'}>{value.confirmed ? '个人题库' : '待逐题确认'}</Tag>{value.executionMode === 'mock' && <Tag>规则演示</Tag>}</Space>
    {value.confirmed ? <><h2>{question.stem}</h2><Input.TextArea aria-label="我的自测回答" rows={3} placeholder="先试着回答，再展开参考要点（作答仅保留在当前页面）" /><details><summary>核对参考要点与来源</summary>{references}</details></> : <div className={styles.learningFields}>
      <label>题干<Input.TextArea aria-label="自测题干" maxLength={600} value={question.stem} onChange={e => { setChecked(false); setQuestion({ ...question, stem: e.target.value }); }} /></label>
      <label>考察点<Input aria-label="自测考察点" maxLength={200} value={question.point} onChange={e => { setChecked(false); setQuestion({ ...question, point: e.target.value }); }} /></label>
      <label>参考要点（每行一项，最多 6 项）<Input.TextArea aria-label="自测参考要点" rows={4} value={question.referencePoints.join('\n')} onChange={e => { setChecked(false); setQuestion({ ...question, referencePoints: e.target.value.split('\n') }); }} /></label>
      <details open><summary>生成时的原文依据</summary>{references}</details>
      <Checkbox checked={checked} disabled={busy} onChange={e => setChecked(e.target.checked)}>已逐题核对题干、考察点、参考要点与来源</Checkbox>
      <Button type="primary" loading={busy} disabled={!checked || !question.stem.trim() || !question.point.trim() || question.referencePoints.length > 6 || question.referencePoints.some(p => !p.trim() || p.length > 500)} onClick={() => void run()}>确认并保存到我的题目</Button>
    </div>}
    {error && <Alert type="error" message={error} />}
    <Popconfirm title={value.confirmed ? '删除这道个人自测题？' : '丢弃这道候选题？'} onConfirm={() => run(true)} okText="确认删除" cancelText="取消"><Button type="text" danger disabled={busy}>{value.confirmed ? '删除自测题' : '丢弃候选题'}</Button></Popconfirm>
  </article>;
}
