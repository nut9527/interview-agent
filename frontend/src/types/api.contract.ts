import type * as UI from './api.d';
import type * as Jobs from './targetJob';
import type * as Questions from './questionBank';
import type * as Knowledge from './knowledge';
import type * as InterviewLearning from '../api/modules/interviewLearning';
import type { ResumeImportResult } from '../api/modules/resume';
import type { Schemas } from './generated/openapi';

type Equal<A, B> = [A] extends [B] ? [B] extends [A] ? true : false : false;
type Assert<T extends true> = T;

/** 递归检查所有字段、数组及枚举值，UI 继续兼容历史可选和可空字段。
 * OpenAPI 校验、required 和 nullable 的变更仍与 Git 基线比较。 */
type Shape<F, B> = ValueShape<NonNullable<F>, NonNullable<B>>;
type ValueShape<F, B> = [B] extends [string] ? [F] extends [string] ? string extends B ? true : Equal<F, B> : false
  : [B] extends [number] ? Equal<F, number>
  : [B] extends [boolean] ? [F] extends [boolean] ? true : false
  : [B] extends [Array<infer E>] ? [F] extends [Array<infer V>] ? Shape<V, E> : false
  : [B] extends [object] ? [F] extends [object] ? Equal<keyof F, keyof B> extends true
    ? false extends { [K in keyof B]-?: K extends keyof F ? Shape<F[K], B[K]> : false }[keyof B] ? false : true
    : false : false
  : unknown extends B ? true : Equal<F, B>;

