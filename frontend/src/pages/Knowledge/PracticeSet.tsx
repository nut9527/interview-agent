import { useEffect, useState } from 'react';
import { Alert, Button, Empty, Pagination, Segmented, Space, Spin } from 'antd';
import { Link, useSearchParams } from 'react-router-dom';
import { knowledgeService } from '@/api/modules/knowledge';
import { captureAccountScope } from '@/api/accountScope';
import type { KnowledgeSelfTest } from '@/types/knowledge';
import QuestionCard from './QuestionCard';
import styles from './index.module.css';

export default function PracticeSet() {
  const [params, setParams] = useSearchParams(), confirmed = params.get('tab') !== 'drafts';
  const [page, setPage] = useState(1), [reload, setReload] = useState(0);
  const [rows, setRows] = useState<KnowledgeSelfTest[]>([]), [total, setTotal] = useState(0), [busy, setBusy] = useState(false), [error, setError] = useState('');
  useEffect(() => {
    const controller = new AbortController(), scope = captureAccountScope(); setBusy(true); setError(''); setRows([]);
    void knowledgeService.selfTests(confirmed, page, controller.signal).then(result => { if (scope.isCurrent() && !controller.signal.aborted) { if (!result.items.length && page > 1) setPage(page - 1); setRows(result.items); setTotal(result.total); } })
      .catch(cause => { if (scope.isCurrent() && !controller.signal.aborted) setError(cause instanceof Error ? cause.message : '读取失败'); })
      .finally(() => { if (scope.isCurrent() && !controller.signal.aborted) setBusy(false); });
    return () => controller.abort();
  }, [confirmed, page, reload]);
  return <main className={styles.page}><header className={styles.heading}><div><h1>我的题目</h1><p>先独立回答，再展开要点；每道题保留生成时的来源版本。</p></div><Link to="/knowledge"><Button>导入资料与出题</Button></Link></header>
    <Space wrap><Segmented value={confirmed ? 'saved' : 'drafts'} options={[{ value: 'saved', label: '已确认题目' }, { value: 'drafts', label: '待确认' }]} onChange={value => { setPage(1); setParams(value === 'drafts' ? { tab: 'drafts' } : {}); }} /><Button onClick={() => setReload(n => n + 1)}>刷新</Button><span>{total} 道题</span></Space>
    <p className={styles.retention}>删除资料后题目与引用摘录仍保留，原文链接标为不可用。删除题目会清除该题；注销账号会清除全部自测题。</p>
    {error && <Alert type="error" message={error} />}{busy ? <Spin /> : rows.length ? rows.map(value => <QuestionCard key={value.id} value={value} onChanged={() => setReload(n => n + 1)} />) : <Empty description={confirmed ? '还没有已确认题目，请先到「资料与出题」生成并核对' : '没有待确认候选题'} />}
    {total > 20 && <Pagination current={page} total={total} pageSize={20} showSizeChanger={false} onChange={setPage} />}
  </main>;
}
