import { useEffect, useState } from 'react';
import { Alert, Button, Empty, Pagination, Spin, Tag } from 'antd';
import { Link } from 'react-router-dom';
import { knowledgeService } from '@/api/modules/knowledge';
import { captureAccountScope } from '@/api/accountScope';
import type { KnowledgeFeedback } from '@/types/knowledge';
import { feedbackLabels } from './AnswerFeedback';
import styles from './index.module.css';

export default function FeedbackLog() {
  const [page, setPage] = useState(1), [rows, setRows] = useState<KnowledgeFeedback[]>([]), [total, setTotal] = useState(0), [error, setError] = useState(''), [busy, setBusy] = useState(true), [retry, setRetry] = useState(0);
  useEffect(() => {
    const controller = new AbortController(), scope = captureAccountScope(); setBusy(true); setError('');
    void knowledgeService.feedbacks(page, controller.signal).then(result => { if (scope.isCurrent() && !controller.signal.aborted) { setRows(result.items); setTotal(result.total); } })
      .catch(cause => { if (scope.isCurrent() && !controller.signal.aborted) setError(cause instanceof Error ? cause.message : '读取失败'); })
      .finally(() => { if (scope.isCurrent() && !controller.signal.aborted) setBusy(false); });
    return () => controller.abort();
  }, [page, retry]);
  return <main className={styles.page}><header className={styles.heading}><div><h1>回答反馈</h1><p>反馈关联当时的问题、回答和引用，供个人回看整理。</p></div><Link to="/knowledge"><Button>返回知识库</Button></Link></header>
    {error && <Alert type="error" message={error} action={<Button onClick={() => setRetry(n => n + 1)}>重试</Button>} />}
    {busy ? <Spin /> : rows.length ? rows.map(row => <article className={styles.questionCard} key={row.answerId}><h2><Link to={'/knowledge/history?answer=' + encodeURIComponent(row.answerId)}>{row.question}</Link></h2><div>{row.reasons.map(reason => <Tag key={reason} color="orange">{feedbackLabels[reason]}</Tag>)}</div><p className={styles.feedbackComment}>{row.comment || '未填写补充说明'}</p>{row.missingSources.map(source => <p key={source.documentId}>遗漏资料：{source.title} · v{source.version}{!source.available && ' · 来源不可用'}</p>)}</article>) : <Empty description="还没有回答反馈" />}
    {total > 20 && <Pagination current={page} total={total} pageSize={20} showSizeChanger={false} onChange={setPage} />}
  </main>;
}
