import { useEffect, useState } from 'react';
import { Alert, Button, Checkbox, Input, Select, Space } from 'antd';
import { Link } from 'react-router-dom';
import { knowledgeService } from '@/api/modules/knowledge';
import { captureAccountScope } from '@/api/accountScope';
import type { FeedbackReason, KnowledgeSummary } from '@/types/knowledge';
import styles from './index.module.css';

export const feedbackLabels: Record<FeedbackReason, string> = { UNRESOLVED: '没有解决问题', UNSUPPORTED_CITATION: '引用不支持结论', MISSING_MATERIAL: '漏掉相关资料' };
export default function AnswerFeedback({ id }: { id: string }) {
  const [reasons, setReasons] = useState<FeedbackReason[]>([]), [comment, setComment] = useState(''), [missing, setMissing] = useState<string[]>([]);
  const [choices, setChoices] = useState<KnowledgeSummary[]>([]), [error, setError] = useState(''), [saved, setSaved] = useState(false), [busy, setBusy] = useState(true), [retry, setRetry] = useState(0);
  useEffect(() => {
    const controller = new AbortController(), scope = captureAccountScope(); setBusy(true); setError('');
    void Promise.all([knowledgeService.feedback(id, controller.signal), knowledgeService.choices(controller.signal)]).then(([feedback, documents]) => {
      if (!scope.isCurrent() || controller.signal.aborted) return;
      setReasons(feedback.reasons); setComment(feedback.comment); setMissing(feedback.missingSources.filter(s => s.available).map(s => s.documentId)); setChoices(documents.items); setSaved(feedback.reasons.length > 0);
    }).catch(cause => { if (scope.isCurrent() && !controller.signal.aborted) setError(cause instanceof Error ? cause.message : '反馈读取失败'); })
      .finally(() => { if (scope.isCurrent() && !controller.signal.aborted) setBusy(false); });
    return () => controller.abort();
  }, [id, retry]);
  const save = async () => {
    if (busy) return;
    const scope = captureAccountScope(); setBusy(true); setError('');
    try { await knowledgeService.saveFeedback(id, reasons, comment, reasons.includes('MISSING_MATERIAL') ? missing : []); if (scope.isCurrent()) setSaved(true); }
    catch (cause) { if (scope.isCurrent()) setError(cause instanceof Error ? cause.message : '反馈保存失败'); }
    finally { if (scope.isCurrent()) setBusy(false); }
  };
  return <details className={styles.feedback}><summary>{saved ? '已记录回答反馈 · 查看或修改' : '这次回答有问题？记录反馈'}</summary><div className={styles.learningFields}>
    <Checkbox.Group aria-label="回答反馈类型" disabled={busy} value={reasons} options={Object.entries(feedbackLabels).map(([value, label]) => ({ value, label }))} onChange={values => { setReasons(values as FeedbackReason[]); setSaved(false); }} />
    <Input.TextArea aria-label="反馈说明" disabled={busy} rows={2} maxLength={1000} placeholder="可指出具体结论、引用或遗漏，方便回看真实失败样例" value={comment} onChange={e => { setComment(e.target.value); setSaved(false); }} />
    {reasons.includes('MISSING_MATERIAL') && <Select mode="multiple" aria-label="遗漏的相关资料" maxCount={5} disabled={busy} placeholder="可选择遗漏的资料（最多 5 份）" value={missing} options={choices.map(d => ({ value: d.id, label: d.title }))} onChange={value => { setMissing(value); setSaved(false); }} />}
    <Space wrap><Button loading={busy} disabled={!reasons.length || saved} onClick={() => void save()}>保存回答反馈</Button><Link to="/knowledge/feedback">查看反馈样例</Link></Space>
    {saved && <Alert type="success" message="反馈已保存，关联这次问题、回答及来源版本。" />}{error && <Alert type="error" message={error} action={<Button onClick={() => setRetry(n => n + 1)}>重新读取</Button>} />}
  </div></details>;
}
