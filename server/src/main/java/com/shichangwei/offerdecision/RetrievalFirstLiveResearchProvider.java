package com.shichangwei.offerdecision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class RetrievalFirstLiveResearchProvider implements LiveResearchProvider {

  private static final int MAX_STRUCTURING_EVIDENCE_PER_COMPANY = 2;
  private static final int MAX_STRUCTURING_TOTAL_EVIDENCE = 4;
  private static final int MAX_TITLE_LENGTH = 90;
  private static final int MAX_SNIPPET_LENGTH = 180;
  private static final int MAX_OFFER_FIELD_LENGTH = 48;

  private static final Logger log =
      LoggerFactory.getLogger(RetrievalFirstLiveResearchProvider.class);

  private final LiveResearchProperties liveResearchProperties;
  private final LiveSearchProvider liveSearchProvider;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;

  public RetrievalFirstLiveResearchProvider(
      LiveResearchProperties liveResearchProperties,
      LiveSearchProvider liveSearchProvider,
      ObjectMapper objectMapper) {
    this.liveResearchProperties = liveResearchProperties;
    this.liveSearchProvider = liveSearchProvider;
    this.objectMapper = objectMapper;
    this.httpClient =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds()))
            .build();
  }

  @Override
  public boolean isEnabled() {
    return liveResearchProperties.enabled()
        && liveSearchProvider.isEnabled()
        && hasExternalStructuringModel();
  }

  @Override
  public Optional<LiveResearchReport> research(DecisionRequest request) {
    if (!isEnabled()) {
      return Optional.empty();
    }

    long startedAt = System.nanoTime();
    List<ResearchStageTiming> stageTimings = new ArrayList<>();

    try {
      List<RetrievedEvidence> evidence =
          runStage("retrieval.search", stageTimings, () -> liveSearchProvider.search(request));
      if (evidence.isEmpty()) {
        return Optional.empty();
      }

      List<RetrievedEvidence> compactedEvidence =
          runStage(
              "retrieval.compact-evidence",
              stageTimings,
              () -> compactEvidenceForStructuring(request, evidence));

      try {
        JsonNode root =
            runStage(
                "retrieval.structure",
                stageTimings,
                () -> structureRetrievedEvidence(request, compactedEvidence, PromptVariant.STANDARD));
        if (root != null && root.isObject()) {
          return Optional.of(toLiveResearchReport(root, request, evidence, startedAt, stageTimings));
        }
      } catch (Exception error) {
        log.warn("Retrieval-first structuring primary attempt failed: {}", error.getMessage());
      }

      List<RetrievedEvidence> retryEvidence = compactEvidenceForRetry(compactedEvidence);
      try {
        JsonNode retryRoot =
            runStage(
                "retrieval.structure-retry",
                stageTimings,
                () -> structureRetrievedEvidence(request, retryEvidence, PromptVariant.RETRY));
        if (retryRoot != null && retryRoot.isObject()) {
          return Optional.of(
              toLiveResearchReport(retryRoot, request, evidence, startedAt, stageTimings));
        }
      } catch (Exception error) {
        log.warn(
            "Retrieval-first structuring retry failed: {}. Falling back to local evidence composer.",
            error.getMessage());
      }

      return Optional.of(localFallbackReport(request, evidence, startedAt, stageTimings));
    } catch (Exception error) {
      log.warn("Retrieval-first live research failed: {}", error.getMessage());
      return Optional.empty();
    }
  }

  public String providerLabel() {
    return "retrieval-first + " + structuringProviderLabel();
  }

  private JsonNode structureRetrievedEvidence(
      DecisionRequest request, List<RetrievedEvidence> evidence, PromptVariant promptVariant)
      throws IOException, InterruptedException {
    ObjectNode requestBody = objectMapper.createObjectNode();
    requestBody.put("model", liveResearchProperties.structuringModel());
    requestBody.put("temperature", 0.2);
    requestBody.put("max_tokens", promptVariant == PromptVariant.RETRY ? 700 : 900);
    requestBody.set("response_format", jsonObjectResponseFormat());
    requestBody.set(
        "messages",
        objectMapper
            .createArrayNode()
            .add(message("system", buildSystemPrompt()))
            .add(message("user", buildUserPrompt(request, evidence, promptVariant))));

    HttpRequest httpRequest =
        HttpRequest.newBuilder(URI.create(liveResearchProperties.structuringBaseUrl() + "/chat/completions"))
            .timeout(Duration.ofSeconds(structuringTimeoutSeconds(promptVariant)))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + liveResearchProperties.structuringApiKey())
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
            .build();

    HttpResponse<String> response =
        httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
    ensureOk(response, "structure retrieval evidence");

    JsonNode completion = objectMapper.readTree(response.body());
    JsonNode content = completion.path("choices").path(0).path("message").path("content");
    return parseStructuredContent(content);
  }

  private LiveResearchReport localFallbackReport(
      DecisionRequest request,
      List<RetrievedEvidence> evidence,
      long startedAt,
      List<ResearchStageTiming> stageTimings) {
    long fallbackStartedAt = System.nanoTime();
    List<LiveCompanyResearch> companySignals = new ArrayList<>();
    Map<String, ResearchSource> sources = new LinkedHashMap<>();

    for (OfferInput offer : request.offers()) {
      List<RetrievedEvidence> companyEvidence =
          evidence.stream()
              .filter(item -> item.company().equalsIgnoreCase(offer.company()))
              .sorted(Comparator.comparingInt(RetrievedEvidence::qualityScore).reversed())
              .limit(3)
              .toList();

      companySignals.add(buildFallbackCompanyResearch(offer, companyEvidence));
      for (RetrievedEvidence item : companyEvidence) {
        sources.putIfAbsent(
            item.url(),
            new ResearchSource(
                offer.company(), item.source(), item.url(), domainFromUrl(item.url())));
      }
    }

    stageTimings.add(
        new ResearchStageTiming(
            "retrieval.local-compose",
            elapsedMillis(fallbackStartedAt),
            "done"));

    return new LiveResearchReport(
        "retrieval-first local fallback",
        "none",
        buildFallbackMarketTakeaway(companySignals),
        companySignals,
        new ArrayList<>(sources.values()),
        elapsedMillis(startedAt),
        List.copyOf(stageTimings));
  }

  private LiveCompanyResearch buildFallbackCompanyResearch(
      OfferInput offer, List<RetrievedEvidence> evidence) {
    if (evidence.isEmpty()) {
      return new LiveCompanyResearch(
          offer.company(),
          "真实检索层没有为这家公司返回足够可用的近期公开证据，因此当前只能保留低置信度判断。",
          "low",
          "unclear",
          "unclear",
          "unclear",
          List.of(
              new ResearchSignal(
                  "公开证据不足",
                  "检索层当前没有拿到足够强的近期公开片段。",
                  "这意味着后续判断应更多依赖面试中的直接核验，而不是市场叙事。",
                  "暂无可靠公开来源",
                  "",
                  "unclear")),
          List.of("先把注意力放在团队职责、带教质量和经理判断上。"),
          List.of("公开信号偏薄，不宜把它当成最终市场判断。"),
          List.of("直接核验团队当前优先级和招聘强度。"));
    }

    int positiveSignals = 0;
    int technicalSignals = 0;
    List<ResearchSignal> keySignals = new ArrayList<>();
    for (RetrievedEvidence item : evidence) {
      String combined = (item.title() + " " + item.snippet()).toLowerCase(Locale.ROOT);
      if (containsAny(combined, "hiring", "launch", "expand", "growth", "rollout")) {
        positiveSignals++;
      }
      if (containsAny(combined, "engineering", "platform", "infra", "evaluation", "reliability")) {
        technicalSignals++;
      }
      keySignals.add(
          new ResearchSignal(
              item.title(),
              item.snippet(),
              "这条信号来自真实检索层，并经过了 URL、摘要和时效性门控。",
              item.source(),
              item.url(),
              normalizeFreshness(item.freshness())));
    }

    return new LiveCompanyResearch(
        offer.company(),
        offer.company()
            + " 当前有 "
            + evidence.size()
            + " 条通过质量门控的公开片段。"
            + evidence.getFirst().snippet(),
        evidence.getFirst().qualityScore() >= 80 ? "medium" : "low",
        positiveSignals >= 2 ? "positive" : positiveSignals == 1 ? "mixed" : "unclear",
        positiveSignals >= 1 ? "mixed" : "unclear",
        technicalSignals >= 2 ? "positive" : technicalSignals == 1 ? "mixed" : "unclear",
        keySignals,
        List.of(
            technicalSignals >= 2
                ? offer.company() + " 最近重复出现了平台或工程向信号，说明这条技术线仍在被持续投入。"
                : offer.company() + " 仍有一定机会通过追问公开信号背后的团队背景，补齐判断。"),
        List.of(
            offer.company() + " 的公开证据仍然有限，不能替代对经理质量和真实职责范围的直接核验。"),
        List.of("确认 " + offer.company() + " 这些公开信号是否仍然对应当前团队。"));
  }

  private String buildFallbackMarketTakeaway(List<LiveCompanyResearch> companySignals) {
    if (companySignals.isEmpty()) {
      return "真实检索层当前没有足够证据，因此只能保留低置信度判断。";
    }

    LiveCompanyResearch strongest =
        companySignals.stream()
            .max(Comparator.comparingInt(signal -> signal.keySignals().size()))
            .orElse(companySignals.getFirst());
    return strongest.company()
        + " 当前拥有更密集的公开检索足迹。即使结构化模型没有稳定返回，我们仍然优先保留基于真实证据的保守总结。";
  }

  private LiveResearchReport toLiveResearchReport(
      JsonNode root,
      DecisionRequest request,
      List<RetrievedEvidence> evidence,
      long startedAt,
      List<ResearchStageTiming> stageTimings) {
    List<LiveCompanyResearch> companies = new ArrayList<>();
    for (OfferInput offer : request.offers()) {
      JsonNode node = findCompanyNode(root.path("companies"), offer);
      if (node == null || !node.isObject()) {
        List<RetrievedEvidence> companyEvidence =
            evidence.stream()
                .filter(item -> item.company().equalsIgnoreCase(offer.company()))
                .sorted(Comparator.comparingInt(RetrievedEvidence::qualityScore).reversed())
                .limit(2)
                .toList();
        companies.add(buildFallbackCompanyResearch(offer, companyEvidence));
        continue;
      }
      companies.add(parseCompany(node, offer));
    }

    List<ResearchSource> sources = parseSources(root.path("sources"), evidence);

    return new LiveResearchReport(
        root.path("_provider").asText(providerLabel()),
        root.path("_model").asText(liveResearchProperties.structuringModel()),
        normalizeText(
            root.path("marketTakeaway").asText(""),
            "当前基于真实检索证据完成了第一轮研究总结，但仍需结合面试与团队核验做最终判断。"),
        companies,
        sources,
        elapsedMillis(startedAt),
        List.copyOf(stageTimings));
  }

  private LiveCompanyResearch parseCompany(JsonNode node, OfferInput offer) {
    return new LiveCompanyResearch(
        offer.company(),
        normalizeText(
            node.path("summary").asText(""),
            "当前有部分真实检索证据，但仍不足以形成高置信度结论。"),
        normalizeConfidence(node.path("confidence").asText("low")),
        normalizeSignal(node.path("hiringSignal").asText("unclear")),
        normalizeSignal(node.path("businessSignal").asText("unclear")),
        normalizeSignal(node.path("technicalSignal").asText("unclear")),
        parseSignals(node.path("keySignals")),
        parseTextList(node.path("opportunities"), "当前还没有足够强的机会型证据。"),
        parseTextList(node.path("risks"), "当前公开证据仍然偏薄，建议保持保守。"),
        parseTextList(node.path("mustVerify"), "请核验这家公司的公开信号是否仍然对应当前团队。"));
  }

  private List<ResearchSignal> parseSignals(JsonNode node) {
    List<ResearchSignal> signals = new ArrayList<>();
    if (!node.isArray()) {
      return List.of(
          new ResearchSignal(
              "结构化信号缺失",
              "结构化模型没有返回完整的 key signals。",
              "因此当前结论仍应保持保守。",
              "暂无可靠公开来源",
              "",
              "unclear"));
    }

    for (JsonNode item : node) {
      signals.add(
          new ResearchSignal(
              normalizeText(item.path("title").asText(""), "公开信号"),
              normalizeText(item.path("detail").asText(""), "没有足够完整的细节。"),
              normalizeText(item.path("whyItMatters").asText(""), "这条信号仍需结合更多证据理解。"),
              normalizeText(item.path("sourceLabel").asText(""), "公开来源"),
              normalizeText(item.path("sourceUrl").asText(""), ""),
              normalizeFreshness(item.path("freshness").asText("unclear"))));
    }
    return signals.isEmpty()
        ? List.of(
            new ResearchSignal(
                "结构化信号缺失",
                "结构化模型没有返回完整的 key signals。",
                "因此当前结论仍应保持保守。",
                "暂无可靠公开来源",
                "",
                "unclear"))
        : signals;
  }

  private List<ResearchSource> parseSources(JsonNode node, List<RetrievedEvidence> evidence) {
    Map<String, ResearchSource> sources = new LinkedHashMap<>();
    if (node.isArray()) {
      for (JsonNode item : node) {
        String url = normalizeText(item.path("url").asText(""), "");
        if (url.isBlank()) {
          continue;
        }
        sources.putIfAbsent(
            url,
            new ResearchSource(
                normalizeText(item.path("company").asText(""), "整体市场信号"),
                normalizeText(item.path("label").asText(""), domainFromUrl(url)),
                url,
                normalizeText(item.path("domain").asText(""), domainFromUrl(url))));
      }
    }

    if (sources.isEmpty()) {
      for (RetrievedEvidence item : evidence.stream().limit(4).toList()) {
        sources.putIfAbsent(
            item.url(),
            new ResearchSource(item.company(), item.source(), item.url(), domainFromUrl(item.url())));
      }
    }
    return new ArrayList<>(sources.values());
  }

  private JsonNode findCompanyNode(JsonNode companiesNode, OfferInput offer) {
    if (!companiesNode.isArray()) {
      return null;
    }

    for (JsonNode node : companiesNode) {
      if (offer.id().equalsIgnoreCase(node.path("offerId").asText(""))
          || offer.company().equalsIgnoreCase(node.path("company").asText(""))) {
        return node;
      }
    }
    return null;
  }

  private List<String> parseTextList(JsonNode node, String fallback) {
    if (!node.isArray()) {
      return List.of(fallback);
    }
    List<String> values = new ArrayList<>();
    for (JsonNode item : node) {
      String value = normalizeText(item.asText(""), "");
      if (!value.isBlank()) {
        values.add(value);
      }
    }
    return values.isEmpty() ? List.of(fallback) : values;
  }

  private JsonNode parseStructuredContent(JsonNode contentNode) throws IOException {
    if (contentNode == null || contentNode.isMissingNode() || contentNode.isNull()) {
      return null;
    }

    if (contentNode.isTextual()) {
      return parseStructuredText(contentNode.asText());
    }

    if (contentNode.isArray()) {
      StringBuilder builder = new StringBuilder();
      for (JsonNode item : contentNode) {
        if (item.hasNonNull("text")) {
          builder.append(item.path("text").asText());
        } else if (item.hasNonNull("content")) {
          builder.append(item.path("content").asText());
        } else if (item.isTextual()) {
          builder.append(item.asText());
        }
      }
      if (builder.isEmpty()) {
        return null;
      }
      return parseStructuredText(builder.toString());
    }

    return contentNode;
  }

  JsonNode parseStructuredText(String rawText) throws IOException {
    if (rawText == null || rawText.isBlank()) {
      return null;
    }

    String trimmed = rawText.trim();
    List<String> candidates = new ArrayList<>();
    candidates.add(trimmed);

    String withoutFences =
        trimmed
            .replace("```json", "")
            .replace("```JSON", "")
            .replace("```", "")
            .trim();
    if (!withoutFences.equals(trimmed)) {
      candidates.add(withoutFences);
    }

    String objectSlice = extractJsonObjectSlice(withoutFences);
    if (!objectSlice.isBlank()) {
      candidates.add(objectSlice);
    }

    for (String candidate : candidates) {
      try {
        return objectMapper.readTree(candidate);
      } catch (IOException ignored) {
        // Try the next JSON candidate.
      }
    }

    return null;
  }

  private String extractJsonObjectSlice(String text) {
    int objectStart = text.indexOf('{');
    if (objectStart < 0) {
      return "";
    }

    int depth = 0;
    boolean inString = false;
    boolean escaped = false;
    for (int index = objectStart; index < text.length(); index++) {
      char current = text.charAt(index);
      if (escaped) {
        escaped = false;
        continue;
      }
      if (current == '\\') {
        escaped = true;
        continue;
      }
      if (current == '"') {
        inString = !inString;
        continue;
      }
      if (inString) {
        continue;
      }
      if (current == '{') {
        depth++;
      } else if (current == '}') {
        depth--;
        if (depth == 0) {
          return text.substring(objectStart, index + 1);
        }
      }
    }
    return "";
  }

  private ObjectNode jsonObjectResponseFormat() {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("type", "json_object");
    return node;
  }

  private ObjectNode message(String role, String content) {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("role", role);
    node.put("content", content);
    return node;
  }

  private String buildSystemPrompt() {
    return """
        You are a retrieval-first research summarizer for a job-offer decision system.
        The retrieval layer already collected candidate public evidence with company, title, URL,
        source label, freshness, quality score, and snippet.

        Use only the provided evidence and user offer fields. Do not invent extra web facts.
        Keep confidence conservative when evidence is thin. All user-facing natural-language fields
        must be written in Simplified Chinese.

        Return one compact JSON object with this exact shape:
        {
          "_provider": "retrieval-first + openai-compatible",
          "_model": "string",
          "marketTakeaway": "string",
          "companies": [
            {
              "offerId": "string",
              "company": "string",
              "summary": "string",
              "confidence": "low|medium",
              "hiringSignal": "positive|mixed|negative|unclear",
              "businessSignal": "positive|mixed|negative|unclear",
              "technicalSignal": "positive|mixed|negative|unclear",
              "keySignals": [
                {
                  "title": "string",
                  "detail": "string",
                  "whyItMatters": "string",
                  "sourceLabel": "string",
                  "sourceUrl": "https://example.com",
                  "freshness": "fresh|unclear"
                }
              ],
              "opportunities": ["string"],
              "risks": ["string"],
              "mustVerify": ["string"]
            }
          ],
          "sources": [
            {
              "company": "string",
              "label": "string",
              "url": "https://example.com",
              "domain": "example.com"
            }
          ]
        }
        The companies array must contain exactly two entries.
        Each company should have 1 to 2 keySignals only.
        Always preserve the exact offerId and company from the user.
        """;
  }

  private String buildUserPrompt(
      DecisionRequest request, List<RetrievedEvidence> evidence, PromptVariant promptVariant) {
    StringBuilder builder = new StringBuilder();
    builder
        .append("候选人目标：")
        .append(shorten(request.userProfile().target(), MAX_OFFER_FIELD_LENGTH))
        .append("\n风险偏好：")
        .append(shorten(request.userProfile().riskAppetite(), 16))
        .append("\n优先项：")
        .append(shorten(String.join(", ", request.userProfile().priorities()), 120))
        .append("\n\nOffer 映射：\n");

    for (OfferInput offer : request.offers()) {
      builder
          .append("- offerId=")
          .append(offer.id())
          .append(", company=")
          .append(offer.company())
          .append(", role=")
          .append(shorten(offer.role(), MAX_OFFER_FIELD_LENGTH))
          .append(", stage=")
          .append(shorten(offer.stage(), 16));
      if (promptVariant == PromptVariant.STANDARD) {
        builder
            .append(", domain=")
            .append(shorten(offer.domain(), MAX_OFFER_FIELD_LENGTH))
            .append(", stack=")
            .append(shorten(offer.stack(), MAX_OFFER_FIELD_LENGTH));
      }
      builder.append("\n");
    }

    builder.append("\n检索证据：\n");
    for (RetrievedEvidence item : evidence) {
      builder
          .append("- company=")
          .append(item.company())
          .append("\n  title=")
          .append(item.title())
          .append("\n  url=")
          .append(item.url())
          .append("\n  source=")
          .append(item.source())
          .append("\n  freshness=")
          .append(item.freshness())
          .append("\n  qualityScore=")
          .append(item.qualityScore());
      if (promptVariant == PromptVariant.STANDARD) {
        builder
            .append("\n  snippet=")
            .append(shorten(item.snippet(), MAX_SNIPPET_LENGTH));
      }
      if (promptVariant == PromptVariant.RETRY) {
        builder
            .append("\n  summary=")
            .append(shorten(item.title() + " | " + item.snippet(), 110));
      }
      builder.append("\n");
    }
    return builder.toString();
  }

  List<RetrievedEvidence> compactEvidenceForStructuring(
      DecisionRequest request, List<RetrievedEvidence> evidence) {
    Comparator<RetrievedEvidence> rankingComparator =
        Comparator.comparingInt(this::freshnessRank)
            .reversed()
            .thenComparing(Comparator.comparingInt(RetrievedEvidence::qualityScore).reversed())
            .thenComparing(item -> item.url().length());

    Map<String, RetrievedEvidence> selected = new LinkedHashMap<>();
    for (OfferInput offer : request.offers()) {
      evidence.stream()
          .filter(item -> item.company().equalsIgnoreCase(offer.company()))
          .sorted(rankingComparator)
          .limit(MAX_STRUCTURING_EVIDENCE_PER_COMPANY)
          .map(this::compactEvidenceItem)
          .forEach(item -> selected.putIfAbsent(item.url(), item));
    }

    if (selected.isEmpty()) {
      evidence.stream()
          .sorted(rankingComparator)
          .limit(MAX_STRUCTURING_TOTAL_EVIDENCE)
          .map(this::compactEvidenceItem)
          .forEach(item -> selected.putIfAbsent(item.url(), item));
    }

    return selected.values().stream().limit(MAX_STRUCTURING_TOTAL_EVIDENCE).toList();
  }

  private RetrievedEvidence compactEvidenceItem(RetrievedEvidence item) {
    return new RetrievedEvidence(
        item.company(),
        shorten(item.title(), MAX_TITLE_LENGTH),
        item.url(),
        shorten(item.snippet(), MAX_SNIPPET_LENGTH),
        shorten(item.source(), 32),
        item.freshness(),
        item.qualityScore(),
        item.qualityReason());
  }

  private List<RetrievedEvidence> compactEvidenceForRetry(List<RetrievedEvidence> evidence) {
    Map<String, RetrievedEvidence> selected = new LinkedHashMap<>();
    for (RetrievedEvidence item : evidence) {
      if (selected.containsKey(item.company())) {
        continue;
      }
      selected.put(
          item.company(),
          new RetrievedEvidence(
              item.company(),
              shorten(item.title(), 56),
              item.url(),
              shorten(item.snippet(), 96),
              shorten(item.source(), 24),
              item.freshness(),
              item.qualityScore(),
              item.qualityReason()));
    }
    return new ArrayList<>(selected.values());
  }

  private <T> T runStage(String stage, List<ResearchStageTiming> timings, StageCall<T> call)
      throws Exception {
    long startedAt = System.nanoTime();
    try {
      T result = call.execute();
      timings.add(new ResearchStageTiming(stage, elapsedMillis(startedAt), stageStatus(result)));
      return result;
    } catch (Exception error) {
      timings.add(
          new ResearchStageTiming(
              stage, elapsedMillis(startedAt), "failed: " + shortMessage(error)));
      throw error;
    }
  }

  private String stageStatus(Object result) {
    if (result instanceof List<?> values) {
      return "done: " + values.size() + " item(s)";
    }
    return "done";
  }

  private void ensureOk(HttpResponse<String> response, String action) {
    if (response.statusCode() >= 200 && response.statusCode() < 300) {
      return;
    }
    throw new IllegalStateException(
        "Failed to " + action + ". status=" + response.statusCode() + " body=" + response.body());
  }

  private boolean hasExternalStructuringModel() {
    return !nullSafe(liveResearchProperties.structuringApiKey()).isBlank()
        && !nullSafe(liveResearchProperties.structuringBaseUrl()).isBlank()
        && !nullSafe(liveResearchProperties.structuringModel()).isBlank();
  }

  private String structuringProviderLabel() {
    String configured = nullSafe(liveResearchProperties.structuringProvider());
    return configured.isBlank() ? "openai-compatible" : configured;
  }

  private int connectTimeoutSeconds() {
    return positiveOrDefault(liveResearchProperties.connectTimeoutSeconds(), 10);
  }

  private int structuringTimeoutSeconds(PromptVariant promptVariant) {
    int timeout = positiveOrDefault(liveResearchProperties.requestTimeoutSeconds(), 24);
    int fallback = promptVariant == PromptVariant.RETRY ? 30 : 90;
    return Math.max(5, timeout > 0 ? timeout : fallback);
  }

  private int freshnessRank(RetrievedEvidence item) {
    return "fresh".equalsIgnoreCase(item.freshness()) ? 1 : 0;
  }

  private int positiveOrDefault(int value, int fallback) {
    return value > 0 ? value : fallback;
  }

  private String normalizeConfidence(String value) {
    String normalized = nullSafe(value).toLowerCase(Locale.ROOT);
    return switch (normalized) {
      case "medium", "high" -> normalized;
      default -> "low";
    };
  }

  private String normalizeSignal(String value) {
    String normalized = nullSafe(value).toLowerCase(Locale.ROOT);
    return switch (normalized) {
      case "positive", "mixed", "negative", "unclear" -> normalized;
      default -> "unclear";
    };
  }

  private String normalizeFreshness(String value) {
    String normalized = nullSafe(value).toLowerCase(Locale.ROOT);
    return "fresh".equals(normalized) ? "fresh" : "unclear";
  }

  private String normalizeText(String value, String fallback) {
    String trimmed = nullSafe(value).trim();
    return trimmed.isBlank() ? fallback : trimmed;
  }

  private boolean containsAny(String text, String... terms) {
    for (String term : terms) {
      if (text.contains(term)) {
        return true;
      }
    }
    return false;
  }

  private String shortMessage(Exception exception) {
    String message = exception.getMessage();
    if (message == null || message.isBlank()) {
      return exception.getClass().getSimpleName();
    }
    return message.length() > 120 ? message.substring(0, 117) + "..." : message;
  }

  private long elapsedMillis(long startedAt) {
    return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
  }

  private String domainFromUrl(String url) {
    try {
      return URI.create(url).getHost().replaceFirst("^www\\.", "");
    } catch (Exception ignored) {
      return url;
    }
  }

  private String nullSafe(String value) {
    return value == null ? "" : value;
  }

  private String shorten(String value, int maxLength) {
    String trimmed = nullSafe(value).trim().replaceAll("\\s+", " ");
    if (trimmed.length() <= maxLength) {
      return trimmed;
    }
    return trimmed.substring(0, Math.max(0, maxLength - 1)) + "…";
  }

  @FunctionalInterface
  private interface StageCall<T> {
    T execute() throws Exception;
  }

  private enum PromptVariant {
    STANDARD,
    RETRY
  }
}
