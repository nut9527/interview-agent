import type { KnowledgeDocument } from '@/types/knowledge';

type Chunk = KnowledgeDocument['chunks'][number];
export interface ReadingSection { title: string; start: number; end: number; level: number }
export type ReadingBlock = { kind: 'heading' | 'paragraph' | 'list' | 'quote' | 'code' | 'table'; text: string; level?: number };

export function heading(line: string): { title: string; level: number } | undefined {
  const md = /^(#{1,6})\s+(.+?)\s*#*\s*$/.exec(line.trim());
  if (md) return { title: md[2], level: md[1].length };
  const value = line.trim();
  if (value.length > 70) return;
  if (/^(第[一二三四五六七八九十百\d]+[章节篇部]|[一二三四五六七八九十]+、|\d+(?:\.\d+)+\s)\S*/.test(value)) return { title: value, level: 2 };
  if (value.length >= 5 && value.length <= 50 && /^[^，。；;]+[？?]$/.test(value)) return { title: value, level: 3 };
}

function plainHeading(text: string) {
  const value = text.trim();
  // 已导入 PDF/DOCX 的标题样式可能已丢失，仅识别单独成段的短标题。
  if (value.length >= 3 && value.length <= 40 && !/[\r\n，。；：:;!?！？|{}<>()[\]`]/.test(value)
    && !/^(?:[#*+>-]|\d+[.)]|https?\b|SELECT\b|INSERT\b|UPDATE\b|DELETE\b)/i.test(value)
    && /[\p{Script=Han}a-z]/iu.test(value)) return { title: value, level: 2 };
}

export function buildReaderSections(chunks: Chunk[]): ReadingSection[] {
  const sections: ReadingSection[] = [];
  let fenced = false;
  chunks.forEach((chunk, index) => {
    let title: ReturnType<typeof heading> = fenced ? undefined : plainHeading(chunk.content);
    for (const line of chunk.content.split(/\r?\n/)) {
      if (/^\s*(```|~~~)/.test(line)) { fenced = !fenced; continue; }
      if (!fenced && !title) title = heading(line);
    }
    const previous = sections[sections.length - 1];
    if (!previous || title || index - previous.start >= 12) {
      if (previous) previous.end = index;
      sections.push({ title: title?.title || (previous ? `${previous.title.replace(/ · 续读 \d+$/, '')} · 续读 ${index + 1}` : chunk.heading || '正文'),
        level: title?.level || previous?.level || 1, start: index, end: chunks.length });
    }
  });
  return sections;
}

// 只生成 React 文本节点，不执行 HTML，不加载资料中的图片或脚本。
export function readingBlocks(text: string, inCode = false): { blocks: ReadingBlock[]; inCode: boolean } {
  const title = !inCode && plainHeading(text);
  if (title) return { blocks: [{ kind: 'heading', text: title.title, level: title.level }], inCode };
  const blocks: ReadingBlock[] = [];
  let buffer: string[] = [], kind: ReadingBlock['kind'] = inCode ? 'code' : 'paragraph';
  const flush = () => { if (buffer.length) blocks.push({ kind, text: buffer.join('\n') }); buffer = []; };
  const lines = text.split(/\r?\n/);
  for (let index = 0; index < lines.length; index++) {
    const line = lines[index];
    if (/^\s*(```|~~~)/.test(line)) { flush(); inCode = !inCode; kind = inCode ? 'code' : 'paragraph'; continue; }
    if (inCode) { buffer.push(line); continue; }
    const title = heading(line);
    if (title) { flush(); blocks.push({ kind: 'heading', text: title.title, level: title.level }); continue; }
    if (!line.trim()) { flush(); kind = 'paragraph'; continue; }
    if (line.includes('|') && /^\s*\|?\s*:?-{3,}/.test(lines[index + 1] || '')) {
      flush(); const rows = [line]; index += 2;
      while (index < lines.length && lines[index].includes('|') && lines[index].trim()) rows.push(lines[index++]);
      index--; blocks.push({ kind: 'table', text: rows.join('\n') }); continue;
    }
    const nextKind = /^\s*[-*+]\s+|^\s*\d+[.)]\s+/.test(line) ? 'list' : /^\s*>/.test(line) ? 'quote' : 'paragraph';
    if (nextKind !== kind) { flush(); kind = nextKind; }
    buffer.push(line);
  }
  flush();
  return { blocks, inCode };
}

export function documentReadingBlocks(chunks: Chunk[]): ReadingBlock[][] {
  let inCode = false;
  return chunks.map(chunk => { const parsed = readingBlocks(chunk.content, inCode); inCode = parsed.inCode; return parsed.blocks; });
}
