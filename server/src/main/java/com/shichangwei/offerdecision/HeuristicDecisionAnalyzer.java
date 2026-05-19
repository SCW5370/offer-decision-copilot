package com.shichangwei.offerdecision;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class HeuristicDecisionAnalyzer {

  private static final Pattern NUMBER_PATTERN = Pattern.compile("(\\d+)");

  private static final Map<String, Map<String, Double>> PRIORITY_WEIGHTS =
      Map.of(
          "technical depth", Map.of("technicalDepth", 1.3, "growth", 1.1),
          "growth speed", Map.of("growth", 1.3, "personalFit", 1.1),
          "brand", Map.of("teamMaturity", 1.05, "companyRisk", 1.05),
          "compensation", Map.of("personalFit", 1.05),
          "stability", Map.of("companyRisk", 1.35, "teamMaturity", 1.05),
          "mentorship", Map.of("teamMaturity", 1.25, "growth", 1.05));

  public DecisionAnalysis analyze(DecisionRequest request) {
    List<DimensionInsight> dimensions =
        List.of(
            compareDimension("growth", "成长速度", 1.2, request),
            compareDimension("technicalDepth", "技术深度", 1.25, request),
            compareDimension("teamMaturity", "团队成熟度", 1.05, request),
            compareDimension("companyRisk", "公司稳定性", 1.1, request),
            compareDimension("personalFit", "个人匹配度", 1.35, request));

    List<OfferSnapshot> snapshots =
        request.offers().stream()
            .map(offer -> buildSnapshot(request, offer, dimensions))
            .sorted(Comparator.comparingInt(OfferSnapshot::overallScore).reversed())
            .toList();

    OfferSnapshot winner = snapshots.getFirst();

    return new DecisionAnalysis(
        Instant.now().toString(),
        new EngineInfo(
            "heuristic",
            "heuristic",
            "Java 规则引擎",
            "当前由 Spring Boot 提供基础决策逻辑，不依赖任何外部模型。"),
        new Recommendation(
            winner.company(),
            winner.company()
                + " 是更值得优先考虑的选择，但这个优势只有在完成实时核验后才真正可信。",
            winner.company()
                + " 更占优，因为它同时叠加了你最看重的几个优先项，比如："
                + winner.strengths().getFirst(),
            "当前结果仍然属于离线研究结论。在真正做决定前，团队质量、直属经理情况以及公司近期动态，都应该再用新近证据核验一次。"),
        List.of(
            new PipelineStep(
                "问题定义",
                "把宽泛的职业选择题拆成了五个明确的决策维度。",
                "done"),
            new PipelineStep(
                "证据聚合",
                "把每个 Offer 的 JD、技术栈、公司阶段、执行风格和经理信号映射成可比较的证据桶。",
                "done"),
            new PipelineStep(
                "风险校正",
                "结合你的风险偏好和职业目标，对“上限”和“稳定性”的权衡进行了校正。",
                "done"),
            new PipelineStep(
                "实时核验清单",
                "把公司动态、招聘节奏和经理质量标记为强时效信息，不应只依赖静态知识判断。",
                "watch")),
        snapshots,
        dimensions,
        new FreshnessInfo(
            List.of(
                "岗位与技术栈对长期技术深度的匹配程度",
                "不同公司阶段带来的上限与秩序感权衡",
                "经理支持度如何影响校招生的成长速度"),
            List.of(
                "团队当前的招聘强度和组织变化",
                "公司的资金安全边际、产品势能和最近的公开信号变化",
                "发 Offer 之后，实际工作内容是否仍与 JD 一致")),
        null);
  }

  private DimensionInsight compareDimension(
      String key, String title, double weight, DecisionRequest request) {
    OfferInput offerA = request.offers().get(0);
    OfferInput offerB = request.offers().get(1);

    Map<String, Integer> scoresA = applyPriorityWeights(request, baseScores(request, offerA));
    Map<String, Integer> scoresB = applyPriorityWeights(request, baseScores(request, offerB));
    int scoreA = scoresA.get(key);
    int scoreB = scoresB.get(key);
    String winner = scoreA >= scoreB ? offerA.company() : offerB.company();

    String verdict =
        switch (key) {
          case "technicalDepth" ->
              scoreA == scoreB
                  ? "两个机会都能接触到有价值的后端工作，但现阶段技术上限看起来比较接近。"
                  : winner
                      + " 当前在技术成长曲线上更有优势，因为它的技术栈和问题空间更贴近你的目标路径。";
          case "growth" ->
              scoreA == scoreB
                  ? "两个机会的成长速度比较接近，最终更取决于你对不确定性的接受度。"
                  : winner
                      + " 更可能让你更快被拉伸，因为职责范围、带教质量和目标匹配度在同一个方向上叠加了。";
          case "teamMaturity" ->
              scoreA == scoreB
                  ? "流程成熟度相近，因此更需要通过面试去核验真实的经理质量和评审纪律。"
                  : winner
                      + " 对校招生而言显得更健康，因为这个环境更容易在其中稳定学习，而不是快速迷失。";
          case "companyRisk" ->
              scoreA == scoreB
                  ? "从纸面上看，两者都不明显更安全，因此需要用招聘、融资和组织变化等新近信号来打破平局。"
                  : winner
                      + " 会是更保守的选择，前提是你更看重可预期的资金安全边际和入职稳定性，而不是更高上限。";
          default ->
              scoreA == scoreB
                  ? "两个机会与当前目标都算匹配，最后的决策更取决于后续追问的答案。"
                  : winner
                      + " 更贴近你想成为的工程师类型，而不只是短期薪酬包更合适。";
        };

    List<String> risks =
        winner.equals(offerA.company())
            ? List.of(
                offerB.company()
                    + " 仍可能反超，如果它的真实团队质量比 JD 呈现出来的更强。",
                winner
                    + " 仍需结合近期招聘、团队健康度和真实入职支持再做一次核验。")
            : List.of(
                offerA.company()
                    + " 如果团队真的能从第一天就让新人上手交付，它的真实上限可能比结构化评分体现得更高。",
                winner
                    + " 也可能变成“舒适区选择”，如果第一年的职责边界比预期更窄。");

    List<String> followUps =
        "companyRisk".equals(key)
            ? List.of(
                "问清楚 " + offerA.company() + " 的资金安全边际、当前招聘节奏，以及最近 6 个月团队发生了什么变化。",
                "确认 " + offerB.company() + " 的团队现在是在继续扩张，还是主要在优化一条已经成熟的业务线。")
            : List.of(
                "让每位经理都给一个具体例子：优秀的校招生在前 90 天真正交付过什么。",
                "追问代码评审、带教机制和技术决策在每周的真实运转方式。");

    return new DimensionInsight(
        key,
        title,
        weight,
        winner,
        scoreA,
        scoreB,
        verdict,
        null,
        List.of(
            new EvidenceItem(
                offerA.company() + " 岗位信号：" + offerA.jdSignals(),
                "user input",
                "needs live verification"),
            new EvidenceItem(
                offerA.company() + " 技术栈覆盖：" + offerA.stack(),
                "user input",
                "stable"),
            new EvidenceItem(
                offerA.company()
                    + " 的工作环境推断自 "
                    + stageLabel(offerA.stage())
                    + " 阶段与 "
                    + executionStyleLabel(offerA.executionStyle())
                    + " 执行风格",
                "heuristic",
                "stable"),
            new EvidenceItem(
                offerB.company() + " 岗位信号：" + offerB.jdSignals(),
                "user input",
                "needs live verification")),
        risks,
        followUps);
  }

  private OfferSnapshot buildSnapshot(
      DecisionRequest request, OfferInput offer, List<DimensionInsight> dimensions) {
    Map<String, Integer> scores = applyPriorityWeights(request, baseScores(request, offer));
    int overallScore =
        clamp(
            (int)
                Math.round(
                    dimensions.stream()
                            .mapToDouble(
                                dimension ->
                                    (offer.company().equals(request.offers().get(0).company())
                                                ? dimension.scoreA()
                                                : dimension.scoreB())
                                            * dimension.weight())
                            .sum()
                        / dimensions.stream().mapToDouble(DimensionInsight::weight).sum()));

    List<String> strengths = new ArrayList<>();
    if (scores.get("technicalDepth") >= 8) {
      strengths.add("相对你的目标路径，技术杠杆很高。");
    }
    if (scores.get("growth") >= 8) {
      strengths.add("有望显著拉高第一年的成长曲线。");
    }
    if (scores.get("teamMaturity") >= 8) {
      strengths.add("流程更清晰，对校招生更有支撑。");
    }
    if (scores.get("personalFit") >= 8) {
      strengths.add("与你表达的职业方向高度一致。");
    }
    if (strengths.isEmpty()) {
      strengths.add("整体比较均衡，但上限优势还没有被明显拉开。");
    }

    List<String> watchouts = new ArrayList<>();
    if (scores.get("companyRisk") <= 5) {
      watchouts.add("资金安全边际或组织波动需要通过实时信息进一步核验。");
    }
    if (scores.get("teamMaturity") <= 6) {
      watchouts.add("流程信号偏弱，可能提高入职初期的不确定性。");
    }
    if (scores.get("technicalDepth") <= 6) {
      watchouts.add("这套技术栈可能不足以快速累积到 AI 平台方向。");
    }
    if (watchouts.isEmpty()) {
      watchouts.add("当前离线输入里没有明显红旗，但实时信号依然重要。");
    }

    return new OfferSnapshot(
        offer.id(), offer.company(), offer.role(), overallScore, strengths, watchouts);
  }

  private Map<String, Integer> baseScores(DecisionRequest request, OfferInput offer) {
    int technicalDepth =
        clamp(
            (int)
                Math.round(
                    stackDepthScore(offer.stack()) * 0.55
                        + domainMomentumScore(offer.domain()) * 0.25
                        + upsideScore(offer.stage()) * 0.2));
    int growth =
        clamp(
            (int)
                Math.round(
                    upsideScore(offer.stage()) * 0.4
                        + managerScore(offer.managerSupport()) * 0.35
                        + matchTargetScore(request.userProfile().target(), offer) * 0.25));
    int teamMaturity =
        clamp(
            (int)
                Math.round(
                    maturityScore(offer.stage()) * 0.45
                        + executionScore(offer.executionStyle()) * 0.35
                        + managerScore(offer.managerSupport()) * 0.2));
    int companyRisk =
        clamp(
            (int)
                Math.round(
                    stabilityScore(offer.stage()) * 0.6
                        + executionScore(offer.executionStyle()) * 0.15
                        + workModeScore(offer.workMode()) * 0.05
                        + compensationScore(offer.compensation()) * 0.2
                        + riskPreferenceAdjustment(request.userProfile().riskAppetite(), offer)));
    int personalFit =
        clamp(
            (int)
                Math.round(
                    compensationScore(offer.compensation()) * 0.15
                        + matchTargetScore(request.userProfile().target(), offer) * 0.45
                        + managerScore(offer.managerSupport()) * 0.2
                        + workModeScore(offer.workMode()) * 0.2));

    Map<String, Integer> result = new HashMap<>();
    result.put("technicalDepth", technicalDepth);
    result.put("growth", growth);
    result.put("teamMaturity", teamMaturity);
    result.put("companyRisk", companyRisk);
    result.put("personalFit", personalFit);
    return result;
  }

  private Map<String, Integer> applyPriorityWeights(
      DecisionRequest request, Map<String, Integer> scores) {
    Map<String, Integer> weighted = new HashMap<>(scores);

    for (String priority : request.userProfile().priorities()) {
      Map<String, Double> weights = PRIORITY_WEIGHTS.get(priority.toLowerCase(Locale.ROOT));
      if (weights == null) {
        continue;
      }

      for (Map.Entry<String, Double> entry : weights.entrySet()) {
        weighted.computeIfPresent(
            entry.getKey(),
            (ignored, value) -> clamp((int) Math.round(value * entry.getValue())));
      }
    }

    return weighted;
  }

  private int stackDepthScore(String stack) {
    String lower = safeLower(stack);
    int score = 4;

    if (containsAny(lower, "agent", "llm", "eval", "retrieval", "workflow", "platform")) {
      score += 3;
    }
    if (containsAny(lower, "kafka", "redis", "observability", "distributed", "governance", "performance")) {
      score += 2;
    }
    if (containsAny(lower, "typescript", "python", "java", "postgres", "mysql")) {
      score += 1;
    }

    return clamp(score);
  }

  private int domainMomentumScore(String domain) {
    String lower = safeLower(domain);
    int score = 5;

    if (containsAny(lower, "agent", "ai", "inference", "model", "automation")) {
      score += 3;
    }
    if (containsAny(lower, "platform", "infra", "developer", "workflow")) {
      score += 1;
    }
    if (containsAny(lower, "mature", "legacy")) {
      score -= 1;
    }

    return clamp(score);
  }

  private int compensationScore(String compensation) {
    Matcher matcher = NUMBER_PATTERN.matcher(compensation == null ? "" : compensation);
    if (!matcher.find()) {
      return 6;
    }

    int monthly = Integer.parseInt(matcher.group(1));
    if (monthly >= 30) {
      return 9;
    }
    if (monthly >= 26) {
      return 8;
    }
    if (monthly >= 22) {
      return 7;
    }
    return 6;
  }

  private double riskPreferenceAdjustment(String riskAppetite, OfferInput offer) {
    String normalized = safeLower(riskAppetite);
    if ("high".equals(normalized) && "startup".equalsIgnoreCase(offer.stage())) {
      return 1.5;
    }
    if ("low".equals(normalized) && "startup".equalsIgnoreCase(offer.stage())) {
      return -2;
    }
    if ("low".equals(normalized) && "big-tech".equalsIgnoreCase(offer.stage())) {
      return 1.5;
    }
    return 0;
  }

  private int matchTargetScore(String target, OfferInput offer) {
    String lowerTarget = safeLower(target);
    int score = 5;

    if (containsAny(lowerTarget, "backend", "platform", "infra")
        && containsAny(safeLower(offer.role()), "platform", "api", "backend", "infra")) {
      score += 2;
    }
    if (containsAny(lowerTarget, "ai", "agent")
        && containsAny(
            safeLower(offer.domain() + " " + offer.stack()),
            "ai",
            "agent",
            "llm",
            "retrieval",
            "eval")) {
      score += 2;
    }
    if (containsAny(lowerTarget, "mentor", "guided", "learn")
        && "high".equalsIgnoreCase(offer.managerSupport())) {
      score += 1;
    }

    return clamp(score);
  }

  private int upsideScore(String stage) {
    return switch (safeLower(stage)) {
      case "startup" -> 9;
      case "growth" -> 7;
      default -> 5;
    };
  }

  private int stabilityScore(String stage) {
    return switch (safeLower(stage)) {
      case "startup" -> 3;
      case "growth" -> 6;
      default -> 9;
    };
  }

  private int maturityScore(String stage) {
    return switch (safeLower(stage)) {
      case "startup" -> 4;
      case "growth" -> 6;
      default -> 9;
    };
  }

  private int managerScore(String support) {
    return switch (safeLower(support)) {
      case "high" -> 9;
      case "medium" -> 6;
      default -> 3;
    };
  }

  private int executionScore(String style) {
    return switch (safeLower(style)) {
      case "structured" -> 9;
      case "balanced" -> 7;
      default -> 3;
    };
  }

  private int workModeScore(String mode) {
    return switch (safeLower(mode)) {
      case "hybrid" -> 8;
      case "remote" -> 6;
      default -> 7;
    };
  }

  private String stageLabel(String stage) {
    return switch (safeLower(stage)) {
      case "startup" -> "创业期";
      case "growth" -> "成长期";
      case "big-tech" -> "大厂";
      default -> stage;
    };
  }

  private String executionStyleLabel(String style) {
    return switch (safeLower(style)) {
      case "structured" -> "流程化";
      case "balanced" -> "平衡";
      case "chaotic" -> "偏混乱";
      default -> style;
    };
  }

  private int clamp(int score) {
    return Math.max(1, Math.min(10, score));
  }

  private boolean containsAny(String text, String... needles) {
    for (String needle : needles) {
      if (text.contains(needle)) {
        return true;
      }
    }
    return false;
  }

  private String safeLower(String value) {
    return value == null ? "" : value.toLowerCase(Locale.ROOT);
  }
}
