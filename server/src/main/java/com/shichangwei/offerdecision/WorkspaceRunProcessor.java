package com.shichangwei.offerdecision;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class WorkspaceRunProcessor {

  private final WorkspaceAnalysisRunRepository workspaceAnalysisRunRepository;
  private final WorkspaceRepository workspaceRepository;
  private final DecisionAnalysisService decisionAnalysisService;
  private final ObjectMapper objectMapper;

  public WorkspaceRunProcessor(
      WorkspaceAnalysisRunRepository workspaceAnalysisRunRepository,
      WorkspaceRepository workspaceRepository,
      DecisionAnalysisService decisionAnalysisService,
      ObjectMapper objectMapper) {
    this.workspaceAnalysisRunRepository = workspaceAnalysisRunRepository;
    this.workspaceRepository = workspaceRepository;
    this.decisionAnalysisService = decisionAnalysisService;
    this.objectMapper = objectMapper;
  }

  @Async("workspaceRunTaskExecutor")
  public void processAsync(String runId, DecisionRequest request, String mode) {
    WorkspaceAnalysisRunEntity entity =
        workspaceAnalysisRunRepository.findById(runId).orElse(null);
    if (entity == null) {
      return;
    }

    entity.setStatus("running");
    entity.setStartedAt(now());
    entity.setProgressDetail(runningDetail(mode));
    workspaceAnalysisRunRepository.save(entity);
    touchWorkspace(entity.getWorkspaceId());

    try {
      DecisionAnalysis analysis = decisionAnalysisService.analyze(request, mode);
      entity.setAnalysisJson(writeJson(analysis));
      entity.setEngineMode(analysis.engine().mode());
      entity.setWinner(analysis.recommendation().winner());
      entity.setSummary(analysis.recommendation().summary());
      entity.setStatus("completed");
      entity.setProgressDetail(completedDetail(mode, analysis));
      entity.setCompletedAt(now());
      workspaceAnalysisRunRepository.save(entity);
      touchWorkspace(entity.getWorkspaceId());
    } catch (Exception exception) {
      entity.setStatus("failed");
      entity.setProgressDetail(
          "研究任务失败，本次先保留离线基线。失败原因：" + shortMessage(exception));
      entity.setCompletedAt(now());
      workspaceAnalysisRunRepository.save(entity);
      touchWorkspace(entity.getWorkspaceId());
    }
  }

  private String completedDetail(String requestedMode, DecisionAnalysis analysis) {
    if ("heuristic".equalsIgnoreCase(requestedMode)) {
      return "规则分析已完成，没有调用实时研究。";
    }
    if ("live".equalsIgnoreCase(analysis.engine().mode())) {
      if (analysis.liveResearch() == null) {
        return "研究任务已完成，但当前结果没有附带公开证据层。";
      }
      return "研究任务已完成。当前结果已经合并了 "
          + analysis.liveResearch().provider()
          + " 路径的最新研究输出。";
    }
    return "研究任务已完成，但实时研究没有返回稳定结果，因此当前保留规则基线。";
  }

  private String runningDetail(String requestedMode) {
    if ("heuristic".equalsIgnoreCase(requestedMode)) {
      return "正在生成规则基线。";
    }
    return "正在后台执行实时研究：先准备基线，再补充检索与证据总结。";
  }

  private void touchWorkspace(String workspaceId) {
    workspaceRepository
        .findById(workspaceId)
        .ifPresent(
            workspace -> {
              workspace.setUpdatedAt(now());
              workspaceRepository.save(workspace);
            });
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("研究结果序列化失败。", exception);
    }
  }

  private String shortMessage(Exception exception) {
    String message = exception.getMessage();
    if (message == null || message.isBlank()) {
      return exception.getClass().getSimpleName();
    }
    return message.length() > 140 ? message.substring(0, 137) + "..." : message;
  }

  private String now() {
    return Instant.now().toString();
  }
}
