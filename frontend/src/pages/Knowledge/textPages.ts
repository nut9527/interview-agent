export const PAGE_SIZE = 12000;
/** 仅为编辑器分段；按原顺序拼接可还原全文，边界不拆开 UTF-16 代理对或 CRLF。 */
export function splitKnowledgeText(value: string) {
  const pages: string[] = []; let start = 0;
  while (start < value.length) {
    let end = Math.min(start + PAGE_SIZE, value.length);
    if (end < value.length) {
      const newline = value.lastIndexOf('\n', end);
      if (newline > end - 1000) end = newline + 1;
      if (/[\uD800-\uDBFF]/.test(value[end - 1])) end--;
      if (value[end - 1] === '\r' && value[end] === '\n') end--;
    }
    pages.push(value.slice(start, end)); start = end;
  }
  return pages.length ? pages : [''];
}
