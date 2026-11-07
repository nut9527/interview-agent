import type { Schemas } from './generated/openapi';
import type { ResponseView } from './responseView';

export type TargetRequirement = Omit<ResponseView<Schemas['cn.edu.whu.zhizhida.jobs.api.TargetJob$Requirement']>, 'sourceText'> & {
  sourceText: string | null;
};
/** Form values are complete; blank optional backend fields use empty strings/null. */
export type TargetJobInput = Omit<ResponseView<Schemas['cn.edu.whu.zhizhida.jobs.api.TargetJobRequest']>, 'deadline' | 'requirements'> & {
  deadline: string | null; requirements: TargetRequirement[];
};
export type TargetJob = Omit<ResponseView<Schemas['cn.edu.whu.zhizhida.jobs.api.TargetJob']>, 'deadline' | 'requirements'> & {
  deadline: string | null; requirements: TargetRequirement[];
};
export type TargetJobSummary = Omit<ResponseView<Schemas['cn.edu.whu.zhizhida.jobs.api.TargetJob$Summary']>, 'deadline'> & {
  deadline: string | null;
};
export type TargetExtraction = Omit<ResponseView<Schemas['cn.edu.whu.zhizhida.jobs.api.TargetJob$Extraction']>, 'requirements'> & {
  requirements: TargetRequirement[];
};
type RequirementMatch = ResponseView<Schemas['cn.edu.whu.zhizhida.jobs.api.TargetJob$RequirementMatch']>;
export type TargetAnalysis = Omit<ResponseView<Schemas['cn.edu.whu.zhizhida.jobs.api.TargetJob$Analysis']>, 'requirements'> & {
  requirements: Array<Omit<RequirementMatch, 'requirement' | 'status'> & {
    requirement: TargetRequirement;
    status: 'REFERENCED' | 'UNKNOWN';
  }>;
};
