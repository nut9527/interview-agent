export interface TextEdit {
  id: number;
  start: number;
  end: number;
  before: string;
  after: string;
}

/** Trim shared edges without splitting a Unicode code point. Offsets are JS string offsets. */
export function changedText(before: string, after: string) {
  const left = Array.from(before);
  const right = Array.from(after);
  let prefix = 0;
  let suffix = 0;
  while (prefix < left.length && prefix < right.length && left[prefix] === right[prefix]) prefix++;
  while (suffix < left.length - prefix && suffix < right.length - prefix
    && left[left.length - suffix - 1] === right[right.length - suffix - 1]) suffix++;
  const start = left.slice(0, prefix).join('').length;
  return {
    start, end: before.length - left.slice(left.length - suffix).join('').length,
    before: left.slice(prefix, left.length - suffix).join(''),
    after: right.slice(prefix, right.length - suffix).join(''),
  };
}

/** Align unchanged sentences/lines; split changed runs into independently selectable replacements. */
export function buildTextEdits(source: string, proposed: string): TextEdit[] {
  const segments = (text: string) => text.match(/[^。！？!?；;\n]+[。！？!?；;]?|\n|[。！？!?；;]/gu) ?? [];
  const left = segments(source);
  const right = segments(proposed);
  const edits: TextEdit[] = [];
  const add = (before: string, after: string, offset: number) => {
    if (before === after) return;
    const change = changedText(before, after);
    edits.push({ ...change, start: offset + change.start, end: offset + change.end, id: edits.length });
  };
  // Bound memory and the number of review cards for pathological punctuation-heavy input.
  if (left.length > 200 || right.length > 200) {
    add(source, proposed, 0);
    return edits;
  }
  const lcs = Array.from({ length: left.length + 1 }, () => new Uint16Array(right.length + 1));
  for (let i = left.length - 1; i >= 0; i--) {
    for (let j = right.length - 1; j >= 0; j--) {
      lcs[i][j] = left[i] === right[j] ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
    }
  }
  let i = 0, j = 0, offset = 0;
  while (i < left.length || j < right.length) {
    if (i < left.length && j < right.length && left[i] === right[j]) {
      offset += left[i++].length;
      j++;
      continue;
    }
    const deleted: string[] = [], inserted: string[] = [];
    while (i < left.length || j < right.length) {
      if (i < left.length && j < right.length && left[i] === right[j]) break;
      if (j < right.length && (i === left.length || lcs[i][j + 1] > lcs[i + 1][j])) inserted.push(right[j++]);
      else deleted.push(left[i++]);
    }
    const pairs = Math.min(deleted.length, inserted.length);
    for (let n = 0; n < pairs; n++) {
      add(deleted[n], inserted[n], offset);
      offset += deleted[n].length;
    }
    add(deleted.slice(pairs).join(''), inserted.slice(pairs).join(''), offset);
    offset += deleted.slice(pairs).join('').length;
  }
  return edits;
}

export function applyTextEdits(source: string, edits: TextEdit[]): string {
  let result = '', offset = 0;
  for (const edit of [...edits].sort((a, b) => a.start - b.start || a.id - b.id)) {
    result += source.slice(offset, edit.start) + edit.after;
    offset = edit.end;
  }
  return result + source.slice(offset);
}

/** Undo only the adopted span. Preserve later edits outside it; overlap is deliberately a conflict. */
export function undoText(before: string, after: string, current: string): string | null {
  if (current === after) return before;
  const inverse = changedText(after, before);
  const manual = changedText(after, current);
  let shift = 0;
  if (manual.start === inverse.start && (manual.end === manual.start || inverse.end === inverse.start)) return null;
  if (manual.end <= inverse.start) shift = manual.after.length - manual.before.length;
  else if (manual.start < inverse.end) return null;
  const start = inverse.start + shift, end = inverse.end + shift;
  if (current.slice(start, end) !== inverse.before) return null;
  return current.slice(0, start) + inverse.after + current.slice(end);
}
