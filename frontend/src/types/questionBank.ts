import type { Schemas } from './generated/openapi';
import type { ResponseView } from './responseView';

/** UI filter state; pagination and empty filters are not response fields. */
export interface QuestionFilters {
  page: number; size: number; topic?: string; difficulty?: string; keyword?: string;
}

export type QuestionSummary = ResponseView<Schemas['cn.edu.whu.zhizhida.questionbank.api.Question$Summary']>;
export type Question = Omit<ResponseView<Schemas['cn.edu.whu.zhizhida.questionbank.api.Question']>, 'sourceTitle' | 'sourceUrl'> & {
  sourceTitle: string | null; sourceUrl: string | null;
};
export type QuestionProgress = ResponseView<Schemas['cn.edu.whu.zhizhida.interview.api.QuestionPracticeHistory$Progress']>;
type Practice = ResponseView<Schemas['cn.edu.whu.zhizhida.interview.api.QuestionPracticeHistory$Practice']>;
export type QuestionPractice = Omit<Practice, 'question' | 'rounds'> & {
  question: import('./api').QuestionSnapshot;
  rounds: Array<Omit<Practice['rounds'][number], 'level'> & {
    level: Practice['rounds'][number]['level'] | null;
  }>;
};
export type QuestionDraw = ResponseView<Schemas['cn.edu.whu.zhizhida.questionbank.api.Question$Draw']>;
export type QuestionMetadata = ResponseView<Schemas['cn.edu.whu.zhizhida.questionbank.api.Question$Metadata']>;
