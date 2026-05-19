package com.shichangwei.offerdecision;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OfferWorkspaceService {

  private static final String DEFAULT_WORKSPACE_ID = "default-workspace";
  private static final String DEFAULT_WORKSPACE_NAME = "我的 Offer 工作台";

  private final WorkspaceRepository workspaceRepository;
  private final WorkspaceOfferRepository workspaceOfferRepository;
  private final WorkspaceAnalysisRunRepository workspaceAnalysisRunRepository;
  private final OfferIntakeParser offerIntakeParser;
  private final DecisionAnalysisService decisionAnalysisService;
  private final WorkspaceRunProcessor workspaceRunProcessor;
  private final ObjectMapper objectMapper;

  public OfferWorkspaceService(
      WorkspaceRepository workspaceRepository,
      WorkspaceOfferRepository workspaceOfferRepository,
      WorkspaceAnalysisRunRepository workspaceAnalysisRunRepository,
      OfferIntakeParser offerIntakeParser,
      DecisionAnalysisService decisionAnalysisService,
      WorkspaceRunProcessor workspaceRunProcessor,
      ObjectMapper objectMapper) {
    this.workspaceRepository = workspaceRepository;
    this.workspaceOfferRepository = workspaceOfferRepository;
    this.workspaceAnalysisRunRepository = workspaceAnalysisRunRepository;
    this.offerIntakeParser = offerIntakeParser;
    this.decisionAnalysisService = decisionAnalysisService;
    this.workspaceRunProcessor = workspaceRunProcessor;
    this.objectMapper = objectMapper;
  }

  public WorkspaceResponse getDefaultWorkspace() {
    WorkspaceEntity workspace = getOrCreateDefaultWorkspace();
    return toResponse(workspace);
  }

  public WorkspaceResponse updateProfile(UpdateWorkspaceProfileRequest request) {
    if (request == null || blank(request.target()) || request.priorities() == null || request.priorities().isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "画像信息不完整。");
    }

    WorkspaceEntity workspace = getOrCreateDefaultWorkspace();
    workspace.setTarget(request.target().trim());
    workspace.setRiskAppetite(fallback(request.riskAppetite(), "medium"));
    workspace.setPriorities(joinPriorities(request.priorities()));
    workspace.setUpdatedAt(now());
    workspaceRepository.save(workspace);
    return toResponse(workspace);
  }

  public SavedOfferRecord saveOffer(SaveWorkspaceOfferRequest request) {
    if (request == null || blank(request.company()) || blank(request.role()) || blank(request.stack()) || blank(request.jdSignals())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Offer 信息不完整。");
    }

    getOrCreateDefaultWorkspace();

    WorkspaceOfferEntity offer =
        blank(request.id())
            ? new WorkspaceOfferEntity()
            : workspaceOfferRepository
                .findById(request.id())
                .orElseGet(WorkspaceOfferEntity::new);

    String now = now();
    if (blank(offer.getId())) {
      offer.setId(UUID.randomUUID().toString());
      offer.setCreatedAt(now);
    }

    offer.setWorkspaceId(DEFAULT_WORKSPACE_ID);
    offer.setCompany(request.company().trim());
    offer.setRole(request.role().trim());
    offer.setCity(clean(request.city()));
    offer.setCompensation(clean(request.compensation()));
    offer.setStage(fallback(clean(request.stage()), "startup"));
    offer.setDomain(clean(request.domain()));
    offer.setWorkMode(fallback(clean(request.workMode()), "onsite"));
    offer.setStack(request.stack().trim());
    offer.setManagerSupport(fallback(clean(request.managerSupport()), "medium"));
    offer.setExecutionStyle(fallback(clean(request.executionStyle()), "balanced"));
    offer.setJdSignals(request.jdSignals().trim());
    offer.setNotes(clean(request.notes()));
    offer.setRawText(clean(request.rawText()));
    offer.setSource(fallback(clean(request.source()), "manual"));
    offer.setUpdatedAt(now);

    workspaceOfferRepository.save(offer);
    touchWorkspace();
    return toSavedOfferRecord(offer);
  }

  public void deleteOffer(String offerId) {
    if (blank(offerId)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Offer id 不能为空。");
    }
    workspaceOfferRepository.deleteById(offerId);
    touchWorkspace();
  }

  public IntakeParseResponse parseOfferText(IntakeParseRequest request) {
    if (request == null || blank(request.rawText())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请先粘贴 Offer 或 JD 原文。");
    }
    return offerIntakeParser.parse(request.rawText());
  }

  public WorkspaceRunDetail createRun(CreateWorkspaceRunRequest request) {
    if (request == null || request.request() == null || request.request().userProfile() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "研究请求不能为空。");
    }

    DecisionRequest decisionRequest = request.request();
    validateDecisionRequest(decisionRequest);
    syncProfileFromRequest(decisionRequest.userProfile());

    String mode = blank(request.mode()) ? "auto" : request.mode().trim();
    DecisionAnalysis analysis = decisionAnalysisService.analyze(decisionRequest, mode);
    String createdAt = now();

    WorkspaceAnalysisRunEntity entity = new WorkspaceAnalysisRunEntity();
    entity.setId(UUID.randomUUID().toString());
    entity.setWorkspaceId(DEFAULT_WORKSPACE_ID);
    entity.setRequestedMode(mode);
    entity.setEngineMode(analysis.engine().mode());
    entity.setStatus("completed");
    entity.setProgressDetail(completedProgressDetail(mode, analysis));
    entity.setWinner(analysis.recommendation().winner());
    entity.setSummary(analysis.recommendation().summary());
    entity.setRequestJson(writeJson(decisionRequest));
    entity.setAnalysisJson(writeJson(analysis));
    entity.setCreatedAt(createdAt);
    entity.setStartedAt(createdAt);
    entity.setCompletedAt(createdAt);
    workspaceAnalysisRunRepository.save(entity);
    touchWorkspace();

    return toRunDetail(entity, decisionRequest, analysis);
  }

  public WorkspaceRunJobAccepted enqueueRun(CreateWorkspaceRunRequest request) {
    if (request == null || request.request() == null || request.request().userProfile() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "研究请求不能为空。");
    }

    DecisionRequest decisionRequest = request.request();
    validateDecisionRequest(decisionRequest);
    syncProfileFromRequest(decisionRequest.userProfile());

    String mode = blank(request.mode()) ? "auto" : request.mode().trim();
    if ("heuristic".equalsIgnoreCase(mode)) {
      WorkspaceRunDetail completedRun = createRun(request);
      return new WorkspaceRunJobAccepted(
          completedRun.id(),
          completedRun.requestedMode(),
          completedRun.status(),
          completedRun.progressDetail(),
          completedRun.analysis(),
          completedRun.createdAt());
    }

    DecisionAnalysis baselineAnalysis = toQueuedBaseline(decisionRequest, mode);
    String createdAt = now();

    WorkspaceAnalysisRunEntity entity = new WorkspaceAnalysisRunEntity();
    entity.setId(UUID.randomUUID().toString());
    entity.setWorkspaceId(DEFAULT_WORKSPACE_ID);
    entity.setRequestedMode(mode);
    entity.setEngineMode(baselineAnalysis.engine().mode());
    entity.setStatus("queued");
    entity.setProgressDetail(initialProgressDetail(mode));
    entity.setWinner(baselineAnalysis.recommendation().winner());
    entity.setSummary(baselineAnalysis.recommendation().summary());
    entity.setRequestJson(writeJson(decisionRequest));
    entity.setAnalysisJson(writeJson(baselineAnalysis));
    entity.setCreatedAt(createdAt);
    workspaceAnalysisRunRepository.save(entity);
    touchWorkspace();

    workspaceRunProcessor.processAsync(entity.getId(), decisionRequest, mode);

    return new WorkspaceRunJobAccepted(
        entity.getId(),
        mode,
        "queued",
        entity.getProgressDetail(),
        baselineAnalysis,
        createdAt);
  }

  public List<WorkspaceRunSummary> getRecentRuns() {
    getOrCreateDefaultWorkspace();
    return workspaceAnalysisRunRepository.findTop6ByWorkspaceIdOrderByCreatedAtDesc(DEFAULT_WORKSPACE_ID)
        .stream()
        .map(this::toRunSummary)
        .toList();
  }

  public WorkspaceRunDetail getRunDetail(String runId) {
    if (blank(runId)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "run id 不能为空。");
    }

    WorkspaceAnalysisRunEntity entity =
        workspaceAnalysisRunRepository
            .findById(runId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "没有找到这次研究记录。"));

    try {
      return new WorkspaceRunDetail(
          entity.getId(),
          entity.getRequestedMode(),
          fallback(clean(entity.getStatus()), "completed"),
          fallback(clean(entity.getProgressDetail()), ""),
          objectMapper.readValue(entity.getRequestJson(), DecisionRequest.class),
          objectMapper.readValue(entity.getAnalysisJson(), DecisionAnalysis.class),
          entity.getCreatedAt(),
          fallback(clean(entity.getStartedAt()), ""),
          fallback(clean(entity.getCompletedAt()), ""));
    } catch (JsonProcessingException exception) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "研究记录解析失败。");
    }
  }

  private WorkspaceEntity getOrCreateDefaultWorkspace() {
    return workspaceRepository
        .findById(DEFAULT_WORKSPACE_ID)
        .orElseGet(
            () -> {
              String now = now();
              WorkspaceEntity workspace = new WorkspaceEntity();
              workspace.setId(DEFAULT_WORKSPACE_ID);
              workspace.setName(DEFAULT_WORKSPACE_NAME);
              workspace.setTarget("");
              workspace.setRiskAppetite("medium");
              workspace.setPriorities("");
              workspace.setCreatedAt(now);
              workspace.setUpdatedAt(now);
              return workspaceRepository.save(workspace);
            });
  }

  private void touchWorkspace() {
    WorkspaceEntity workspace = getOrCreateDefaultWorkspace();
    workspace.setUpdatedAt(now());
    workspaceRepository.save(workspace);
  }

  private WorkspaceResponse toResponse(WorkspaceEntity workspace) {
    List<SavedOfferRecord> offers =
        workspaceOfferRepository.findByWorkspaceIdOrderByUpdatedAtDesc(workspace.getId()).stream()
            .map(this::toSavedOfferRecord)
            .toList();
    List<WorkspaceRunSummary> recentRuns =
        workspaceAnalysisRunRepository.findTop6ByWorkspaceIdOrderByCreatedAtDesc(workspace.getId()).stream()
            .map(this::toRunSummary)
            .toList();

    UserProfile profile =
        new UserProfile(
            fallback(clean(workspace.getTarget()), ""),
            fallback(clean(workspace.getRiskAppetite()), "medium"),
            parsePriorities(workspace.getPriorities()));

    return new WorkspaceResponse(
        workspace.getId(), workspace.getName(), profile, offers, recentRuns, workspace.getUpdatedAt());
  }

  private SavedOfferRecord toSavedOfferRecord(WorkspaceOfferEntity offer) {
    return new SavedOfferRecord(
        offer.getId(),
        offer.getCompany(),
        offer.getRole(),
        fallback(offer.getCity(), ""),
        fallback(offer.getCompensation(), ""),
        fallback(offer.getStage(), "startup"),
        fallback(offer.getDomain(), ""),
        fallback(offer.getWorkMode(), "onsite"),
        fallback(offer.getStack(), ""),
        fallback(offer.getManagerSupport(), "medium"),
        fallback(offer.getExecutionStyle(), "balanced"),
        fallback(offer.getJdSignals(), ""),
        fallback(offer.getNotes(), ""),
        fallback(offer.getRawText(), ""),
        fallback(offer.getSource(), "manual"),
        offer.getCreatedAt(),
        offer.getUpdatedAt());
  }

  private List<String> parsePriorities(String priorities) {
    if (blank(priorities)) {
      return List.of();
    }
    return Arrays.stream(priorities.split(","))
        .map(String::trim)
        .filter(item -> !item.isBlank())
        .toList();
  }

  private String joinPriorities(List<String> priorities) {
    return priorities.stream().map(String::trim).filter(item -> !item.isBlank()).distinct().limit(4).reduce((left, right) -> left + "," + right).orElse("technical depth,growth speed");
  }

  private WorkspaceRunSummary toRunSummary(WorkspaceAnalysisRunEntity entity) {
    return new WorkspaceRunSummary(
        entity.getId(),
        entity.getRequestedMode(),
        fallback(clean(entity.getEngineMode()), "heuristic"),
        fallback(clean(entity.getStatus()), "completed"),
        fallback(clean(entity.getProgressDetail()), ""),
        fallback(clean(entity.getWinner()), "研究任务"),
        fallback(entity.getSummary(), ""),
        entity.getCreatedAt(),
        fallback(clean(entity.getCompletedAt()), ""));
  }

  private WorkspaceRunDetail toRunDetail(
      WorkspaceAnalysisRunEntity entity, DecisionRequest request, DecisionAnalysis analysis) {
    return new WorkspaceRunDetail(
        entity.getId(),
        entity.getRequestedMode(),
        fallback(clean(entity.getStatus()), "completed"),
        fallback(clean(entity.getProgressDetail()), ""),
        request,
        analysis,
        entity.getCreatedAt(),
        fallback(clean(entity.getStartedAt()), ""),
        fallback(clean(entity.getCompletedAt()), ""));
  }

  private DecisionAnalysis toQueuedBaseline(DecisionRequest request, String requestedMode) {
    DecisionAnalysis baseline = decisionAnalysisService.analyze(request, "heuristic");
    return new DecisionAnalysis(
        baseline.generatedAt(),
        new EngineInfo(
            "heuristic",
            requestedMode,
            "Java 规则基线",
            "后台实时研究任务已经入队。当前先返回一份规则基线，稍后会把实时检索与研究结果补进来。"),
        baseline.recommendation(),
        baseline.pipeline(),
        baseline.snapshots(),
        baseline.dimensions(),
        baseline.freshness(),
        null);
  }

  private String initialProgressDetail(String mode) {
    return "demo".equalsIgnoreCase(mode)
        ? "研究任务已入队。后台将运行本地可复现实时链路，并在完成后覆盖这份规则基线。"
        : "研究任务已入队。后台会先尝试真实实时研究；如果外部引擎超时或证据不足，再安全降级。";
  }

  private String completedProgressDetail(String mode, DecisionAnalysis analysis) {
    if ("heuristic".equalsIgnoreCase(mode)) {
      return "规则分析已完成。";
    }
    if ("live".equalsIgnoreCase(analysis.engine().mode())) {
      return "研究运行已完成，并返回了可用的实时研究结果。";
    }
    return "研究运行已完成，但实时引擎没有给出稳定结果，因此最终保留规则路径。";
  }

  private void syncProfileFromRequest(UserProfile profile) {
    WorkspaceEntity workspace = getOrCreateDefaultWorkspace();
    workspace.setTarget(clean(profile.target()));
    workspace.setRiskAppetite(fallback(clean(profile.riskAppetite()), "medium"));
    workspace.setPriorities(joinPriorities(profile.priorities()));
    workspace.setUpdatedAt(now());
    workspaceRepository.save(workspace);
  }

  private void validateDecisionRequest(DecisionRequest request) {
    if (blank(request.userProfile().target())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "候选人目标不能为空。");
    }
    if (request.userProfile().priorities() == null || request.userProfile().priorities().isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请至少选择一个优先项。");
    }
    if (request.offers() == null || request.offers().size() != 2) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前研究引擎需要恰好两个 Offer。");
    }
    boolean invalidOffer =
        request.offers().stream()
            .anyMatch(
                offer ->
                    offer == null
                        || blank(offer.company())
                        || blank(offer.role())
                        || blank(offer.stack())
                        || blank(offer.jdSignals()));
    if (invalidOffer) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "两个 Offer 都需要补齐公司、岗位、技术栈和 JD 信号。");
    }
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "研究记录序列化失败。");
    }
  }

  private String now() {
    return Instant.now().toString();
  }

  private String fallback(String value, String fallback) {
    return blank(value) ? fallback : value;
  }

  private String clean(String value) {
    return value == null ? "" : value.trim();
  }

  private boolean blank(String value) {
    return value == null || value.isBlank();
  }
}
