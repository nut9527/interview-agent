import { useEffect, useRef, useState } from 'react';
import { Input, Pagination } from 'antd';
import { PAGE_SIZE, splitKnowledgeText as split } from './textPages';

export default function PagedTextEditor({ value, onChange, label, disabled, rows = 16 }: {
  value: string; onChange: (value: string) => void; label: string; disabled?: boolean; rows?: number;
}) {
  const [pages, setPages] = useState(() => split(value)), [page, setPage] = useState(1);
  // 自己触发的值回传保留当前分段与页码，外部替换正文才重新分页。
  const expected = useRef(value);
  useEffect(() => {
    if (value !== expected.current) { expected.current = value; setPages(split(value)); setPage(1); }
  }, [value]);
  const current = Math.min(page, pages.length);
  return <div>
    <Input.TextArea key={current} aria-label={label} rows={rows} disabled={disabled} value={pages[current - 1]} onChange={event => {
      const next = [...pages]; next[current - 1] = event.target.value;
      const text = next.join(''); expected.current = text;
      setPages(event.target.value.length > PAGE_SIZE * 2 ? split(text) : next); onChange(text);
    }} />
    <p>正文共 {value.length.toLocaleString()} / 2,000,000 字{pages.length > 1 ? '；按文字分段核对，分段不对应原文件页码。修改与换页均保留全部正文。' : ''}</p>
    {pages.length > 1 && <Pagination simple current={current} total={pages.length} pageSize={1} disabled={disabled} onChange={setPage} />}
  </div>;
}
