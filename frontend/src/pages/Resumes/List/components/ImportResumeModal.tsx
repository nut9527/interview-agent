import { useEffect, useRef, useState } from 'react';
import { Modal, Upload, Button, Alert, Steps, message } from 'antd';
import { InboxOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { captureAccountScope } from '@/api/accountScope';
import { isRequestCanceled } from '@/api/ApiError';
import { resumeService } from '@/api/modules/resume';
import { useAuthStore } from '@/stores/authStore';
import { formValues } from '@/pages/Resumes/Editor/editorData';
import { readDraft, stageImport } from '@/pages/Resumes/Editor/resumeDraft';

export default function ImportResumeModal({ open, onClose }: { open: boolean; onClose: () => void }) {
  const navigate = useNavigate();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const request = useRef<AbortController>();
  const generation = useRef(0);
  const mounted = useRef(false);
  const cancel = () => { generation.current++; request.current?.abort(); };
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; cancel(); }; }, []);
  useEffect(() => { if (!open) { cancel(); setLoading(false); setError(''); } }, [open]);
  const close = () => { cancel(); setLoading(false); setError(''); onClose(); };
  const parse = async (file: File) => {
    cancel();
    const sequence = generation.current;
    const scope = captureAccountScope();
    const userId = useAuthStore.getState().user?.id;
    const abort = new AbortController();
    request.current = abort;
    const current = () => mounted.current && generation.current === sequence && scope.isCurrent();
    setLoading(true); setError('');
    try {
      const parsed = await resumeService.parseDocument(file, abort.signal);
      if (!current() || !userId) return;
      if (!stageImport(userId, formValues(parsed))) {
        setError('无法暂存解析结果，请允许浏览器使用会话存储后重试。文件尚未创建为简历。');
        return;
      }
      close();
      navigate('/resumes/edit?import=1', { state: { parsedImport: true } });
    } catch (error) {
      if (current() && !isRequestCanceled(error)) setError(error instanceof Error ? error.message : '解析失败，请重试或更换文件');
    } finally { if (current()) setLoading(false); }
  };
  return <Modal title="导入简历" open={open} onCancel={close} footer={<Button onClick={close}>取消</Button>} destroyOnClose>
    <Steps size="small" current={0} style={{ margin: '16px 0 24px' }} items={[{ title: '解析' }, { title: '检查纠正' }, { title: '确认保存' }]} />
    <Alert type="info" showIcon message="解析不会创建简历" description="解析后进入编辑页，检查并纠正字段，再点击确认保存。支持文字型 PDF、DOC、DOCX，最大 5 MB；PDF 最多 30 页，不支持扫描图片。" style={{ marginBottom: 20 }} />
    {error && <Alert type="error" showIcon message={error} style={{ marginBottom: 16 }} />}
    <Upload.Dragger accept=".pdf,.doc,.docx" multiple={false} showUploadList={false} disabled={loading}
      beforeUpload={file => {
        if (!/\.(pdf|doc|docx)$/i.test(file.name) || file.size > 5 * 1024 * 1024 || file.size === 0) {
          message.warning('请选择非空 PDF、DOC 或 DOCX 文件，大小不超过 5 MB'); return false;
        }
        const userId = useAuthStore.getState().user?.id;
        const scope = captureAccountScope();
        if (userId && readDraft(userId, 'import').draft) Modal.confirm({ title: '替换尚未保存的导入草稿？',
          content: '已有一份导入草稿。新文件解析成功后会替换它；取消后可继续检查原草稿。', okText: '解析新文件', cancelText: '取消',
          onOk: () => { if (mounted.current && scope.isCurrent()) void parse(file); } });
        else void parse(file);
        return false;
      }}>
      <p className="ant-upload-drag-icon"><InboxOutlined /></p>
      <p role="status">{loading ? '正在解析，可取消等待' : '点击或拖拽文件到此处'}</p>
    </Upload.Dragger>
    <Button type="link" onClick={() => { close(); navigate('/resumes/edit?import=1'); }}>继续检查已有导入草稿</Button>
  </Modal>;
}
