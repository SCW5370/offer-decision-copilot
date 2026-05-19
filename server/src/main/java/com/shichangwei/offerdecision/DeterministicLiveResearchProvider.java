package com.shichangwei.offerdecision;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class DeterministicLiveResearchProvider {

  private final LiveResearchProperties liveResearchProperties;
  private final ConfigurableLiveSearchProvider liveSearchProvider;
  private final MockSearchFixtureService mockSearchFixtureService;
  private final Clock clock;

  @Autowired
  public DeterministicLiveResearchProvider(
      LiveResearchProperties liveResearchProperties,
      ConfigurableLiveSearchProvider liveSearchProvider,
      MockSearchFixtureService mockSearchFixtureService) {
    this(liveResearchProperties, liveSearchProvider, mockSearchFixtureService, Clock.systemUTC());
  }

  DeterministicLiveResearchProvider(
      LiveResearchProperties liveResearchProperties,
      ConfigurableLiveSearchProvider liveSearchProvider,
      MockSearchFixtureService mockSearchFixtureService,
      Clock clock) {
    this.liveResearchProperties = liveResearchProperties;
    this.liveSearchProvider = liveSearchProvider;
    this.mockSearchFixtureService = mockSearchFixtureService;
    this.clock = clock;
  }

  public boolean isEnabled() {
    return liveResearchProperties.enabled()
        && "deterministic".equalsIgnoreCase(nullSafe(liveResearchProperties.provider()));
  }

  public boolean isDemoAvailable() {
    return true;
  }

  public Optional<LiveResearchReport> research(DecisionRequest request) {
    return researchInternal(request, false);
  }

  public Optional<LiveResearchReport> researchDemo(DecisionRequest request) {
    return researchInternal(request, true);
  }

  private Optional<LiveResearchReport> researchInternal(DecisionRequest request, boolean demoMode) {
    if (!demoMode && !isEnabled()) {
      return Optional.empty();
    }

    long startedAt = System.nanoTime();
    List<RetrievedEvidence> evidence =
        demoMode ? deterministicEvidence(request) : liveSearchProvider.search(request);
    if (evidence.isEmpty()) {
      return Optional.empty();
    }

    List<LiveCompanyResearch> companySignals = new ArrayList<>();
    List<ResearchSource> sources = new ArrayList<>();

    for (OfferInput offer : request.offers()) {
      List<RetrievedEvidence> companyEvidence =
          evidence.stream()
              .filter(item -> item.company().equalsIgnoreCase(offer.company()))
              .sorted(Comparator.comparingInt(RetrievedEvidence::qualityScore).reversed())
              .toList();
      companySignals.add(buildCompanyResearch(offer, companyEvidence));
      companyEvidence.stream()
          .limit(3)
          .forEach(
              item ->
                  sources.add(
                      new ResearchSource(
                          offer.company(), item.source(), item.url(), domainFromUrl(item.url()))));
    }

    String marketTakeaway = buildMarketTakeaway(companySignals);
    return Optional.of(
        new LiveResearchReport(
            "deterministic retrieval",
            "none",
            marketTakeaway,
            companySignals,
            dedupeSources(sources),
            elapsedMillis(startedAt),
            List.of(
                new ResearchStageTiming(
                    demoMode
                        ? "retrieval.deterministic-demo-compose"
                        : "retrieval.deterministic-compose",
                    elapsedMillis(startedAt),
                    "done: " + evidence.size() + " item(s)"))));
  }

  private List<RetrievedEvidence> deterministicEvidence(DecisionRequest request) {
    List<RetrievedEvidence> evidence = new ArrayList<>();
    for (OfferInput offer : request.offers()) {
      mockSearchFixtureService.search(offer.company(), 3).stream()
          .map(
              item ->
                  liveSearchProvider.toRetrievedEvidence(
                      offer.company(),
                      item.title(),
                      item.url(),
                      item.snippet(),
                      item.source(),
                      item.freshness()))
          .forEach(evidence::add);
    }
    return liveSearchProvider.qualityGate(evidence);
  }

  private LiveCompanyResearch buildCompanyResearch(
      OfferInput offer, List<RetrievedEvidence> companyEvidence) {
    if (companyEvidence.isEmpty()) {
      return new LiveCompanyResearch(
          offer.company(),
          "在本地可复现模式下，检索层没有为这家公司返回足够可用的公开证据。",
          "low",
          "unclear",
          "unclear",
          "unclear",
          List.of(
              new ResearchSignal(
                  "没有足够可用的检索证据",
                  "本地可复现实时引擎没有拿到这家公司足够强的公开片段。",
                  "在接入更强的实时信息源之前，这意味着建议必须保持保守。",
                  "暂无可靠公开来源",
                  "",
                  "unclear")),
          List.of("在把它当作最终市场判断前，应先接入更强的搜索来源。"),
          List.of("当前可复现模式下的公开信号仍然偏薄。"),
          List.of("直接确认团队当前优先级和招聘强度。"));
    }

    int positiveSignals = 0;
    int cautionSignals = 0;
    int technicalSignals = 0;
    List<ResearchSignal> keySignals = new ArrayList<>();

    for (RetrievedEvidence item : companyEvidence.stream().limit(3).toList()) {
      String combined = (item.title() + " " + item.snippet()).toLowerCase(Locale.ROOT);
      if (containsAny(combined, "hiring", "expand", "launch", "rollout", "update")) {
        positiveSignals++;
      }
      if (containsAny(combined, "incident", "selective", "stable", "incremental")) {
        cautionSignals++;
      }
      if (containsAny(combined, "engineering", "platform", "evaluation", "reliability", "observability")) {
        technicalSignals++;
      }

      keySignals.add(
          new ResearchSignal(
              item.title(),
              item.snippet(),
              "这条信号来自检索层，并通过了质量门控，当前分数为 "
                  + item.qualityScore()
                  + "。",
              item.source(),
              item.url(),
              freshnessLabel(item.freshness(), item.qualityReason())));
    }

    String confidence =
        companyEvidence.stream().mapToInt(RetrievedEvidence::qualityScore).max().orElse(0) >= 80
            ? "medium"
            : "low";
    String hiringSignal = positiveSignals >= 2 ? "positive" : cautionSignals >= 2 ? "mixed" : "unclear";
    String businessSignal = positiveSignals > cautionSignals ? "positive" : cautionSignals > 0 ? "mixed" : "unclear";
    String technicalSignal = technicalSignals >= 2 ? "positive" : technicalSignals == 1 ? "mixed" : "unclear";

    return new LiveCompanyResearch(
        offer.company(),
        buildSummary(offer.company(), companyEvidence, positiveSignals, cautionSignals, technicalSignals),
        confidence,
        hiringSignal,
        businessSignal,
        technicalSignal,
        keySignals,
        List.of(opportunityLine(offer.company(), technicalSignals, positiveSignals)),
        List.of(riskLine(offer.company(), confidence, cautionSignals)),
        List.of("确认 " + offer.company() + " 哪些公开信号仍然真实反映当前团队的职责范围。"));
  }

  private String buildMarketTakeaway(List<LiveCompanyResearch> companySignals) {
    if (companySignals.isEmpty()) {
      return "当前没有可用的可复现检索证据。";
    }
    LiveCompanyResearch strongest =
        companySignals.stream()
            .max(Comparator.comparingInt(signal -> signal.keySignals().size()))
            .orElse(companySignals.getFirst());
    return strongest.company()
        + " 在本地可复现模式下拥有更密集的检索足迹，但在把它当作最终结论前，仍然需要真实搜索来源进一步验证。";
  }

  private String buildSummary(
      String company,
      List<RetrievedEvidence> evidence,
      int positiveSignals,
      int cautionSignals,
      int technicalSignals) {
    RetrievedEvidence top = evidence.getFirst();
    return company
        + " 当前有 "
        + evidence.size()
        + " 条带检索证据支撑的公开信号。"
        + top.snippet()
        + " 技术信号数="
        + technicalSignals
        + "，扩张信号数="
        + positiveSignals
        + "，谨慎信号数="
        + cautionSignals
        + "。";
  }

  private String opportunityLine(String company, int technicalSignals, int positiveSignals) {
    if (technicalSignals >= 2) {
      return company + " 出现了重复的技术向公开信号，有利于累积后端或平台深度。";
    }
    if (positiveSignals >= 2) {
      return company + " 最近出现了偏扩张的公开信号，可能带来更大的职责空间。";
    }
    return company + " 当前公开动态有限但仍可利用，适合在后续面试中进一步追问。";
  }

  private String riskLine(String company, String confidence, int cautionSignals) {
    if ("low".equals(confidence)) {
      return company + " 在可复现模式下的证据覆盖仍然偏薄。";
    }
    if (cautionSignals > 0) {
      return company + " 同时也存在偏保守或混合的公开信号，不宜过度乐观解读。";
    }
    return company + " 仍然需要团队层面的直接核验，因为检索证据不等于真实情况。";
  }

  private List<ResearchSource> dedupeSources(List<ResearchSource> sources) {
    Map<String, ResearchSource> deduped = new LinkedHashMap<>();
    for (ResearchSource source : sources) {
      deduped.putIfAbsent(source.url(), source);
    }
    return new ArrayList<>(deduped.values());
  }

  private boolean containsAny(String text, String... terms) {
    for (String term : terms) {
      if (text.contains(term)) {
        return true;
      }
    }
    return false;
  }

  private String freshnessLabel(String freshness, String qualityReason) {
    String normalizedFreshness = nullSafe(freshness).toLowerCase(Locale.ROOT);
    String normalizedReason = nullSafe(qualityReason).toLowerCase(Locale.ROOT);
    if (normalizedReason.contains("stale-date")) {
      return "unclear";
    }
    if (normalizedFreshness.contains("2026")
        || normalizedFreshness.contains("2025")
        || normalizedReason.contains("fresh-date")
        || normalizedReason.contains("freshness-hint")) {
      return "fresh";
    }
    return "unclear";
  }

  private String domainFromUrl(String url) {
    try {
      String host = java.net.URI.create(url).getHost();
      return host == null ? "source" : host.replaceFirst("^www\\.", "");
    } catch (Exception ignored) {
      return "source";
    }
  }

  private long elapsedMillis(long startedAt) {
    return (System.nanoTime() - startedAt) / 1_000_000;
  }

  private String nullSafe(String value) {
    return value == null ? "" : value.trim();
  }
}
