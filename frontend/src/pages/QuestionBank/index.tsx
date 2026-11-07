import { useEffect, useState } from 'react';
import { Alert, Button, Empty, Input, Pagination, Select, Skeleton, Tag } from 'antd';
import { ArrowRightOutlined, SwapOutlined } from '@ant-design/icons';
import { Link, useLocation, useSearchParams } from 'react-router-dom';
import { questionBankService } from '@/api/modules/questionBank';
import type { PageResult } from '@/types/api';
import type { QuestionMetadata, QuestionSummary, QuestionProgress } from '@/types/questionBank';
import { practiceStatusLabel } from './practiceStatus';
import { readFilters, updateFilters } from './filters';
import styles from './index.module.css';

export default function QuestionBank() {
  const [search, setSearch] = useSearchParams();
  const location = useLocation();
  const filters = readFilters(search);
  const [keyword, setKeyword] = useState(filters.keyword || '');
  const [data, setData] = useState<PageResult<QuestionSummary>>();
  const [metadata, setMetadata] = useState<QuestionMetadata>();
  const [progress, setProgress] = useState<QuestionProgress[]>([]);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const [retry, setRetry] = useState(0);
  const query = search.toString();
  useEffect(() => { setKeyword(readFilters(new URLSearchParams(query)).keyword || ''); }, [query]);
  useEffect(() => {
    const controller = new AbortController();
    setLoading(true); setError(''); setData(undefined);
    Promise.all([questionBankService.page(readFilters(new URLSearchParams(query)), controller.signal),
      questionBankService.metadata(controller.signal), questionBankService.progress(controller.signal)])
      .then(([page, meta, statuses]) => { if (!controller.signal.aborted) { setData(page); setMetadata(meta); setProgress(statuses); } })
      .catch((reason: unknown) => { if (!controller.signal.aborted) setError(reason instanceof Error ? reason.message : '题库加载失败'); })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [query, retry]);
  useEffect(() => {
    if (data && location.hash) document.getElementById(location.hash.slice(1))?.scrollIntoView({ block: 'center' });
  }, [data, location.hash]);
  return <main className={styles.page}>
    <header className={styles.hero}>
      <div><h1>精选题目</h1><p>按主题和难度选题，进入面试练习或查看参考解析。</p></div>
      <Link className={styles.drawLink} to={'/questions/practice?' + search}><SwapOutlined aria-hidden="true" />抽题练习组</Link>
    </header>
    <section aria-label="题目筛选" className={styles.filters}>
      <label>主题<Select aria-label="主题" allowClear placeholder="全部主题" value={filters.topic}
        options={metadata?.topics} onChange={topic => setSearch(updateFilters(search, { topic }))} /></label>
      <label>难度<Select aria-label="难度" allowClear placeholder="全部难度" value={filters.difficulty}
        options={metadata?.difficulties} onChange={difficulty => setSearch(updateFilters(search, { difficulty }))} /></label>
      <label>关键词<Input.Search aria-label="关键词" placeholder="搜索标题或题干" allowClear maxLength={100}
        value={keyword} onChange={event => setKeyword(event.target.value)}
        onSearch={value => setSearch(updateFilters(search, { keyword: value.trim() }))} enterButton="搜索" /></label>
    </section>
    {loading ? <div role="status" aria-label="正在加载题库"><Skeleton active paragraph={{ rows: 8 }} /></div>
      : error ? <Alert type="error" showIcon message="题库加载失败" description={error}
        action={<Button onClick={() => setRetry(value => value + 1)}>重试</Button>} />
      : data && <>
        <div className={styles.count} aria-live="polite">共 {data.total} 道题</div>
        {data.items.length === 0 ? <Empty description="没有找到符合条件的题目">
          <Button onClick={() => setSearch(new URLSearchParams())}>清除筛选</Button>
        </Empty> : <div className={styles.list}>{data.items.map(item => <article id={item.id} key={item.id} className={styles.item}>
          <div className={styles.meta}><span>{metadata?.topics.find(t => t.value === item.topic)?.label || item.topic}</span>
            <Tag>{metadata?.difficulties.find(d => d.value === item.difficulty)?.label || item.difficulty}</Tag></div>
          <h2><Link to={'/questions/' + encodeURIComponent(item.id) + location.search}>{item.title}</Link></h2>
          <p className={styles.summary}>{item.questionSummary}</p>
          <div>{item.tags.map(tag => <Tag key={tag}>{tag}</Tag>)}</div>
          <footer className={styles.cardFooter}>
            <Link to={'/questions/'+item.id+'/practices'}>{practiceStatusLabel(progress.find(p => p.questionId===item.id)?.status)} · 练习记录</Link>
            <Link to={'/questions/' + encodeURIComponent(item.id) + location.search}>查看题目 <ArrowRightOutlined aria-hidden="true" /></Link>
          </footer>
        </article>)}</div>}
        <Pagination className={styles.pagination} current={data.page} pageSize={data.size} total={data.total}
          responsive showSizeChanger pageSizeOptions={[12, 24, 48]} hideOnSinglePage={false}
          onChange={(page, size) => setSearch(updateFilters(search, { page: size === data.size ? page : 1, size }))} />
      </>}
  </main>;
}

