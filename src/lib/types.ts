export type Priority =
  | "technical depth"
  | "growth speed"
  | "brand"
  | "compensation"
  | "stability"
  | "mentorship";

export type AnalysisMode = "auto" | "heuristic" | "demo";
export type EngineMode = "heuristic" | "live";
export type SignalTone = "positive" | "mixed" | "negative" | "unclear";
export type ConfidenceLevel = "low" | "medium" | "high";
export type CapabilityMode = AnalysisMode;
export type WorkspaceRunStatus = "queued" | "running" | "completed" | "failed";

export type OfferInput = {
  id: string;
  company: string;
  role: string;
  city: string;
  compensation: string;
  stage: "startup" | "growth" | "big-tech";
  domain: string;
  workMode: "onsite" | "hybrid" | "remote";
  stack: string;
  managerSupport: "high" | "medium" | "low";
  executionStyle: "structured" | "balanced" | "chaotic";
  jdSignals: string;
  notes: string;
};

export type UserProfile = {
  target: string;
  riskAppetite: "low" | "medium" | "high";
  priorities: Priority[];
};

export type DecisionRequest = {
  userProfile: UserProfile;
  offers: OfferInput[];
};

export type SavedOfferRecord = OfferInput & {
  rawText: string;
  source: string;
  createdAt: string;
  updatedAt: string;
};

export type WorkspaceResponse = {
  id: string;
  name: string;
  userProfile: UserProfile;
  offers: SavedOfferRecord[];
  recentRuns: WorkspaceRunSummary[];
  updatedAt: string;
};

export type SaveWorkspaceOfferRequest = OfferInput & {
  rawText: string;
  source: string;
};

export type UpdateWorkspaceProfileRequest = UserProfile;

export type ParsedOfferDraft = {
  company: string;
  role: string;
  city: string;
  compensation: string;
  stage: OfferInput["stage"];
  domain: string;
  workMode: OfferInput["workMode"];
  stack: string;
  managerSupport: OfferInput["managerSupport"];
  executionStyle: OfferInput["executionStyle"];
  jdSignals: string;
  notes: string;
  rawText: string;
  source: string;
};

export type IntakeParseResponse = {
  draft: ParsedOfferDraft;
  confidenceScore: number;
  warnings: string[];
  extractedSignals: string[];
};

export type WorkspaceRunSummary = {
  id: string;
  requestedMode: AnalysisMode;
  engineMode: EngineMode;
  status: WorkspaceRunStatus;
  progressDetail: string;
  winner: string;
  summary: string;
  createdAt: string;
  completedAt: string;
};

export type WorkspaceRunJobAccepted = {
  id: string;
  requestedMode: AnalysisMode;
  status: WorkspaceRunStatus;
  progressDetail: string;
  analysis: DecisionAnalysis;
  createdAt: string;
};

export type WorkspaceRunDetail = {
  id: string;
  requestedMode: AnalysisMode;
  status: WorkspaceRunStatus;
  progressDetail: string;
  request: DecisionRequest;
  analysis: DecisionAnalysis;
  createdAt: string;
  startedAt: string;
  completedAt: string;
};

export type PipelineStep = {
  title: string;
  detail: string;
  status: "done" | "watch";
};

export type EvidenceItem = {
  label: string;
  sourceType: "user input" | "heuristic" | "web research" | "model fallback";
  freshness: "stable" | "fresh" | "needs live verification";
};

export type DimensionInsight = {
  key: string;
  title: string;
  weight: number;
  winner: string;
  scoreA: number;
  scoreB: number;
  verdict: string;
  liveAdjustment?: {
    deltaA: number;
    deltaB: number;
    companyAEffect: string;
    companyBEffect: string;
    summary: string;
  } | null;
  evidence: EvidenceItem[];
  risks: string[];
  followUps: string[];
};

export type OfferSnapshot = {
  id: string;
  company: string;
  role: string;
  overallScore: number;
  strengths: string[];
  watchouts: string[];
};

export type ResearchSignal = {
  title: string;
  detail: string;
  whyItMatters: string;
  sourceLabel: string;
  sourceUrl: string;
  freshness: "fresh" | "unclear";
};

export type LiveCompanyResearch = {
  company: string;
  summary: string;
  confidence: ConfidenceLevel;
  hiringSignal: SignalTone;
  businessSignal: SignalTone;
  technicalSignal: SignalTone;
  keySignals: ResearchSignal[];
  opportunities: string[];
  risks: string[];
  mustVerify: string[];
};

export type ResearchSource = {
  company: string;
  label: string;
  url: string;
  domain: string;
};

export type ResearchStageTiming = {
  stage: string;
  latencyMs: number;
  status: string;
};

export type LiveResearchReport = {
  provider: string;
  model: string;
  marketTakeaway: string;
  companySignals: LiveCompanyResearch[];
  sources: ResearchSource[];
  latencyMs: number;
  stageTimings: ResearchStageTiming[];
};

export type ModeCapability = {
  mode: CapabilityMode;
  enabled: boolean;
  label: string;
  detail: string;
  provider: string;
};

export type DecisionCapabilities = {
  generatedAt: string;
  modes: ModeCapability[];
  liveProvider: {
    enabled: boolean;
    provider: string;
    model: string;
    structuringProvider: string;
    detail: string;
  };
  retrieval: {
    enabled: boolean;
    provider: string;
    maxResults: number;
    minQualityScore: number;
    cacheTtlMinutes: number;
    maxFreshnessAgeDays: number;
    detail: string;
  };
};

export type DecisionAnalysis = {
  generatedAt: string;
  engine: {
    mode: EngineMode;
    requestedMode: AnalysisMode;
    label: string;
    detail: string;
  };
  recommendation: {
    winner: string;
    summary: string;
    rationale: string;
    caution: string;
  };
  pipeline: PipelineStep[];
  snapshots: OfferSnapshot[];
  dimensions: DimensionInsight[];
  freshness: {
    stableKnowledge: string[];
    needsVerification: string[];
  };
  liveResearch: LiveResearchReport | null;
};
