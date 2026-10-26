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

export interface UpdateUserRequest {
  displayName: string;
  targetRole: string;
  skillTags?: string[];
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

/** @deprecated 使用 ResumeProfileRequest 替代 */
export interface CreateResumeRequest extends ResumeProfileRequest {}

export interface OptimizeResumeRequest {
  rawText: string;
  targetRole: string;
}

export interface ResumeImprovement {
  category: string;
  issue: string;
  action: string;
  example: string;
}

export interface OptimizeResumeResponse {
  optimizedText: string;
  suggestions: string[];
  improvements: ResumeImprovement[];
  targetKeywords: string[];
  optimizationSummary: string;
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

// ─── 版本管理 ───

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

// ─── 版本对比 ───

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

// ─── 岗位 ───


export interface InterviewSessionRequest {
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

export type InterviewMode = 'GENERAL' | 'BACKEND_BASICS' | 'PROJECT_DEEP_DIVE';

export type InterviewSessionState = NonNullable<import('./generated/openapi').Schemas['cn.edu.whu.zhizhida.interview.api.InterviewSession']['state']>;

export type SpeakerType = 'system' | 'interviewer' | 'user';

export interface InterviewTurn {
  speaker: SpeakerType;
  content: string;
  requestId?: string;
}

export interface InterviewSession {
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
  version: string;
  executionMode: string;
  plan: Array<{ id: string; name: string; objective: string; materialAnchors?: Array<{
    kind: 'PROJECT' | 'INTERNSHIP' | 'SKILLS' | 'JOB' | 'SUMMARY'; source: string; quote: string;
  }> }>;
  activeCompetencyId: string;
  evaluations: Array<{ round: number; competencyId: string; answeredProbe?: InterviewProbe | null; answeredIntent?: string | null; decision: {
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
  } }>;
  digest?: Array<{ round: number; competencyId: string; level: 'INSUFFICIENT' | 'PARTIAL' | 'STRONG'; evidence: string; gap: string; answeredProbe?: InterviewProbe | null }>;
  practiceMemory?: Array<{ competencyId: string; probe: InterviewProbe; attempts: number; insufficient: number; partial: number }>;
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

export interface SubmitAnswerRequest {
  answer: string;
  requestId?: string;
  expectedTurn?: number;
}

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
