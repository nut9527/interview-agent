import assert from 'node:assert/strict';
import test from 'node:test';
import { splitKnowledgeText, PAGE_SIZE } from './textPages.ts';

test('long review pages preserve every Unicode character, CRLF, blank line and source position', () => {
  for (const text of ['', 'x'.repeat(PAGE_SIZE - 1) + '🙂\r\n尾段', 'x'.repeat(PAGE_SIZE - 1) + '\r\n尾段',
    '# 标题\r\n\r\n' + '缓存🙂正文\r\n\r\n'.repeat(16000), '字'.repeat(2000000)]) {
    const pages = splitKnowledgeText(text);
    assert.equal(pages.join(''), text);
    for (let i = 0; i < pages.length - 1; i++) {
      assert.ok(pages[i].length <= PAGE_SIZE + 1);
      assert.ok(!/[\uD800-\uDBFF]$/.test(pages[i]));
      assert.ok(!(pages[i].endsWith('\r') && pages[i + 1].startsWith('\n')));
    }
  }
});
test('editing first, middle and last pages retains untouched text and exact line endings', () => {
  const original = '段落🙂\r\n'.repeat(12000), pages = splitKnowledgeText(original);
  const untouched = pages.slice();
  pages[0] = '修正文序\r\n' + pages[0];
  const middle = Math.floor(pages.length / 2); pages[middle] += '\r\n补全缺失内容';
  pages[pages.length - 1] += '尾段纠正';
  const joined = pages.join('');
  assert.ok(joined.startsWith('修正文序\r\n')); assert.ok(joined.endsWith('尾段纠正'));
  for (let i = 1; i < pages.length - 1; i++) if (i !== middle) assert.equal(pages[i], untouched[i]);
  assert.equal(splitKnowledgeText(joined).join(''), joined);
});
