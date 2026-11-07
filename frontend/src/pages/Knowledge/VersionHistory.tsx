import { useEffect, useRef, useState } from 'react';
import { Alert, Button, Drawer, Empty, Pagination, Spin, Tag } from 'antd';
import { knowledgeService } from '@/api/modules/knowledge';
import { captureAccountScope } from '@/api/accountScope';
import { ApiError, isRequestCanceled } from '@/api/ApiError';
import type { KnowledgeDocument, KnowledgeSummary } from '@/types/knowledge';
import styles from './index.module.css';

export default function VersionHistory({ id, onClose, onRestored }: {
  id: string; onClose: () => void; onRestored: (document: KnowledgeDocument) => void;
}) {
  const [rows, setRows] = useState<KnowledgeSummary[]>([]), [total, setTotal] = useState(0), [page, setPage] = useState(1);
  const [current, setCurrent] = useState<KnowledgeDocument>(), [preview, setPreview] = useState<KnowledgeDocument>();
  const [selected, setSelected] = useState<number>(), [error, setError] = useState('');
  const [loading, setLoading] = useState(true), [reading, setReading] = useState(false), [busy, setBusy] = useState(false), [reload, setReload] = useState(0);
  const lock = useRef(false), mounted = useRef(true);
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);
  useEffect(() => {
    const request = new AbortController(), scope = captureAccountScope(); setLoading(true);
    void Promise.all([knowledgeService.versions(id, page, request.signal), knowledgeService.find(id, request.signal)]).then(([versions, document]) => {
      if (scope.isCurrent() && !request.signal.aborted) { setRows(versions.items); setTotal(versions.total); setCurrent(document); }
    }).catch(cause => { if (scope.isCurrent() && !request.signal.aborted && !isRequestCanceled(cause)) setError(cause instanceof Error ? cause.message : '读取失败'); })
      .finally(() => { if (scope.isCurrent() && !request.signal.aborted) setLoading(false); });
    return () => request.abort();
  }, [id, page, reload]);
  useEffect(() => {
    if (!selected) return;
    const request = new AbortController(), scope = captureAccountScope(); setReading(true); setPreview(undefined);
    void knowledgeService.version(id, selected, request.signal).then(result => { if (scope.isCurrent() && !request.signal.aborted) setPreview(result); })
      .catch(cause => { if (scope.isCurrent() && !request.signal.aborted && !isRequestCanceled(cause)) setError(cause instanceof Error ? cause.message : '预览失败'); })
      .finally(() => { if (scope.isCurrent() && !request.signal.aborted) setReading(false); });
    return () => request.abort();
  }, [id, selected]);
  const restore = async () => {
    if (!preview || !current || lock.current) return; lock.current = true; setBusy(true); setError(''); const scope = captureAccountScope();
    try { const result = await knowledgeService.restore(id, preview.version, current.version); if (scope.isCurrent() && mounted.current) onRestored(result); }
    catch (cause) { if (scope.isCurrent() && mounted.current) {
      if (cause instanceof ApiError && cause.status === 409) { setError('其他窗口已更新正文。本次未恢复，请刷新历史并核对最新版后重新选择。'); setSelected(undefined); setPreview(undefined); setCurrent(undefined); }
      else setError(cause instanceof Error ? cause.message : '恢复失败，请重试');
    } } finally { lock.current = false; if (scope.isCurrent() && mounted.current) setBusy(false); }
  };
  return <Drawer open title="正文版本历史" width={760} maskClosable={!busy} keyboard={!busy} onClose={() => { if (!busy) onClose(); }}>
    <p>选择版本预览。恢复只复制旧正文，并产生新版本，完整修订历史会保留。</p>
    {error && <Alert type="error" showIcon message={error} action={<Button onClick={() => { setError(''); setReload(n => n + 1); }}>刷新历史</Button>} />}
    {loading ? <Spin /> : rows.length ? <div className={styles.versionList}>{rows.map(row => <Button key={row.version} disabled={busy} aria-pressed={selected === row.version} onClick={() => setSelected(row.version)}>
      v{row.version} · {new Date(row.updatedAt).toLocaleString()}{row.version === current?.version ? ' · 当前版本' : ''}
    </Button>)}</div> : <Empty description="暂无历史版本" />}
    {total > 20 && <Pagination simple current={page} total={total} pageSize={20} disabled={busy} onChange={setPage} />}
    {reading ? <Spin /> : preview && <section aria-label="历史正文预览"><h3>{preview.title} <Tag>v{preview.version}</Tag></h3><pre className={styles.versionText}>{preview.content}</pre>
      <Button type="primary" loading={busy} disabled={loading || !current || preview.version === current.version} onClick={() => void restore()}>恢复此正文为 v{(current?.version ?? 0) + 1}</Button>
    </section>}
  </Drawer>;
}
