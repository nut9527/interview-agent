import { useEffect, useRef, useState } from 'react';
import { Alert, Button, Modal } from 'antd';
import { knowledgeService } from '@/api/modules/knowledge';
import { captureAccountScope } from '@/api/accountScope';
import { ApiError } from '@/api/ApiError';
import type { KnowledgeDocument } from '@/types/knowledge';
import styles from './index.module.css';
import PagedTextEditor from './PagedTextEditor';

export default function DocumentEditor({ document: initial, onClose, onSaved }: {
  document: KnowledgeDocument; onClose: () => void; onSaved: (document: KnowledgeDocument) => void;
}) {
  const [base, setBase] = useState(initial), [content, setContent] = useState(initial.content);
  const [latest, setLatest] = useState<KnowledgeDocument>(), [conflict, setConflict] = useState(false);
  const [error, setError] = useState(''), [busy, setBusy] = useState(false);
  const lock = useRef(false), mounted = useRef(true);
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);
  useEffect(() => {
    const prevent = (event: BeforeUnloadEvent) => { if (content !== initial.content) event.preventDefault(); };
    window.addEventListener('beforeunload', prevent); return () => window.removeEventListener('beforeunload', prevent);
  }, [content, initial.content]);
  const loadLatest = async () => {
    const scope = captureAccountScope();
    try { const result = await knowledgeService.find(initial.id); if (scope.isCurrent() && mounted.current) setLatest(result); }
    catch (cause) { if (scope.isCurrent() && mounted.current) setError(cause instanceof Error ? cause.message : '读取最新正文失败'); }
  };
  const save = async () => {
    if (lock.current) return; lock.current = true; setBusy(true); setError(''); const scope = captureAccountScope();
    try { const result = await knowledgeService.edit(initial.id, content, base.version); if (scope.isCurrent() && mounted.current) onSaved(result); }
    catch (cause) {
      if (scope.isCurrent() && mounted.current) {
        if (cause instanceof ApiError && cause.status === 409) { setConflict(true); await loadLatest(); }
        else setError(cause instanceof Error ? cause.message : '保存失败，请重试');
      }
    } finally { lock.current = false; if (scope.isCurrent() && mounted.current) setBusy(false); }
  };
  const close = () => {
    if (busy) return;
    if (content === initial.content) onClose();
    else Modal.confirm({ title: '放弃尚未保存的正文？', content: '关闭后，本次编辑不会保存。', okText: '放弃编辑', cancelText: '继续编辑', onOk: onClose });
  };
  return <Modal open width={960} title={`编辑正文 · ${initial.title}`} maskClosable={false} keyboard={false} onCancel={close}
    onOk={() => void save()} confirmLoading={busy} okText="保存为新版本" cancelText="取消"
    okButtonProps={{ disabled: conflict || !content.trim() || content.length > 2000000 || content === base.content }}>
    <p>基于 v{base.version} 编辑，保存后生成 v{base.version + 1}。历史版本和已有训练快照会保留。</p>
    {error && <Alert type="error" showIcon message={error} />}
    {conflict && <Alert type="warning" showIcon message="其他窗口已更新正文，本地编辑已保留。请核对后合并内容。"
      action={<Button disabled={busy} onClick={() => void loadLatest()}>重新读取最新版</Button>} />}
    <div className={latest ? styles.comparison : undefined}>
      <label>本地编辑<PagedTextEditor label="编辑正文" rows={18} value={content} disabled={busy} onChange={setContent} /></label>
      {latest && <section aria-label="服务器最新正文"><strong>服务器最新正文 · v{latest.version}</strong><pre className={styles.versionText}>{latest.content}</pre>
        <Button onClick={() => { setBase(latest); setLatest(undefined); setConflict(false); }}>已核对，以 v{latest.version} 为基准继续编辑</Button>
        <p>本地文字会保留，请先合并需要的内容，再保存新版本。</p>
      </section>}
    </div>
  </Modal>;
}
