export interface ApiResponse<T> {
  code?: import('./generated/openapi').ErrorCode | null;
  success: boolean;
  message: string;
  data: T | null;
}

export interface UserProfile {
  id: number;
  username: string;
  displayName: string;
  targetRole: string;         // 新注册用户初始值为 ""
  skillTags: string[];        // 新注册用户初始值为 []
  demo: boolean;              // true 表示本次登录使用独立的限时演示空间
}

export interface RegisterRequest {
  username: string;
  password: string;
  displayName?: string;
}

export interface LoginRequest {
  username: string;
  password: string;
}

export interface AuthResponse {
  token: string;
  expiresAt: string;
  user: UserProfile;
}

export interface ChangePasswordRequest {
  currentPassword: string;
  newPassword: string;
}

export interface DeleteAccountRequest {
  currentPassword: string;
}

/** 简历写请求（创建/更新时使用，不含 id） */
export interface ResumeProfileRequest {
  details?: ResumeDetails | null;
  ownerName: string;                    // 必填、非空
  targetRole: string;                   // 必填、非空
  summary?: string | null;
  skills?: string[] | null;
  educationExperiences?: string[] | null;
  projectExperiences?: string[] | null;
  internshipExperiences?: string[] | null;
  certificates?: string[] | null;
  templateKey?: string | null;          // 默认 classic
  revision?: number;                   // 更新时携带读取到的修订号
}

/** 简历响应类型（含后端生成的 id 与标准化字段） */
export interface ResumeProfile {
  details?: ResumeDetails | null;
  id: number;
  ownerName: string;
  targetRole: string;
  summary: string | null;
  skills: string[];
  educationExperiences: string[];
  projectExperiences: string[];
  internshipExperiences: string[];
  certificates: string[];
  templateKey: ResumeTemplateKey;
  revision?: number;                   // 兼容历史快照；真实后端始终返回
}

export interface DraftOptimizationEntry {
  kind: 'summary' | 'skill' | 'projectExperience' | 'projectBackground' | 'projectContribution' | 'internshipExperience';
  text: string;
  projectName?: string;
}

export interface DraftOptimizationRequest {
  targetRole?: string;
  entries: DraftOptimizationEntry[];
}

export interface DraftOptimizationResponse {
  changes: Array<{ index: number; text: string }>;
}

export interface ResumeDetails {
  graduationDate?: string | null;
  availability?: string | null;
  contact?: {
    phone?: string | null;
    email?: string | null;
    location?: string | null;
    links?: Array<{ label: string; url: string }>;
  } | null;
  education?: Array<{ school: string; major: string; degree: string; startDate: string; endDate: string }>;
  projects: Array<{ name: string; role?: string; period?: string; background?: string; contributions: string[]; techStack?: string[] }>;
}

export type ResumeTemplateKey = 'classic' | 'modern' | 'compact' | 'campus' | 'minimal';


export type ResumeChangeType = 'CREATED' | 'UPDATED' | 'IMPORTED' | 'TEMPLATE' | 'RESTORED' | 'CURRENT';

export interface ResumeVersionView {
  id: number | null;              // CURRENT 临时视图为 null
  resumeId: number;
  versionNumber: number;          // CURRENT 临时视图为 0
  changeType: ResumeChangeType;
  changeNote: string;
  createdAt: string | null;       // CURRENT 临时视图为 null
  snapshot: ResumeProfile;
}


export interface ResumeReference {
  resumeId: number;               // 必填
  versionId?: number | null;      // 为空表示当前保存内容
}

export interface ResumeCompareRequest {
  left: ResumeReference;
  right: ResumeReference;
}

export interface ResumeFieldDiff {
  field: string;
  label: string;                  // 中文标签
  leftValues: string[];
  rightValues: string[];
  added: string[];
  removed: string[];
  changed: boolean;
}

export interface ResumeComparison {
  left: ResumeVersionView;
  right: ResumeVersionView;
  changedFieldCount: number;
  fields: ResumeFieldDiff[];
}



export interface InterviewSessionRequest {
  experienceMode?: 'TEACHING' | 'FORMAL';
  timeLimitMinutes?: number;
  knowledgeDocumentIds?: string[];
  questionId?: string;
  questionVersion?: number;
  trainingTopic?: InterviewTrainingTopic;
  retrySessionId?: string;
  retryRound?: number;
  targetJobId?: string;
  targetJobRevision?: number;
  targetRole: string;
  resumeSummary?: string;
  resumeId?: number;
  jobDescription?: string;
  mode: InterviewMode;
  totalRounds: number;
  difficulty?: InterviewDifficulty;
}

export type InterviewDifficulty = 'BEGINNER' | 'STANDARD' | 'ADVANCED';

export type InterviewTrainingTopic = 'DATABASE' | 'CONCURRENCY' | 'PERFORMANCE' | 'PROJECT';

export interface InterviewTraining {
  objective?: string | null;
  knowledge?: KnowledgeSnapshot[] | null;
  question?: QuestionSnapshot | null;
  topic?: InterviewTrainingTopic | null;
  retry?: { sourceSessionId: string; sourceRound: number; competencyId: string; competencyName: string;
    question: string; answer: string; level: 'INSUFFICIENT' | 'PARTIAL' | 'STRONG'; evidence: string; gap: string } | null;
}

export interface KnowledgeSnapshot {
  id: string; title: string; sourceName: string; version: number; sha256: string; content: string;
  excerpts: Array<{ chunkId: string; heading: string; startLine: number; endLine: number; content: string }>;
}
export type KnowledgeReference = Pick<KnowledgeSnapshot, 'id' | 'title' | 'version' | 'sha256' | 'excerpts'>;

