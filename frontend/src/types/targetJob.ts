export type RequirementKind = 'MUST' | 'BONUS' | 'UNKNOWN';
export interface TargetRequirement {
  id: string;
  text: string;
  kind: RequirementKind;
  keywords: string[];
  sourceText: string | null;
}
export interface TargetJobInput {
  revision: number;
  title: string;
  company: string;
  sourceName: string;
  sourceUrl: string;
  deadline: string | null;
  jdText: string;
  requirements: TargetRequirement[];
}
export interface TargetJob extends TargetJobInput { id: string; updatedAt: string }
export type TargetJobSummary = Pick<TargetJob, 'id' | 'revision' | 'title' | 'company' | 'sourceName' | 'deadline' | 'updatedAt'>;
export interface TargetExtraction { requirements: TargetRequirement[]; warnings: string[]; ruleVersion: string }
export interface TargetAnalysis {
  targetJobId: string;
  targetJobRevision: number;
  resumeId: number;
  resumeRevision: number;
  ruleVersion: string;
  requirements: {
    requirement: TargetRequirement;
    status: 'REFERENCED' | 'UNKNOWN';
    evidence: { fieldPath: string; label: string; quote: string; keywords: string[] }[];
    unreferencedKeywords: string[];
  }[];
  unknownInformation: string[];
}
