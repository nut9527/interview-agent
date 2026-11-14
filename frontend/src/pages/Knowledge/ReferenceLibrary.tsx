import { useEffect, useRef, useState } from 'react';
import { Alert, Button, Empty, Input, List, Modal, Select, Tag } from 'antd';
import { knowledgeService } from '@/api/modules/knowledge';
import { captureAccountScope } from '@/api/accountScope';
import { isRequestCanceled } from '@/api/ApiError';
import type { KnowledgeImport, ReferenceSummary } from '@/types/knowledge';
import styles from './index.module.css';

const topics: Record<string, string> = {
  agent: 'Agent', cache: '缓存', messaging: '消息队列', mysql: 'MySQL', network: '计算机网络',
  rag: 'RAG', redis: 'Redis', reliability: '可靠性', spring: 'Spring', system: '操作系统', threads: '线程池',
};

export default function ReferenceLibrary({ onClose, onSelect }: {
  onClose: () => void; onSelect: (id: string, draft: KnowledgeImport) => void;
}) {
  const [rows, setRows] = useState<ReferenceSummary[]>([]), [query, setQuery] = useState('');
  const [topic, setTopic] = useState<string>(), [previewing, setPreviewing] = useState<string>();
  const [loading, setLoading] = useState(true), [error, setError] = useState('');
  const previewRequest = useRef<AbortController>();
  useEffect(() => {
    const request = new AbortController(), scope = captureAccountScope();
    void knowledgeService.references(request.signal)
      .then(result => { if (scope.isCurrent() && !request.signal.aborted) setRows(result); })
      .catch(cause => { if (scope.isCurrent() && !request.signal.aborted && !isRequestCanceled(cause)) setError(cause instanceof Error ? cause.message : '资料目录加载失败'); })
      .finally(() => { if (scope.isCurrent() && !request.signal.aborted) setLoading(false); });
    return () => { request.abort(); previewRequest.current?.abort(); };
  }, []);
  const preview = async (id: string) => {
    const request = new AbortController(), scope = captureAccountScope();
    previewRequest.current = request; setPreviewing(id); setError('');
    try {
      const draft = await knowledgeService.reference(id, request.signal);
      if (scope.isCurrent() && !request.signal.aborted) onSelect(id, draft);
    } catch (cause) {
      if (scope.isCurrent() && !request.signal.aborted && !isRequestCanceled(cause)) setError(cause instanceof Error ? cause.message : '资料加载失败');
    } finally { if (scope.isCurrent() && !request.signal.aborted) setPreviewing(undefined); }
  };
  const filtered = rows.filter(row => (!topic || row.topic === topic)
    && `${row.title} ${row.sourceLabel}`.toLocaleLowerCase().includes(query.trim().toLocaleLowerCase()));
  return <Modal open title="参考资料" width={800} footer={null} onCancel={onClose}>
    <div className={styles.filterBar}>
      <Input aria-label="搜索参考资料" placeholder="搜索标题或来源" value={query} onChange={event => setQuery(event.target.value)} allowClear />
      <Select aria-label="筛选参考资料主题" placeholder="全部主题" allowClear value={topic} onChange={setTopic}
        options={[...new Set(rows.map(row => row.topic))].map(value => ({ value, label: topics[value] || value }))} />
    </div>
    {error && <Alert type="error" showIcon message={error} />}
    <List loading={loading} dataSource={filtered} pagination={{ pageSize: 6, hideOnSinglePage: true, showSizeChanger: false }}
      locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="没有符合条件的资料" /> }}
      renderItem={row => <List.Item actions={[<Button key="preview" loading={previewing === row.id} disabled={!!previewing} onClick={() => void preview(row.id)}>查看原文</Button>]}>
        <List.Item.Meta title={<><Tag>{topics[row.topic] || row.topic}</Tag>{row.title}</>} description={row.sourceLabel} />
      </List.Item>} />
  </Modal>;
}
