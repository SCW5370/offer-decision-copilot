package com.shichangwei.offerdecision;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

record AnalyzeDecisionRequest(DecisionRequest request, String mode) {}

record DecisionRequest(UserProfile userProfile, List<OfferInput> offers) {}

record UserProfile(String target, String riskAppetite, List<String> priorities) {}

record OfferInput(
    String id,
    String company,
    String role,
    String city,
    String compensation,
    String stage,
    String domain,
    String workMode,
    String stack,
    String managerSupport,
    String executionStyle,
    String jdSignals,
    String notes) {}

record DecisionAnalysis(
    String generatedAt,
    EngineInfo engine,
    Recommendation recommendation,
    List<PipelineStep> pipeline,
    List<OfferSnapshot> snapshots,
    List<DimensionInsight> dimensions,
    FreshnessInfo freshness,
    @JsonInclude(JsonInclude.Include.NON_NULL) LiveResearchReport liveResearch) {}

record EngineInfo(String mode, String requestedMode, String label, String detail) {}

record Recommendation(String winner, String summary, String rationale, String caution) {}

record PipelineStep(String title, String detail, String status) {}

record OfferSnapshot(
    String id,
    String company,
    String role,
    int overallScore,
    List<String> strengths,
    List<String> watchouts) {}

record DimensionInsight(
    String key,
    String title,
    double weight,
    String winner,
    int scoreA,
    int scoreB,
    String verdict,
    @JsonInclude(JsonInclude.Include.NON_NULL) LiveDimensionAdjustment liveAdjustment,
    List<EvidenceItem> evidence,
    List<String> risks,
    List<String> followUps) {}

record LiveDimensionAdjustment(
    int deltaA,
    int deltaB,
    String companyAEffect,
    String companyBEffect,
    String summary) {}

record EvidenceItem(String label, String sourceType, String freshness) {}

record FreshnessInfo(List<String> stableKnowledge, List<String> needsVerification) {}

record LiveResearchReport(
    String provider,
    String model,
    String marketTakeaway,
    List<LiveCompanyResearch> companySignals,
    List<ResearchSource> sources,
    long latencyMs,
    List<ResearchStageTiming> stageTimings) {}

record DecisionCapabilities(
    String generatedAt,
    List<ModeCapability> modes,
    LiveProviderStatus liveProvider,
    RetrievalStatus retrieval) {}

record ModeCapability(String mode, boolean enabled, String label, String detail, String provider) {}

record LiveProviderStatus(
    boolean enabled, String provider, String model, String structuringProvider, String detail) {}

record RetrievalStatus(
    boolean enabled,
    String provider,
    int maxResults,
    int minQualityScore,
    int cacheTtlMinutes,
    int maxFreshnessAgeDays,
    String detail) {}

record LiveCompanyResearch(
    String company,
    String summary,
    String confidence,
    String hiringSignal,
    String businessSignal,
    String technicalSignal,
    List<ResearchSignal> keySignals,
    List<String> opportunities,
    List<String> risks,
    List<String> mustVerify) {}

record ResearchSignal(
    String title,
    String detail,
    String whyItMatters,
    String sourceLabel,
    String sourceUrl,
    String freshness) {}

record ResearchSource(String company, String label, String url, String domain) {}

record ResearchStageTiming(String stage, long latencyMs, String status) {}
