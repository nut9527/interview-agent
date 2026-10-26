import { useEffect, useRef, useState } from 'react';
import { Alert, Button, Form, Input, Modal, Upload } from 'antd';
import { InboxOutlined } from '@ant-design/icons';
import { resumeService } from '@/api/modules/resume';
import { targetJobService } from '@/api/modules/targetJob';
import { captureAccountScope } from '@/api/accountScope';
import { isRequestCanceled } from '@/api/ApiError';
import type { ResumeProfile, ResumeProfileRequest } from '@/types/api.d';
import type { TargetJob } from '@/types/targetJob';
import styles from './setup.module.css';

function useImportRequest() {
  const pending = useRef<AbortController | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  useEffect(() => () => pending.current?.abort(), []);
  const run = async <T,>(work: (signal: AbortSignal) => Promise<T>, accept: (value: T) => void) => {
    if (pending.current) return;
    const scope = captureAccountScope();
    const request = new AbortController(); pending.current = request; setBusy(true); setError('');
    const current = () => !request.signal.aborted && scope.isCurrent();
    try { const result = await work(request.signal); if (current()) accept(result); }
    catch (cause) { if (current() && !isRequestCanceled(cause)) setError(cause instanceof Error ? cause.message : '导入失败，请重试'); }
    finally { if (current()) { pending.current = null; setBusy(false); } }
  };
  return { run, busy, error, setError };
}

type ResumeFields = { ownerName: string; targetRole: string; summary: string } & Record<'skills' | 'educationExperiences' | 'projectExperiences' | 'internshipExperiences' | 'certificates', string>;
const sections = [
  ['skills', '技能', 3], ['educationExperiences', '教育经历', 3], ['projectExperiences', '项目经历与个人贡献', 6],
  ['internshipExperiences', '实习 / 工作经历', 4], ['certificates', '证书（可选）', 2],
] as const;
const lines = (value: string | undefined) => (value ?? '').split('\n').map(line => line.trim()).filter(Boolean);

export function ResumeImport({ onClose, onSaved }: { onClose: () => void; onSaved: (resume: ResumeProfile) => void }) {
  const [form] = Form.useForm<ResumeFields>();
  const [parsed, setParsed] = useState<ResumeProfileRequest | null>(null);
  const request = useImportRequest();
  const importFile = (file: File) => {
    if (!/\.(pdf|doc|docx)$/i.test(file.name) || file.size === 0 || file.size > 5 * 1024 * 1024) {
      request.setError('请选择非空 PDF、DOC 或 DOCX 文件，大小不超过 5 MB。'); return false;
    }
    void request.run(signal => resumeService.parseDocument(file, signal), result => {
      setParsed(result);
      form.setFieldsValue({ ownerName: result.ownerName, targetRole: result.targetRole, summary: result.summary ?? '',
        ...Object.fromEntries(sections.map(([key]) => [key, (result[key] ?? []).join('\n')])) });
    });
    return false;
  };
  const save = (values: ResumeFields) => {
    const body: ResumeProfileRequest = { ownerName: values.ownerName.trim(), targetRole: values.targetRole.trim(),
      summary: values.summary?.trim() ?? '', templateKey: 'classic',
      ...Object.fromEntries(sections.map(([key]) => [key, lines(values[key])])) };
    void request.run(signal => resumeService.create(body, signal), onSaved);
  };
  return <Modal open title="导入个人简历" width={760} onCancel={onClose} maskClosable={false} closable={!request.busy} keyboard={!request.busy} footer={null}>
    <p className={styles.hint}>上传后核对识别内容，确认保存即可用于面试；也可以直接填写。支持文字型 PDF、DOC、DOCX，不支持扫描图片。</p>
    <Upload.Dragger accept=".pdf,.doc,.docx" multiple={false} showUploadList={false} disabled={request.busy} beforeUpload={importFile}>
      <p className="ant-upload-drag-icon"><InboxOutlined /></p><p>{request.busy ? '正在处理…' : '点击或拖入简历文件（最多 5 MB）'}</p>
    </Upload.Dragger>
    {parsed && <Alert type="info" showIcon message="识别完成，请逐项核对后保存。解析尚未创建简历。" className={styles.feedback} />}
    <Form form={form} layout="vertical" className={styles.importForm} onFinish={save} disabled={request.busy}>
      <div className={styles.formRow}><Form.Item name="ownerName" label="姓名 / 简历称呼" rules={[{ required: true, whitespace: true, max: 80 }]}><Input maxLength={80} /></Form.Item>
        <Form.Item name="targetRole" label="求职方向" rules={[{ required: true, whitespace: true, max: 120 }]}><Input maxLength={120} placeholder="例如：Java 后端工程师" /></Form.Item></div>
      <Form.Item name="summary" label="个人简介" rules={[{ max: 4000 }]}><Input.TextArea rows={3} maxLength={4000} showCount /></Form.Item>
      {sections.map(([key, label, rows]) => <Form.Item key={key} name={key} label={label} extra="每行一项，保留真实经历与个人贡献。"
        rules={[{ validator: (_, value: string | undefined) => lines(value).length <= 100 && lines(value).every(item => item.length <= 3000)
          ? Promise.resolve() : Promise.reject(new Error('最多 100 项，每项不超过 3000 字；请精简后保存。')) }]}>
        <Input.TextArea rows={rows} placeholder={key === 'skills' ? 'Java\nMySQL\nSpring Boot' : undefined} />
      </Form.Item>)}
      {request.error && <Alert type="error" showIcon message={request.error} className={styles.feedback} />}
      <div className={styles.dialogActions}><Button disabled={request.busy} onClick={onClose}>取消</Button><Button type="primary" htmlType="submit" loading={request.busy}>确认保存并用于面试</Button></div>
    </Form>
  </Modal>;
}