export type InterviewMode = 'GENERAL' | 'BACKEND_BASICS' | 'PROJECT_DEEP_DIVE';

export interface QuestionSnapshot {
  id: string; version: number; title: string; question: string; topic: string; competencyId: string;
  difficulty: InterviewDifficulty; tags: string[]; keyPoints: string[]; referenceAnswer: string;
  commonMistakes: string[]; followUps: string[]; sourceType: string; sourceTitle: string | null; sourceUrl: string | null;
}

export type InterviewSessionState = NonNullable<import('./generated/openapi').Schemas['cn.edu.whu.zhizhida.interview.api.InterviewSession']['state']>;

export type SpeakerType = 'system' | 'interviewer' | 'user';

export interface InterviewTurn {
  speaker: SpeakerType;
  content: string;
  requestId?: string;
}

export interface InterviewSession {
  sourceRevoked?: boolean | null;
  experience?: { format: 'TEACHING' | 'FORMAL'; timeLimitMinutes: number | null; startedAt: string } | null;
  difficulty?: InterviewDifficulty;
  agent?: InterviewAgentState | null;
  preparation?: InterviewPreparation | null;
  sessionId: string;
  targetRole: string;
  resumeId: number | null;
  resumeSnapshot: string;
  resumeRevision: number | null;
  jobId: number | null;
  jobSnapshot: string;
  mode: InterviewMode;
  totalRounds: number;
  answerLeaseUntil: string | null;
  state: InterviewSessionState;
  turnCount: number;
  turns: InterviewTurn[];
}

export type InterviewProbe = 'OPENING' | 'EVIDENCE' | 'QUANTIFY' | 'COUNTEREXAMPLE' | 'TRANSFER' | 'TRADEOFF' | 'CLARIFY' | 'NONE';

export interface InterviewAgentState {
  planningRevisions?: import('./generated/openapi').Schemas['cn.edu.whu.zhizhida.interview.api.InterviewPlanning$PlanRevision'][];
  training?: InterviewTraining | null;
  version: string;
  executionMode: string;
  plan: Array<{ id: string; name: string; objective: string; materialAnchors?: Array<{
    kind: 'PROJECT' | 'INTERNSHIP' | 'SKILLS' | 'JOB' | 'SUMMARY'; source: string; quote: string;
  }> }>;
  activeCompetencyId: string;
  evaluations: Array<{ round: number; competencyId: string; answeredProbe?: InterviewProbe | null; answeredIntent?: string | null; knowledgeSources?: KnowledgeReference[] | null;
    assessment?: import('./generated/openapi').Schemas['cn.edu.whu.zhizhida.evaluation.api.AssessmentResult'] | null; decision: {
    level: 'INSUFFICIENT' | 'PARTIAL' | 'STRONG';
    evidence: string;
    gap: string;
    action: 'FOLLOW_UP' | 'ADVANCE' | 'FINISH';
    nextCompetencyId: string;
    reason: string;
    practice: string;
    probe?: InterviewProbe | null;
    intent?: string | null;
    questionCompetencyId?: string | null;
    focus?: {
      dimension: 'EVIDENCE_SUFFICIENCY' | 'TECHNICAL_CORRECTNESS' | 'CAUSAL_EXPLANATION' | 'TRADEOFFS' | 'RESPONSIBILITY' | 'EXPRESSION';
      scenario: 'CURRENT' | 'FAILURE' | 'ALTERNATIVE' | 'NEW_CONTEXT' | 'MEASUREMENT';
      claimToVerify: string;
      expectedEvidence: string;
    } | null;
  } }>;
  digest?: Array<{ round: number; competencyId: string; level: 'INSUFFICIENT' | 'PARTIAL' | 'STRONG'; evidence: string; gap: string; answeredProbe?: InterviewProbe | null }>;
  practiceMemory?: Array<{ competencyId: string; probe: InterviewProbe; attempts: number; insufficient: number; partial: number }>;
  memoryReferences?: Array<{ observationId: string; revision: number; sourceSessionId: string; sourceVersion: string;
    sourceRound: number; competencyId: string; dimension: string; rubricVersion: string; executionMode: string; usedAtRound: number }>;
}

export interface InterviewPreparation {
  assessmentId: string;
  diagnostic: Record<string, unknown>;
  projectField: string;
  projectSnapshot: ResumeDetails['projects'][number];
  targetJobSnapshot: import('./targetJob').TargetJob | null;
  topics: string[];
  followUps: Array<{ turn: number; question: string; answer: string; feedback: string }>;
}


export interface InterviewHistoryItem {
  sessionId: string;
  targetRole: string;
  resumeId: number | null;
  mode: InterviewMode;
  totalRounds: number;
  state: InterviewSessionState;
  turnCount: number;
  answeredCount: number;
  preview: string;
  createdAt: string;
  updatedAt: string;
}

export type SubmitAnswerRequest = import('./generated/openapi').Schemas['cn.edu.whu.zhizhida.interview.api.InterviewRun$Answer'];

export interface HealthResponse {
  status: string;
  service: string;
  version: string;
  storage: string;
  aiProvider: string;
  features: string[];
  time: string;
}

export interface PageResult<T> { items: T[]; total: number; page: number; size: number }
export type ResumeVersionSummary = Omit<ResumeVersionView, 'snapshot'>;
