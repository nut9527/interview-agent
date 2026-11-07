import { useEffect, useState } from 'react';
import { Alert, Button, Skeleton, Tag } from 'antd';
import { Link, useLocation, useParams } from 'react-router-dom';
import { questionBankService, type ReadingReference } from '@/api/modules/questionBank';
import type { Question, QuestionMetadata } from '@/types/questionBank';
import styles from './index.module.css';
import PracticeLauncher from './PracticeLauncher';
import AddSourceTask from '@/pages/Interview/AddSourceTask';

function ReadingList({ id }: { id: string }) {
  const [items, setItems] = useState<ReadingReference[]>();
  const [error, setError] = useState('');
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    setError('');
    setItems(undefined);
    questionBankService.references(id, controller.signal)
      .then(value => { if (!controller.signal.aborted) setItems(value); })
      .catch((reason: unknown) => {
        if (!controller.signal.aborted) setError(reason instanceof Error ? reason.message : '延伸阅读暂不可用');
      });
    return () => controller.abort();
  }, [id, attempt]);
  return <section aria-label="延伸阅读">
    <h2>延伸阅读</h2>
    {error ? <Alert type="warning" message={error}
      action={<Button onClick={() => setAttempt(value => value + 1)}>重试</Button>} />
      : !items ? <p role="status">正在检索参考文档…</p>
      : items.length === 0 ? <p>暂未找到与本题匹配的文档。</p>
      : <ul>{items.map(item => <li key={item.url}>
          <a href={item.url} target="_blank" rel="noopener noreferrer">{item.title}</a>
          <p>{item.summary}</p>
        </li>)}</ul>}
  </section>;
}

export default function QuestionDetail() {
  const { id = '' } = useParams();
  const { search } = useLocation();
  const [question, setQuestion] = useState<Question>();
  const [metadata, setMetadata] = useState<QuestionMetadata>();
  const [expanded, setExpanded] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [retry, setRetry] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    setExpanded(false); setLoading(true); setError(''); setQuestion(undefined);
    Promise.all([questionBankService.find(id, controller.signal), questionBankService.metadata(controller.signal)])
      .then(([value, meta]) => { if (!controller.signal.aborted) { setQuestion(value); setMetadata(meta); } })
      .catch((reason: unknown) => { if (!controller.signal.aborted) setError(reason instanceof Error ? reason.message : '题目加载失败'); })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [id, retry]);
  return <main className={styles.page}>
    <Link className={styles.back} to={'/questions' + search + '#' + encodeURIComponent(id)}>← 返回题库</Link>
    {loading ? <div role="status" aria-label="正在加载题目"><Skeleton active paragraph={{ rows: 8 }} /></div>
      : error ? <Alert type="error" showIcon message="题目加载失败" description={error}
        action={<Button onClick={() => setRetry(value => value + 1)}>重试</Button>} />
      : question && <article className={styles.detail}>
        <div className={styles.meta}><span>{metadata?.topics.find(t => t.value === question.topic)?.label}</span>
          <Tag>{metadata?.difficulties.find(d => d.value === question.difficulty)?.label}</Tag></div>
        <h1>{question.title}</h1>
        <div>{question.tags.map(tag => <Tag key={tag}>{tag}</Tag>)}</div>
        <section className={styles.prompt} aria-label="题干"><h2>题干</h2><p>{question.question}</p></section>
        <div className={styles.practiceActions}><PracticeLauncher question={question} />
          <AddSourceTask key={`${question.id}:${question.version}`} source={{ kind: 'QUESTION', id: question.id, version: question.version }} />
          <Link to={'/questions/'+id+'/practices'}>我的同题练习记录</Link><Tag>版本 {question.version}</Tag></div>
        <Button type="primary" aria-expanded={expanded} aria-controls="question-analysis"
          onClick={() => setExpanded(value => !value)}>{expanded ? '收起解析' : '查看解析'}</Button>
        {expanded && <section id="question-analysis" className={styles.analysis}>
          <h2>参考回答</h2><p className={styles.hint}>以下是一种可行思路；合理的替代方案同样值得讨论。</p>
          <p>{question.referenceAnswer}</p>
          <h2>考察要点</h2><ul>{question.keyPoints.map(point => <li key={point}>{point}</li>)}</ul>
          <h2>常见误区</h2><ul>{question.commonMistakes.map(point => <li key={point}>{point}</li>)}</ul>
          <ReadingList key={question.id} id={question.id} />
        </section>}
        <section className={styles.followUps}><h2>建议追问</h2>
          <ol>{question.followUps.map(point => <li key={point}>{point}</li>)}</ol></section>
        <footer className={styles.source}>
          <span>{question.sourceType === 'ORIGINAL' ? '原创 · 项目内置示例题' : '外部参考'}</span>
          {question.sourceTitle && <span>{question.sourceTitle}</span>}
          {question.sourceUrl && /^https?:\/\//i.test(question.sourceUrl) &&
            <a href={question.sourceUrl} target="_blank" rel="noopener noreferrer">查看来源</a>}
          <span>更新于 {new Date(question.updatedAt).toLocaleDateString('zh-CN')}</span>
        </footer>
      </article>}
  </main>;
}

