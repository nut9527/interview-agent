import { useEffect, useRef, useState } from 'react';
import { Alert, Button, Input, InputNumber, Modal, Select, Space } from 'antd';
import { Link } from 'react-router-dom';
import { knowledgeService } from '@/api/modules/knowledge';
import { captureAccountScope } from '@/api/accountScope';
import type { GenerationOptions, KnowledgeSelfTest, SelfTestSelection } from '@/types/knowledge';
import QuestionCard from './QuestionCard';
import styles from './index.module.css';

export default function GenerateSelfTests({ sources, onClose }: { sources: SelfTestSelection[]; onClose: () => void }) {
  const [count, setCount] = useState(3), [questions, setQuestions] = useState<KnowledgeSelfTest[]>();
  const [generatedCount, setGeneratedCount] = useState(0);
  const [options, setOptions] = useState<GenerationOptions>({ intent: 'COMPREHENSIVE', difficulty: 'INTERMEDIATE', focus: '' });
  const [busy, setBusy] = useState(false), [error, setError] = useState('');
  const request = useRef<AbortController>();
  useEffect(() => () => request.current?.abort(), []);
  const generate = async () => {
    if (request.current) return;
    const controller = new AbortController(), scope = captureAccountScope(); request.current = controller; setBusy(true); setError('');
    try { const result = await knowledgeService.generate(sources, count, controller.signal, options); if (scope.isCurrent() && !controller.signal.aborted) { setQuestions(result); setGeneratedCount(result.length); } }
    catch (cause) { if (scope.isCurrent() && !controller.signal.aborted) setError(cause instanceof Error ? cause.message : '生成失败'); }
    finally { if (scope.isCurrent() && !controller.signal.aborted) setBusy(false); if (request.current === controller) request.current = undefined; }
  };
  return <Modal title="从资料生成面试练习题" open width={900} className={styles.importReview} onCancel={onClose} footer={<Space wrap><Link to="/knowledge/practice?tab=drafts"><Button>待确认题目</Button></Link><Link to="/knowledge/practice"><Button type="primary">查看我的题目</Button></Link></Space>}>
    <p>从全文跨章节选材；填写考察重点后优先检索相关内容。指定片段时只使用所选范围。所有题目保留版本与原文依据，需逐题核对。</p>
    {!questions && <div className={styles.generationForm}>
      <label>考察方向<Select aria-label="考察方向" value={options.intent} disabled={busy} onChange={intent => setOptions({ ...options, intent })} options={[{ value: 'COMPREHENSIVE', label: '综合面试 · 场景、原理与取舍' }, { value: 'PRACTICAL', label: '实战应用 · 设计、排错与验证' }, { value: 'FUNDAMENTALS', label: '机制理解 · 因果与常见误区' }]} /></label>
      <label>难度<Select aria-label="出题难度" value={options.difficulty} disabled={busy} onChange={difficulty => setOptions({ ...options, difficulty })} options={[{ value: 'BASIC', label: '入门 · 单一机制应用' }, { value: 'INTERMEDIATE', label: '进阶 · 场景分析与权衡' }, { value: 'ADVANCED', label: '深入 · 多机制与边界验证' }]} /></label>
      <label className={styles.generationFocus}>考察重点（选填）<Input.TextArea aria-label="考察重点" rows={3} maxLength={300} showCount disabled={busy} value={options.focus} onChange={event => setOptions({ ...options, focus: event.target.value })} placeholder="例如：Redis 持久化。重点考察故障恢复时如何权衡数据丢失、性能与恢复时间，避免纯定义题。" /></label>
      <p className={styles.generationFocus}>综合面试默认至少 80% 考察综合与实战；资料不足时少出题，避免凑题。</p>
      <Space wrap className={styles.generationFocus}><span>题目上限</span><InputNumber aria-label="生成题数" min={1} max={5} value={count} disabled={busy} onChange={value => setCount(value || 1)} /><Button type="primary" loading={busy} onClick={() => void generate()}>生成候选题</Button></Space>
    </div>}
    {error && <Alert type="error" message={error} />}
    {questions?.length === 0 && <Alert type="info" message={generatedCount ? '本次候选题已处理完成，可到「我的题目」查看。' : '资料不足以支持当前考察要求，请调整重点、补充资料或选择其他片段。'} action={!generatedCount && <Button onClick={() => setQuestions(undefined)}>调整要求</Button>} />}
    {questions?.map(value => <QuestionCard key={value.id} value={value} onChanged={() => setQuestions(previous => previous?.filter(q => q.id !== value.id))} />)}
  </Modal>;
}
