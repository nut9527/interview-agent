import type { Schemas } from './generated/openapi';
import type { ResponseView } from './responseView';

type KnowledgeView<Name extends keyof Schemas> = ResponseView<Schemas[Name]>;
export type KnowledgeSummary = KnowledgeView<'cn.edu.whu.zhizhida.knowledge.api.KnowledgeDocument$Summary'>;
export type KnowledgeDocument = KnowledgeView<'cn.edu.whu.zhizhida.knowledge.api.KnowledgeDocument'>;
export type KnowledgeImport = KnowledgeView<'cn.edu.whu.zhizhida.knowledge.api.KnowledgeImport'>;
export type KnowledgeImportJob = Omit<KnowledgeView<'cn.edu.whu.zhizhida.knowledge.api.KnowledgeImportJob'>, 'documentId'> & {
  documentId?: string | null;
};
export type KnowledgeHit = KnowledgeView<'cn.edu.whu.zhizhida.knowledge.api.KnowledgeDocument$Hit'>;
export type KnowledgeAnswer = Omit<KnowledgeView<'cn.edu.whu.zhizhida.knowledge.api.KnowledgeAnswer'>, 'historyId'> & {
  historyId?: string;
};
export type KnowledgeAnswerSource = KnowledgeView<'cn.edu.whu.zhizhida.knowledge.api.KnowledgeAnswerRecord$Source'>;
export type KnowledgeAnswerSummary = KnowledgeView<'cn.edu.whu.zhizhida.knowledge.api.KnowledgeAnswerRecord$Summary'>;
export type KnowledgeAnswerRecord = Omit<KnowledgeView<'cn.edu.whu.zhizhida.knowledge.api.KnowledgeAnswerRecord'>, 'parentAnswerId' | 'answer'> & {
  parentAnswerId?: string | null; answer: KnowledgeAnswer;
};
export type KnowledgeOrganization = KnowledgeView<'cn.edu.whu.zhizhida.knowledge.api.KnowledgeLearning$Organization'>;
export type SelfTestSelection = KnowledgeView<'cn.edu.whu.zhizhida.knowledge.api.KnowledgeLearning$Selection'>;
/** These controls are always populated in the generation form. */
export type GenerationOptions = Pick<KnowledgeView<'cn.edu.whu.zhizhida.knowledge.api.KnowledgeLearning$Generate'>, 'intent' | 'difficulty' | 'focus'>;
export type SelfTestQuestion = KnowledgeView<'cn.edu.whu.zhizhida.knowledge.api.KnowledgeLearning$Question'>;
export type KnowledgeSelfTest = KnowledgeView<'cn.edu.whu.zhizhida.knowledge.api.KnowledgeLearning$SelfTest'>;
export type KnowledgeFeedback = KnowledgeView<'cn.edu.whu.zhizhida.knowledge.api.KnowledgeLearning$Feedback'>;
export type FeedbackReason = KnowledgeFeedback['reasons'][number];
