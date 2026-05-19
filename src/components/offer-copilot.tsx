"use client";

import { useEffect, useEffectEvent, useState, useTransition } from "react";
import { demoRequest } from "@/lib/demo-data";
import type {
  AnalysisMode,
  DecisionCapabilities,
  DecisionAnalysis,
  DecisionRequest,
  IntakeParseResponse,
  OfferInput,
  ParsedOfferDraft,
  Priority,
  SaveWorkspaceOfferRequest,
  SavedOfferRecord,
  WorkspaceResponse,
  WorkspaceRunDetail,
  WorkspaceRunJobAccepted,
  WorkspaceRunStatus,
} from "@/lib/types";

const priorityOptions: Priority[] = [
  "technical depth",
  "growth speed",
  "mentorship",
  "stability",
  "compensation",
  "brand",
];

const workflowLabels = [
  "定义决策问题",
  "聚合证据",
  "识别风险与不确定性",
  "生成建议",
];

const HISTORY_STORAGE_KEY = "offer-decision-history-v2";

type HistoryEntry = {
  id: string;
  savedAt: string;
  mode: AnalysisMode;
  request: DecisionRequest;
  analysis: DecisionAnalysis;
};

function updateOffer(
  offers: OfferInput[],
  index: number,
  field: keyof OfferInput,
  value: string,
) {
  const next = [...offers];
  next[index] = {
    ...next[index],
    [field]: value,
  };
  return next;
}

function Pill({ children }: { children: React.ReactNode }) {
  return (
    <span className="rounded-full border border-border bg-white/70 px-3 py-1 text-[11px] font-medium uppercase tracking-[0.24em] text-text-secondary">
      {children}
    </span>
  );
}

function signalToneLabel(signal: string) {
  switch (signal) {
    case "positive":
      return "正向";
    case "negative":
      return "谨慎";
    case "mixed":
      return "混合";
    default:
      return "不明确";
  }
}

function signalToneClass(signal: string) {
  switch (signal) {
    case "positive":
      return "border-[#b7d7c2] bg-[#eef8f1] text-[#2c6b46]";
    case "negative":
      return "border-[#e3c0b6] bg-[#fdf0ea] text-[#9a4a33]";
    case "mixed":
      return "border-[#d8caa8] bg-[#fbf3de] text-[#8b6b26]";
    default:
      return "border-border bg-white/70 text-text-secondary";
  }
}

function deltaToneClass(delta: number) {
  if (delta > 0) {
    return "bg-[#eef8f1] text-[#2c6b46]";
  }
  if (delta < 0) {
    return "bg-[#fdf0ea] text-[#9a4a33]";
  }
  return "bg-white/75 text-text-secondary";
}

function formatDelta(delta: number) {
  if (delta > 0) {
    return `+${delta}`;
  }
  return `${delta}`;
}

function formatLatency(ms: number) {
  if (ms >= 1000) {
    return `${(ms / 1000).toFixed(1)}s`;
  }
  return `${ms}ms`;
}

function buildCapabilitiesUrl(analysisApiUrl: string) {
  if (analysisApiUrl.endsWith("/analyze")) {
    return analysisApiUrl.replace(/\/analyze$/, "/capabilities");
  }
  return `${analysisApiUrl.replace(/\/$/, "")}/capabilities`;
}

function buildWorkspaceBaseUrl(analysisApiUrl: string) {
  const normalized = analysisApiUrl.replace(/\/$/, "");
  if (normalized.endsWith("/decision/analyze")) {
    return normalized.replace(/\/decision\/analyze$/, "/workspace");
  }
  return `${normalized}/workspace`;
}

function toOfferInput(saved: SavedOfferRecord): OfferInput {
  return {
    id: saved.id,
    company: saved.company,
    role: saved.role,
    city: saved.city,
    compensation: saved.compensation,
    stage: saved.stage,
    domain: saved.domain,
    workMode: saved.workMode,
    stack: saved.stack,
    managerSupport: saved.managerSupport,
    executionStyle: saved.executionStyle,
    jdSignals: saved.jdSignals,
    notes: saved.notes,
  };
}

function parsedDraftToOffer(draft: ParsedOfferDraft, fallbackId: string): OfferInput {
  return {
    id: fallbackId,
    company: draft.company,
    role: draft.role,
    city: draft.city,
    compensation: draft.compensation,
    stage: draft.stage,
    domain: draft.domain,
    workMode: draft.workMode,
    stack: draft.stack,
    managerSupport: draft.managerSupport,
    executionStyle: draft.executionStyle,
    jdSignals: draft.jdSignals,
    notes: draft.notes,
  };
}

function modeButtonLabel(mode: AnalysisMode) {
  switch (mode) {
    case "auto":
      return "标准分析";
    case "demo":
      return "离线体验";
    default:
      return "快速判断";
  }
}

function priorityLabel(priority: Priority) {
  switch (priority) {
    case "technical depth":
      return "技术深度";
    case "growth speed":
      return "成长速度";
    case "mentorship":
      return "导师带教";
    case "stability":
      return "稳定性";
    case "compensation":
      return "薪酬";
    default:
      return "品牌";
  }
}

function riskAppetiteLabel(risk: DecisionRequest["userProfile"]["riskAppetite"]) {
  switch (risk) {
    case "low":
      return "低";
    case "medium":
      return "中";
    default:
      return "高";
  }
}

function stageLabel(stage: OfferInput["stage"]) {
  switch (stage) {
    case "startup":
      return "创业期";
    case "growth":
      return "成长期";
    default:
      return "大厂";
  }
}

function workModeLabel(mode: OfferInput["workMode"]) {
  switch (mode) {
    case "onsite":
      return "现场办公";
    case "hybrid":
      return "混合办公";
    default:
      return "远程办公";
  }
}

function managerSupportLabel(level: OfferInput["managerSupport"]) {
  switch (level) {
    case "high":
      return "高";
    case "medium":
      return "中";
    default:
      return "低";
  }
}

function executionStyleLabel(style: OfferInput["executionStyle"]) {
  switch (style) {
    case "structured":
      return "流程化";
    case "balanced":
      return "平衡";
    default:
      return "混乱";
  }
}

function confidenceLabel(level: string) {
  switch (level) {
    case "high":
      return "高";
    case "medium":
      return "中";
    case "low":
      return "低";
    default:
      return level;
  }
}

function sourceTypeLabel(sourceType: string) {
  switch (sourceType) {
    case "user input":
      return "用户输入";
    case "heuristic":
      return "规则判断";
    case "web research":
      return "公开检索";
    case "model fallback":
      return "模型降级";
    default:
      return sourceType;
  }
}

function freshnessLabel(freshness: string) {
  switch (freshness) {
    case "stable":
      return "稳定知识";
    case "fresh":
      return "新近信息";
    case "needs live verification":
      return "需实时核验";
    case "unclear":
      return "不明确";
    default:
      return freshness;
  }
}

function pipelineStatusLabel(status: string) {
  switch (status) {
    case "done":
      return "完成";
    case "watch":
      return "关注";
    default:
      return status;
  }
}

function engineLabel(mode: string) {
  switch (mode) {
    case "live":
      return "Java 实时研究引擎";
    case "heuristic":
      return "Java 规则引擎";
    default:
      return mode;
  }
}

function engineModeLabel(mode: string) {
  switch (mode) {
    case "live":
      return "实时";
    case "heuristic":
      return "规则";
    default:
      return mode;
  }
}

function stageStatusLabel(status: string) {
  if (status.startsWith("done")) {
    return status.replace(/^done/, "完成");
  }
  if (status === "empty") {
    return "空结果";
  }
  if (status === "failed") {
    return "失败";
  }
  return status;
}

function isLiveRequested(mode: AnalysisMode) {
  return mode === "auto" || mode === "demo";
}

function runOutcomeLabel(requestedMode: AnalysisMode, engineMode: string) {
  if (!isLiveRequested(requestedMode)) {
    return "快速判断";
  }
  if (engineMode === "live") {
    return "研究已完成";
  }
  return "已回退基线";
}

function runOutcomeClass(requestedMode: AnalysisMode, engineMode: string) {
  if (!isLiveRequested(requestedMode)) {
    return "bg-background-strong text-text-secondary";
  }
  if (engineMode === "live") {
    return "bg-[#eef8f1] text-[#2c6b46]";
  }
  return "bg-[#fbf2e8] text-[#8a6431]";
}

function researchStatusTitle(analysis: DecisionAnalysis) {
  if (!isLiveRequested(analysis.engine.requestedMode)) {
    return "本次使用了快速判断";
  }
  if (analysis.engine.mode === "live") {
    return "本次已补充公开信号";
  }
  return "本次未拿到可用研究结果";
}

function researchStatusDetail(analysis: DecisionAnalysis) {
  if (!isLiveRequested(analysis.engine.requestedMode)) {
    return "这次结果主要基于你填写的画像、Offer 信息和本地规则，适合快速建立第一版判断。";
  }
  if (analysis.engine.mode === "live") {
    if (analysis.engine.requestedMode === "demo") {
      return "这次使用的是离线体验链路，结果稳定可复现，适合试用产品流程，但不代表真实公网的最新动态。";
    }
    return "这次结果已经合并了新近公开信号。你可以继续查看来源与重点核验项，再决定是否进入下一轮沟通。";
  }
  return "系统尝试补充公开信号，但这次没有拿到可用结果，因此先回退到基线建议。当前结论仍可参考，但更适合作为下一步沟通提纲。";
}

function researchStatusClass(analysis: DecisionAnalysis) {
  if (!isLiveRequested(analysis.engine.requestedMode)) {
    return "border-border bg-white/75 text-text-secondary";
  }
  if (analysis.engine.mode === "live") {
    return "border-[#b7d7c2] bg-[#eef8f1] text-[#2c6b46]";
  }
  return "border-[#d8c2a7] bg-[#fbf2e8] text-[#7a4f2d]";
}

function runStatusLabel(status: WorkspaceRunStatus) {
  switch (status) {
    case "queued":
      return "已入队";
    case "running":
      return "研究中";
    case "completed":
      return "已完成";
    default:
      return "失败";
  }
}

function runStatusClass(status: WorkspaceRunStatus) {
  switch (status) {
    case "queued":
      return "bg-background-strong text-text-secondary";
    case "running":
      return "bg-[#fbf3de] text-[#8b6b26]";
    case "completed":
      return "bg-[#eef8f1] text-[#2c6b46]";
    default:
      return "bg-[#fbf2e8] text-[#8a6431]";
  }
}