// 使用 .ts 而非 .d.ts，避免 skipLibCheck 跳过契约断言。
export type ApiContractChecks = [
  Assert<Shape<Knowledge.KnowledgeSummary, Schemas['cn.edu.whu.zhizhida.knowledge.api.KnowledgeDocument$Summary']>>,
  Assert<Shape<Knowledge.KnowledgeDocument, Schemas['cn.edu.whu.zhizhida.knowledge.api.KnowledgeDocument']>>,
  Assert<Shape<Knowledge.KnowledgeHit, Schemas['cn.edu.whu.zhizhida.knowledge.api.KnowledgeDocument$Hit']>>,
  Assert<Shape<Knowledge.SelfTestSelection, Schemas['cn.edu.whu.zhizhida.knowledge.api.KnowledgeLearning$Selection']>>,
  Assert<Shape<Knowledge.SelfTestQuestion, Schemas['cn.edu.whu.zhizhida.knowledge.api.KnowledgeLearning$Question']>>,
  Assert<Shape<Jobs.TargetRequirement, Schemas['cn.edu.whu.zhizhida.jobs.api.TargetJob$Requirement']>>,
  Assert<Shape<InterviewLearning.InterviewResources, Schemas['cn.edu.whu.zhizhida.interview.api.InterviewResources']>>,
  Assert<Shape<InterviewLearning.TrainingTask, Schemas['cn.edu.whu.zhizhida.interview.api.TrainingTask']>>,
  Assert<Shape<Knowledge.KnowledgeOrganization, Schemas['cn.edu.whu.zhizhida.knowledge.api.KnowledgeLearning$Organization']>>,
  Assert<Shape<Knowledge.KnowledgeSelfTest, Schemas['cn.edu.whu.zhizhida.knowledge.api.KnowledgeLearning$SelfTest']>>,
  Assert<Shape<Knowledge.KnowledgeFeedback, Schemas['cn.edu.whu.zhizhida.knowledge.api.KnowledgeLearning$Feedback']>>,
  Assert<Shape<Knowledge.KnowledgeImport, Schemas['cn.edu.whu.zhizhida.knowledge.api.KnowledgeImport']>>,
  Assert<Shape<Knowledge.KnowledgeImportJob, Schemas['cn.edu.whu.zhizhida.knowledge.api.KnowledgeImportJob']>>,
  Assert<Shape<Knowledge.KnowledgeAnswer, Schemas['cn.edu.whu.zhizhida.knowledge.api.KnowledgeAnswer']>>,
  Assert<Shape<Knowledge.KnowledgeAnswerSource, Schemas['cn.edu.whu.zhizhida.knowledge.api.KnowledgeAnswerRecord$Source']>>,
  Assert<Shape<Knowledge.KnowledgeAnswerSummary, Schemas['cn.edu.whu.zhizhida.knowledge.api.KnowledgeAnswerRecord$Summary']>>,
  Assert<Shape<Knowledge.KnowledgeAnswerRecord, Schemas['cn.edu.whu.zhizhida.knowledge.api.KnowledgeAnswerRecord']>>,
  Assert<Shape<UI.QuestionSnapshot, Schemas['cn.edu.whu.zhizhida.interview.api.QuestionSnapshot']>>,
  Assert<Shape<Questions.QuestionProgress, Schemas['cn.edu.whu.zhizhida.interview.api.QuestionPracticeHistory$Progress']>>,
  Assert<Shape<Questions.QuestionPractice, Schemas['cn.edu.whu.zhizhida.interview.api.QuestionPracticeHistory$Practice']>>,
  Assert<Shape<Questions.QuestionDraw, Schemas['cn.edu.whu.zhizhida.questionbank.api.Question$Draw']>>,
  Assert<Shape<Questions.Question, Schemas['cn.edu.whu.zhizhida.questionbank.api.Question']>>,
  Assert<Shape<Questions.QuestionSummary, Schemas['cn.edu.whu.zhizhida.questionbank.api.Question$Summary']>>,
  Assert<Shape<Questions.QuestionMetadata, Schemas['cn.edu.whu.zhizhida.questionbank.api.Question$Metadata']>>,
  Assert<Shape<UI.PageResult<Questions.QuestionSummary>, Schemas['cn.edu.whu.zhizhida.common.PageResultCn.edu.whu.zhizhida.questionbank.api.Question$Summary']>>,
  Assert<Shape<UI.HealthResponse, Schemas['cn.edu.whu.zhizhida.health.HealthResponse']>>,
  Assert<Shape<UI.RegisterRequest, Schemas['cn.edu.whu.zhizhida.auth.api.AuthRequest']>>,
  Assert<Shape<UI.LoginRequest, Omit<Schemas['cn.edu.whu.zhizhida.auth.api.AuthRequest'], 'displayName'>>>,
  Assert<Shape<UI.AuthResponse, Schemas['cn.edu.whu.zhizhida.auth.api.AuthResponse']>>,
  Assert<Shape<UI.UserProfile, Schemas['cn.edu.whu.zhizhida.auth.api.UserProfile']>>,
  Assert<Shape<UI.ChangePasswordRequest, Schemas['cn.edu.whu.zhizhida.auth.api.ChangePasswordRequest']>>,
  Assert<Shape<UI.DeleteAccountRequest, Schemas['cn.edu.whu.zhizhida.auth.api.DeleteAccountRequest']>>,
  Assert<Shape<UI.ResumeProfileRequest, Schemas['cn.edu.whu.zhizhida.resume.api.ResumeProfileRequest']>>,
  Assert<Shape<{ draft: UI.ResumeProfileRequest; recognitionMode: 'AI' | 'LOCAL'; warnings: Array<{
    field: string; message: string;
    code: 'MISSING_FIELD' | 'TYPE_MISMATCH' | 'EMPTY_VALUE' | 'LIMIT_EXCEEDED' | 'INVALID_VALUE' | 'INVALID_JSON' | 'SEMANTIC_MISMATCH';
  }> }, ResumeImportResult>>,
  Assert<Shape<UI.ResumeProfile, Schemas['cn.edu.whu.zhizhida.resume.api.ResumeProfile']>>,
  Assert<Shape<UI.ResumeDetails, Schemas['cn.edu.whu.zhizhida.resume.api.ResumeDetails']>>,
  Assert<Shape<UI.ResumeVersionView, Schemas['cn.edu.whu.zhizhida.resume.api.ResumeVersionView']>>,
  Assert<Shape<UI.ResumeVersionSummary, Schemas['cn.edu.whu.zhizhida.resume.api.ResumeVersionSummary']>>,
  Assert<Shape<UI.ResumeCompareRequest, Schemas['cn.edu.whu.zhizhida.resume.api.ResumeCompareRequest']>>,
  Assert<Shape<UI.ResumeComparison, Schemas['cn.edu.whu.zhizhida.resume.api.ResumeComparison']>>,
  Assert<Shape<UI.DraftOptimizationRequest, Schemas['cn.edu.whu.zhizhida.resume.api.ResumeDraftOptimizeRequest']>>,
  Assert<Shape<UI.DraftOptimizationResponse, Schemas['cn.edu.whu.zhizhida.resume.api.ResumeDraftOptimizeResponse']>>,
  Assert<Shape<Jobs.TargetJobInput, Schemas['cn.edu.whu.zhizhida.jobs.api.TargetJobRequest']>>,
  Assert<Shape<Jobs.TargetJob, Schemas['cn.edu.whu.zhizhida.jobs.api.TargetJob']>>,
  Assert<Shape<Jobs.TargetJobSummary, Schemas['cn.edu.whu.zhizhida.jobs.api.TargetJob$Summary']>>,
  Assert<Shape<Jobs.TargetExtraction, Schemas['cn.edu.whu.zhizhida.jobs.api.TargetJob$Extraction']>>,
  Assert<Shape<Jobs.TargetAnalysis, Schemas['cn.edu.whu.zhizhida.jobs.api.TargetJob$Analysis']>>,
  Assert<Shape<UI.InterviewSessionRequest, Schemas['cn.edu.whu.zhizhida.interview.api.InterviewStartRequest']>>,
  Assert<Shape<UI.InterviewSession, Schemas['cn.edu.whu.zhizhida.interview.api.InterviewSession']>>,
  Assert<Shape<UI.SubmitAnswerRequest, Schemas['cn.edu.whu.zhizhida.interview.api.InterviewRun$Answer']>>,
  Assert<Shape<UI.InterviewHistoryItem, Schemas['cn.edu.whu.zhizhida.interview.api.InterviewHistoryItem']>>,
  Assert<Shape<UI.PageResult<UI.InterviewHistoryItem>, Schemas['cn.edu.whu.zhizhida.common.PageResultCn.edu.whu.zhizhida.interview.api.InterviewHistoryItem']>>,
];

// 编译器校验反例；校验器意外放宽时，下方指令必须报错。
// @ts-expect-error field removal must fail
export type RejectMissing = Assert<Shape<{ id: number }, { id: number; revision: number }>>;
// @ts-expect-error stale frontend fields must fail
export type RejectExtra = Assert<Shape<{ id: number; oldName: string }, { id: number }>>;
// @ts-expect-error nested array element types must fail
export type RejectNested = Assert<Shape<{ rows: { id: string }[] }, { rows: { id: number }[] }>>;
// @ts-expect-error new backend enum values require client review
export type RejectEnum = Assert<Shape<'OPEN', 'OPEN' | 'CLOSED'>>;
