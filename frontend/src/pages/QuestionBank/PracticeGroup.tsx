import { useEffect, useRef, useState } from 'react';
import { Alert, Button, Empty, Select, Skeleton, Space, Tag } from 'antd';
import { Link, useSearchParams } from 'react-router-dom';
import { questionBankService } from '@/api/modules/questionBank';
import type { QuestionMetadata, QuestionProgress, QuestionSummary } from '@/types/questionBank';
import PracticeLauncher from './PracticeLauncher';
import { practiceStatusLabel } from './practiceStatus';
import styles from './index.module.css';

export default function PracticeGroup() {
  const [search, setSearch] = useSearchParams();
  const [metadata, setMetadata] = useState<QuestionMetadata>();
  const [items, setItems] = useState<QuestionSummary[]>();
  const [progress, setProgress] = useState<QuestionProgress[]>([]);
  const [topic, setTopic] = useState(search.get('topic') || undefined);
  const [difficulty, setDifficulty] = useState(search.get('difficulty') || undefined);
  const requested = Number(search.get('requested'));
  const [count, setCount] = useState([1,2,3,5,10].includes(requested) ? requested : 3);
  const configuredRounds = Number(search.get('rounds'));
  const [rounds, setRounds] = useState([3,4,5,6].includes(configuredRounds) ? configuredRounds : 3);
  const [error, setError] = useState('');
  const [drawFailed, setDrawFailed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [reload, setReload] = useState(0);
  const drawing = useRef<AbortController>();
  const ids = search.get('ids') || '';
  const query = search.toString();
  useEffect(() => {
    const params = new URLSearchParams(query);
    setTopic(params.get('topic') || undefined); setDifficulty(params.get('difficulty') || undefined);
    const requestedCount = Number(params.get('requested')), requestedRounds = Number(params.get('rounds'));
    setCount([1,2,3,5,10].includes(requestedCount) ? requestedCount : 3);
    setRounds([3,4,5,6].includes(requestedRounds) ? requestedRounds : 3);
  }, [query]);
  useEffect(() => {
    const request = new AbortController(); setError(''); setDrawFailed(false); setItems(undefined);
    const values = ids ? ids.split(',') : [];
    if (values.length>10 || new Set(values).size!==values.length || values.some(id => !/^qb-[a-z]+-\d{3}$/.test(id))) {
      setError('练习组链接无效，请重新抽题'); setItems([]); return () => request.abort();
    }
    Promise.all([questionBankService.metadata(request.signal), questionBankService.progress(request.signal),
      Promise.all(values.map(id => questionBankService.find(id, request.signal)))])
      .then(([meta, statuses, questions]) => { if (!request.signal.aborted) {
        setMetadata(meta); setProgress(statuses); setItems(questions.map(q => ({ ...q, questionSummary: q.question })));
      } }).catch(reason => { if (!request.signal.aborted) setError(reason instanceof Error ? reason.message : '读取练习组失败'); });
    return () => request.abort();
  }, [ids, reload]);
  useEffect(() => () => drawing.current?.abort(), []);
  const draw = async () => {
    if (drawing.current) return;
    const request = new AbortController(); drawing.current=request; setBusy(true); setError(''); setDrawFailed(false);
    try {
      const result = await questionBankService.draw({ topic, difficulty, count, roundsPerQuestion: rounds }, request.signal);
      if (!request.signal.aborted) {
        setItems(result.items);
        const next = new URLSearchParams({ rounds: String(rounds), requested: String(count) });
        if (topic) next.set('topic',topic); if (difficulty) next.set('difficulty',difficulty);
        if (result.items.length) next.set('ids',result.items.map(q => q.id).join(','));
        setSearch(next);
      }
    } catch (reason) { if (!request.signal.aborted) { setError(reason instanceof Error ? reason.message : '抽题失败'); setDrawFailed(true); } }
    finally { if (!request.signal.aborted) setBusy(false); if (drawing.current===request) drawing.current=undefined; }
  };
  return <main className={styles.page}><Link to="/questions">← 返回题库</Link>
    <header className={styles.hero}><h1>抽题练习组</h1><p>优先待复练，其次未练过。每题独立会话，保留充分追问时间。</p></header>
    <Space wrap className={styles.groupControls}>
      <Select aria-label="抽题主题" placeholder="全部主题" allowClear value={topic} options={metadata?.topics} onChange={setTopic} />
      <Select aria-label="抽题难度" placeholder="全部难度" allowClear value={difficulty} options={metadata?.difficulties} onChange={setDifficulty} />
      <Select aria-label="抽题数量" value={count} onChange={setCount} options={[1,2,3,5,10].map(value => ({ value, label: value+' 题' }))} />
      <Select aria-label="组内每题轮数" value={rounds} onChange={setRounds} options={[3,4,5,6].map(value => ({ value, label: '每题 '+value+' 轮' }))} />
      <Button type="primary" loading={busy} onClick={() => void draw()}>生成练习组</Button>
    </Space>
    {error && <Alert type="error" message={error} action={<Button disabled={busy}
      onClick={() => drawFailed ? void draw() : setReload(v => v+1)}>{drawFailed ? '重试抽题' : '重试读取'}</Button>} />}
    {!items && !error ? <Skeleton active /> : items && <>
      {search.has('requested') && <p>本组 {items.length} / {search.get('requested')} 题，符合条件的题目不足时按实际数量返回。</p>}
      {!items.length ? <Empty description="请选择条件生成练习组；无匹配时可放宽筛选" /> : items.map(q => <article key={q.id} className={styles.item}>
        <Tag>{practiceStatusLabel(progress.find(p => p.questionId===q.id)?.status)}</Tag>
        <h2><Link to={'/questions/'+q.id}>{q.title}</Link></h2><p>{q.questionSummary}</p>
        <PracticeLauncher key={q.id+':'+search.get('rounds')} question={q}
          rounds={[3,4,5,6].includes(Number(search.get('rounds'))) ? Number(search.get('rounds')) : 3} group={query} />
        <Link className={styles.historyLink} to={'/questions/'+q.id+'/practices'}>我的练习记录</Link>
      </article>)}
    </>}
  </main>;
}