function isTerminalRunStatus(status: WorkspaceRunStatus) {
  return status === "completed" || status === "failed";
}

function providerLabel(provider: string) {
  switch (provider) {
    case "deterministic retrieval":
      return "本地可复现检索链路";
    case "generic-json":
      return "通用 JSON 搜索源";
    case "tavily":
      return "Tavily 搜索";
    case "kimi":
      return "Kimi";
    case "model-only fallback + openai-compatible":
      return "仅模型降级 + OpenAI 兼容结构化";
    case "retrieval fallback + openai-compatible":
      return "检索降级 + OpenAI 兼容结构化";
    case "retrieval-first + openai-compatible":
      return "检索优先 + OpenAI 兼容结构化";
    case "retrieval-first local fallback":
      return "检索优先本地降级";
    case "未设置":
      return "未设置";
    default:
      return provider;
  }
}

function modeCapabilityDetail(mode: AnalysisMode, enabled: boolean) {
  if (mode === "heuristic") {
    return "适合在信息还不完整时先快速建立判断，不依赖额外研究服务。";
  }
  if (mode === "demo") {
    return enabled
      ? "使用内置样例信号完成一次完整研究流程，适合先体验产品的研究方式。"
      : "当前离线体验链路暂不可用。";
  }
  return enabled
    ? "会结合你填写的信息和公开信号，产出一份更完整的比较建议。"
    : "当前公开信号服务暂不可用，系统会自动回到基线判断，不影响继续使用。";
}

function liveProviderDetail(enabled: boolean, provider: string) {
  if (!enabled) {
    return "当前仅提供基线建议，公开信号不会参与最终结论。";
  }
  if (provider === "deterministic") {
    return "当前使用离线体验链路，适合稳定试用产品流程，但不会反映真实公网最新动态。";
  }
  return "当前已接入公开信号研究服务；如果外部结果不可用，系统会自动回退到更稳妥的基线建议。";
}

function retrievalDetail(enabled: boolean) {
  return enabled
    ? "公开信号来源已接入，系统会优先整理可追溯的外部依据，再把它们合并进分析。"
    : "当前没有接入公开信号来源，结果会更依赖你填写的信息。";
}