type JobFields = { title: string; company?: string; jdText: string };
export function JobImport({ onClose, onSaved }: { onClose: () => void; onSaved: (job: TargetJob) => void }) {
  const [form] = Form.useForm<JobFields>();
  const request = useImportRequest();
  const save = (values: JobFields) => void request.run(signal => targetJobService.save(undefined, {
    revision: 0, title: values.title.trim(), company: values.company?.trim() ?? '', jdText: values.jdText.trim(),
    sourceName: '面试导入', sourceUrl: '', deadline: null, requirements: [],
  }, signal), onSaved);
  const importFile = (file: File) => {
    if (!/\.(txt|md)$/i.test(file.name) || file.size === 0 || file.size > 100 * 1024) {
      request.setError('请选择非空 UTF-8 TXT 或 Markdown 文件，大小不超过 100 KB。'); return false;
    }
    void request.run(async () => {
      const text = await file.text();
      if (!text.trim() || text.length > 20000) throw new Error('岗位说明须为 1–20000 字，请核对文件内容。');
      return text;
    }, text => form.setFieldValue('jdText', text));
    return false;
  };
  return <Modal open title="导入目标岗位" width={720} onCancel={onClose} maskClosable={false} closable={!request.busy} keyboard={!request.busy} footer={null}>
    <p className={styles.hint}>粘贴真实招聘 JD，或导入 UTF-8 TXT / Markdown 文件。只需岗位信息，保存后直接用于模拟面试。</p>
    <Form form={form} layout="vertical" onFinish={save} disabled={request.busy}>
      <div className={styles.formRow}><Form.Item name="title" label="岗位名称" rules={[{ required: true, whitespace: true, max: 160 }]}><Input maxLength={160} placeholder="例如：Java 后端开发实习生" /></Form.Item>
        <Form.Item name="company" label="公司（可选）"><Input maxLength={160} /></Form.Item></div>
      <Upload accept=".txt,.md" showUploadList={false} beforeUpload={importFile} disabled={request.busy}><Button loading={request.busy}>从文件读取 JD</Button></Upload>
      <Form.Item name="jdText" label="完整岗位说明 / JD" rules={[{ required: true, whitespace: true, max: 20000 }]} className={styles.importForm}>
        <Input.TextArea rows={11} maxLength={20000} showCount placeholder={'粘贴工作职责、任职要求和技术栈…'} />
      </Form.Item>
      {request.error && <Alert type="error" showIcon message={request.error} className={styles.feedback} />}
      <div className={styles.dialogActions}><Button disabled={request.busy} onClick={onClose}>取消</Button><Button type="primary" htmlType="submit" loading={request.busy}>保存岗位并用于面试</Button></div>
    </Form>
  </Modal>;
}
