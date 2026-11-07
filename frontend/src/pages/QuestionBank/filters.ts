import type { QuestionFilters } from '@/types/questionBank';
const positive = (value: string | null, fallback: number, max: number) => {
  const number = Number(value);
  return Number.isInteger(number) && number >= 1 && number <= max ? number : fallback;
};
// URL 承载列表状态，刷新、详情链接和历史导航均可恢复。
export function readFilters(search: URLSearchParams): QuestionFilters {
  return { page: positive(search.get('page'), 1, 1000000), size: positive(search.get('size'), 12, 100),
    topic: search.get('topic') || undefined, difficulty: search.get('difficulty') || undefined,
    keyword: search.get('keyword') || undefined };
}
export function updateFilters(search: URLSearchParams, values: Partial<QuestionFilters>): URLSearchParams {
  const next = new URLSearchParams(search);
  for (const [key, value] of Object.entries(values)) {
    if (value === undefined || value === '') next.delete(key);
    else next.set(key, String(value));
  }
  if (!Object.prototype.hasOwnProperty.call(values, 'page')) next.set('page', '1');
  return next;
}

