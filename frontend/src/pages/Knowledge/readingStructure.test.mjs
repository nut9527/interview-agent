import test from 'node:test';
import assert from 'node:assert/strict';
import { buildReaderSections, readingBlocks, documentReadingBlocks } from './readingStructure.ts';

const chunk = (content, index) => ({ id: `doc:${index}`, content, heading: '', ordinal: index, startLine: index, endLine: index, startOffset: 0, endOffset: content.length });
test('directory recognizes existing Markdown and plain-text chapters without treating code as headings', () => {
  const chunks = ['# Redis\n简介', '## 持久化\n正文', '```python\n# not a chapter', 'print(1)\n```', '第三章 故障恢复\n正文', '如何选择持久化方式？\n正文'].map(chunk);
  assert.deepEqual(buildReaderSections(chunks).map(s => s.title), ['Redis', '持久化', '第三章 故障恢复', '如何选择持久化方式？']);
  assert.equal(documentReadingBlocks(chunks)[3][0].kind, 'code');
  assert.equal(buildReaderSections([chunk('## 持久化', 1)])[0].title, '持久化');
});
test('long unstructured documents have bounded sections and every source chunk remains reachable', () => {
  const chunks = Array.from({ length: 50 }, (_, i) => chunk('普通正文内容。', i));
  const sections = buildReaderSections(chunks);
  assert.equal(sections.length, 5);
  assert.deepEqual(sections.flatMap(s => chunks.slice(s.start, s.end)), chunks);
  assert.ok(sections.every(s => s.end - s.start <= 12));
});
test('plain PDF heading paragraphs can form a directory without changing source text', () => {
  const chunks = ['Redis 持久化', 'RDB 是持久化方式之一。', '故障恢复与数据丢失', '恢复时需要考虑持久化策略。'].map(chunk);
  assert.deepEqual(buildReaderSections(chunks).map(s => s.title), ['Redis 持久化', '故障恢复与数据丢失']);
  assert.equal(readingBlocks(chunks[0].content).blocks[0].kind, 'heading');
});
test('reading blocks retain text and recognize lists, quotes, tables and code as inert text', () => {
  const { blocks } = readingBlocks('## 机制\n\n- **事务**\n- `锁`\n\n> 引用原文\n\n| 模式 | 成本 |\n| --- | --- |\n| A | 高 |\n\n```html\n<script>alert(1)</script>\n```');
  assert.deepEqual(blocks.map(b => b.kind), ['heading', 'list', 'quote', 'table', 'code']);
  assert.equal(blocks[4].text, '<script>alert(1)</script>');
});
