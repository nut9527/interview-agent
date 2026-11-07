import { useEffect, useMemo, useRef, useState } from 'react';
import { Button, Input, Select, Space, Switch } from 'antd';
import type { KnowledgeDocument } from '@/types/knowledge';
import { buildReaderSections, documentReadingBlocks, type ReadingBlock } from './readingStructure';
import styles from './index.module.css';

const inline = (text: string) => text.split(/(\*\*[^*]+\*\*|`[^`]+`)/g).map((part, i) => part.startsWith('**')
  ? <strong key={i}>{part.slice(2, -2)}</strong> : part.startsWith('`') ? <code key={i}>{part.slice(1, -1)}</code> : part);
function Block({ block }: { block: ReadingBlock }) {
  if (block.kind === 'heading') return block.level === 1 ? <h2>{inline(block.text)}</h2> : block.level === 2 ? <h3>{inline(block.text)}</h3> : <h4>{inline(block.text)}</h4>;
  if (block.kind === 'code') return <pre className={styles.readingCode}><code>{block.text}</code></pre>;
  if (block.kind === 'quote') return <blockquote>{inline(block.text.replace(/^\s*>\s?/gm, ''))}</blockquote>;
  if (block.kind === 'list') {
    const items = block.text.split('\n').map((line, index) => <li key={index}>{inline(line.replace(/^\s*(?:[-*+]|\d+[.)])\s+/, ''))}</li>);
    return /^\s*\d+[.)]/.test(block.text) ? <ol>{items}</ol> : <ul>{items}</ul>;
  }
  if (block.kind === 'table') {
    const rows = block.text.split('\n').map(line => line.trim().replace(/^\||\|$/g, '').split('|'));
    return <div className={styles.readingTable}><table><thead><tr>{rows[0].map((cell, i) => <th key={i}>{inline(cell.trim())}</th>)}</tr></thead><tbody>{rows.slice(1).map((row, i) => <tr key={i}>{row.map((cell, j) => <td key={j}>{inline(cell.trim())}</td>)}</tr>)}</tbody></table></div>;
  }
  return <p>{inline(block.text)}</p>;
}

export default function DocumentReader({ document: doc, chunkId, onGenerate }: {
  document: KnowledgeDocument; chunkId: string | null; onGenerate: (ids: string[]) => void;
}) {
  const sections = useMemo(() => buildReaderSections(doc.chunks), [doc.chunks]);
  const blocks = useMemo(() => documentReadingBlocks(doc.chunks), [doc.chunks]);
  const [active, setActive] = useState(0), [filter, setFilter] = useState(''), [original, setOriginal] = useState(false);
  const pane = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const index = doc.chunks.findIndex(chunk => chunk.id === chunkId);
    const section = sections.findIndex(item => index >= item.start && index < item.end);
    if (section >= 0) setActive(section);
  }, [doc.chunks, chunkId, sections]);
  useEffect(() => {
    if (chunkId) document.getElementById('source-' + chunkId)?.scrollIntoView({ block: 'nearest' });
  }, [active, chunkId]);
  const current = sections[Math.min(active, sections.length - 1)];
  const navigate = (index: number) => { setActive(index); pane.current?.scrollTo({ top: 0 }); };
  if (!current) return <p>暂无可读正文。</p>;
  return <div className={styles.readingLayout}>
    <nav className={styles.readingToc} aria-label="资料目录">
      <div className={styles.sectionHeading}><strong>目录</strong><small>{sections.length} 节</small></div>
      <Input aria-label="筛选目录" placeholder="查找章节" allowClear value={filter} onChange={event => setFilter(event.target.value)} />
      <div className={styles.tocEntries}>{sections.map((section, i) => (!filter || section.title.toLowerCase().includes(filter.toLowerCase())) && <button key={i} type="button" aria-current={active === i ? 'location' : undefined} onClick={() => navigate(i)} style={{ paddingInlineStart: 10 + Math.min(section.level - 1, 2) * 8 }}><span>{String(i + 1).padStart(2, '0')}</span>{section.title}</button>)}</div>
      {filter && !sections.some(s => s.title.toLowerCase().includes(filter.toLowerCase())) && <p>没有匹配的章节</p>}
    </nav>
    <div className={styles.readingMain}>
      <div className={styles.readingToolbar}><Select aria-label="跳转章节" value={active} onChange={navigate} showSearch optionFilterProp="label" options={sections.map((section, i) => ({ value: i, label: `${i + 1}. ${section.title}` }))} /><Space><span>原始文本</span><Switch aria-label="显示原始文本" checked={original} onChange={setOriginal} /></Space></div>
      <div ref={pane} className={styles.readingPane} aria-label="章节正文" tabIndex={0}>
        <p className={styles.readingHint}>按正文标题生成目录；无标题长段按阅读长度分节。排版视图不改变入库原文，引用仍定位到原始行号。</p>
        {doc.chunks.slice(current.start, current.end).map((chunk, index) => <article id={'source-' + chunk.id} key={chunk.id} className={`${styles.readingChunk} ${chunkId === chunk.id ? styles.highlight : ''}`} tabIndex={0}>
          {original ? <pre className={styles.readingOriginal}>{chunk.content}</pre> : blocks[current.start + index].map((block, i) => <Block key={i} block={block} />)}
          <footer><small>第 {chunk.startLine}–{chunk.endLine} 行</small><Button type="text" size="small" onClick={() => onGenerate([chunk.id])}>从这个片段出题</Button></footer>
        </article>)}
      </div>
      <div className={styles.readingNavigation}><Button disabled={active === 0} onClick={() => navigate(active - 1)}>上一节</Button><span>{active + 1} / {sections.length}</span><Button disabled={active >= sections.length - 1} onClick={() => navigate(active + 1)}>下一节</Button></div>
    </div>
  </div>;
}
