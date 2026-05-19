package com.shichangwei.offerdecision;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class DecisionAnalysisService {

  private final HeuristicDecisionAnalyzer heuristicDecisionAnalyzer;
  private final LiveResearchProvider liveResearchProvider;

  public DecisionAnalysisService(
      HeuristicDecisionAnalyzer heuristicDecisionAnalyzer,
      LiveResearchProvider liveResearchProvider) {
    this.heuristicDecisionAnalyzer = heuristicDecisionAnalyzer;
    this.liveResearchProvider = liveResearchProvider;
  }

  public DecisionAnalysis analyze(DecisionRequest request, String requestedMode) {
    String normalizedMode =
        "heuristic".equalsIgnoreCase(requestedMode)
            ? "heuristic"
            : "demo".equalsIgnoreCase(requestedMode) ? "demo" : "auto";

    DecisionAnalysis base = heuristicDecisionAnalyzer.analyze(request);

    if ("heuristic".equals(normalizedMode)) {
      return withEngine(
          base,
          new EngineInfo(
              "heuristic",
              normalizedMode,
              "Java 规则引擎",
              "当前由 Spring Boot 提供核心决策逻辑，本次有意跳过了实时研究引擎调用。"));
    }

    if (!liveResearchProvider.isEnabled(normalizedMode)) {
      return withEngine(
          base,
          new EngineInfo(
              "heuristic",
              normalizedMode,
              "Java 规则引擎",
              "当前由 Spring Boot 提供核心决策逻辑。"
                  + ("demo".equals(normalizedMode)
                      ? "本地演示实时引擎当前不可用，因此本次保持在离线路径。"
                      : "实时研究引擎还没有配置完成，因此本次保持在离线路径。")));
    }

    Optional<LiveResearchReport> liveResearch = liveResearchProvider.research(request, normalizedMode);

    if (liveResearch.isEmpty()) {
      return withEngine(
          base,
          new EngineInfo(
              "heuristic",
              normalizedMode,
              "Java 规则引擎",
              "demo".equals(normalizedMode)
                  ? "本地可复现实时引擎没有返回结构化结果，因此服务安全回退到了规则路径。"
                  : "实时研究引擎虽然已开启，但没有返回结构化结果，因此服务安全回退到了规则路径。"));
    }

    OfferInput offerA = request.offers().get(0);
    OfferInput offerB = request.offers().get(1);
    LiveResearchReport report = liveResearch.get();
    LiveImpact impactA = impactForOffer(offerA, report);
    LiveImpact impactB = impactForOffer(offerB, report);
    boolean modelOnlyReport = isModelOnlyReport(report);

    List<DimensionInsight> mergedDimensions =
        base.dimensions().stream()
            .map(dimension -> mergeDimension(dimension, request, impactA, impactB))
            .toList();

    List<OfferSnapshot> mergedSnapshots =
        List.of(
                mergeSnapshot(base.snapshots(), mergedDimensions, offerA, 0, impactA),
                mergeSnapshot(base.snapshots(), mergedDimensions, offerB, 1, impactB))
            .stream()
            .sorted((left, right) -> Integer.compare(right.overallScore(), left.overallScore()))
            .toList();

    return new DecisionAnalysis(
        Instant.now().toString(),
        new EngineInfo(
            "live",
            normalizedMode,
            "Java 实时研究引擎",
            (modelOnlyReport
                    ? "由于实时公开研究没有返回可用证据，Spring Boot 服务本次使用了仅模型降级路径。"
                    : "Spring Boot 服务已将时效敏感的公开信号合并进最终决策结果。")
                + report.marketTakeaway()),
        buildRecommendation(mergedSnapshots, impactA, impactB),
        appendLiveStep(base.pipeline(), report),
        mergedSnapshots,
        mergedDimensions,
        mergeFreshness(base.freshness(), impactA, impactB, report),
        report);
  }

  private DecisionAnalysis withEngine(DecisionAnalysis base, EngineInfo engineInfo) {
    return new DecisionAnalysis(
        Instant.now().toString(),
        engineInfo,
        base.recommendation(),
        base.pipeline(),
        base.snapshots(),
        base.dimensions(),
        base.freshness(),
        null);
  }

  private DimensionInsight mergeDimension(
      DimensionInsight base,
      DecisionRequest request,
      LiveImpact impactA,
      LiveImpact impactB) {
    OfferInput offerA = request.offers().get(0);
    OfferInput offerB = request.offers().get(1);

    int scoreA =
        clamp(base.scoreA() + deltaForDimension(base.key(), request.userProfile(), offerA, impactA));
    int scoreB =
        clamp(base.scoreB() + deltaForDimension(base.key(), request.userProfile(), offerB, impactB));
    String winner = scoreA >= scoreB ? offerA.company() : offerB.company();

    List<EvidenceItem> evidence = new ArrayList<>(base.evidence());
    evidence.add(toEvidenceItem(base.key(), offerA.company(), impactA));
    evidence.add(toEvidenceItem(base.key(), offerB.company(), impactB));

    List<String> risks =
        dedupeAndLimit(
            base.risks(),
            riskLine(base.key(), offerA.company(), impactA),
            riskLine(base.key(), offerB.company(), impactB));

    List<String> followUps =
        dedupeAndLimit(
            base.followUps(),
            followUpLine(offerA.company(), impactA),
            followUpLine(offerB.company(), impactB));

    return new DimensionInsight(
        base.key(),
        base.title(),
        base.weight(),
        winner,
        scoreA,
        scoreB,
        base.verdict() + " " + liveVerdict(base.key(), winner, offerA.company(), offerB.company(), impactA, impactB),
        new LiveDimensionAdjustment(
            deltaForDimension(base.key(), request.userProfile(), offerA, impactA),
            deltaForDimension(base.key(), request.userProfile(), offerB, impactB),
            effectSummary(offerA.company(), impactA),
            effectSummary(offerB.company(), impactB),
            liveAdjustmentSummary(base.key(), offerA.company(), offerB.company(), impactA, impactB)),
        evidence,
        risks,
        followUps);
  }

  private OfferSnapshot mergeSnapshot(
      List<OfferSnapshot> baseSnapshots,
      List<DimensionInsight> dimensions,
      OfferInput offer,
      int offerIndex,
      LiveImpact impact) {
    OfferSnapshot baseSnapshot = snapshotForCompany(baseSnapshots, offer.company());
    int overallScore =
        clamp(
            (int)
                Math.round(
                    dimensions.stream()
                            .mapToDouble(
                                dimension ->
                                    (offerIndex == 0 ? dimension.scoreA() : dimension.scoreB())
                                        * dimension.weight())
                            .sum()
                        / dimensions.stream().mapToDouble(DimensionInsight::weight).sum()));

    List<String> strengths = new ArrayList<>(baseSnapshot.strengths());
    List<String> watchouts = new ArrayList<>(baseSnapshot.watchouts());

    String momentumLine = momentumLine(impact);
    if (!momentumLine.isBlank()) {
      if (hasNetPositiveMomentum(impact)) {
        strengths.add(momentumLine);
      } else {
        watchouts.add(momentumLine);
      }
    }

    if ("low".equals(impact.confidence()) || isMostlyUnclear(impact)) {
      watchouts.add(
          "最近的公开证据置信度仍然偏低，因此团队层面的直接核验比市场叙事更重要。");
    }

    return new OfferSnapshot(
        baseSnapshot.id(),
        baseSnapshot.company(),
        baseSnapshot.role(),
        overallScore,
        dedupeAndLimit(strengths),
        dedupeAndLimit(watchouts));
  }

  private Recommendation buildRecommendation(
      List<OfferSnapshot> snapshots, LiveImpact impactA, LiveImpact impactB) {
    OfferSnapshot winner = snapshots.getFirst();
    OfferSnapshot runnerUp = snapshots.getLast();
    LiveImpact winnerImpact =
        winner.company().equalsIgnoreCase(impactA.company()) ? impactA : impactB;

    String summary =
        winner.company()
            + " 在经过实时研究后仍然是更强的一档选择，目前在加权决策分上领先 "
            + Math.max(0, winner.overallScore() - runnerUp.overallScore())
            + " 分。";

    String rationale =
        winner.company()
            + " 仍然同时满足了你更多核心优先项。"
            + momentumSummary(winnerImpact);

    String caution =
        followUpLine(winner.company(), winnerImpact)
            + " "
            + "不要让公开层面的势能信号盖过对经理质量、入职支持范围，以及前 90 天真实成长空间的直接核验。";

    return new Recommendation(winner.company(), summary, rationale, caution.trim());
  }

  private FreshnessInfo mergeFreshness(
      FreshnessInfo base, LiveImpact impactA, LiveImpact impactB, LiveResearchReport report) {
    List<String> needsVerification =
        dedupeAndLimit(
            base.needsVerification(),
            impactA.mustVerify(),
            impactB.mustVerify(),
            report.sources().isEmpty()
                ? "实时研究引擎返回的引用覆盖较薄，因此近期组织变化仍然需要人工核验。"
                : null);

    return new FreshnessInfo(base.stableKnowledge(), needsVerification);
  }

  private List<PipelineStep> appendLiveStep(List<PipelineStep> pipeline, LiveResearchReport report) {
    List<PipelineStep> merged = new ArrayList<>(pipeline);
    boolean modelOnlyReport = isModelOnlyReport(report);
    merged.add(
        new PipelineStep(
            "实时核验",
            modelOnlyReport
                ? "本次执行了一次时效敏感的研究流程，耗时 "
                    + report.latencyMs()
                    + " ms。由于公开研究结果不可用，服务改走了不带引用的 input-only 模型降级路径。"
                : "本次执行了一次时效敏感的研究流程，耗时 "
                    + report.latencyMs()
                    + " ms，并将 "
                    + report.sources().size()
                    + " 条公开来源引用合并进最终评分层。",
            "done"));
    return merged;
  }

  private LiveImpact impactForOffer(OfferInput offer, LiveResearchReport report) {
    for (LiveCompanyResearch companyResearch : report.companySignals()) {
      if (isCompanyMatch(offer.company(), companyResearch.company())) {
        return toImpact(offer.company(), companyResearch, isModelOnlyReport(report));
      }
    }

    return LiveImpact.empty(offer.company());
  }

  private LiveImpact toImpact(String fallbackCompany, LiveCompanyResearch research, boolean modelOnly) {
    String company = blankToFallback(research.company(), fallbackCompany);
    return new LiveImpact(
        company,
        research.summary(),
        safeLower(research.confidence()),
        modelOnly ? "needs live verification" : hasFreshSignal(research) ? "fresh" : "needs live verification",
        scoreSignal(research.hiringSignal()),
        scoreSignal(research.businessSignal()),
        scoreSignal(research.technicalSignal()),
        List.copyOf(research.opportunities()),
        List.copyOf(research.risks()),
        List.copyOf(research.mustVerify()),
        modelOnly);
  }

  private int deltaForDimension(
      String key, UserProfile userProfile, OfferInput offer, LiveImpact impact) {
    double confidenceWeight = confidenceWeight(impact.confidence());
    double rawDelta =
        switch (key) {
          case "growth" ->
              (impact.hiringScore() * 0.95
                      + impact.businessScore() * 0.85
                      + impact.technicalScore() * 0.45)
                  * confidenceWeight;
          case "technicalDepth" ->
              (impact.technicalScore() * 1.1 + impact.businessScore() * 0.2) * confidenceWeight;
          case "teamMaturity" ->
              (impact.hiringScore() * 0.7 + impact.businessScore() * 0.55) * confidenceWeight;
          case "companyRisk" ->
              (impact.businessScore() * 1.0 + impact.hiringScore() * 0.8) * confidenceWeight;
          default ->
              (impact.technicalScore() * targetFitWeight(userProfile.target(), offer)
                      + impact.businessScore() * riskFitWeight(userProfile.riskAppetite()))
                  * confidenceWeight;
        };

    return normalizedDelta(rawDelta);
  }

  private double targetFitWeight(String target, OfferInput offer) {
    String combined = safeLower(target) + " " + safeLower(offer.domain()) + " " + safeLower(offer.role());
    if (containsAny(combined, "ai", "agent", "platform", "infra", "backend")) {
      return 0.75;
    }
    return 0.5;
  }

  private double riskFitWeight(String riskAppetite) {
    return switch (safeLower(riskAppetite)) {
      case "high" -> 0.45;
      case "low" -> 0.25;
      default -> 0.35;
    };
  }

  private int normalizedDelta(double raw) {
    if (raw >= 1.15) {
      return 2;
    }
    if (raw >= 0.35) {
      return 1;
    }
    if (raw <= -1.15) {
      return -2;
    }
    if (raw <= -0.35) {
      return -1;
    }
    return 0;
  }

  private EvidenceItem toEvidenceItem(String dimensionKey, String company, LiveImpact impact) {
    String signalPrefix = impact.modelOnly() ? "input-only 降级信号" : "实时信号";
    String label =
        switch (dimensionKey) {
          case "growth" ->
              company
                  + " "
                  + signalPrefix
                  + "：招聘趋势为"
                  + toneLabel(impact.hiringScore())
                  + "，业务势能为"
                  + toneLabel(impact.businessScore())
                  + "。";
          case "technicalDepth" ->
              company
                  + " "
                  + signalPrefix
                  + "：技术势能为"
                  + toneLabel(impact.technicalScore())
                  + "。";
          case "teamMaturity" ->
              company
                  + " "
                  + signalPrefix
                  + "：组织健康度信号为"
                  + toneLabel((impact.hiringScore() + impact.businessScore()) / 2.0)
                  + "。";
          case "companyRisk" ->
              company
                  + " "
                  + signalPrefix
                  + "：公司稳定性信号为"
                  + toneLabel(impact.businessScore() + impact.hiringScore());
          default ->
              company
                  + " "
                  + signalPrefix
                  + "总结："
                  + impact.summary();
        };

    return new EvidenceItem(label, impact.modelOnly() ? "model fallback" : "web research", impact.freshness());
  }

  private String riskLine(String dimensionKey, String company, LiveImpact impact) {
    if (isMostlyUnclear(impact)) {
      return company
          + " 当前公开信号覆盖仍然偏弱，因此这个维度更应该依赖面试中的直接核验。";
    }

    if (impact.businessScore() < 0 || impact.hiringScore() < 0 || impact.technicalScore() < 0) {
      return company
          + " 在 "
          + humanDimensionName(dimensionKey)
          + " 这个维度上至少存在一个负向近期公开信号，因此它的优势可能没有基线规则判断中那么稳固。";
    }

    return "";
  }

  private String followUpLine(String company, LiveImpact impact) {
    if (!impact.mustVerify().isEmpty()) {
      return company + "：" + impact.mustVerify().getFirst();
    }
    return "问清楚 " + company + " 最近 6 个月团队发生了什么变化，以及这会如何影响当前岗位。";
  }

  private String liveVerdict(
      String dimensionKey,
      String winner,
      String companyA,
      String companyB,
      LiveImpact impactA,
      LiveImpact impactB) {
    int liveEdge = rawLiveDimensionDelta(dimensionKey, impactA) - rawLiveDimensionDelta(dimensionKey, impactB);

    if (Math.abs(liveEdge) <= 0) {
      return impactA.modelOnly() || impactB.modelOnly()
          ? "本次 model-only 降级没有在这个维度上明显拉开任何一方。"
          : "本次实时研究没有在这个维度上发现足够强的最新公开优势。";
    }

    String favoredCompany = liveEdge > 0 ? companyA : companyB;
    String sourceLabel = impactA.modelOnly() || impactB.modelOnly() ? "input-only 降级信号" : "近期公开信号";
    return sourceLabel + " 在这个维度上轻微强化了 " + favoredCompany + "，尽管最终胜出者仍然是 " + winner + "。";
  }

  private String momentumLine(LiveImpact impact) {
    if (isMostlyUnclear(impact)) {
      return "实时研究结果仍然不够明确，因此最近势能不足以作为可靠区分项。";
    }
    if (hasNetPositiveMomentum(impact)) {
      return impact.modelOnly()
          ? "仅模型降级信号方向上偏正面，但仍然需要外部证据核验。"
          : "近期公开势能整体偏正面，而不是偏风险。";
    }
    if (impact.businessScore() < 0 || impact.hiringScore() < 0 || impact.technicalScore() < 0) {
      return "近期公开势能里已经出现谨慎信号，建议在团队沟通中直接核验。";
    }
    return "";
  }

  private String momentumSummary(LiveImpact impact) {
    if (isMostlyUnclear(impact)) {
      return "实时研究引擎没有找到足够强的公开证据来明显推动建议，因此结果仍然高度依赖面试侧核验。";
    }
    if (hasNetPositiveMomentum(impact)) {
      return impact.modelOnly()
          ? "仅模型降级在技术或匹配度信号上也偏支持，但它不能被当成外部证据。"
          : "近期公开信号在招聘、业务动向和技术势能上也偏支持，而不是去推翻基线判断。";
    }
    return "实时层虽然捕捉到了一些谨慎信号，但它们还不足以推翻基线决策模型给出的整体匹配结论。";
  }

  private boolean hasNetPositiveMomentum(LiveImpact impact) {
    return impact.businessScore() + impact.hiringScore() + impact.technicalScore() > 0;
  }

  private int rawLiveDimensionDelta(String key, LiveImpact impact) {
    double confidenceWeight = confidenceWeight(impact.confidence());
    double rawDelta =
        switch (key) {
          case "growth" ->
              (impact.hiringScore() * 0.95
                      + impact.businessScore() * 0.85
                      + impact.technicalScore() * 0.45)
                  * confidenceWeight;
          case "technicalDepth" ->
              (impact.technicalScore() * 1.1 + impact.businessScore() * 0.2) * confidenceWeight;
          case "teamMaturity" ->
              (impact.hiringScore() * 0.7 + impact.businessScore() * 0.55) * confidenceWeight;
          case "companyRisk" ->
              (impact.businessScore() * 1.0 + impact.hiringScore() * 0.8) * confidenceWeight;
          default -> (impact.technicalScore() * 0.65 + impact.businessScore() * 0.35) * confidenceWeight;
        };
    return normalizedDelta(rawDelta);
  }

  private boolean isMostlyUnclear(LiveImpact impact) {
    return impact.businessScore() == 0
        && impact.hiringScore() == 0
        && impact.technicalScore() == 0;
  }

  private boolean hasFreshSignal(LiveCompanyResearch research) {
    return research.keySignals().stream()
        .anyMatch(signal -> "fresh".equalsIgnoreCase(safeLower(signal.freshness())));
  }

  private int scoreSignal(String signal) {
    return switch (safeLower(signal)) {
      case "positive" -> 1;
      case "negative" -> -1;
      default -> 0;
    };
  }

  private double confidenceWeight(String confidence) {
    return switch (safeLower(confidence)) {
      case "high" -> 1.2;
      case "low" -> 0.6;
      default -> 1.0;
    };
  }

  private String toneLabel(double score) {
    if (score > 0.35) {
      return "偏正向";
    }
    if (score < -0.35) {
      return "偏谨慎";
    }
    return "混合或不明确";
  }

  private String humanDimensionName(String key) {
    return switch (key) {
      case "growth" -> "成长速度";
      case "technicalDepth" -> "技术深度";
      case "teamMaturity" -> "团队成熟度";
      case "companyRisk" -> "公司稳定性";
      default -> "个人匹配度";
    };
  }

  private boolean isCompanyMatch(String offerCompany, String researchCompany) {
    String normalizedOffer = normalizeCompany(offerCompany);
    String normalizedResearch = normalizeCompany(researchCompany);

    if (normalizedOffer.equals(normalizedResearch)) {
      return true;
    }

    return normalizedOffer.contains(normalizedResearch) || normalizedResearch.contains(normalizedOffer);
  }

  private String normalizeCompany(String value) {
    return safeLower(value).replaceAll("[^a-z0-9]", "");
  }

  private String safeLower(String value) {
    return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
  }

  private boolean containsAny(String value, String... candidates) {
    for (String candidate : candidates) {
      if (value.contains(candidate)) {
        return true;
      }
    }
    return false;
  }

  private OfferSnapshot snapshotForCompany(List<OfferSnapshot> snapshots, String company) {
    return snapshots.stream()
        .filter(snapshot -> snapshot.company().equalsIgnoreCase(company))
        .findFirst()
        .orElseThrow();
  }

  private String blankToFallback(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  private String effectSummary(String company, LiveImpact impact) {
    if (isMostlyUnclear(impact)) {
      return company + " 在本次实时研究中仍然大多处于未解析状态。";
    }
    if (hasNetPositiveMomentum(impact)) {
      return impact.modelOnly()
          ? company + " 获得了偏正向的 input-only 降级信号。"
          : company + " 获得了净正向的近期公开信号。";
    }
    return impact.modelOnly()
        ? company + " 获得了偏谨慎或混合的 input-only 降级信号。"
        : company + " 获得了偏谨慎或混合的近期公开信号。";
  }

  private String liveAdjustmentSummary(
      String dimensionKey,
      String companyA,
      String companyB,
      LiveImpact impactA,
      LiveImpact impactB) {
    int deltaA = rawLiveDimensionDelta(dimensionKey, impactA);
    int deltaB = rawLiveDimensionDelta(dimensionKey, impactB);

    if (deltaA == 0 && deltaB == 0) {
      return "实时层没有在这个维度上实质性改变任何一方。";
    }

    if (deltaA == deltaB) {
      return "实时层在这个维度上对两边的影响大致相同。";
    }

    String favoredCompany = deltaA > deltaB ? companyA : companyB;
    String layerLabel = impactA.modelOnly() || impactB.modelOnly() ? "模型降级层" : "实时层";
    String signalLabel = impactA.modelOnly() || impactB.modelOnly() ? "input-only" : "近期公开信号";
    return layerLabel + " 在这个维度上给了 " + favoredCompany + " 更强的 " + signalLabel + " 校正。";
  }

  private boolean isModelOnlyReport(LiveResearchReport report) {
    return safeLower(report.provider()).contains("model-only");
  }

  private int clamp(int score) {
    return Math.max(1, Math.min(10, score));
  }

  private List<String> dedupeAndLimit(List<String> current, String... additions) {
    LinkedHashSet<String> values = new LinkedHashSet<>();
    values.addAll(current);
    if (additions != null) {
      for (String addition : additions) {
        if (addition != null && !addition.isBlank()) {
          values.add(addition);
        }
      }
    }
    return values.stream().limit(5).toList();
  }

  private List<String> dedupeAndLimit(List<String> current) {
    return dedupeAndLimit(current, (String[]) null);
  }

  private List<String> dedupeAndLimit(List<String> current, List<String> additionsA, List<String> additionsB, String extra) {
    LinkedHashSet<String> values = new LinkedHashSet<>();
    values.addAll(current);
    if (additionsA != null) {
      values.addAll(additionsA);
    }
    if (additionsB != null) {
      values.addAll(additionsB);
    }
    if (extra != null && !extra.isBlank()) {
      values.add(extra);
    }
    return values.stream().limit(6).toList();
  }

  private record LiveImpact(
      String company,
      String summary,
      String confidence,
      String freshness,
      int hiringScore,
      int businessScore,
      int technicalScore,
      List<String> opportunities,
      List<String> risks,
      List<String> mustVerify,
      boolean modelOnly) {

    private static LiveImpact empty(String company) {
      return new LiveImpact(
          company,
          "实时研究没有返回足够可用的公司级证据，因此当前无法对这个机会做出更有把握的调整。",
          "low",
          "needs live verification",
          0,
          0,
          0,
          List.of(),
          List.of(),
          List.of("由于公开信号覆盖偏薄，建议直接核验团队最近的变化。"),
          false);
    }
  }
}
