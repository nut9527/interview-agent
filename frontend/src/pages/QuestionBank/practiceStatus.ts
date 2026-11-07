import type { QuestionProgress } from '@/types/questionBank';
export const practiceStatusLabel = (status?: QuestionProgress['status']) =>
  ({ UNPRACTICED: '未练过', PRACTICED: '已练过', NEEDS_REVIEW: '待复练' }[status || 'UNPRACTICED']);
