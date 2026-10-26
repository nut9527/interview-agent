import { useEffect, useRef, useState } from 'react';
import { Alert, Button, Drawer, Empty, Modal, Pagination, Spin, message } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { targetJobService } from '@/api/modules/targetJob';
import { captureAccountScope } from '@/api/accountScope';
import type { TargetJob, TargetJobSummary } from '@/types/targetJob';
import TargetJobEditor from './TargetJobEditor';
import TargetJobAnalysis from './TargetJobAnalysis';
import styles from './index.module.css';

export default function TargetJobs() {
  const [jobs, setJobs] = useState<TargetJobSummary[]>([]);
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [reload, setReload] = useState(0);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [selected, setSelected] = useState<TargetJob | null>(null);
  const [editor, setEditor] = useState<{ job: TargetJob | null } | null>(null);
  const detail = useRef<AbortController | null>(null);
  useEffect(() => () => { detail.current?.abort(); }, []);
  useEffect(() => {
    const request = new AbortController(); setLoading(true); setError('');
    void targetJobService.list(page, request.signal).then(result => {
      if (!request.signal.aborted) {
        setJobs(result.items); setTotal(result.total);
        if (!result.items.length && page > 1) setPage(page - 1);
      }
    }).catch(cause => { if (!request.signal.aborted) setError(cause instanceof Error ? cause.message : '读取失败'); })
      .finally(() => { if (!request.signal.aborted) setLoading(false); });
    return () => request.abort();
  }, [page, reload]);
  const open = async (id: string) => {
    detail.current?.abort(); const request = new AbortController(); detail.current = request;
    try {
      const job = await targetJobService.get(id, request.signal);
      if (!request.signal.aborted) setSelected(job);
    } catch (cause) { if (!request.signal.aborted) message.error(cause instanceof Error ? cause.message : '岗位读取失败'); }
  };
  const remove = (job: TargetJob) => {
    const scope = captureAccountScope();
    Modal.confirm({ title: `删除“${job.title}”？`, content: '该岗位的 JD 和已纠正要求将一并删除。', okText: '删除', okType: 'danger', cancelText: '取消',
      onOk: async () => {
        if (!scope.isCurrent()) return;
        try { await targetJobService.delete(job); if (scope.isCurrent()) { setSelected(null); setReload(v => v + 1); message.success('已删除'); } }
        catch (cause) { if (scope.isCurrent()) message.error(cause instanceof Error ? cause.message : '删除失败'); throw cause; }
      } });
  };
  return <main className={styles.page}>
    <header className={styles.hero}>
      <div><span className={styles.eyebrow}>面试材料 / TARGET JOBS</span><h1>为下一场面试准备目标。</h1><p>保存 JD，核对要求，让简历里的每一处证据有迹可循。</p></div>
      <Button type="primary" size="large" icon={<PlusOutlined />} onClick={() => { detail.current?.abort(); setEditor({ job: null }); }}>添加目标岗位</Button>
    </header>
    <div className={styles.listHeading}><h2>我的目标岗位 <span>{total}</span></h2><Button onClick={() => setReload(v => v + 1)}>刷新列表</Button></div>
    {error && <Alert type="error" showIcon message={error} action={<Button onClick={() => setReload(v => v + 1)}>重试</Button>} />}
    <Spin spinning={loading}>
      {!jobs.length && !loading && !error ? <div className={styles.empty}><Empty description="把想投的第一份 JD 放在这里"><Button type="primary" onClick={() => setEditor({ job: null })}>粘贴 JD，添加岗位</Button></Empty></div> : <div className={styles.jobGrid}>
        {jobs.map(job => <article className={styles.jobCard} key={job.id}>
          <span className={styles.company}>{job.company || '公司待补充'}</span><h3>{job.title}</h3>
          <p>来源 · {job.sourceName || '未填写'}</p><p>截止 · {job.deadline || '未说明'}</p>
          <Button onClick={() => void open(job.id)}>查看要求与简历证据 →</Button>
        </article>)}
      </div>}
    </Spin>
    {total > 12 && <Pagination current={page} pageSize={12} total={total} showSizeChanger={false} onChange={setPage} />}
    <Drawer open={Boolean(selected)} title={selected?.title} width="min(960px, 100vw)" onClose={() => { detail.current?.abort(); setSelected(null); }}
      extra={selected && <div className={styles.drawerActions}><Button onClick={() => setEditor({ job: selected })}>编辑 / 纠正要求</Button><Button danger onClick={() => remove(selected)}>删除</Button></div>}>
      {selected && <TargetJobAnalysis key={`${selected.id}:${selected.revision}`} job={selected} />}
    </Drawer>
    {editor && <TargetJobEditor job={editor.job} onClose={() => setEditor(null)} onSaved={job => { setEditor(null); setSelected(job); setPage(1); setReload(v => v + 1); }} />}
  </main>;
}
