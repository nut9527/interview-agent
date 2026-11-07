import { useEffect, useRef, useState } from 'react';
import { Alert, Checkbox, Input, Modal, Tag } from 'antd';
import { knowledgeService } from '@/api/modules/knowledge';
import { captureAccountScope } from '@/api/accountScope';
import { isRequestCanceled } from '@/api/ApiError';
import type { KnowledgeDocument, KnowledgeImport } from '@/types/knowledge';
import styles from './index.module.css';
import PagedTextEditor from './PagedTextEditor';

export default function ImportReview({ draft, jobId, onClose, onSaved }: {
  draft: KnowledgeImport; jobId: string; onClose: () => void; onSaved: (document: KnowledgeDocument) => void;
}) {
  const [title, setTitle] = useState(draft.title), [content, setContent] = useState(draft.content);
  const [checked, setChecked] = useState(false), [busy, setBusy] = useState(false), [error, setError] = useState('');
  const lock = useRef(false), mounted = useRef(true);
  useEffect(() => {
    mounted.current = true;
    const prevent = (event: BeforeUnloadEvent) => event.preventDefault();
    window.addEventListener('beforeunload', prevent);
    return () => { mounted.current = false; window.removeEventListener('beforeunload', prevent); };
  }, []);
  const valid = !!title.trim() && title.trim().length <= 160 && !!content.trim() && content.length <= 2000000 && !content.includes('\0');
  const save = async () => {
    if (lock.current || !checked || !valid) return;
    lock.current = true; setBusy(true); setError(''); const scope = captureAccountScope();
    try {
      const result = await knowledgeService.confirmJob(jobId, { ...draft, title, content });
      if (scope.isCurrent() && mounted.current) onSaved(result);
    } catch (cause) {
      if (scope.isCurrent() && mounted.current && !isRequestCanceled(cause)) setError(cause instanceof Error ? cause.message : '入库失败，请重试；核对内容已保留。');
    } finally { lock.current = false; if (scope.isCurrent() && mounted.current) setBusy(false); }
  };
  const discard = async () => {
    const scope = captureAccountScope(); setBusy(true);
    try { await knowledgeService.cancelImport(jobId); if (scope.isCurrent() && mounted.current) onClose(); }
    catch (cause) { if (scope.isCurrent() && mounted.current) setError(cause instanceof Error ? cause.message : '取消导入失败，请重试'); }
    finally { if (scope.isCurrent() && mounted.current) setBusy(false); }
  };
  const close = () => {
    if (busy) return;
    if (title === draft.title && content === draft.content) void discard();
    else Modal.confirm({ title: '放弃本次导入核对？', content: '修订的正文尚未入库，关闭后不会保存。', okText: '放弃导入', cancelText: '继续核对', onOk: discard });
  };
  return <Modal open width={1040} className={styles.importReview} title="导入前核对" maskClosable={false} keyboard={false} onCancel={close}
    onOk={() => void save()} confirmLoading={busy} okText="确认入库为 v1" cancelText="取消导入" cancelButtonProps={{ disabled: busy }} okButtonProps={{ disabled: !checked || !valid }}
    footer={(buttons) => <div className={styles.importFooter}>
      <Checkbox checked={checked} disabled={busy || !valid} onChange={event => setChecked(event.target.checked)}>我已核对文序、文字与完整性，确认以上正文入库</Checkbox>
      <div className={styles.importActions}>{buttons}</div>
    </div>}>
    <div className={styles.importMeta}><Tag color="green">待核对 · 尚未保存</Tag><span>来源：{draft.sourceName}</span></div>
    <p>对照原文件调整文序、修正乱码、补全缺失内容。保存后的分段、搜索、引用和专项训练都使用下方确认正文。</p>
    {draft.warnings.map((warning, i) => <Alert key={i} type="warning" showIcon message={warning} className={styles.importWarning} />)}
    {error && <Alert type="error" showIcon message={error} />}
    <div className={styles.importFields}>
      <label>资料标题<Input aria-label="导入资料标题" maxLength={160} value={title} disabled={busy} onChange={event => { setTitle(event.target.value); setChecked(false); }} /></label>
      <div className={styles.importBodyEditor}><span>确认正文</span><PagedTextEditor label="确认正文" value={content} disabled={busy} onChange={value => { setContent(value); setChecked(false); }} /></div>
      {content.length > 2000000 && <Alert type="error" message="正文超过 200 万字，请删减或拆分后再保存。" />}
    </div>
  </Modal>;
}
