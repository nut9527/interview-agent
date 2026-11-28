import { useEffect, useRef, useState } from 'react';
import { Modal, Upload, Button, Alert, Steps, Typography, message } from 'antd';
import { InboxOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { captureAccountScope } from '@/api/accountScope';
import { ApiError, isRequestCanceled, toApiError } from '@/api/ApiError';
import { resumeImportNotice, resumeService } from '@/api/modules/resume';
import { useAuthStore } from '@/stores/authStore';
import { formValues } from '@/pages/Resumes/Editor/editorData';
import { readDraft, stageImport } from '@/pages/Resumes/Editor/resumeDraft';

export default function ImportResumeModal({ open, onClose }: { open: boolean; onClose: () => void }) {
  const navigate = useNavigate();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const request = useRef<AbortController>();
  const generation = useRef(0);
  const mounted = useRef(false);
  const cancel = () => {
    generation.current++;
    request.current?.abort();
  };
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      cancel();
    };
  }, []);
  useEffect(() => {
    if (!open) {
      cancel();
      setLoading(false);
      setError(null);
    }
  }, [open]);
  const close = () => {
    cancel();
    setLoading(false);
    setError(null);
    onClose();
  };
  const parse = async (file: File) => {
    cancel();
    const sequence = generation.current;
    const scope = captureAccountScope();
    const userId = useAuthStore.getState().user?.id;
    const abort = new AbortController();
    request.current = abort;
    const current = () => mounted.current && generation.current === sequence && scope.isCurrent();
    setLoading(true);
    setError(null);
    try {
      const parsed = await resumeService.parseDocument(file, abort.signal);
      if (!current() || !userId) return;
      if (
        !stageImport(userId, formValues(parsed.draft), {
          warnings: parsed.warnings,
          recognitionMode: parsed.recognitionMode,
        })
      ) {
        setError(new ApiError('无法暂存解析结果，请允许浏览器使用会话存储后重试。文件尚未创建为简历。'));
        return;
      }
      close();
      navigate('/resumes/edit?import=1', { state: { parsedImport: true } });
    } catch (error) {
      if (current() && !isRequestCanceled(error)) setError(toApiError(error));
    } finally {
      if (current()) setLoading(false);
    }
  };
  return (
    <Modal title="导入简历" open={open} onCancel={close} footer={<Button onClick={close}>取消</Button>} destroyOnHidden>
      <Steps
        size="small"
        current={0}
        style={{ margin: '16px 0 24px' }}
        items={[{ title: '识别文件' }, { title: '核对字段' }, { title: '确认保存' }]}
      />
      <Alert
        type="info"
        showIcon
        message="识别后可修改，确认保存时创建简历"
        description={resumeImportNotice}
        style={{ marginBottom: 20 }}
      />
      {error && (
        <Alert
          type="error"
          showIcon
          message={error.message}
          style={{ marginBottom: 16 }}
          description={
            (error.code !== undefined || error.requestId) && (
              <>
                {error.code !== undefined && <div>错误码：{error.code}</div>}
                {error.requestId && (
                  <div>
                    请求编号：<Typography.Text copyable>{error.requestId}</Typography.Text>
                  </div>
                )}
              </>
            )
          }
        />
      )}
      <Upload.Dragger
        accept=".pdf,.doc,.docx"
        multiple={false}
        showUploadList={false}
        disabled={loading}
        beforeUpload={(file) => {
          if (!/\.(pdf|doc|docx)$/i.test(file.name) || file.size > 5 * 1024 * 1024 || file.size === 0) {
            message.warning('请选择非空 PDF、DOC 或 DOCX 文件，大小不超过 5 MB');
            return false;
          }
          const userId = useAuthStore.getState().user?.id;
          const scope = captureAccountScope();
          const sequence = generation.current;
          if (userId && readDraft(userId, 'import').draft)
            Modal.confirm({
              title: '替换尚未保存的导入草稿？',
              content: '新文件识别成功后会替换已有草稿。',
              okText: '识别新文件',
              cancelText: '取消',
              onOk: () => {
                if (mounted.current && generation.current === sequence && scope.isCurrent()) void parse(file);
              },
            });
          else void parse(file);
          return false;
        }}
      >
        <p className="ant-upload-drag-icon">
          <InboxOutlined />
        </p>
        <p role="status">{loading ? '正在识别简历，可取消等待' : '点击或拖入简历文件'}</p>
        <p className="ant-upload-hint">文字型 PDF、DOC、DOCX，最大 5 MB，PDF 最多 30 页。</p>
      </Upload.Dragger>
      <Button
        type="link"
        onClick={() => {
          close();
          navigate('/resumes/edit?import=1');
        }}
      >
        继续检查已有导入草稿
      </Button>
    </Modal>
  );
}
