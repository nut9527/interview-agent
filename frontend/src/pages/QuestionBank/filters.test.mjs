import test from 'node:test';
import assert from 'node:assert/strict';
import { readFilters, updateFilters } from './filters.ts';

test('URL keeps combination filters, pagination and refresh state', () => {
  const params = new URLSearchParams('topic=JAVA&difficulty=STANDARD&keyword=record&page=3&size=24');
  assert.deepEqual(readFilters(params), { topic: 'JAVA', difficulty: 'STANDARD', keyword: 'record', page: 3, size: 24 });
  assert.equal(readFilters(updateFilters(params, { topic: 'REDIS' })).page, 1);
  assert.equal(readFilters(updateFilters(params, { keyword: '' })).keyword, undefined);
  assert.equal(readFilters(updateFilters(params, { page: 4 })).page, 4);
  assert.equal(params.get('page'), '3');
});
test('malformed URL page sizes cannot become unbounded requests', () => {
  for (const value of ['-1', '0', 'Infinity', 'abc', '1.5', '1000001']) {
    const filters = readFilters(new URLSearchParams('page=' + value + '&size=' + value));
    assert.equal(filters.page, 1);
    assert.equal(filters.size, 12);
  }
});
