import { useEffect, useRef, useState } from 'react';
import { Alert, Button, Progress, Space, Tag } from 'antd';
import { knowledgeService } from '@/api/modules/knowledge';
import { captureAccountScope } from '@/api/accountScope';
import { isRequestCanceled } from '@/api/ApiError';
import type { KnowledgeImport, KnowledgeImportJob } from '@/types/knowledge';
import styles from './index.module.css';

export default function ImportQueue({ newest, onReview }: { newest?: string; onReview: (id: string, draft: KnowledgeImport) => void }) {
  const [jobs, setJobs] = useState<KnowledgeImportJob[]>([]), [reload, setReload] = useState(0), [error, setError] = useState('');
  const [busy, setBusy] = useState(false); const reviewed = useRef(new Set<string>());
  const reviewRef = useRef(onReview); reviewRef.current = onReview;
  useEffect(() => {
    const scope = captureAccountScope(), controller = new AbortController(); let timer: ReturnType<typeof setTimeout>;
    const load = async () => {
      try {
        const rows = await knowledgeService.importJobs(controller.signal);
        if (!scope.isCurrent() || controller.signal.aborted) return;
        setJobs(rows); setError('');
        const ready = rows.find(job => job.id === newest && job.status === 'READY' && !reviewed.current.has(job.id));
        if (ready) {
          const draft = await knowledgeService.importResult(ready.id, controller.signal);
          if (scope.isCurrent() && !controller.signal.aborted) { reviewed.current.add(ready.id); reviewRef.current(ready.id, draft); }
        }
        if (rows.some(job => job.status === 'QUEUED' || job.status === 'RUNNING')) timer = setTimeout(() => void load(), 1500);
      } catch (cause) { if (scope.isCurrent() && !controller.signal.aborted && !isRequestCanceled(cause)) setError(cause instanceof Error ? cause.message : '读取导入进度失败，请刷新重试'); }
    };
    void load(); return () => { controller.abort(); clearTimeout(timer); };
  }, [newest, reload]);
  const act = async (job: KnowledgeImportJob, cancel = false) => {
    if (busy) return; setBusy(true); const scope = captureAccountScope();
    try {
      if (cancel) { await knowledgeService.cancelImport(job.id); reviewed.current.add(job.id); setReload(n => n + 1); }
      else { const draft = await knowledgeService.importResult(job.id); if (scope.isCurrent()) { reviewed.current.add(job.id); onReview(job.id, draft); } }
    } catch (cause) { if (scope.isCurrent() && !isRequestCanceled(cause)) setError(cause instanceof Error ? cause.message : '操作失败，请重试'); }
    finally { if (scope.isCurrent()) setBusy(false); }
  };
  return <section aria-label="文件导入进度">
    {error && <Alert type="error" showIcon message={error} action={<Button onClick={() => setReload(n => n + 1)}>刷新进度</Button>} />}
    {!!jobs.length && <div className={styles.results}><div className={styles.sectionHeading}><h2>文件导入</h2><Button onClick={() => setReload(n => n + 1)}>刷新进度</Button></div>
      <p>上传后在后台解析，可离开页面后回来核对。待核对正文保留 30 分钟，确认后才进入资料库。</p>
      {jobs.map(job => <article key={job.id} className={styles.importTask}>
        <Space wrap><strong>{job.sourceName}</strong><Tag>{({ QUEUED: '排队中', RUNNING: '正在解析', READY: '待核对', FAILED: '解析失败', CANCELED: '已取消', IMPORTED: '已入库' } as Record<string, string>)[job.status]}</Tag></Space>
        {job.status === 'RUNNING' && (job.totalPages > 0 ? <><Progress percent={Math.floor(job.completedPages * 100 / job.totalPages)} /><small>已解析 {job.completedPages} / {job.totalPages} 页</small></> : <p>正在提取正文，请稍候；DOCX 按内容处理。</p>)}
        {job.message && <p role={job.status === 'FAILED' ? 'alert' : undefined}>{job.message}</p>}
        <Space wrap>{job.status === 'READY' && <Button type="primary" disabled={busy} onClick={() => void act(job)}>核对正文</Button>}
          {['QUEUED', 'RUNNING', 'READY'].includes(job.status) && <Button disabled={busy} onClick={() => void act(job, true)}>取消导入</Button>}</Space>
      </article>)}
    </div>}
  </section>;
}
