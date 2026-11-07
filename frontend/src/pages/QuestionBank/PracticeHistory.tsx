import { useEffect, useState } from 'react';
import { Alert, Button, Empty, Pagination, Skeleton, Space, Tag } from 'antd';
import { Link, useParams } from 'react-router-dom';
import { questionBankService } from '@/api/modules/questionBank';
import type { PageResult } from '@/types/api';
import type { QuestionPractice } from '@/types/questionBank';
import PracticeLauncher from './PracticeLauncher';
import styles from './index.module.css';

export default function PracticeHistory() {
  const { id = '' } = useParams();
  const [page, setPage] = useState(1);
  const [reload, setReload] = useState(0);
  const [data, setData] = useState<PageResult<QuestionPractice>>();
  const [error, setError] = useState('');
  useEffect(() => {
    const request = new AbortController(); setData(undefined); setError('');
    questionBankService.history(id, page, request.signal).then(value => { if (!request.signal.aborted) setData(value); })
      .catch(reason => { if (!request.signal.aborted) setError(reason instanceof Error ? reason.message : '读取记录失败'); });
    return () => request.abort();
  }, [id, page, reload]);
  return <main className={styles.page}><Link to={'/questions/' + encodeURIComponent(id)}>← 返回题目</Link>
    <header className={styles.hero}><h1>我的同题练习</h1><p>逐次回看回答、反馈和当时的题目版本。记录仅本人可见。</p></header>
    {error ? <Alert type="error" message={error} action={<Button onClick={() => setReload(v => v+1)}>重试</Button>} />
      : !data ? <Skeleton active /> : <>
        {!data.items.length && <Empty description="还没有这道题的练习记录" />}
        {data.items.map(item => <article key={item.sessionId} className={styles.item}>
          <h2>{item.question.title} <Tag>版本 {item.question.version}</Tag></h2>
          <p>{new Date(item.createdAt).toLocaleString()} · {item.state === 'FINISHED' ? '已完成' : '进行中'} · {item.rounds.length} 次回答</p>
          <Space wrap><Link to={'/interview?' + new URLSearchParams({ sessionId: item.sessionId })}>
            {item.state === 'FINISHED' ? '查看完整复盘' : '继续练习'}</Link>
            {item.state === 'FINISHED' && item.rounds.length > 0 && <PracticeLauncher
              question={{ ...item.question, difficulty: item.difficulty }}
              retry={{ sessionId: item.sessionId, round: item.rounds[item.rounds.length-1].round }} />}</Space>
          <details className={styles.archive}><summary>当时的题干与参考解析</summary>
            <p>{item.question.question}</p>{!item.question.referenceAnswer && item.state !== 'FINISHED' ? <p>正式模拟进行中，参考解析和追问提示将在结束后展示。</p> : <><p>{item.question.referenceAnswer}</p>
            <h3>考察要点</h3><ul>{item.question.keyPoints.map(point => <li key={point}>{point}</li>)}</ul>
            <h3>常见误区</h3><ul>{item.question.commonMistakes.map(point => <li key={point}>{point}</li>)}</ul>
            <h3>建议追问</h3><ul>{item.question.followUps.map(point => <li key={point}>{point}</li>)}</ul>
            </>}
          </details>
          {!item.rounds.length && <p>尚未提交回答，可继续本次练习。</p>}
          {item.rounds.map(round => <details key={round.round} className={styles.archive}>
            <summary>第 {round.round} 轮 · 查看回答与反馈</summary>
            <h3>问题</h3><p>{round.question}</p><h3>我的回答</h3><p>{round.answer}</p>
            <h3>反馈</h3>{round.feedback ? <><p>{round.feedback}</p><p>待验证：{round.gap}</p></> : <p>本场结束后统一展示反馈。</p>}
            {round.evidence && <blockquote>{round.evidence}</blockquote>}
          </details>)}
        </article>)}
        <Pagination current={page} pageSize={10} total={data.total} showSizeChanger={false} onChange={setPage} />
      </>}
  </main>;
}
