package com.shichangwei.offerdecision;

import java.util.List;

record WorkspaceResponse(
    String id,
    String name,
    UserProfile userProfile,
    List<SavedOfferRecord> offers,
    List<WorkspaceRunSummary> recentRuns,
    String updatedAt) {}

record SavedOfferRecord(
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
    String notes,
    String rawText,
    String source,
    String createdAt,
    String updatedAt) {}

record UpdateWorkspaceProfileRequest(String target, String riskAppetite, List<String> priorities) {}

record SaveWorkspaceOfferRequest(
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
    String notes,
    String rawText,
    String source) {}

record IntakeParseRequest(String rawText) {}

record IntakeParseResponse(
    ParsedOfferDraft draft, int confidenceScore, List<String> warnings, List<String> extractedSignals) {}

record ParsedOfferDraft(
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
    String notes,
    String rawText,
    String source) {}

record CreateWorkspaceRunRequest(DecisionRequest request, String mode) {}

record WorkspaceRunJobAccepted(
    String id,
    String requestedMode,
    String status,
    String progressDetail,
    DecisionAnalysis analysis,
    String createdAt) {}

record WorkspaceRunSummary(
    String id,
    String requestedMode,
    String engineMode,
    String status,
    String progressDetail,
    String winner,
    String summary,
    String createdAt,
    String completedAt) {}

record WorkspaceRunDetail(
    String id,
    String requestedMode,
    String status,
    String progressDetail,
    DecisionRequest request,
    DecisionAnalysis analysis,
    String createdAt,
    String startedAt,
    String completedAt) {}
