import { useEffect, useRef, useState } from 'react';
import { Alert, Button, Form, Input, Modal, Select, Space, message } from 'antd';
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import { targetJobService } from '@/api/modules/targetJob';
import { captureAccountScope } from '@/api/accountScope';
import type { TargetJob, TargetJobInput } from '@/types/targetJob';
import styles from './index.module.css';

export const kindOptions = [{ value: 'MUST', label: '必须要求' }, { value: 'BONUS', label: '加分项' }, { value: 'UNKNOWN', label: '未知 / 待确认' }];

export default function TargetJobEditor({ job, onSaved, onClose }: {
  job: TargetJob | null; onSaved: (job: TargetJob) => void; onClose: () => void;
}) {
  const [form] = Form.useForm<TargetJobInput>();
  const [busy, setBusy] = useState<'extract' | 'save' | null>(null);
  const [dirty, setDirty] = useState(false);
  const [reviewedJd, setReviewedJd] = useState(job?.jdText ?? '');
  const [warning, setWarning] = useState('');
  const [error, setError] = useState('');
  const pending = useRef<AbortController | null>(null);
  const active = useRef(true);
  const jdText = Form.useWatch('jdText', form) as string | undefined;
  const stale = jdText !== reviewedJd;
  useEffect(() => { active.current = true; return () => { active.current = false; pending.current?.abort(); }; }, []);
  useEffect(() => {
    const leave = (event: BeforeUnloadEvent) => { if (dirty) { event.preventDefault(); event.returnValue = ''; } };
    window.addEventListener('beforeunload', leave);
    return () => window.removeEventListener('beforeunload', leave);
  }, [dirty]);

  const extract = async () => {
    if (pending.current || !active.current) return;
    const scope = captureAccountScope();
    try { await form.validateFields(['jdText']); } catch { return; }
    if (pending.current || !active.current || !scope.isCurrent()) return;
    const jd = form.getFieldValue('jdText') as string;
    const request = new AbortController();
    pending.current = request; setBusy('extract'); setError('');
    try {
      const result = await targetJobService.extract(jd, request.signal);
      if (request.signal.aborted || !scope.isCurrent()) return;
      form.setFieldValue('requirements', result.requirements);
      setReviewedJd(jd); setDirty(true); setWarning(result.warnings.join(' '));
    } catch (cause) {
      if (!request.signal.aborted && scope.isCurrent()) setError(cause instanceof Error ? cause.message : '提取失败，请重试');
    } finally {
      if (!request.signal.aborted && scope.isCurrent()) { pending.current = null; setBusy(null); }
    }
  };
  const confirmExtract = () => {
    const scope = captureAccountScope();
    if ((form.getFieldValue('requirements') as unknown[] | undefined)?.length) {
      Modal.confirm({ title: '重新提取将替换当前要求', content: '已纠正的分类和检索词也会被替换。', okText: '重新提取', cancelText: '保留当前要求', onOk: () => { if (scope.isCurrent()) return extract(); } });
    } else void extract();
  };
  const close = () => {
    if (!dirty) { onClose(); return; }
    Modal.confirm({ title: '放弃未保存的目标岗位修改？', okText: '放弃修改', okType: 'danger', cancelText: '继续编辑', onOk: onClose });
  };
  const save = async (values: TargetJobInput) => {
    if (pending.current || stale || !active.current) return;
    const scope = captureAccountScope();
    const request = new AbortController(); pending.current = request; setBusy('save'); setError('');
    try {
      const saved = await targetJobService.save(job?.id, { ...values, revision: job?.revision ?? 0,
        company: values.company ?? '', sourceName: values.sourceName ?? '', sourceUrl: values.sourceUrl ?? '',
        deadline: values.deadline || null, requirements: values.requirements ?? [] }, request.signal);
      if (!request.signal.aborted && scope.isCurrent()) { message.success('目标岗位已保存'); onSaved(saved); }
    } catch (cause) {
      if (!request.signal.aborted && scope.isCurrent()) setError(cause instanceof Error ? cause.message : '保存失败，请核对列表后重试');
    } finally {
      if (!request.signal.aborted && scope.isCurrent()) { pending.current = null; setBusy(null); }
    }
  };

  return <Modal open title={job ? '编辑目标岗位' : '添加我的目标岗位'} width={900} onCancel={close} maskClosable={false}
    closable={!busy} keyboard={!busy} footer={null}>
    <Form form={form} layout="vertical" initialValues={job ?? { requirements: [], jdText: '' }} disabled={Boolean(busy)}
      onValuesChange={() => setDirty(true)} onFinish={values => void save(values)}>
      <div className={styles.formGrid}>
        <Form.Item name="title" label="岗位名称" rules={[{ required: true, whitespace: true, max: 160 }]}><Input maxLength={160} placeholder="例如：Java 后端开发实习生" /></Form.Item>
        <Form.Item name="company" label="公司"><Input maxLength={160} placeholder="暂不清楚可留空" /></Form.Item>
        <Form.Item name="sourceName" label="来源"><Input maxLength={160} placeholder="官网、招聘平台或内推" /></Form.Item>
        <Form.Item name="sourceUrl" label="来源链接" rules={[{ validator: (_, value: string | undefined) => {
          if (!value?.trim()) return Promise.resolve();
          try { const url = new URL(value); if (['http:', 'https:'].includes(url.protocol) && !url.username && !url.password) return Promise.resolve(); } catch { /* invalid URL */ }
          return Promise.reject(new Error('请输入完整的 HTTP/HTTPS 链接，不能包含凭据'));
        } }]}><Input maxLength={1000} placeholder="https://…" /></Form.Item>
        <Form.Item name="deadline" label="投递截止日期"><Input type="date" /></Form.Item>
      </div>
      <Form.Item name="jdText" label="粘贴完整 JD" rules={[{ required: true, whitespace: true, max: 20000 }]}>
        <Input.TextArea rows={8} showCount maxLength={20000} placeholder={'任职要求：\n熟悉 Java 和 MySQL\n加分项：\n有 Docker 部署经验优先'} />
      </Form.Item>
      <div className={styles.sectionHead}><h3>核对岗位要求</h3><Button onClick={confirmExtract} loading={busy === 'extract'}>从 JD 提取要求</Button></div>
      <p className={styles.hint}>可纠正分类和文字、增删要求。检索词用于定位简历原文；学历、年限等条件仍需人工核对。</p>
      {stale && <Alert type="info" showIcon message="JD 已变化，请提取并核对要求后保存。" />}
      {warning && <Alert type="warning" showIcon message={warning} />}
      <Form.List name="requirements">{(fields, { add, remove }) => <>
        {fields.map(field => <section className={styles.requirementEdit} key={field.key}>
          <Form.Item name={[field.name, 'id']} hidden><Input /></Form.Item>
          <Form.Item name={[field.name, 'sourceText']} hidden><Input /></Form.Item>
          <div className={styles.requirementHead}>
            <Form.Item name={[field.name, 'kind']} label={`要求 ${field.name + 1} · 分类`} rules={[{ required: true }]}><Select options={kindOptions} /></Form.Item>
            <Button disabled={Boolean(busy)} danger type="text" icon={<DeleteOutlined />} aria-label={`删除要求 ${field.name + 1}`} onClick={() => { remove(field.name); setDirty(true); }} />
          </div>
          <Form.Item name={[field.name, 'text']} label="要求内容" rules={[{ required: true, whitespace: true, max: 1000 }]}><Input.TextArea autoSize={{ minRows: 2, maxRows: 5 }} maxLength={1000} /></Form.Item>
          <Form.Item name={[field.name, 'keywords']} label="关联简历的检索词（输入后按回车，可留空）" rules={[{ validator: (_, values: string[] = []) =>
            values.length <= 20 && values.every(v => v.trim().length > 0 && v.length <= 80) ? Promise.resolve() : Promise.reject(new Error('最多 20 个词，每个 1–80 字')) }]}>
            <Select mode="tags" tokenSeparators={['，', ',']} open={false} placeholder="Java、MySQL、本科…" />
          </Form.Item>
          {form.getFieldValue(['requirements', field.name, 'sourceText']) && <details className={styles.source}><summary>提取时的 JD 原文</summary><p>{form.getFieldValue(['requirements', field.name, 'sourceText']) as string}</p></details>}
        </section>)}
        <Button disabled={Boolean(busy) || fields.length >= 100} icon={<PlusOutlined />} onClick={() => {
          add({ id: crypto.randomUUID(), text: '', kind: 'UNKNOWN', keywords: [], sourceText: null }); setDirty(true);
        }}>手动补充要求</Button>
      </>}</Form.List>
      {error && <Alert className={styles.feedback} type="error" showIcon message={error} description="当前输入已保留。若保存超时，请先核对列表，避免重复创建。" />}
      <div className={styles.editorFooter}><Space><Button disabled={Boolean(busy)} onClick={close}>取消</Button><Button type="primary" htmlType="submit" loading={busy === 'save'} disabled={Boolean(busy) || stale}>保存目标岗位</Button></Space></div>
    </Form>
  </Modal>;
}
