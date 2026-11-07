import { useEffect, useRef, useState } from 'react';
import { Alert, Button, Empty, Modal, Pagination, Space, Spin, Tag } from 'antd';
import { Link, useSearchParams } from 'react-router-dom';
import { knowledgeService } from '@/api/modules/knowledge';
import { captureAccountScope } from '@/api/accountScope';
import type { KnowledgeAnswerRecord, KnowledgeAnswerSummary } from '@/types/knowledge';
import AnswerView from './AnswerView';
import styles from './index.module.css';

export default function AnswerHistory() {
  const [params, setParams] = useSearchParams();
  const id = params.get('answer');
  const rawPage = Number(params.get('page') || '1');
  const page = Number.isInteger(rawPage) && rawPage >= 1 && rawPage <= 1000000 ? rawPage : 1;
  const [rows, setRows] = useState<KnowledgeAnswerSummary[]>([]), [total, setTotal] = useState(0);
  const [record, setRecord] = useState<KnowledgeAnswerRecord>();
  const [loading, setLoading] = useState(true), [reading, setReading] = useState(false);
  const [listError, setListError] = useState(''), [detailError, setDetailError] = useState('');
  const [reload, setReload] = useState(0), [deleting, setDeleting] = useState(false), [confirm, setConfirm] = useState(false);
  const action = useRef(false);
  useEffect(() => {
    const controller = new AbortController(), scope = captureAccountScope();
    setLoading(true); setListError(''); setRows([]);
    void knowledgeService.answers(page, controller.signal).then(result => {
      if (!scope.isCurrent() || controller.signal.aborted) return;
      if (page > 1 && !result.items.length) { setParams({ page: String(Math.max(1, Math.ceil(result.total / 20))), ...(id ? { answer: id } : {}) }, { replace: true }); return; }
      setRows(result.items); setTotal(result.total);
    }).catch(cause => { if (scope.isCurrent() && !controller.signal.aborted) setListError(cause instanceof Error ? cause.message : '历史列表加载失败'); })
      .finally(() => { if (scope.isCurrent() && !controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [page, id, reload, setParams]);
  useEffect(() => {
    setRecord(undefined); setDetailError(''); setConfirm(false);
    if (!id) { setReading(false); return; }
    const controller = new AbortController(), scope = captureAccountScope(); setReading(true);
    void knowledgeService.answer(id, controller.signal).then(value => { if (scope.isCurrent() && !controller.signal.aborted) setRecord(value); })
      .catch(cause => { if (scope.isCurrent() && !controller.signal.aborted) setDetailError(cause instanceof Error ? cause.message : '问答记录不可用'); })
      .finally(() => { if (scope.isCurrent() && !controller.signal.aborted) setReading(false); });
    return () => controller.abort();
  }, [id, reload]);
  const remove = async () => {
    if (!record || action.current) return;
    action.current = true; setDeleting(true); const scope = captureAccountScope(), deletingId = record.id;
    try {
      await knowledgeService.deleteAnswer(deletingId);
      if (scope.isCurrent()) { setRecord(undefined); setConfirm(false); setParams({ page: String(page) }); setReload(n => n + 1); }
    } catch (cause) { if (scope.isCurrent()) setDetailError(cause instanceof Error ? cause.message : '删除失败，请重试'); }
    finally { action.current = false; if (scope.isCurrent()) setDeleting(false); }
  };
  return <main className={styles.page}>
    <header className={styles.heading}><div><h1>已保存的问答</h1><p>按当时的回答与资料版本核对，每次打开直接读取保存结果。</p></div><Space wrap><Button onClick={() => setReload(n => n + 1)}>刷新历史</Button><Link to="/knowledge"><Button>返回知识库</Button></Link></Space></header>
    <p className={styles.retention}>资料删除后，问答与引用摘录仍保留，来源标为不可用；删除问答会清除该记录。注销账号会清除全部资料及问答历史，退出登录会保留记录。</p>
    <div className={styles.workspace}>
      <aside className={styles.library} aria-label="问答历史列表"><div className={styles.sectionHeading}><h2>问答历史 <small>{total}</small></h2></div>
        {listError ? <Alert type="error" message={listError} /> : loading ? <Spin /> : rows.length ? rows.map(row => <Link className={styles.historyItem} aria-current={row.id === id ? 'page' : undefined} key={row.id} to={'/knowledge/history?' + new URLSearchParams({ page: String(page), answer: row.id })}>
          <Tag color={row.sufficient ? 'green' : 'orange'}>{row.sufficient ? '已回答' : '资料不足'}</Tag><strong>{row.question}</strong><small>{new Date(row.createdAt).toLocaleString('zh-CN')}{row.executionMode === 'mock' ? ' · 规则演示' : ''}</small>
        </Link>) : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="还没有问答记录" />}
        {total > 20 && <Pagination simple current={page} total={total} pageSize={20} onChange={next => setParams({ page: String(next), ...(id ? { answer: id } : {}) })} />}
      </aside>
      <section className={styles.reader} aria-label="已保存问答详情" aria-busy={reading}>
        {detailError && <Alert type="error" message={detailError} />}
        {reading ? <Spin /> : record ? <>
          <div className={styles.sectionHeading}><div><h2 className={styles.question}>{record.question}</h2><small>保存于 {new Date(record.createdAt).toLocaleString('zh-CN')}</small></div><Button danger disabled={deleting} onClick={() => setConfirm(true)}>删除问答记录</Button></div>
          <div className={styles.savedSources} aria-label="当时所选资料"><strong>当时所选资料</strong>{record.documents.map(source => <div key={source.documentId}><span>{source.title} · v{source.version} · {source.sourceName}</span> {source.available ? <Tag>原版本可核对</Tag> : <Tag color="warning">来源不可用（资料已删除）</Tag>}</div>)}</div>
          <AnswerView answer={record.answer} sources={record.documents} />
          <Space wrap><Link to={'/knowledge?parent=' + encodeURIComponent(record.id)}><Button disabled={record.documents.some(source => !source.available)}>从这条回答继续追问</Button></Link>{record.parentAnswerId && <Link to={'/knowledge/history?answer=' + encodeURIComponent(record.parentAnswerId)}>查看上一轮</Link>}</Space>
        </> : !detailError && <Empty description="选择一条问答，查看已保存结果" image={Empty.PRESENTED_IMAGE_SIMPLE} />}
      </section>
    </div>
    <Modal title="删除这条问答记录？" open={confirm} onCancel={() => { if (!deleting) setConfirm(false); }} onOk={() => void remove()} okText="确认删除记录" cancelText="取消" confirmLoading={deleting} okButtonProps={{ danger: true }}>
      <p>将永久删除问题、回答、资料版本记录和引用摘录，删除后无法再次读取。原资料不会被删除。</p>
    </Modal>
  </main>;
}