export function OfferCopilot() {
  const [request, setRequest] = useState<DecisionRequest>(demoRequest);
  const [analysisMode, setAnalysisMode] = useState<AnalysisMode>("auto");
  const [analysis, setAnalysis] = useState<DecisionAnalysis | null>(null);
  const [capabilities, setCapabilities] = useState<DecisionCapabilities | null>(
    null,
  );
  const [workspace, setWorkspace] = useState<WorkspaceResponse | null>(null);
  const [workspaceError, setWorkspaceError] = useState<string | null>(null);
  const [workspaceNotice, setWorkspaceNotice] = useState<string | null>(null);
  const [activeRunId, setActiveRunId] = useState<string | null>(null);
  const [activeRunStatus, setActiveRunStatus] = useState<WorkspaceRunStatus | null>(null);
  const [profileSaving, setProfileSaving] = useState(false);
  const [offerSavingIndex, setOfferSavingIndex] = useState<number | null>(null);
  const [intakeText, setIntakeText] = useState("");
  const [parseResult, setParseResult] = useState<IntakeParseResponse | null>(null);
  const [parsePending, setParsePending] = useState(false);
  const [history, setHistory] = useState<HistoryEntry[]>(() => {
    if (typeof window === "undefined") {
      return [];
    }

    try {
      const raw = window.localStorage.getItem(HISTORY_STORAGE_KEY);
      if (!raw) {
        return [];
      }
      const parsed = JSON.parse(raw) as HistoryEntry[];
      return Array.isArray(parsed) ? parsed : [];
    } catch {
      window.localStorage.removeItem(HISTORY_STORAGE_KEY);
      return [];
    }
  });
  const [error, setError] = useState<string | null>(null);
  const [capabilityError, setCapabilityError] = useState<string | null>(null);
  const [isPending, startTransition] = useTransition();
  const analysisApiUrl =
    process.env.NEXT_PUBLIC_DECISION_API_URL ??
    "http://localhost:8080/api/v1/decision/analyze";
  const capabilitiesApiUrl =
    process.env.NEXT_PUBLIC_DECISION_CAPABILITIES_URL ??
    buildCapabilitiesUrl(analysisApiUrl);
  const workspaceBaseUrl =
    process.env.NEXT_PUBLIC_WORKSPACE_API_URL ??
    buildWorkspaceBaseUrl(analysisApiUrl);

  const handleRunCompletion = useEffectEvent(async (payload: WorkspaceRunDetail) => {
    saveHistoryEntry(payload.analysis, payload.requestedMode, payload.request);
    const workspaceResponse = await fetch(`${workspaceBaseUrl}/default`, {
      method: "GET",
    });
    if (workspaceResponse.ok) {
      const workspacePayload =
        (await workspaceResponse.json()) as WorkspaceResponse;
      setWorkspace(workspacePayload);
      setWorkspaceError(null);
    }
    setWorkspaceNotice("后台研究任务已经完成，最新结果已写回工作台。");
    setWorkspaceError(null);
    setActiveRunId(null);
    setActiveRunStatus(null);
  });

  const handleRunFailure = useEffectEvent(async () => {
    const workspaceResponse = await fetch(`${workspaceBaseUrl}/default`, {
      method: "GET",
    });
    if (workspaceResponse.ok) {
      const workspacePayload =
        (await workspaceResponse.json()) as WorkspaceResponse;
      setWorkspace(workspacePayload);
    }
    setWorkspaceError("后台实时研究失败，当前先保留规则基线。你仍然可以继续查看与比较。");
    setActiveRunId(null);
    setActiveRunStatus(null);
  });

  useEffect(() => {
    let cancelled = false;

    void (async () => {
      try {
        const response = await fetch(capabilitiesApiUrl, {
          method: "GET",
        });
        if (!response.ok) {
          throw new Error("capabilities request failed");
        }

        const payload = (await response.json()) as DecisionCapabilities;
        if (!cancelled) {
          setCapabilities(payload);
          setCapabilityError(null);
        }
      } catch {
        if (!cancelled) {
          setCapabilityError(
            "后端能力状态暂时不可用，但分析流程仍然可以继续使用。",
          );
        }
      }
    })();

    return () => {
      cancelled = true;
    };
  }, [capabilitiesApiUrl]);

  useEffect(() => {
    let cancelled = false;

    void (async () => {
      try {
        const response = await fetch(`${workspaceBaseUrl}/default`, {
          method: "GET",
        });
        if (!response.ok) {
          throw new Error("workspace request failed");
        }

        const payload = (await response.json()) as WorkspaceResponse;
        if (cancelled) {
          return;
        }

        setWorkspace(payload);
        setWorkspaceError(null);

        setRequest((current) => {
          const nextOffers = [...current.offers];
          payload.offers.slice(0, 2).forEach((offer, index) => {
            nextOffers[index] = toOfferInput(offer);
          });

          const shouldApplyProfile =
            Boolean(payload.userProfile.target.trim()) ||
            payload.userProfile.priorities.length > 0;

          return {
            userProfile:
              shouldApplyProfile
                ? payload.userProfile
                : current.userProfile,
            offers: nextOffers,
          };
        });
      } catch {
        if (!cancelled) {
          setWorkspaceError("工作台数据暂时不可用，当前仍可直接填写并分析。");
        }
      }
    })();

    return () => {
      cancelled = true;
    };
  }, [workspaceBaseUrl]);

  useEffect(() => {
    if (!activeRunId || !activeRunStatus || isTerminalRunStatus(activeRunStatus)) {
      return;
    }

    let cancelled = false;
    const timer = window.setTimeout(() => {
      void (async () => {
        try {
          const response = await fetch(`${workspaceBaseUrl}/default/run-jobs/${activeRunId}`, {
            method: "GET",
          });
          if (!response.ok) {
            throw new Error("run job poll failed");
          }

          const payload = (await response.json()) as WorkspaceRunDetail;
          if (cancelled) {
            return;
          }

          setAnalysis(payload.analysis);
          setActiveRunStatus(payload.status);

          if (payload.status === "completed") {
            await handleRunCompletion(payload);
            return;
          }

          if (payload.status === "failed") {
            await handleRunFailure();
          }
        } catch {
          if (!cancelled) {
            setWorkspaceError("轮询研究任务状态失败，请稍后手动刷新工作台。");
            setActiveRunId(null);
            setActiveRunStatus(null);
          }
        }
      })();
    }, 1800);

    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, [activeRunId, activeRunStatus, workspaceBaseUrl]);

  function togglePriority(priority: Priority) {
    setRequest((current) => {
      const exists = current.userProfile.priorities.includes(priority);
      const nextPriorities = exists
        ? current.userProfile.priorities.filter((item) => item !== priority)
        : [...current.userProfile.priorities, priority];

      return {
        ...current,
        userProfile: {
          ...current.userProfile,
          priorities: nextPriorities.slice(0, 4),
        },
      };
    });
  }

  async function refreshWorkspace() {
    const response = await fetch(`${workspaceBaseUrl}/default`, {
      method: "GET",
    });
    if (!response.ok) {
      throw new Error("workspace refresh failed");
    }
    const payload = (await response.json()) as WorkspaceResponse;
    setWorkspace(payload);
    setWorkspaceError(null);
    return payload;
  }

  async function handleSaveProfile() {
    setProfileSaving(true);
    setWorkspaceNotice(null);
    try {
      const response = await fetch(`${workspaceBaseUrl}/default/profile`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
        },
        body: JSON.stringify(request.userProfile),
      });

      if (!response.ok) {
        throw new Error("profile save failed");
      }

      const payload = (await response.json()) as WorkspaceResponse;
      setWorkspace(payload);
      setWorkspaceNotice("候选人画像已保存到工作台。");
    } catch {
      setWorkspaceError("保存画像失败，请确认后端服务可用。");
    } finally {
      setProfileSaving(false);
    }
  }

  async function handleSaveOffer(index: number) {
    setOfferSavingIndex(index);
    setWorkspaceNotice(null);
    try {
      const offer = request.offers[index];
      const payload: SaveWorkspaceOfferRequest = {
        ...offer,
        rawText: "",
        source: "manual-form",
      };

      const response = await fetch(`${workspaceBaseUrl}/default/offers`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
        },
        body: JSON.stringify(payload),
      });

      if (!response.ok) {
        throw new Error("offer save failed");
      }

      await refreshWorkspace();
      setWorkspaceNotice(`已保存 ${offer.company} 到工作台。`);
    } catch {
      setWorkspaceError("保存 Offer 失败，请确认字段完整后重试。");
    } finally {
      setOfferSavingIndex(null);
    }
  }

  function applySavedOfferToSlot(saved: SavedOfferRecord, slotIndex: number) {
    setRequest((current) => ({
      ...current,
      offers: current.offers.map((offer, index) =>
        index === slotIndex ? toOfferInput(saved) : offer,
      ),
    }));
    setWorkspaceNotice(`已将 ${saved.company} 载入到机会 ${slotIndex + 1}。`);
  }

  async function handleDeleteSavedOffer(offerId: string) {
    setWorkspaceNotice(null);
    try {
      const response = await fetch(`${workspaceBaseUrl}/default/offers/${offerId}`, {
        method: "DELETE",
      });
      if (!response.ok) {
        throw new Error("delete offer failed");
      }
      await refreshWorkspace();
      setWorkspaceNotice("已从工作台删除这条 Offer。");
    } catch {
      setWorkspaceError("删除 Offer 失败，请稍后再试。");
    }
  }

  async function handleParseIntake() {
    if (!intakeText.trim()) {
      setWorkspaceError("请先粘贴 Offer、JD 或面试记录原文。");
      return;
    }

    setParsePending(true);
    setWorkspaceNotice(null);
    try {
      const response = await fetch(`${workspaceBaseUrl}/intake/parse`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
        },
        body: JSON.stringify({
          rawText: intakeText,
        }),
      });

      if (!response.ok) {
        throw new Error("parse failed");
      }

      const payload = (await response.json()) as IntakeParseResponse;
      setParseResult(payload);
      setWorkspaceError(null);
      setWorkspaceNotice("原文已经结构化完成，你可以先校对再落进对比槽位。");
    } catch {
      setWorkspaceError("原文解析失败，请确认后端服务已启动。");
    } finally {
      setParsePending(false);
    }
  }

  function applyParsedDraftToSlot(slotIndex: number) {
    if (!parseResult) {
      return;
    }
    setRequest((current) => ({
      ...current,
      offers: current.offers.map((offer, index) =>
        index === slotIndex
          ? parsedDraftToOffer(parseResult.draft, offer.id)
          : offer,
      ),
    }));
    setWorkspaceNotice(`解析结果已填入机会 ${slotIndex + 1}，你现在可以继续修改或直接保存。`);
  }

  async function handleSaveParsedDraft() {
    if (!parseResult) {
      return;
    }
    setOfferSavingIndex(-1);
    setWorkspaceNotice(null);
    try {
      const draft = parseResult.draft;
      const payload: SaveWorkspaceOfferRequest = {
        ...parsedDraftToOffer(draft, crypto.randomUUID()),
        rawText: draft.rawText,
        source: draft.source,
      };

      const response = await fetch(`${workspaceBaseUrl}/default/offers`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
        },
        body: JSON.stringify(payload),
      });

      if (!response.ok) {
        throw new Error("save parsed draft failed");
      }

      await refreshWorkspace();
      setWorkspaceNotice(`已将解析出的 ${draft.company} 保存到工作台。`);
    } catch {
      setWorkspaceError("保存解析结果失败，请稍后再试。");
    } finally {
      setOfferSavingIndex(null);
    }
  }

  async function restoreWorkspaceRun(runId: string) {
    try {
      const response = await fetch(`${workspaceBaseUrl}/default/runs/${runId}`, {
        method: "GET",
      });
      if (!response.ok) {
        throw new Error("restore workspace run failed");
      }
      const payload = (await response.json()) as WorkspaceRunDetail;
      setRequest(payload.request);
      setAnalysisMode(payload.requestedMode);
      setAnalysis(payload.analysis);
      setActiveRunId(isTerminalRunStatus(payload.status) ? null : payload.id);
      setActiveRunStatus(payload.status);
      setWorkspaceNotice("已恢复这次后端研究记录。");
      setWorkspaceError(null);
    } catch {
      setWorkspaceError("恢复后端研究记录失败，请稍后再试。");
    }
  }

  function saveHistoryEntry(
    nextAnalysis: DecisionAnalysis,
    mode: AnalysisMode = analysisMode,
    nextRequest: DecisionRequest = request,
  ) {
    const nextEntry: HistoryEntry = {
      id: `${nextAnalysis.generatedAt}-${nextAnalysis.recommendation.winner}`,
      savedAt: new Date().toISOString(),
      mode,
      request: nextRequest,
      analysis: nextAnalysis,
    };

    setHistory((current) => {
      const next = [nextEntry, ...current.filter((item) => item.id !== nextEntry.id)].slice(0, 6);
      try {
        window.localStorage.setItem(HISTORY_STORAGE_KEY, JSON.stringify(next));
      } catch {
        return current;
      }
      return next;
    });
  }

  function restoreHistoryEntry(entry: HistoryEntry) {
    setRequest(entry.request);
    setAnalysisMode(entry.mode);
    setAnalysis(entry.analysis);
    setActiveRunId(null);
    setActiveRunStatus(null);
    setError(null);
  }

  function handleSubmit() {
    setError(null);
    setWorkspaceNotice(null);
    setActiveRunId(null);
    setActiveRunStatus(null);

    startTransition(() => {
      void (async () => {
        try {
          const response = await fetch(`${workspaceBaseUrl}/default/run-jobs`, {
            method: "POST",
            headers: {
              "Content-Type": "application/json",
            },
            body: JSON.stringify({
              request,
              mode: analysisMode,
            }),
          });

          if (!response.ok) {
            setError(
              "分析请求失败，请检查 Offer 信息后重试。",
            );
            return;
          }

          const nextRun = (await response.json()) as WorkspaceRunJobAccepted;
          setAnalysis(nextRun.analysis);
          setActiveRunId(isTerminalRunStatus(nextRun.status) ? null : nextRun.id);
          setActiveRunStatus(nextRun.status);
          await refreshWorkspace();
          if (nextRun.status === "completed") {
            saveHistoryEntry(nextRun.analysis, nextRun.requestedMode, request);
            setWorkspaceNotice("研究引擎已完成一次正式运行，结果已保存在后端工作台。");
          } else {
            setWorkspaceNotice("研究任务已经入队。当前先展示规则基线，后台会继续补全实时研究结果。");
          }
        } catch {
          try {
            const fallbackResponse = await fetch(analysisApiUrl, {
              method: "POST",
              headers: {
                "Content-Type": "application/json",
              },
              body: JSON.stringify({
                request,
                mode: analysisMode,
              }),
            });

            if (!fallbackResponse.ok) {
              setError("分析请求失败，请检查 Offer 信息后重试。");
              return;
            }

            const nextAnalysis = (await fallbackResponse.json()) as DecisionAnalysis;
            setAnalysis(nextAnalysis);
            saveHistoryEntry(nextAnalysis);
            setWorkspaceError("后端工作台运行记录暂时不可用，本次结果仅保留在前端会话中。");
          } catch {
            setError(
              "暂时无法连接后端服务，请启动 Spring Boot 服务后再试。",
            );
          }
        }
      })();
    });
  }

  const selectedCapability =
    capabilities?.modes.find((mode) => mode.mode === analysisMode) ?? null;

  return (
    <main className="grain relative overflow-hidden px-5 py-6 sm:px-8 lg:px-12">
      <div className="mx-auto flex max-w-7xl flex-col gap-6">
        <section className="panel relative overflow-hidden rounded-[32px] border p-8 sm:p-10 lg:p-12">
          <div className="absolute right-0 top-0 h-44 w-44 rounded-full bg-accent-soft blur-3xl" />
          <div className="relative flex flex-col gap-8 lg:flex-row lg:items-end lg:justify-between">
            <div className="max-w-3xl">
              <Pill>面向校招与社招的 Offer 决策工作台</Pill>
              <h1 className="mt-6 max-w-3xl font-serif text-5xl leading-[0.96] tracking-tight text-text-primary sm:text-6xl">
                Offer 决策工作台
              </h1>
              <p className="mt-5 max-w-2xl text-base leading-8 text-text-secondary sm:text-lg">
                把机会比较、公开信号补充和关键核验项放进同一个界面里。
                你可以先导入自己的 Offer 与 JD，再让系统给出一份带依据、
                带保留项、能指导下一轮沟通的问题清单。
              </p>
            </div>

            <div className="grid gap-3 rounded-[28px] border border-border bg-white/75 p-5 text-sm text-text-secondary shadow-[0_20px_45px_rgba(65,46,20,0.08)] sm:grid-cols-3">
              <div>
                <div className="text-2xl font-semibold text-text-primary">5</div>
                <div className="mt-1">比较维度</div>
              </div>
              <div>
                <div className="text-2xl font-semibold text-text-primary">2</div>
                <div className="mt-1">机会并排比较</div>
              </div>
              <div>
                <div className="text-2xl font-semibold text-text-primary">1</div>
                <div className="mt-1">核验清单</div>
              </div>
            </div>
          </div>
        </section>

        <section className="grid gap-6 xl:grid-cols-[1.15fr_0.85fr]">
          <div className="panel rounded-[30px] p-6 sm:p-8">
            <div className="flex flex-wrap items-center justify-between gap-4">
              <div>
                <p className="text-xs font-medium uppercase tracking-[0.26em] text-text-secondary">
                  我的工作台
                </p>
                <h2 className="mt-2 text-2xl font-semibold text-text-primary">
                  先把你的 Offer 存进系统
                </h2>
              </div>
              <button
                type="button"
                onClick={handleSaveProfile}
                disabled={profileSaving}
                className="rounded-full border border-border bg-white/80 px-4 py-2 text-sm font-medium text-text-primary transition hover:-translate-y-0.5 disabled:cursor-wait disabled:opacity-70"
              >
                {profileSaving ? "正在保存画像..." : "保存当前画像"}
              </button>
            </div>

            <p className="mt-3 text-sm leading-7 text-text-secondary">
              你可以把目标岗位、Offer 资料和原始材料长期放在这里。
              后续补充面试反馈、HR 信息或新一轮公开动态时，也能在同一个工作台里继续更新。
            </p>

            {workspaceNotice ? (
              <div className="mt-5 rounded-[20px] border border-[#b7d7c2] bg-[#eef8f1] px-4 py-3 text-sm leading-7 text-[#2c6b46]">
                {workspaceNotice}
              </div>
            ) : null}
            {workspaceError ? (
              <div className="mt-5 rounded-[20px] border border-[#d8c2a7] bg-[#fbf2e8] px-4 py-3 text-sm leading-7 text-[#7a4f2d]">
                {workspaceError}
              </div>
            ) : null}
            {activeRunId && activeRunStatus && !isTerminalRunStatus(activeRunStatus) ? (
              <div className="mt-5 rounded-[20px] border border-[#d8caa8] bg-[#fbf3de] px-4 py-3 text-sm leading-7 text-[#6f5522]">
                后台研究任务正在继续：
                {runStatusLabel(activeRunStatus)}。你现在看到的是规则基线，页面会在研究完成后自动刷新为最新结果。
              </div>
            ) : null}

            <div className="mt-5 grid gap-4 sm:grid-cols-3">
              <div className="rounded-[22px] border border-border bg-white/75 p-4">
                <div className="text-2xl font-semibold text-text-primary">
                  {workspace?.offers.length ?? 0}
                </div>
                <div className="mt-1 text-sm text-text-secondary">已保存 Offer</div>
              </div>
              <div className="rounded-[22px] border border-border bg-white/75 p-4">
                <div className="text-2xl font-semibold text-text-primary">
                  {workspace?.userProfile.priorities.length ?? request.userProfile.priorities.length}
                </div>
                <div className="mt-1 text-sm text-text-secondary">画像优先项</div>
              </div>
              <div className="rounded-[22px] border border-border bg-white/75 p-4">
                <div className="text-sm font-medium text-text-primary">最近更新</div>
                <div className="mt-2 text-sm leading-7 text-text-secondary">
                  {workspace?.updatedAt
                    ? new Date(workspace.updatedAt).toLocaleString()
                    : "尚未同步到后端"}
                </div>
              </div>
            </div>

            <div className="mt-5 space-y-3">
              {workspace?.offers.length ? (
                workspace.offers.map((savedOffer) => (
                  <div
                    key={savedOffer.id}
                    className="rounded-[22px] border border-border bg-white/75 p-4"
                  >
                    <div className="flex flex-wrap items-start justify-between gap-3">
                      <div>
                        <div className="text-sm font-medium text-text-primary">
                          {savedOffer.company}
                        </div>
                        <div className="mt-1 text-sm text-text-secondary">
                          {savedOffer.role} · {stageLabel(savedOffer.stage)} · {savedOffer.city}
                        </div>
                      </div>
                      <div className="rounded-full border border-border bg-background/70 px-3 py-1 text-[11px] uppercase tracking-[0.22em] text-text-secondary">
                        {savedOffer.source === "intake-parser" ? "原文解析" : "手工保存"}
                      </div>
                    </div>
                    <p className="mt-3 text-sm leading-7 text-text-secondary">
                      {savedOffer.jdSignals}
                    </p>
                    <div className="mt-4 flex flex-wrap gap-2">
                      <button
                        type="button"
                        onClick={() => applySavedOfferToSlot(savedOffer, 0)}
                        className="rounded-full border border-border bg-white/80 px-4 py-2 text-sm font-medium text-text-primary transition hover:-translate-y-0.5"
                      >
                        放入机会 1
                      </button>
                      <button
                        type="button"
                        onClick={() => applySavedOfferToSlot(savedOffer, 1)}
                        className="rounded-full border border-border bg-white/80 px-4 py-2 text-sm font-medium text-text-primary transition hover:-translate-y-0.5"
                      >
                        放入机会 2
                      </button>
                      <button
                        type="button"
                        onClick={() => handleDeleteSavedOffer(savedOffer.id)}
                        className="rounded-full border border-[#e3c0b6] bg-[#fff5f1] px-4 py-2 text-sm font-medium text-[#9a4a33] transition hover:-translate-y-0.5"
                      >
                        删除
                      </button>
                    </div>
                  </div>
                ))
              ) : (
                <div className="rounded-[22px] border border-border bg-white/75 p-4 text-sm leading-7 text-text-secondary">
                  你还没有保存任何 Offer。可以先手填下面的两张卡片，也可以直接把 JD / Offer 原文贴到右侧，让系统先帮你做一次结构化。
                </div>
              )}
            </div>

            <div className="mt-6">
              <div className="text-sm font-medium text-text-primary">最近研究运行</div>
              <div className="mt-3 space-y-3">
                {workspace?.recentRuns.length ? (
                  workspace.recentRuns.map((run) => (
                    <div
                      key={run.id}
                      className="rounded-[22px] border border-border bg-white/75 p-4"
                    >
                      <div className="flex flex-wrap items-start justify-between gap-3">
                        <div>
                          <div className="text-sm font-medium text-text-primary">
                            {run.winner}
                          </div>
                          <div className="mt-1 flex flex-wrap items-center gap-2 text-sm text-text-secondary">
                            <span>
                              {modeButtonLabel(run.requestedMode)} ·{" "}
                              {engineModeLabel(run.engineMode)}
                            </span>
                          <span
                              className={`rounded-full px-3 py-1 text-[11px] uppercase tracking-[0.22em] ${runStatusClass(
                                run.status,
                              )}`}
                            >
                              {runStatusLabel(run.status)}
                            </span>
                          </div>
                        </div>
                        <div className="text-right text-sm text-text-secondary">
                          {new Date(run.createdAt).toLocaleString()}
                        </div>
                      </div>
                      <p className="mt-3 text-sm leading-7 text-text-secondary">
                        {run.summary}
                      </p>
                      {run.progressDetail ? (
                        <p className="mt-2 text-sm leading-7 text-text-secondary/90">
                          {run.progressDetail}
                        </p>
                      ) : null}
                      <div className="mt-4">
                        <button
                          type="button"
                          onClick={() => restoreWorkspaceRun(run.id)}
                          className="rounded-full border border-border bg-white/80 px-4 py-2 text-sm font-medium text-text-primary transition hover:-translate-y-0.5"
                        >
                          恢复这次研究
                        </button>
                      </div>
                    </div>
                  ))
                ) : (
                  <div className="rounded-[22px] border border-border bg-white/75 p-4 text-sm leading-7 text-text-secondary">
                    还没有后端研究记录。保存自己的 Offer 后，点击“开始分析”，研究引擎就会把一次正式运行写入工作台。
                  </div>
                )}
              </div>
            </div>
          </div>

          <div className="panel rounded-[30px] p-6 sm:p-8">
            <p className="text-xs font-medium uppercase tracking-[0.26em] text-text-secondary">
              原文接入
            </p>
            <h2 className="mt-2 text-2xl font-semibold text-text-primary">
              粘贴 Offer 或 JD，让系统先结构化
            </h2>
            <p className="mt-3 text-sm leading-7 text-text-secondary">
              直接粘贴 JD、Offer 原文、面试纪要或 HR 补充说明。
              系统会先整理出结构化草稿，你只需要确认和修正关键字段。
            </p>

            <div className="mt-5 space-y-4">
              <textarea
                rows={9}
                value={intakeText}
                onChange={(event) => setIntakeText(event.target.value)}
                placeholder="把 offer 原文、JD、面试记录、HR 补充信息贴到这里。越完整，结构化结果越稳。"
                className="w-full rounded-[22px] border border-border bg-white/80 px-4 py-4 text-sm leading-7 outline-none transition focus:border-accent"
              />
              <div className="flex flex-wrap gap-3">
                <button
                  type="button"
                  onClick={handleParseIntake}
                  disabled={parsePending}
                  className="rounded-full bg-text-primary px-5 py-3 text-sm font-medium text-white transition hover:-translate-y-0.5 disabled:cursor-wait disabled:opacity-70"
                >
                  {parsePending ? "正在解析..." : "解析原文"}
                </button>
                <button
                  type="button"
                  onClick={() => {
                    setIntakeText("");
                    setParseResult(null);
                  }}
                  className="rounded-full border border-border bg-white/80 px-5 py-3 text-sm font-medium text-text-primary transition hover:-translate-y-0.5"
                >
                  清空
                </button>
              </div>
            </div>

            {parseResult ? (
              <div className="mt-5 rounded-[24px] border border-border bg-white/75 p-5">
                <div className="flex flex-wrap items-center justify-between gap-3">
                  <div>
                    <div className="text-sm font-medium text-text-primary">
                      结构化草稿
                    </div>
                    <div className="mt-1 text-sm text-text-secondary">
                      置信度 {parseResult.confidenceScore}/100
                    </div>
                  </div>
                  <div className="flex flex-wrap gap-2">
                    <button
                      type="button"
                      onClick={() => applyParsedDraftToSlot(0)}
                      className="rounded-full border border-border bg-white/80 px-4 py-2 text-sm font-medium text-text-primary transition hover:-translate-y-0.5"
                    >
                      填入机会 1
                    </button>
                    <button
                      type="button"
                      onClick={() => applyParsedDraftToSlot(1)}
                      className="rounded-full border border-border bg-white/80 px-4 py-2 text-sm font-medium text-text-primary transition hover:-translate-y-0.5"
                    >
                      填入机会 2
                    </button>
                    <button
                      type="button"
                      onClick={handleSaveParsedDraft}
                      disabled={offerSavingIndex === -1}
                      className="rounded-full bg-accent px-4 py-2 text-sm font-medium text-white transition hover:-translate-y-0.5 disabled:cursor-wait disabled:opacity-70"
                    >
                      {offerSavingIndex === -1 ? "正在保存..." : "保存到工作台"}
                    </button>
                  </div>
                </div>

                <div className="mt-4 grid gap-3 sm:grid-cols-2">
                  {[
                    ["公司", parseResult.draft.company],
                    ["岗位", parseResult.draft.role],
                    ["城市", parseResult.draft.city],
                    ["薪酬", parseResult.draft.compensation],
                    ["阶段", stageLabel(parseResult.draft.stage)],
                    ["办公方式", workModeLabel(parseResult.draft.workMode)],
                  ].map(([label, value]) => (
                    <div
                      key={label}
                      className="rounded-[18px] border border-border bg-background/60 p-3"
                    >
                      <div className="text-xs uppercase tracking-[0.22em] text-text-secondary">
                        {label}
                      </div>
                      <div className="mt-2 text-sm text-text-primary">{value}</div>
                    </div>
                  ))}
                </div>

                <div className="mt-4 rounded-[18px] border border-border bg-background/60 p-4">
                  <div className="text-sm font-medium text-text-primary">职责与信号</div>
                  <p className="mt-2 text-sm leading-7 text-text-secondary">
                    {parseResult.draft.jdSignals}
                  </p>
                </div>

                {parseResult.extractedSignals.length ? (
                  <div className="mt-4 flex flex-wrap gap-2">
                    {parseResult.extractedSignals.map((signal) => (
                      <span
                        key={signal}
                        className="rounded-full border border-[#b7d7c2] bg-[#eef8f1] px-3 py-1 text-[11px] uppercase tracking-[0.22em] text-[#2c6b46]"
                      >
                        {signal}
                      </span>
                    ))}
                  </div>
                ) : null}

                {parseResult.warnings.length ? (
                  <div className="mt-4 rounded-[18px] border border-[#d8c2a7] bg-[#fbf2e8] p-4">
                    <div className="text-sm font-medium text-text-primary">需要你确认</div>
                    <div className="mt-2 space-y-2 text-sm leading-7 text-text-secondary">
                      {parseResult.warnings.map((warning) => (
                        <p key={warning}>• {warning}</p>
                      ))}
                    </div>
                  </div>
                ) : null}
              </div>
            ) : null}
          </div>
        </section>

        <section className="grid gap-6 xl:grid-cols-[1.15fr_0.85fr]">
          <div className="panel rounded-[30px] p-6 sm:p-8">
            <div className="flex flex-wrap items-center justify-between gap-4">
              <div>
                <p className="text-xs font-medium uppercase tracking-[0.26em] text-text-secondary">
                  候选人画像
                </p>
                <h2 className="mt-2 text-2xl font-semibold text-text-primary">
                  先把问题定义清楚，再开始打分
                </h2>
              </div>

              <button
                type="button"
                onClick={() => {
                  setRequest(demoRequest);
                  setAnalysis(null);
                  setActiveRunId(null);
                  setActiveRunStatus(null);
                  setError(null);
                }}
                className="rounded-full border border-border bg-white/80 px-4 py-2 text-sm font-medium text-text-primary transition hover:-translate-y-0.5"
              >
                填充示例案例
              </button>
            </div>

            <div className="mt-6 space-y-6">
              <label className="block">
                <span className="mb-2 block text-sm font-medium text-text-primary">
                  3 年目标
                </span>
                <textarea
                  rows={3}
                  value={request.userProfile.target}
                  onChange={(event) =>
                    setRequest((current) => ({
                      ...current,
                      userProfile: {
                        ...current.userProfile,
                        target: event.target.value,
                      },
                    }))
                  }
                  className="min-h-[112px] w-full rounded-[22px] border border-border bg-white/80 px-4 py-3 text-sm leading-7 outline-none transition focus:border-accent"
                />
              </label>

              <div className="grid gap-6 lg:grid-cols-[0.6fr_1fr]">
                <label className="block">
                  <span className="mb-2 block text-sm font-medium text-text-primary">
                    风险偏好
                  </span>
                  <select
                    value={request.userProfile.riskAppetite}
                    onChange={(event) =>
                      setRequest((current) => ({
                        ...current,
                        userProfile: {
                          ...current.userProfile,
                          riskAppetite: event.target
                            .value as DecisionRequest["userProfile"]["riskAppetite"],
                        },
                      }))
                    }
                    className="w-full rounded-[18px] border border-border bg-white/80 px-4 py-3 text-sm outline-none transition focus:border-accent"
                  >
                    <option value="low">{riskAppetiteLabel("low")}</option>
                    <option value="medium">{riskAppetiteLabel("medium")}</option>
                    <option value="high">{riskAppetiteLabel("high")}</option>
                  </select>
                </label>

                <div>
                  <span className="mb-2 block text-sm font-medium text-text-primary">
                    优先项
                  </span>
                  <div className="flex flex-wrap gap-2">
                    {priorityOptions.map((priority) => {
                      const active = request.userProfile.priorities.includes(priority);

                      return (
                        <button
                          key={priority}
                          type="button"
                          onClick={() => togglePriority(priority)}
                          className={`rounded-full border px-4 py-2 text-sm transition ${
                            active
                              ? "border-accent bg-accent text-white"
                              : "border-border bg-white/70 text-text-primary hover:-translate-y-0.5"
                          }`}
                        >
                          {priorityLabel(priority)}
                        </button>
                      );
                    })}
                  </div>
                </div>
              </div>

              <div className="rounded-[24px] border border-border bg-white/75 p-5">
                <div className="flex flex-wrap items-center justify-between gap-3">
                  <div>
                    <div className="text-sm font-medium text-text-primary">
                      分析模式
                    </div>
                    <div className="mt-1 text-sm leading-7 text-text-secondary">
                      你可以先用快速判断拿到一版结论，再决定是否补充公开信号做更完整的比较。
                    </div>
                  </div>
                  <div className="flex rounded-full border border-border bg-background-strong p-1">
                    <button
                      type="button"
                      onClick={() => setAnalysisMode("auto")}
                      className={`rounded-full px-4 py-2 text-sm transition ${
                        analysisMode === "auto"
                          ? "bg-text-primary text-white"
                          : "text-text-secondary"
                      }`}
                    >
                      {modeButtonLabel("auto")}
                    </button>
                    <button
                      type="button"
                      onClick={() => setAnalysisMode("demo")}
                      className={`rounded-full px-4 py-2 text-sm transition ${
                        analysisMode === "demo"
                          ? "bg-text-primary text-white"
                          : "text-text-secondary"
                      }`}
                    >
                      {modeButtonLabel("demo")}
                    </button>
                    <button
                      type="button"
                      onClick={() => setAnalysisMode("heuristic")}
                      className={`rounded-full px-4 py-2 text-sm transition ${
                        analysisMode === "heuristic"
                          ? "bg-text-primary text-white"
                          : "text-text-secondary"
                      }`}
                    >
                      {modeButtonLabel("heuristic")}
                    </button>
                  </div>
                </div>
                <div className="mt-4 rounded-[18px] border border-[#d8caa8] bg-[#fbf3de] px-4 py-3 text-sm leading-7 text-[#6f5522]">
                  离线体验模式会使用内置样例信号跑通完整流程，适合先体验产品交互，或者在外部研究服务暂不可用时继续试用。
                </div>
                {selectedCapability ? (
                  <div className="mt-4 rounded-[18px] border border-border bg-white/80 px-4 py-3 text-sm leading-7 text-text-secondary">
                    <div className="flex flex-wrap items-center justify-between gap-3">
                      <span className="font-medium text-text-primary">
                        {selectedCapability.label}
                      </span>
                      <span
                        className={`rounded-full px-3 py-1 text-[11px] uppercase tracking-[0.22em] ${
                          selectedCapability.enabled
                            ? "bg-[#eef8f1] text-[#2c6b46]"
                            : "bg-[#fbf2e8] text-[#8a6431]"
                        }`}
                      >
                        {selectedCapability.enabled
                          ? "可用"
                          : "将降级"}
                      </span>
                    </div>
                    <p className="mt-2">
                      {modeCapabilityDetail(
                        analysisMode,
                        selectedCapability.enabled,
                      )}
                    </p>
                  </div>
                ) : null}
              </div>
            </div>
          </div>

          <div className="panel rounded-[30px] p-6 sm:p-8">
            <p className="text-xs font-medium uppercase tracking-[0.26em] text-text-secondary">
              使用流程
            </p>
            <h2 className="mt-2 text-2xl font-semibold text-text-primary">
              从材料到结论
            </h2>
            <div className="mt-6 space-y-3">
              {workflowLabels.map((label, index) => (
                <div
                  key={label}
                  className="flex items-center justify-between rounded-[20px] border border-border bg-white/75 px-4 py-4"
                    >
                      <div>
                        <div className="text-sm font-medium text-text-primary">
                          {label}
                        </div>
                    <div className="mt-1 text-sm text-text-secondary">
                      {index === 0 &&
                        "先明确你的目标、风险偏好和优先项。"}
                      {index === 1 &&
                        "把 JD、Offer 和面试反馈整理成可比较的信息。"}
                      {index === 2 &&
                        "把可长期参考的信息和必须核验的近期变化分开。"}
                      {index === 3 &&
                        "输出建议、风险点和下一轮要问清楚的问题。"}
                    </div>
                  </div>
                  <div className="rounded-full border border-border px-3 py-1 font-mono text-xs uppercase tracking-[0.22em] text-text-secondary">
                    0{index + 1}
                  </div>
                </div>
              ))}
            </div>
          </div>
        </section>

        <section className="grid gap-6 xl:grid-cols-2">
          {request.offers.map((offer, index) => (
            <div key={offer.id} className="panel rounded-[30px] p-6 sm:p-8">
              <div className="flex items-start justify-between gap-4">
                <div>
                  <p className="text-xs font-medium uppercase tracking-[0.26em] text-text-secondary">
                    候选机会 {index + 1}
                  </p>
                  <h3 className="mt-2 text-2xl font-semibold text-text-primary">
                    {offer.company}
                  </h3>
                </div>
                <div className="rounded-full border border-border bg-white/80 px-4 py-2 text-xs uppercase tracking-[0.22em] text-text-secondary">
                  {stageLabel(offer.stage)}
                </div>
              </div>

              <div className="mt-4 flex flex-wrap gap-3">
                <button
                  type="button"
                  onClick={() => handleSaveOffer(index)}
                  disabled={offerSavingIndex === index}
                  className="rounded-full border border-border bg-white/80 px-4 py-2 text-sm font-medium text-text-primary transition hover:-translate-y-0.5 disabled:cursor-wait disabled:opacity-70"
                >
                  {offerSavingIndex === index ? "正在保存..." : "保存当前机会到工作台"}
                </button>
              </div>

              <div className="mt-6 grid gap-4 sm:grid-cols-2">
                {[
                  ["company", "公司"],
                  ["role", "岗位"],
                  ["city", "城市"],
                  ["compensation", "薪酬"],
                  ["domain", "业务方向"],
                  ["stack", "核心技术栈"],
                ].map(([field, label]) => (
                  <label key={field} className="block">
                    <span className="mb-2 block text-sm font-medium text-text-primary">
                      {label}
                    </span>
                    <input
                      value={offer[field as keyof OfferInput] as string}
                      onChange={(event) =>
                        setRequest((current) => ({
                          ...current,
                          offers: updateOffer(
                            current.offers,
                            index,
                            field as keyof OfferInput,
                            event.target.value,
                          ),
                        }))
                      }
                      className="w-full rounded-[18px] border border-border bg-white/80 px-4 py-3 text-sm outline-none transition focus:border-accent"
                    />
                  </label>
                ))}
              </div>

              <div className="mt-4 grid gap-4 sm:grid-cols-3">
                <label className="block">
                  <span className="mb-2 block text-sm font-medium text-text-primary">
                    办公方式
                  </span>
                  <select
                    value={offer.workMode}
                    onChange={(event) =>
                      setRequest((current) => ({
                        ...current,
                        offers: updateOffer(
                          current.offers,
                          index,
                          "workMode",
                          event.target.value,
                        ) as OfferInput[],
                      }))
                    }
                    className="w-full rounded-[18px] border border-border bg-white/80 px-4 py-3 text-sm outline-none transition focus:border-accent"
                  >
                    <option value="onsite">{workModeLabel("onsite")}</option>
                    <option value="hybrid">{workModeLabel("hybrid")}</option>
                    <option value="remote">{workModeLabel("remote")}</option>
                  </select>
                </label>

                <label className="block">
                  <span className="mb-2 block text-sm font-medium text-text-primary">
                    经理支持度
                  </span>
                  <select
                    value={offer.managerSupport}
                    onChange={(event) =>
                      setRequest((current) => ({
                        ...current,
                        offers: updateOffer(
                          current.offers,
                          index,
                          "managerSupport",
                          event.target.value,
                        ) as OfferInput[],
                      }))
                    }
                    className="w-full rounded-[18px] border border-border bg-white/80 px-4 py-3 text-sm outline-none transition focus:border-accent"
                  >
                    <option value="high">{managerSupportLabel("high")}</option>
                    <option value="medium">{managerSupportLabel("medium")}</option>
                    <option value="low">{managerSupportLabel("low")}</option>
                  </select>
                </label>

                <label className="block">
                  <span className="mb-2 block text-sm font-medium text-text-primary">
                    执行风格
                  </span>
                  <select
                    value={offer.executionStyle}
                    onChange={(event) =>
                      setRequest((current) => ({
                        ...current,
                        offers: updateOffer(
                          current.offers,
                          index,
                          "executionStyle",
                          event.target.value,
                        ) as OfferInput[],
                      }))
                    }
                    className="w-full rounded-[18px] border border-border bg-white/80 px-4 py-3 text-sm outline-none transition focus:border-accent"
                  >
                    <option value="structured">{executionStyleLabel("structured")}</option>
                    <option value="balanced">{executionStyleLabel("balanced")}</option>
                    <option value="chaotic">{executionStyleLabel("chaotic")}</option>
                  </select>
                </label>
              </div>

              <div className="mt-4 space-y-4">
                <label className="block">
                  <span className="mb-2 block text-sm font-medium text-text-primary">
                    JD 信号
                  </span>
                  <textarea
                    rows={3}
                    value={offer.jdSignals}
                    onChange={(event) =>
                      setRequest((current) => ({
                        ...current,
                        offers: updateOffer(
                          current.offers,
                          index,
                          "jdSignals",
                          event.target.value,
                        ) as OfferInput[],
                      }))
                    }
                    className="w-full rounded-[18px] border border-border bg-white/80 px-4 py-3 text-sm leading-7 outline-none transition focus:border-accent"
                  />
                </label>

                <label className="block">
                  <span className="mb-2 block text-sm font-medium text-text-primary">
                    备注
                  </span>
                  <textarea
                    rows={3}
                    value={offer.notes}
                    onChange={(event) =>
                      setRequest((current) => ({
                        ...current,
                        offers: updateOffer(
                          current.offers,
                          index,
                          "notes",
                          event.target.value,
                        ) as OfferInput[],
                      }))
                    }
                    className="w-full rounded-[18px] border border-border bg-white/80 px-4 py-3 text-sm leading-7 outline-none transition focus:border-accent"
                  />
                </label>
              </div>
            </div>
          ))}
        </section>

        <section className="grid gap-6 xl:grid-cols-[0.95fr_1.05fr]">
          <div className="panel rounded-[30px] p-6 sm:p-8">
            <p className="text-xs font-medium uppercase tracking-[0.26em] text-text-secondary">
              研究服务
            </p>
            <h2 className="mt-2 text-2xl font-semibold text-text-primary">
              当前服务状态
            </h2>
            {capabilityError ? (
              <div className="mt-5 rounded-[22px] border border-[#d8c2a7] bg-[#fbf2e8] p-4 text-sm leading-7 text-text-secondary">
                {capabilityError}
              </div>
            ) : capabilities ? (
              <div className="mt-5 space-y-4">
                <div className="grid gap-3">
                  {capabilities.modes.map((mode) => (
                    <div
                      key={mode.mode}
                      className="rounded-[22px] border border-border bg-white/75 p-4"
                    >
                      <div className="flex flex-wrap items-center justify-between gap-3">
                        <div>
                          <div className="text-sm font-medium text-text-primary">
                            {modeButtonLabel(mode.mode)}
                          </div>
                          <div className="mt-1 text-sm text-text-secondary">
                            {providerLabel(mode.provider)}
                          </div>
                        </div>
                        <div
                          className={`rounded-full px-3 py-1 text-[11px] uppercase tracking-[0.24em] ${
                            mode.enabled
                              ? "bg-[#eef8f1] text-[#2c6b46]"
                              : "bg-[#fbf2e8] text-[#8a6431]"
                          }`}
                        >
                          {mode.enabled ? "可用" : "会降级"}
                        </div>
                      </div>
                      <p className="mt-3 text-sm leading-7 text-text-secondary">
                        {modeCapabilityDetail(mode.mode, mode.enabled)}
                      </p>
                    </div>
                  ))}
                </div>

                <div className="grid gap-4 lg:grid-cols-2">
                  <div className="rounded-[22px] border border-border bg-white/75 p-4">
                    <div className="text-sm font-medium text-text-primary">
                      公开信号研究
                    </div>
                    <p className="mt-3 text-sm leading-7 text-text-secondary">
                      {liveProviderDetail(
                        capabilities.liveProvider.enabled,
                        capabilities.liveProvider.provider,
                      )}
                    </p>
                    <details className="mt-3 rounded-[16px] border border-border bg-background/60 px-4 py-3 text-sm text-text-secondary">
                      <summary className="cursor-pointer list-none font-medium text-text-primary">
                        查看技术详情
                      </summary>
                      <div className="mt-3 font-mono text-[11px] uppercase tracking-[0.22em] text-text-secondary">
                        {providerLabel(capabilities.liveProvider.provider)} ·{" "}
                        {capabilities.liveProvider.model}
                      </div>
                    </details>
                  </div>
                  <div className="rounded-[22px] border border-border bg-white/75 p-4">
                    <div className="text-sm font-medium text-text-primary">
                      公开来源状态
                    </div>
                    <p className="mt-3 text-sm leading-7 text-text-secondary">
                      {retrievalDetail(capabilities.retrieval.enabled)}
                    </p>
                    <details className="mt-3 rounded-[16px] border border-border bg-background/60 px-4 py-3 text-sm text-text-secondary">
                      <summary className="cursor-pointer list-none font-medium text-text-primary">
                        查看技术详情
                      </summary>
                      <div className="mt-3 font-mono text-[11px] uppercase tracking-[0.22em] text-text-secondary">
                        {providerLabel(capabilities.retrieval.provider)} · top{" "}
                        {capabilities.retrieval.maxResults}
                      </div>
                      <p className="mt-2 text-xs uppercase tracking-[0.22em] text-text-secondary">
                        质量阈值 {capabilities.retrieval.minQualityScore} · TTL{" "}
                        {capabilities.retrieval.cacheTtlMinutes} 分钟 · 新鲜度窗口{" "}
                        {capabilities.retrieval.maxFreshnessAgeDays} 天
                      </p>
                    </details>
                  </div>
                </div>
              </div>
            ) : (
              <div className="mt-5 rounded-[22px] border border-border bg-white/75 p-4 text-sm text-text-secondary">
                正在加载后端能力状态...
              </div>
            )}
          </div>

          <div className="panel rounded-[30px] p-6 sm:p-8">
            <div className="flex flex-wrap items-center justify-between gap-3">
              <div>
                <p className="text-xs font-medium uppercase tracking-[0.26em] text-text-secondary">
                  决策记忆
                </p>
                <h2 className="mt-2 text-2xl font-semibold text-text-primary">
                  最近分析记录
                </h2>
              </div>
              <div className="rounded-full border border-border bg-white/80 px-4 py-2 text-xs uppercase tracking-[0.22em] text-text-secondary">
                本机记录
              </div>
            </div>
            <p className="mt-3 max-w-2xl text-sm leading-7 text-text-secondary">
              最近几次分析会保存在当前浏览器里，方便你回看当时的输入、建议结论和比较分数。
            </p>

            {history.length ? (
              <div className="mt-5 space-y-3">
                {history.map((entry) => (
                  <div
                    key={entry.id}
                    className="rounded-[22px] border border-border bg-white/75 p-4"
                  >
                    <div className="flex flex-wrap items-start justify-between gap-3">
                      <div>
                        <div className="text-sm font-medium text-text-primary">
                          {entry.analysis.recommendation.winner}
                        </div>
                        <div className="mt-1 text-sm text-text-secondary">
                          {entry.request.offers[0].company} 对比{" "}
                          {entry.request.offers[1].company}
                        </div>
                      </div>
                      <div className="text-right">
                        <div className="font-mono text-[11px] uppercase tracking-[0.22em] text-text-secondary">
                          {modeButtonLabel(entry.mode)}
                        </div>
                        <div className="mt-1 text-sm text-text-secondary">
                          {new Date(entry.savedAt).toLocaleString()}
                        </div>
                      </div>
                    </div>
                    <p className="mt-3 text-sm leading-7 text-text-secondary">
                      {entry.analysis.recommendation.summary}
                    </p>
                    <div className="mt-4 flex flex-wrap items-center justify-between gap-3">
                      <div className="text-xs uppercase tracking-[0.22em] text-text-secondary">
                        {engineModeLabel(entry.analysis.engine.mode)} ·{" "}
                        {entry.analysis.snapshots[0]?.overallScore ?? "-"} /{" "}
                        {entry.analysis.snapshots[1]?.overallScore ?? "-"}
                      </div>
                      <button
                        type="button"
                        onClick={() => restoreHistoryEntry(entry)}
                        className="rounded-full border border-border bg-white/80 px-4 py-2 text-sm font-medium text-text-primary transition hover:-translate-y-0.5"
                      >
                        恢复本次
                      </button>
                    </div>
                  </div>
                ))}
              </div>
            ) : (
              <div className="mt-5 rounded-[22px] border border-border bg-white/75 p-4 text-sm leading-7 text-text-secondary">
                还没有保存的分析记录。完成第一次分析后，这里会保留最近的决策链路，
                方便快速回放和比较。
              </div>
            )}
          </div>
        </section>

        <section className="flex flex-col gap-4 rounded-[30px] border border-border bg-[rgba(255,248,238,0.92)] p-6 shadow-[0_24px_80px_rgba(63,40,17,0.08)] sm:flex-row sm:items-center sm:justify-between">
          <div>
            <p className="text-xs font-medium uppercase tracking-[0.26em] text-text-secondary">
              研究执行
            </p>
            <h2 className="mt-2 text-2xl font-semibold text-text-primary">
              开始一轮比较
            </h2>
            <p className="mt-2 max-w-2xl text-sm leading-7 text-text-secondary">
              系统会先整理你的输入，再生成一版基线建议；如果公开信号服务可用，还会继续补充近期动态与来源依据。
            </p>
          </div>

          <button
            type="button"
            onClick={handleSubmit}
            disabled={isPending}
            className="rounded-full bg-text-primary px-6 py-3 text-sm font-medium text-white transition hover:-translate-y-0.5 disabled:cursor-wait disabled:opacity-70"
          >
            {isPending ? "正在分析..." : "开始分析"}
          </button>
        </section>

        {error ? (
          <section className="rounded-[24px] border border-[#d2996d] bg-[#fff3e6] px-5 py-4 text-sm text-[#7a4f2d]">
            {error}
          </section>
        ) : null}

        {analysis ? (
          <section className="grid gap-6 xl:grid-cols-[0.82fr_1.18fr]">
            <div className="space-y-6">
              <div className="panel rounded-[30px] p-6 sm:p-8">
                <div className="flex flex-wrap items-center gap-3">
                  <Pill>{engineLabel(analysis.engine.mode)}</Pill>
                  <span className="font-mono text-xs uppercase tracking-[0.22em] text-text-secondary">
                    本次方式 {modeButtonLabel(analysis.engine.requestedMode)}
                  </span>
                </div>
                <p className="mt-4 text-sm leading-7 text-text-secondary">
                  {analysis.engine.detail}
                </p>
                <div
                  className={`mt-5 rounded-[22px] border px-4 py-4 text-sm leading-7 ${researchStatusClass(
                    analysis,
                  )}`}
                >
                  <div className="flex flex-wrap items-center justify-between gap-3">
                    <div className="font-medium text-text-primary">
                      {researchStatusTitle(analysis)}
                    </div>
                    <div
                      className={`rounded-full px-3 py-1 text-[11px] uppercase tracking-[0.22em] ${runOutcomeClass(
                        analysis.engine.requestedMode,
                        analysis.engine.mode,
                      )}`}
                    >
                      {runOutcomeLabel(
                        analysis.engine.requestedMode,
                        analysis.engine.mode,
                      )}
                    </div>
                  </div>
                  <p className="mt-2">{researchStatusDetail(analysis)}</p>
                </div>
              </div>

              <div className="panel rounded-[30px] p-6 sm:p-8">
                <p className="text-xs font-medium uppercase tracking-[0.26em] text-text-secondary">
                  决策建议
                </p>
                <h2 className="mt-3 max-w-lg font-serif text-4xl leading-tight text-text-primary">
                  {analysis.recommendation.winner}
                </h2>
                <p className="mt-4 text-base leading-8 text-text-secondary">
                  {analysis.recommendation.summary}
                </p>
                <div className="mt-6 rounded-[24px] border border-border bg-white/75 p-4">
                  <div className="text-sm font-medium text-text-primary">
                    为什么推荐它
                  </div>
                  <p className="mt-2 text-sm leading-7 text-text-secondary">
                    {analysis.recommendation.rationale}
                  </p>
                </div>
                <div className="mt-4 rounded-[24px] border border-[#d8c2a7] bg-[#fbf2e8] p-4">
                  <div className="text-sm font-medium text-text-primary">
                    仍需核验的部分
                  </div>
                  <p className="mt-2 text-sm leading-7 text-text-secondary">
                    {analysis.recommendation.caution}
                  </p>
                </div>
              </div>

              <div className="panel rounded-[30px] p-6 sm:p-8">
                <p className="text-xs font-medium uppercase tracking-[0.26em] text-text-secondary">
                  分析链路
                </p>
                <div className="mt-5 space-y-3">
                  {analysis.pipeline.map((step) => (
                    <div
                      key={step.title}
                      className="rounded-[22px] border border-border bg-white/75 p-4"
                    >
                      <div className="flex items-center justify-between gap-3">
                        <div className="text-sm font-medium text-text-primary">
                          {step.title}
                        </div>
                        <div
                          className={`rounded-full px-3 py-1 text-[11px] uppercase tracking-[0.24em] ${
                            step.status === "done"
                              ? "bg-accent-soft text-accent"
                              : "bg-[#f9ead8] text-accent-warm"
                          }`}
                        >
                          {pipelineStatusLabel(step.status)}
                        </div>
                      </div>
                      <p className="mt-2 text-sm leading-7 text-text-secondary">
                        {step.detail}
                      </p>
                    </div>
                  ))}
                </div>
              </div>

              <div className="panel rounded-[30px] p-6 sm:p-8">
                <p className="text-xs font-medium uppercase tracking-[0.26em] text-text-secondary">
                  时效性策略
                </p>
                <div className="mt-5 grid gap-4">
                  <div className="rounded-[22px] border border-border bg-white/75 p-4">
                    <div className="text-sm font-medium text-text-primary">
                      稳定知识
                    </div>
                    <div className="mt-3 space-y-2 text-sm leading-7 text-text-secondary">
                      {analysis.freshness.stableKnowledge.map((item) => (
                        <p key={item}>• {item}</p>
                      ))}
                    </div>
                  </div>
                  <div className="rounded-[22px] border border-[#d8c2a7] bg-[#fbf2e8] p-4">
                    <div className="text-sm font-medium text-text-primary">
                      必须实时核验
                    </div>
                    <div className="mt-3 space-y-2 text-sm leading-7 text-text-secondary">
                      {analysis.freshness.needsVerification.map((item) => (
                        <p key={item}>• {item}</p>
                      ))}
                    </div>
                  </div>
                </div>
              </div>
            </div>

            <div className="space-y-6">
              <div className="panel rounded-[30px] p-6 sm:p-8">
                <div className="flex items-center justify-between gap-4">
                  <div>
                    <p className="text-xs font-medium uppercase tracking-[0.26em] text-text-secondary">
                      机会快照
                    </p>
                    <h2 className="mt-2 text-2xl font-semibold text-text-primary">
                      横向对比视图
                    </h2>
                  </div>
                  <div className="font-mono text-xs uppercase tracking-[0.24em] text-text-secondary">
                    {new Date(analysis.generatedAt).toLocaleString()}
                  </div>
                </div>

                <div className="mt-6 grid gap-4 lg:grid-cols-2">
                  {analysis.snapshots.map((snapshot) => (
                    <div
                      key={snapshot.id}
                      className="rounded-[24px] border border-border bg-white/75 p-5"
                    >
                      <div className="flex items-center justify-between gap-4">
                        <div>
                          <div className="text-lg font-semibold text-text-primary">
                            {snapshot.company}
                          </div>
                          <div className="mt-1 text-sm text-text-secondary">
                            {snapshot.role}
                          </div>
                        </div>
                        <div className="rounded-full bg-accent-soft px-3 py-1 text-sm font-semibold text-accent">
                          {snapshot.overallScore}/10
                        </div>
                      </div>

                      <div className="mt-4">
                        <div className="text-sm font-medium text-text-primary">
                          优势
                        </div>
                        <div className="mt-2 space-y-2 text-sm leading-7 text-text-secondary">
                          {snapshot.strengths.map((item) => (
                            <p key={item}>• {item}</p>
                          ))}
                        </div>
                      </div>

                      <div className="mt-4">
                        <div className="text-sm font-medium text-text-primary">
                          风险点
                        </div>
                        <div className="mt-2 space-y-2 text-sm leading-7 text-text-secondary">
                          {snapshot.watchouts.map((item) => (
                            <p key={item}>• {item}</p>
                          ))}
                        </div>
                      </div>
                    </div>
                  ))}
                </div>
              </div>

              {analysis.liveResearch ? (
                <div className="panel rounded-[30px] p-6 sm:p-8">
                  <p className="text-xs font-medium uppercase tracking-[0.26em] text-text-secondary">
                    公开信号
                  </p>
                  <h2 className="mt-2 text-2xl font-semibold text-text-primary">
                    最近动向与依据
                  </h2>
                  <p className="mt-3 text-sm leading-7 text-text-secondary">
                    {analysis.liveResearch.marketTakeaway}
                  </p>

                  <div className="mt-5 rounded-[24px] border border-border bg-white/75 p-5">
                    <div className="flex flex-wrap items-center justify-between gap-3">
                      <div>
                        <div className="text-sm font-medium text-text-primary">
                          研究摘要
                        </div>
                        <div className="mt-1 text-sm text-text-secondary">
                          本次研究共整合了 {analysis.liveResearch.sources.length} 条可追溯来源。
                        </div>
                      </div>
                      <div className="rounded-full bg-background-strong px-3 py-1 font-mono text-xs uppercase tracking-[0.22em] text-text-secondary">
                        {formatLatency(analysis.liveResearch.latencyMs)}
                      </div>
                    </div>
                    {analysis.engine.requestedMode === "demo" ? (
                      <div className="mt-4 rounded-[18px] border border-[#b7d7c2] bg-[#eef8f1] px-4 py-3 text-sm leading-7 text-[#2c6b46]">
                        这次使用的是离线体验链路，结果稳定可复现，适合先体验完整流程，但不代表真实公网的最新动态。
                      </div>
                    ) : null}
                    <details className="mt-4 rounded-[18px] border border-border bg-background/60 px-4 py-3 text-sm text-text-secondary">
                      <summary className="cursor-pointer list-none font-medium text-text-primary">
                        查看研究详情
                      </summary>
                      <div className="mt-3 text-sm leading-7 text-text-secondary">
                        <p>
                          研究方式：{providerLabel(analysis.liveResearch.provider)}
                        </p>
                        <p>结构化模型：{analysis.liveResearch.model}</p>
                      </div>
                      <div className="mt-4 grid gap-3 md:grid-cols-2">
                        {analysis.liveResearch.stageTimings.map((stage) => (
                          <div
                            key={`${stage.stage}-${stage.status}`}
                            className="rounded-[16px] border border-border bg-white/75 p-3"
                          >
                            <div className="font-mono text-[11px] uppercase tracking-[0.22em] text-text-secondary">
                              {stage.stage}
                            </div>
                            <div className="mt-2 flex items-center justify-between gap-3 text-sm">
                              <span className="text-text-primary">
                                {stageStatusLabel(stage.status)}
                              </span>
                              <span className="text-text-secondary">
                                {formatLatency(stage.latencyMs)}
                              </span>
                            </div>
                          </div>
                        ))}
                      </div>
                    </details>
                  </div>

                  <div className="mt-6 space-y-4">
                    {analysis.liveResearch.companySignals.map((signal) => (
                      <div
                        key={signal.company}
                        className="rounded-[24px] border border-border bg-white/75 p-5"
                      >
                        <div className="flex flex-wrap items-center justify-between gap-3">
                          <div className="text-lg font-semibold text-text-primary">
                            {signal.company}
                          </div>
                          <div className="rounded-full border border-border px-3 py-1 font-mono text-xs uppercase tracking-[0.22em] text-text-secondary">
                            置信度 {confidenceLabel(signal.confidence)}
                          </div>
                        </div>
                        <p className="mt-3 text-sm leading-7 text-text-secondary">
                          {signal.summary}
                        </p>

                        <div className="mt-4 flex flex-wrap gap-2">
                          <span
                            className={`rounded-full border px-3 py-1 text-[11px] font-medium uppercase tracking-[0.22em] ${signalToneClass(signal.hiringSignal)}`}
                          >
                            招聘 {signalToneLabel(signal.hiringSignal)}
                          </span>
                          <span
                            className={`rounded-full border px-3 py-1 text-[11px] font-medium uppercase tracking-[0.22em] ${signalToneClass(signal.businessSignal)}`}
                          >
                            业务 {signalToneLabel(signal.businessSignal)}
                          </span>
                          <span
                            className={`rounded-full border px-3 py-1 text-[11px] font-medium uppercase tracking-[0.22em] ${signalToneClass(signal.technicalSignal)}`}
                          >
                            技术 {signalToneLabel(signal.technicalSignal)}
                          </span>
                        </div>

                        <div className="mt-4 grid gap-4 lg:grid-cols-2">
                          <div className="rounded-[20px] border border-border bg-background/60 p-4">
                            <div className="text-sm font-medium text-text-primary">
                              关键信号
                            </div>
                            <div className="mt-3 space-y-3 text-sm leading-7 text-text-secondary">
                              {signal.keySignals.map((item) => (
                                <div key={`${signal.company}-${item.title}`}>
                                  <p className="font-medium text-text-primary">
                                    {item.title}
                                  </p>
                                  <p>{item.detail}</p>
                                  <p>{item.whyItMatters}</p>
                                  {item.sourceUrl ? (
                                    <a
                                      href={item.sourceUrl}
                                      target="_blank"
                                      rel="noreferrer"
                                      className="text-accent underline underline-offset-4"
                                    >
                                      {item.sourceLabel}
                                    </a>
                                  ) : (
                                    <p className="text-xs uppercase tracking-[0.22em] text-text-secondary/80">
                                      {item.sourceLabel}
                                    </p>
                                  )}
                                </div>
                              ))}
                            </div>
                          </div>

                          <div className="space-y-4">
                            <div className="rounded-[20px] border border-border bg-background/60 p-4">
                              <div className="text-sm font-medium text-text-primary">
                                机会点
                              </div>
                              <div className="mt-3 space-y-2 text-sm leading-7 text-text-secondary">
                                {signal.opportunities.map((item) => (
                                  <p key={`${signal.company}-${item}`}>• {item}</p>
                                ))}
                              </div>
                            </div>
                            <div className="rounded-[20px] border border-[#d8c2a7] bg-[#fbf2e8] p-4">
                              <div className="text-sm font-medium text-text-primary">
                                必须核验
                              </div>
                              <div className="mt-3 space-y-2 text-sm leading-7 text-text-secondary">
                                {signal.mustVerify.map((item) => (
                                  <p key={`${signal.company}-${item}`}>• {item}</p>
                                ))}
                              </div>
                            </div>
                          </div>
                        </div>
                      </div>
                    ))}
                  </div>

                  {analysis.liveResearch.sources.length ? (
                    <div className="mt-6 rounded-[24px] border border-border bg-white/75 p-5">
                      <div className="text-sm font-medium text-text-primary">
                        使用到的来源
                      </div>
                      <div className="mt-3 grid gap-3 md:grid-cols-2">
                        {analysis.liveResearch.sources.map((source) => (
                          <a
                            key={source.url}
                            href={source.url}
                            target="_blank"
                            rel="noreferrer"
                            className="rounded-[18px] border border-border bg-background/60 p-3 text-sm text-text-secondary transition hover:-translate-y-0.5"
                          >
                            <div className="font-medium text-text-primary">
                              {source.label}
                            </div>
                            <div className="mt-1">{source.domain}</div>
                            <div className="mt-1 text-xs uppercase tracking-[0.22em]">
                              {source.company}
                            </div>
                          </a>
                        ))}
                      </div>
                    </div>
                  ) : (
                    <div className="mt-6 rounded-[24px] border border-[#d8c2a7] bg-[#fbf2e8] p-5 text-sm leading-7 text-text-secondary">
                      实时研究引擎虽然返回了可用摘要，但引用覆盖仍然偏薄。
                      这次结果更适合作为方向性信号，而不是最终结论。
                    </div>
                  )}
                </div>
              ) : null}

              <div className="space-y-4">
                {analysis.dimensions.map((dimension) => (
                  <div
                    key={dimension.key}
                    className="panel rounded-[30px] p-6 sm:p-8"
                  >
                    <div className="flex flex-wrap items-start justify-between gap-4">
                      <div>
                        <p className="text-xs font-medium uppercase tracking-[0.26em] text-text-secondary">
                          {dimension.title}
                        </p>
                        <h3 className="mt-2 text-xl font-semibold text-text-primary">
                          {dimension.winner}
                        </h3>
                      </div>
                      <div className="rounded-full border border-border bg-white/80 px-4 py-2 text-xs uppercase tracking-[0.22em] text-text-secondary">
                        权重 {dimension.weight.toFixed(2)}
                      </div>
                    </div>

                    <p className="mt-4 text-sm leading-7 text-text-secondary">
                      {dimension.verdict}
                    </p>

                    {dimension.liveAdjustment ? (
                      <div className="mt-5 rounded-[22px] border border-border bg-[#fffaf3] p-4">
                        <div className="text-sm font-medium text-text-primary">
                          实时校正
                        </div>
                        <p className="mt-2 text-sm leading-7 text-text-secondary">
                          {dimension.liveAdjustment.summary}
                        </p>
                        <div className="mt-4 grid gap-3 md:grid-cols-2">
                          <div className="rounded-[18px] border border-border bg-white/80 p-3">
                            <div className="flex items-center justify-between gap-3">
                              <span className="text-sm font-medium text-text-primary">
                                {request.offers[0].company}
                              </span>
                              <span
                                className={`rounded-full px-3 py-1 text-xs font-medium ${deltaToneClass(dimension.liveAdjustment.deltaA)}`}
                              >
                                {formatDelta(dimension.liveAdjustment.deltaA)}
                              </span>
                            </div>
                            <p className="mt-2 text-sm leading-7 text-text-secondary">
                              {dimension.liveAdjustment.companyAEffect}
                            </p>
                          </div>
                          <div className="rounded-[18px] border border-border bg-white/80 p-3">
                            <div className="flex items-center justify-between gap-3">
                              <span className="text-sm font-medium text-text-primary">
                                {request.offers[1].company}
                              </span>
                              <span
                                className={`rounded-full px-3 py-1 text-xs font-medium ${deltaToneClass(dimension.liveAdjustment.deltaB)}`}
                              >
                                {formatDelta(dimension.liveAdjustment.deltaB)}
                              </span>
                            </div>
                            <p className="mt-2 text-sm leading-7 text-text-secondary">
                              {dimension.liveAdjustment.companyBEffect}
                            </p>
                          </div>
                        </div>
                      </div>
                    ) : null}

                    <div className="mt-5 grid gap-4 md:grid-cols-2">
                      <div className="rounded-[22px] border border-border bg-white/75 p-4">
                        <div className="flex items-center justify-between text-sm font-medium text-text-primary">
                          <span>{request.offers[0].company}</span>
                          <span>{dimension.scoreA}/10</span>
                        </div>
                        <div className="mt-3 h-2 overflow-hidden rounded-full bg-background-strong">
                          <div
                            className="h-full rounded-full bg-accent"
                            style={{ width: `${dimension.scoreA * 10}%` }}
                          />
                        </div>
                      </div>

                      <div className="rounded-[22px] border border-border bg-white/75 p-4">
                        <div className="flex items-center justify-between text-sm font-medium text-text-primary">
                          <span>{request.offers[1].company}</span>
                          <span>{dimension.scoreB}/10</span>
                        </div>
                        <div className="mt-3 h-2 overflow-hidden rounded-full bg-background-strong">
                          <div
                            className="h-full rounded-full bg-accent-warm"
                            style={{ width: `${dimension.scoreB * 10}%` }}
                          />
                        </div>
                      </div>
                    </div>

                    <div className="mt-5 grid gap-4 lg:grid-cols-3">
                      <div className="rounded-[22px] border border-border bg-white/75 p-4">
                        <div className="text-sm font-medium text-text-primary">
                          证据
                        </div>
                        <div className="mt-3 space-y-3 text-sm leading-7 text-text-secondary">
                          {dimension.evidence.map((item) => (
                            <div key={`${dimension.key}-${item.label}`}>
                              <p>{item.label}</p>
                              <p className="font-mono text-[11px] uppercase tracking-[0.22em] text-text-secondary/80">
                                {sourceTypeLabel(item.sourceType)} ·{" "}
                                {freshnessLabel(item.freshness)}
                              </p>
                            </div>
                          ))}
                        </div>
                      </div>

                      <div className="rounded-[22px] border border-[#d8c2a7] bg-[#fbf2e8] p-4">
                        <div className="text-sm font-medium text-text-primary">
                          风险
                        </div>
                        <div className="mt-3 space-y-2 text-sm leading-7 text-text-secondary">
                          {dimension.risks.map((item) => (
                            <p key={`${dimension.key}-${item}`}>• {item}</p>
                          ))}
                        </div>
                      </div>

                      <div className="rounded-[22px] border border-border bg-white/75 p-4">
                        <div className="text-sm font-medium text-text-primary">
                          追问问题
                        </div>
                        <div className="mt-3 space-y-2 text-sm leading-7 text-text-secondary">
                          {dimension.followUps.map((item) => (
                            <p key={`${dimension.key}-${item}`}>• {item}</p>
                          ))}
                        </div>
                      </div>
                    </div>
                  </div>
                ))}
              </div>
            </div>
          </section>
        ) : null}
      </div>
    </main>
  );
}
