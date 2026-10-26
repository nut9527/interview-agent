import type * as UI from './api.d';
import type * as Jobs from './targetJob';
import type { Schemas } from './generated/openapi';

type Equal<A, B> = [A] extends [B] ? [B] extends [A] ? true : false : false;
type Assert<T extends true> = T;

/** Check every field recursively, including arrays and enum values. Legacy optional/null fields
 * stay supported by the UI; OpenAPI validation/required/nullable changes are gated against Git's base. */
type Shape<F, B> = ValueShape<NonNullable<F>, NonNullable<B>>;
type ValueShape<F, B> = [B] extends [string] ? [F] extends [string] ? string extends B ? true : Equal<F, B> : false
  : [B] extends [number] ? Equal<F, number>
  : [B] extends [boolean] ? [F] extends [boolean] ? true : false
  : [B] extends [Array<infer E>] ? [F] extends [Array<infer V>] ? Shape<V, E> : false
  : [B] extends [object] ? [F] extends [object] ? Equal<keyof F, keyof B> extends true
    ? false extends { [K in keyof B]-?: K extends keyof F ? Shape<F[K], B[K]> : false }[keyof B] ? false : true
    : false : false
  : unknown extends B ? true : Equal<F, B>;

// This is .ts, not .d.ts: skipLibCheck must never suppress these assertions.
export type ApiContractChecks = [
  Assert<Shape<UI.HealthResponse, Schemas['cn.edu.whu.zhizhida.health.HealthResponse']>>,
  Assert<Shape<UI.RegisterRequest, Schemas['cn.edu.whu.zhizhida.auth.api.AuthRequest']>>,
  Assert<Shape<UI.LoginRequest, Omit<Schemas['cn.edu.whu.zhizhida.auth.api.AuthRequest'], 'displayName'>>>,
  Assert<Shape<UI.AuthResponse, Schemas['cn.edu.whu.zhizhida.auth.api.AuthResponse']>>,
  Assert<Shape<UI.UserProfile, Schemas['cn.edu.whu.zhizhida.auth.api.UserProfile']>>,
  Assert<Shape<UI.UpdateUserRequest, Schemas['cn.edu.whu.zhizhida.auth.api.UserProfileRequest']>>,
  Assert<Shape<UI.ChangePasswordRequest, Schemas['cn.edu.whu.zhizhida.auth.api.ChangePasswordRequest']>>,
  Assert<Shape<UI.DeleteAccountRequest, Schemas['cn.edu.whu.zhizhida.auth.api.DeleteAccountRequest']>>,
  Assert<Shape<UI.ResumeProfileRequest, Schemas['cn.edu.whu.zhizhida.resume.api.ResumeProfileRequest']>>,
  Assert<Shape<UI.ResumeProfile, Schemas['cn.edu.whu.zhizhida.resume.api.ResumeProfile']>>,
  Assert<Shape<UI.ResumeDetails, Schemas['cn.edu.whu.zhizhida.resume.api.ResumeDetails']>>,
  Assert<Shape<UI.ResumeVersionView, Schemas['cn.edu.whu.zhizhida.resume.api.ResumeVersionView']>>,
  Assert<Shape<UI.ResumeVersionSummary, Schemas['cn.edu.whu.zhizhida.resume.api.ResumeVersionSummary']>>,
  Assert<Shape<UI.ResumeCompareRequest, Schemas['cn.edu.whu.zhizhida.resume.api.ResumeCompareRequest']>>,
  Assert<Shape<UI.ResumeComparison, Schemas['cn.edu.whu.zhizhida.resume.api.ResumeComparison']>>,
  Assert<Shape<UI.OptimizeResumeRequest, Schemas['cn.edu.whu.zhizhida.resume.api.AiOptimizeRequest']>>,
  Assert<Shape<UI.OptimizeResumeResponse, Schemas['cn.edu.whu.zhizhida.resume.api.AiOptimizeResponse']>>,
  Assert<Shape<Jobs.TargetJobInput, Schemas['cn.edu.whu.zhizhida.jobs.api.TargetJobRequest']>>,
  Assert<Shape<Jobs.TargetJob, Schemas['cn.edu.whu.zhizhida.jobs.api.TargetJob']>>,
  Assert<Shape<Jobs.TargetJobSummary, Schemas['cn.edu.whu.zhizhida.jobs.api.TargetJob$Summary']>>,
  Assert<Shape<Jobs.TargetExtraction, Schemas['cn.edu.whu.zhizhida.jobs.api.TargetJob$Extraction']>>,
  Assert<Shape<Jobs.TargetAnalysis, Schemas['cn.edu.whu.zhizhida.jobs.api.TargetJob$Analysis']>>,
  Assert<Shape<UI.InterviewSessionRequest, Schemas['cn.edu.whu.zhizhida.interview.api.InterviewStartRequest']>>,
  Assert<Shape<UI.InterviewSession, Schemas['cn.edu.whu.zhizhida.interview.api.InterviewSession']>>,
  Assert<Shape<UI.SubmitAnswerRequest, Schemas['cn.edu.whu.zhizhida.interview.api.InterviewAnswerRequest']>>,
  Assert<Shape<UI.InterviewHistoryItem, Schemas['cn.edu.whu.zhizhida.interview.api.InterviewHistoryItem']>>,
  Assert<Shape<UI.PageResult<UI.InterviewHistoryItem>, Schemas['cn.edu.whu.zhizhida.common.PageResultCn.edu.whu.zhizhida.interview.api.InterviewHistoryItem']>>,
];

// Compiler-checked counterexamples; an accidentally weakened checker makes these directives fail.
// @ts-expect-error field removal must fail
export type RejectMissing = Assert<Shape<{ id: number }, { id: number; revision: number }>>;
// @ts-expect-error stale frontend fields must fail
export type RejectExtra = Assert<Shape<{ id: number; oldName: string }, { id: number }>>;
// @ts-expect-error nested array element types must fail
export type RejectNested = Assert<Shape<{ rows: { id: string }[] }, { rows: { id: number }[] }>>;
// @ts-expect-error new backend enum values require client review
export type RejectEnum = Assert<Shape<'OPEN', 'OPEN' | 'CLOSED'>>;
