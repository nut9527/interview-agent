import { useEffect, useRef, useState } from 'react';
import { Alert, Button, Modal, Select } from 'antd';
import { Link } from 'react-router-dom';
import { captureAccountScope } from '@/api/accountScope';
import { isRequestCanceled } from '@/api/ApiError';
import { interviewLearning, type DirectTrainingSource, type TrainingTask } from '@/api/modules/interviewLearning';

type Source = { kind: 'QUESTION'; id: string; version: number } | { kind: 'KNOWLEDGE'; id: string; version: number };
export default function AddSourceTask({ source }: { source: Source }) {
  const [open, setOpen] = useState(false), [busy, setBusy] = useState(false), [saved, setSaved] = useState(false), [error, setError] = useState('');
  const [topic, setTopic] = useState<NonNullable<TrainingTask['trainingTopic']>>('DATABASE');
  const lifetime = useRef<AbortController>(), active = useRef(false), keys = useRef(new Map<string, string>());
  useEffect(() => { const controller = new AbortController(); lifetime.current = controller; return () => controller.abort(); }, []);
  const add = async () => {
    const signal = lifetime.current?.signal, scope = captureAccountScope();
    if (!signal || signal.aborted || active.current) return;
    active.current = true; setBusy(true); setError('');
    const body: DirectTrainingSource = source.kind === 'QUESTION'
      ? { sourceType: 'QUESTION', questionId: source.id, questionVersion: source.version }
      : { sourceType: 'KNOWLEDGE', documentId: source.id, documentVersion: source.version, trainingTopic: topic };
    const signature = JSON.stringify(body);
    if (!keys.current.has(signature)) keys.current.set(signature, crypto.randomUUID());
    try { await interviewLearning.addSource(body, keys.current.get(signature)!, signal); if (scope.isCurrent() && !signal.aborted) setSaved(true); }
    catch (e) { if (scope.isCurrent() && !signal.aborted && !isRequestCanceled(e)) setError(e instanceof Error ? e.message : '加入计划失败'); }
    finally { active.current = false; if (scope.isCurrent() && !signal.aborted) setBusy(false); }
  };
  return <><Button onClick={() => { setOpen(true); setSaved(false); setError(''); }}>加入训练计划</Button>
    <Modal title="加入个人训练计划" open={open} onCancel={() => { if (!busy) setOpen(false); }} onOk={() => saved ? setOpen(false) : void add()}
      confirmLoading={busy} okText={saved ? '完成' : '确认加入'} cancelText="取消" closable={!busy} maskClosable={!busy}>
      {error && <Alert type="error" showIcon message={error} />}
      {saved ? <p>任务已保存，可在<Link to="/learning/goals">目标与训练计划</Link>中开始或继续。</p> : <>
        <p>固定当前来源 v{source.version}，建立三轮作答任务。任务可暂停，并在其他设备登录后继续。</p>
        {source.kind === 'KNOWLEDGE' && <><label htmlFor="task-training-topic">训练方向</label><Select id="task-training-topic" aria-label="训练方向" value={topic} onChange={setTopic} style={{ width: '100%' }} options={[
          { value: 'DATABASE', label: '数据库' }, { value: 'CONCURRENCY', label: '并发' }, { value: 'PERFORMANCE', label: '性能' }, { value: 'PROJECT', label: '项目深挖' },
        ]} /><p>开始前资料更新或删除时，需要重新核对来源；已开始的训练保留当时版本。</p></>}
      </>}
    </Modal></>;
}
