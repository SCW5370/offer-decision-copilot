package com.shichangwei.offerdecision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class KimiLiveResearchProvider implements LiveResearchProvider {

  private static final Logger log = LoggerFactory.getLogger(KimiLiveResearchProvider.class);
  private static final String DEFAULT_PROVIDER = "kimi";
  private static final String DEFAULT_STRUCTURING_PROVIDER = "openai-compatible";
  private static final String DEFAULT_BASE_URL = "https://api.moonshot.cn/v1";
  private static final String DEFAULT_MODEL = "kimi-k2.6";
  private static final String DEFAULT_FORMULA_URI = "moonshot/web-search:latest";
  private static final int MAX_TOOL_ROUNDS = 2;
  private static final Pattern URL_PATTERN = Pattern.compile("https://[^\\s)]+");

  private final LiveResearchProperties properties;
  private final LiveSearchProvider liveSearchProvider;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;
  private volatile ArrayNode cachedTools;

  public KimiLiveResearchProvider(
      LiveResearchProperties properties,
      LiveSearchProvider liveSearchProvider,
      ObjectMapper objectMapper) {
    this.properties = properties;
    this.liveSearchProvider = liveSearchProvider;
    this.objectMapper = objectMapper;
    this.httpClient =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(connectTimeoutSeconds())).build();
  }

  @Override
  public boolean isEnabled() {
    return properties.enabled()
        && DEFAULT_PROVIDER.equalsIgnoreCase(nullSafe(properties.provider()))
        && !nullSafe(properties.apiKey()).isBlank();
  }

  @Override
  public Optional<LiveResearchReport> research(DecisionRequest request) {
    if (!isEnabled()) {
      return Optional.empty();
    }

    long startedAt = System.nanoTime();
    List<ResearchStageTiming> stageTimings = new ArrayList<>();
    List<RetrievedEvidence> preflightEvidence = List.of();
    try {
      preflightEvidence =
          liveSearchProvider.isEnabled()
              ? runStage(
                  "retrieval.preflight-search", stageTimings, () -> liveSearchProvider.search(request))
              : List.of();
      ArrayNode tools =
          runStage("kimi.fetch-tools", stageTimings, () -> fetchTools(startedAt));
      List<RetrievedEvidence> evidenceForPrompt = preflightEvidence;
      String researchPayload =
          runStage(
              "kimi.research-conversation",
              stageTimings,
              () -> runResearchConversation(request, tools, evidenceForPrompt, startedAt));
      if (researchPayload == null || researchPayload.isBlank()) {
        log.warn("Kimi live research produced an empty memo after {} ms.", elapsedMillis(startedAt));
        stageTimings.add(new ResearchStageTiming("kimi.memo-validation", 0, "empty"));
        return compatibleFallback(
            request,
            "Kimi returned an empty research memo.",
            preflightEvidence,
            startedAt,
            stageTimings);
      }

      JsonNode parsed =
          runStage(
              "research.parse-output",
              stageTimings,
              () -> parseResearchPayload(researchPayload));
      if (parsed == null || !parsed.isObject()) {
        long parseFallbackStartedAt = System.nanoTime();
        parsed = parseMemoFallback(request, researchPayload);
        stageTimings.add(
            new ResearchStageTiming(
                "research.memo-parser-fallback",
                elapsedMillis(parseFallbackStartedAt),
                parsed == null || !parsed.isObject() ? "failed" : "done"));
      }
      if (parsed == null || !parsed.isObject()) {
        return compatibleFallback(
            request,
            "Kimi returned non-JSON output that could not be locally recovered.",
            preflightEvidence,
            startedAt,
            stageTimings);
      }

      log.info(
          "Kimi live research completed in {} ms. structuringProvider={} stages={}",
          elapsedMillis(startedAt),
          structuringProviderLabel(),
          formatStageTimings(stageTimings));
      return Optional.of(toLiveResearchReport(parsed, request, startedAt, stageTimings));
    } catch (Exception error) {
      log.warn(
          "Kimi live research failed: {}. stages={}",
          error.getMessage(),
          formatStageTimings(stageTimings));
      return compatibleFallback(
          request,
          "Kimi live research failed: " + error.getMessage(),
          preflightEvidence,
          startedAt,
          stageTimings);
    }
  }

  private Optional<LiveResearchReport> compatibleFallback(
      DecisionRequest request,
      String reason,
      List<RetrievedEvidence> preflightEvidence,
      long startedAt,
      List<ResearchStageTiming> stageTimings) {
    if (!hasExternalStructuringModel()) {
      return Optional.empty();
    }

    try {
      JsonNode parsed =
          preflightEvidence.isEmpty()
              ? runStage(
                  "fallback.input-only-structure",
                  stageTimings,
                  () -> structureInputOnlyFallback(request, reason))
              : runStage(
                  "fallback.retrieval-assisted-structure",
                  stageTimings,
                  () -> structureRetrievedEvidenceFallback(request, reason, preflightEvidence));
      if (parsed == null || !parsed.isObject()) {
        return Optional.empty();
      }
      log.info(
          "OpenAI-compatible fallback completed in {} ms. provider={} retrievalEvidence={}",
          elapsedMillis(startedAt),
          structuringProviderLabel(),
          preflightEvidence.size());
      return Optional.of(toLiveResearchReport(parsed, request, startedAt, stageTimings));
    } catch (Exception error) {
      log.warn(
          "OpenAI-compatible input-only fallback failed (provider={}): {}",
          structuringProviderLabel(),
          error.getMessage());
      return Optional.empty();
    }
  }

  private ArrayNode fetchTools(long startedAt) throws IOException, InterruptedException {
    ArrayNode cached = cachedTools;
    if (cached != null && !cached.isEmpty()) {
      return cached.deepCopy();
    }

    ensureWithinResearchBudget(startedAt);
    HttpRequest request =
        authorizedRequest(formulaUrl() + "/tools", properties.apiKey(), toolRequestTimeoutSeconds())
            .GET()
            .build();
    HttpResponse<String> response =
        httpClient.send(request, HttpResponse.BodyHandlers.ofString());

    ensureOk(response, "fetch formula tools");

    JsonNode root = objectMapper.readTree(response.body());
    JsonNode tools = root.path("tools");
    if (tools.isArray()) {
      ArrayNode resolved = ((ArrayNode) tools).deepCopy();
      if (!resolved.isEmpty()) {
        cachedTools = resolved.deepCopy();
      }
      return resolved;
    }

    return objectMapper.createArrayNode();
  }

  private String runResearchConversation(
      DecisionRequest request,
      ArrayNode tools,
      List<RetrievedEvidence> preflightEvidence,
      long startedAt)
      throws IOException, InterruptedException {
    ArrayNode messages = objectMapper.createArrayNode();
    messages.add(message("system", buildResearchSystemPrompt()));
    messages.add(message("user", buildUserPrompt(request, preflightEvidence)));

    for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
      ensureWithinResearchBudget(startedAt);
      JsonNode completion = createResearchCompletion(messages, tools);
      JsonNode choice = completion.path("choices").path(0);
      JsonNode message = choice.path("message");
      String finishReason = choice.path("finish_reason").asText();

      if ("tool_calls".equals(finishReason) && message.path("tool_calls").isArray()) {
        messages.add(copyAssistantMessage(message));
        ArrayNode toolCalls = (ArrayNode) message.path("tool_calls");

        for (JsonNode toolCall : toolCalls) {
          ensureWithinResearchBudget(startedAt);
          String toolCallId = toolCall.path("id").asText();
          JsonNode function = toolCall.path("function");
          String toolResult = callFormula(function);

          ObjectNode toolMessage = objectMapper.createObjectNode();
          toolMessage.put("role", "tool");
          toolMessage.put("tool_call_id", toolCallId);
          toolMessage.put("content", toolResult);
          messages.add(toolMessage);
        }

        continue;
      }

      return extractTextContent(message.path("content"));
    }

    return null;
  }

  private JsonNode createResearchCompletion(ArrayNode messages, ArrayNode tools)
      throws IOException, InterruptedException {
    ObjectNode requestBody = objectMapper.createObjectNode();
    requestBody.put("model", model());
    requestBody.set("messages", messages);
    requestBody.set("tools", tools);
    requestBody.put("tool_choice", "auto");
    requestBody.put("temperature", 0.6);
    requestBody.put("max_completion_tokens", 900);

    ObjectNode responseFormat = objectMapper.createObjectNode();
    responseFormat.put("type", "json_object");
    requestBody.set("response_format", responseFormat);

    ObjectNode thinking = objectMapper.createObjectNode();
    thinking.put("type", "disabled");
    requestBody.set("thinking", thinking);

    HttpRequest request =
        authorizedRequest(baseUrl() + "/chat/completions", properties.apiKey(), researchRequestTimeoutSeconds())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
            .build();

    HttpResponse<String> response =
        httpClient.send(request, HttpResponse.BodyHandlers.ofString());

    ensureOk(response, "create kimi chat completion");
    return objectMapper.readTree(response.body());
  }

  private JsonNode structureResearchMemo(DecisionRequest request, String researchMemo)
      throws IOException, InterruptedException {
    if (hasExternalStructuringModel()) {
      try {
        return structureResearchMemoWithCompatibleModel(request, researchMemo);
      } catch (Exception error) {
        log.warn(
            "External structuring model failed (provider={}): {}. Falling back to Kimi structuring.",
            structuringProviderLabel(),
            error.getMessage());
      }
    }

    return structureResearchMemoWithKimi(request, researchMemo);
  }

  private JsonNode structureResearchMemoWithKimi(DecisionRequest request, String researchMemo)
      throws IOException, InterruptedException {
    ArrayNode messages = objectMapper.createArrayNode();
    messages.add(message("system", buildStructuringSystemPrompt()));
    messages.add(
        message(
            "user",
            buildStructuringUserPrompt(request, researchMemo)));

    ObjectNode requestBody = objectMapper.createObjectNode();
    requestBody.put("model", model());
    requestBody.set("messages", messages);
    requestBody.put("temperature", 0.6);
    requestBody.put("max_completion_tokens", 900);

    ObjectNode responseFormat = objectMapper.createObjectNode();
    responseFormat.put("type", "json_object");
    requestBody.set("response_format", responseFormat);
    ObjectNode thinking = objectMapper.createObjectNode();
    thinking.put("type", "disabled");
    requestBody.set("thinking", thinking);

    HttpRequest requestMessage =
        authorizedRequest(baseUrl() + "/chat/completions", properties.apiKey(), structuringRequestTimeoutSeconds())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
            .build();

    HttpResponse<String> response =
        httpClient.send(requestMessage, HttpResponse.BodyHandlers.ofString());

    ensureOk(response, "structure kimi research memo");

    JsonNode completion = objectMapper.readTree(response.body());
    JsonNode finalMessage = completion.path("choices").path(0).path("message");
    return parseStructuredContent(finalMessage.path("content"));
  }

  private JsonNode structureResearchMemoWithCompatibleModel(
      DecisionRequest request, String researchMemo) throws IOException, InterruptedException {
    ArrayNode messages = objectMapper.createArrayNode();
    messages.add(message("system", buildStructuringSystemPrompt()));
    messages.add(message("user", buildStructuringUserPrompt(request, researchMemo)));

    ObjectNode requestBody = objectMapper.createObjectNode();
    requestBody.put("model", structuringModel());
    requestBody.set("messages", messages);
    requestBody.put("temperature", 0.2);
    requestBody.put("max_tokens", 700);

    ObjectNode responseFormat = objectMapper.createObjectNode();
    responseFormat.put("type", "json_object");
    requestBody.set("response_format", responseFormat);

    HttpRequest requestMessage =
        authorizedRequest(
                structuringBaseUrl() + "/chat/completions",
                properties.structuringApiKey(),
                structuringRequestTimeoutSeconds())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
            .build();

    HttpResponse<String> response =
        httpClient.send(requestMessage, HttpResponse.BodyHandlers.ofString());

    ensureOk(response, "structure live research memo with openai-compatible model");

    JsonNode completion = objectMapper.readTree(response.body());
    JsonNode finalMessage = completion.path("choices").path(0).path("message");
    return parseStructuredContent(finalMessage.path("content"));
  }

  private JsonNode structureInputOnlyFallback(DecisionRequest request, String reason)
      throws IOException, InterruptedException {
    ArrayNode messages = objectMapper.createArrayNode();
    messages.add(message("system", buildInputOnlyFallbackSystemPrompt()));
    messages.add(message("user", buildInputOnlyFallbackUserPrompt(request, reason)));

    ObjectNode requestBody = objectMapper.createObjectNode();
    requestBody.put("model", structuringModel());
    requestBody.set("messages", messages);
    requestBody.put("temperature", 0.2);
    requestBody.put("max_tokens", 1400);

    ObjectNode responseFormat = objectMapper.createObjectNode();
    responseFormat.put("type", "json_object");
    requestBody.set("response_format", responseFormat);

    HttpRequest requestMessage =
        authorizedRequest(
                structuringBaseUrl() + "/chat/completions",
                properties.structuringApiKey())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
            .build();

    HttpResponse<String> response =
        httpClient.send(requestMessage, HttpResponse.BodyHandlers.ofString());

    ensureOk(response, "create openai-compatible input-only fallback");

    JsonNode completion = objectMapper.readTree(response.body());
    JsonNode finalMessage = completion.path("choices").path(0).path("message");
    return parseStructuredContent(finalMessage.path("content"));
  }

  private JsonNode structureRetrievedEvidenceFallback(
      DecisionRequest request, String reason, List<RetrievedEvidence> evidence)
      throws IOException, InterruptedException {
    ArrayNode messages = objectMapper.createArrayNode();
    messages.add(message("system", buildRetrievedEvidenceFallbackSystemPrompt()));
    messages.add(message("user", buildRetrievedEvidenceFallbackUserPrompt(request, reason, evidence)));

    ObjectNode requestBody = objectMapper.createObjectNode();
    requestBody.put("model", structuringModel());
    requestBody.set("messages", messages);
    requestBody.put("temperature", 0.2);
    requestBody.put("max_tokens", 900);

    ObjectNode responseFormat = objectMapper.createObjectNode();
    responseFormat.put("type", "json_object");
    requestBody.set("response_format", responseFormat);

    HttpRequest requestMessage =
        authorizedRequest(
                structuringBaseUrl() + "/chat/completions",
                properties.structuringApiKey(),
                structuringRequestTimeoutSeconds())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
            .build();

    HttpResponse<String> response =
        httpClient.send(requestMessage, HttpResponse.BodyHandlers.ofString());

    ensureOk(response, "create openai-compatible retrieval-assisted fallback");

    JsonNode completion = objectMapper.readTree(response.body());
    JsonNode finalMessage = completion.path("choices").path(0).path("message");
    return parseStructuredContent(finalMessage.path("content"));
  }

  private String callFormula(JsonNode functionNode) throws IOException, InterruptedException {
    HttpRequest request =
        authorizedRequest(formulaUrl() + "/fibers", properties.apiKey(), toolRequestTimeoutSeconds())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(functionNode)))
            .build();

    HttpResponse<String> response =
        httpClient.send(request, HttpResponse.BodyHandlers.ofString());

    ensureOk(response, "execute formula fiber");

    JsonNode fiber = objectMapper.readTree(response.body());
    JsonNode context = fiber.path("context");

    if (context.hasNonNull("encrypted_output")) {
      return context.path("encrypted_output").asText();
    }

    if (context.hasNonNull("output")) {
      return context.path("output").toString();
    }

    if (fiber.hasNonNull("error")) {
      return "Formula execution error: " + fiber.path("error").toString();
    }

    return fiber.toString();
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
        }
      }
      if (builder.isEmpty()) {
        return null;
      }
      return parseStructuredText(builder.toString());
    }

    return contentNode;
  }

  private JsonNode parseResearchPayload(String payload) throws IOException {
    return parseStructuredContent(TextNode.valueOf(payload));
  }

  private JsonNode parseStructuredText(String rawText) throws IOException {
    if (rawText == null || rawText.isBlank()) {
      return null;
    }

    String trimmed = rawText.trim();
    List<String> candidates = new ArrayList<>();
    candidates.add(trimmed);

    String withoutFences = trimmed.replace("```json", "").replace("```", "").trim();
    if (!withoutFences.equals(trimmed)) {
      candidates.add(withoutFences);
    }

    int objectStart = withoutFences.indexOf('{');
    int objectEnd = withoutFences.lastIndexOf('}');
    if (objectStart >= 0 && objectEnd > objectStart) {
      candidates.add(withoutFences.substring(objectStart, objectEnd + 1));
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

  private String extractTextContent(JsonNode contentNode) {
    if (contentNode == null || contentNode.isMissingNode() || contentNode.isNull()) {
      return "";
    }

    if (contentNode.isTextual()) {
      return contentNode.asText();
    }

    if (contentNode.isArray()) {
      StringBuilder builder = new StringBuilder();
      for (JsonNode item : contentNode) {
        if (item.hasNonNull("text")) {
          builder.append(item.path("text").asText()).append("\n");
        } else if (item.hasNonNull("content")) {
          builder.append(item.path("content").asText()).append("\n");
        }
      }
      return builder.toString().trim();
    }

    return contentNode.toString();
  }

  private JsonNode parseMemoFallback(DecisionRequest request, String researchMemo) {
    if (researchMemo == null || researchMemo.isBlank()) {
      return null;
    }

    ObjectNode root = objectMapper.createObjectNode();
    root.put("marketTakeaway", marketTakeawayFromMemo(researchMemo));
    ArrayNode companies = objectMapper.createArrayNode();
    ArrayNode sources = objectMapper.createArrayNode();

    for (OfferInput offer : request.offers()) {
      String section = sectionForOffer(researchMemo, offer);
      ObjectNode companyNode = objectMapper.createObjectNode();
      companyNode.put("offerId", offer.id());
      companyNode.put("company", offer.company());
      companyNode.put(
          "summary",
          firstNonBlank(
              fieldValue(section, "summary"),
              "live research memo 没有为这家公司提供清晰的结构化摘要，因此当前结果只能视为部分可用。"));
      companyNode.put("confidence", normalizeConfidence(fieldValue(section, "confidence")));
      companyNode.put("hiringSignal", normalizeSignal(fieldValue(section, "hiring signal")));
      companyNode.put("businessSignal", normalizeSignal(fieldValue(section, "business signal")));
      companyNode.put("technicalSignal", normalizeSignal(fieldValue(section, "technical signal")));
      companyNode.set("keySignals", buildFallbackKeySignals(section, offer.company(), sources));
      companyNode.set(
          "opportunities",
          stringsToArray(parseList(section, "opportunities", "risks", "must verify")));
      companyNode.set("risks", stringsToArray(parseList(section, "risks", "must verify", null)));
      companyNode.set(
          "mustVerify",
          stringsToArray(
              parseList(
                  section,
                  "must verify",
                  null,
                  "请核验当前团队的真实职责范围是否仍与岗位描述一致。")));
      companies.add(companyNode);
    }

    root.set("companies", companies);
    root.set("sources", sources);
    return root;
  }

  private String marketTakeawayFromMemo(String memo) {
    String upper = memo.toUpperCase(Locale.ROOT);
    int marketIndex = upper.indexOf("MARKET TAKEAWAY");
    if (marketIndex < 0) {
      return "live research memo 仅被部分结构化，因此只能提取有限的市场结论。";
    }

    int start = memo.indexOf('\n', marketIndex);
    if (start < 0) {
      return "live research memo 仅被部分结构化，因此只能提取有限的市场结论。";
    }

    int nextOffer = upper.indexOf("OFFER ", start);
    String takeaway =
        nextOffer > start ? memo.substring(start, nextOffer) : memo.substring(start);
    takeaway = takeaway.replace("MARKET TAKEAWAY", "").trim();
    return takeaway.isBlank()
        ? "live research memo 仅被部分结构化，因此只能提取有限的市场结论。"
        : takeaway;
  }

  private String sectionForOffer(String memo, OfferInput offer) {
    String lowerMemo = memo.toLowerCase(Locale.ROOT);
    String offerMarker = "offer " + offer.id().toLowerCase(Locale.ROOT);
    int markerIndex = lowerMemo.indexOf(offerMarker);
    if (markerIndex < 0) {
      markerIndex = lowerMemo.indexOf(offer.company().toLowerCase(Locale.ROOT));
    }
    if (markerIndex < 0) {
      return memo;
    }

    int nextOffer = lowerMemo.indexOf("offer ", markerIndex + offerMarker.length());
    return nextOffer > markerIndex ? memo.substring(markerIndex, nextOffer) : memo.substring(markerIndex);
  }

  private String fieldValue(String section, String field) {
    if (section == null || section.isBlank()) {
      return "";
    }

    String[] lines = section.split("\\R");
    String normalizedField = field.toLowerCase(Locale.ROOT);
    for (String line : lines) {
      String lowerLine = line.toLowerCase(Locale.ROOT).trim();
      if (lowerLine.startsWith(normalizedField + ":")) {
        return line.substring(line.indexOf(':') + 1).trim();
      }
    }
    return "";
  }

  private ArrayNode buildFallbackKeySignals(
      String section, String company, ArrayNode sources) {
    ArrayNode keySignals = objectMapper.createArrayNode();
    List<String> urls = extractUrls(section);
    String summary = firstNonBlank(fieldValue(section, "summary"), section.trim());

    if (urls.isEmpty()) {
      ObjectNode signalNode = objectMapper.createObjectNode();
      signalNode.put("title", "研究备忘录仅完成部分结构化");
      signalNode.put("detail", truncate(summary, 220));
      signalNode.put(
          "whyItMatters",
          "provider 虽然生成了 memo，但引用结构不够完整，因此不能直接当作最终结论。");
      signalNode.put("sourceLabel", "缺少完整引用的实时 memo");
      signalNode.put("sourceUrl", "");
      signalNode.put("freshness", "unclear");
      keySignals.add(signalNode);
      return keySignals;
    }

    for (String url : urls.stream().limit(3).toList()) {
      ObjectNode signalNode = objectMapper.createObjectNode();
      signalNode.put("title", "从实时 memo 中恢复出的近期公开来源");
      signalNode.put("detail", truncate(summary, 220));
      signalNode.put(
          "whyItMatters",
          "即使只是部分恢复引用，也好过直接丢弃整次实时研究。");
      signalNode.put("sourceLabel", domainFromUrl(url));
      signalNode.put("sourceUrl", url);
      signalNode.put("freshness", "fresh");
      keySignals.add(signalNode);

      ObjectNode sourceNode = objectMapper.createObjectNode();
      sourceNode.put("company", company);
      sourceNode.put("label", domainFromUrl(url));
      sourceNode.put("url", url);
      sourceNode.put("domain", domainFromUrl(url));
      sources.add(sourceNode);
    }

    return keySignals;
  }

  private List<String> parseList(
      String section, String heading, String nextHeading, String fallback) {
    if (section == null || section.isBlank()) {
      return fallback == null ? List.of() : List.of(fallback);
    }

    String lowerSection = section.toLowerCase(Locale.ROOT);
    int headingIndex = lowerSection.indexOf(heading);
    if (headingIndex < 0) {
      return fallback == null ? List.of() : List.of(fallback);
    }

    int start = section.indexOf('\n', headingIndex);
    if (start < 0) {
      return fallback == null ? List.of() : List.of(fallback);
    }

    int end = nextHeading == null ? section.length() : lowerSection.indexOf(nextHeading, start);
    String block = end > start ? section.substring(start, end) : section.substring(start);
    List<String> values = new ArrayList<>();
    for (String line : block.split("\\R")) {
      String trimmed = line.trim();
      if (trimmed.startsWith("-")) {
        values.add(trimmed.substring(1).trim());
      }
    }
    if (values.isEmpty() && fallback != null) {
      values.add(fallback);
    }
    return values;
  }

  private ArrayNode stringsToArray(List<String> values) {
    ArrayNode node = objectMapper.createArrayNode();
    if (values == null || values.isEmpty()) {
      return node;
    }
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        node.add(value);
      }
    }
    return node;
  }

  private List<String> extractUrls(String section) {
    List<String> urls = new ArrayList<>();
    Matcher matcher = URL_PATTERN.matcher(section == null ? "" : section);
    while (matcher.find()) {
      String url = sanitizeUrl(matcher.group());
      if (!url.isBlank() && !urls.contains(url)) {
        urls.add(url);
      }
    }
    return urls;
  }

  private String normalizeConfidence(String value) {
    String normalized = normalizeText(value, "").toLowerCase(Locale.ROOT);
    return switch (normalized) {
      case "high", "medium", "low" -> normalized;
      default -> "low";
    };
  }

  private String normalizeSignal(String value) {
    String normalized = normalizeText(value, "").toLowerCase(Locale.ROOT);
    return switch (normalized) {
      case "positive", "mixed", "negative", "unclear" -> normalized;
      default -> "unclear";
    };
  }

  private String firstNonBlank(String value, String fallback) {
    if (value != null && !value.isBlank()) {
      return value;
    }
    return fallback;
  }

  private String truncate(String value, int maxLength) {
    if (value == null || value.length() <= maxLength) {
      return value == null ? "" : value;
    }
    return value.substring(0, maxLength - 3).trim() + "...";
  }

  private LiveResearchReport toLiveResearchReport(
      JsonNode root,
      DecisionRequest request,
      long startedAt,
      List<ResearchStageTiming> stageTimings) {
    String marketTakeaway =
        normalizeText(
            root.path("marketTakeaway").asText(""),
            "最近的公开证据偏弱或存在歧义，因此实时研究层暂时无法形成强结论。");
    List<LiveCompanyResearch> companySignals = new ArrayList<>();
    Map<String, ResearchSource> sources = new LinkedHashMap<>();
    Set<String> matchedCompanies = new HashSet<>();

    appendTopLevelSources(root.path("sources"), sources);

    for (JsonNode companyNode : collectCompanyNodes(root)) {
      String company = resolveCompanyName(companyNode, request, companySignals.size());
      List<ResearchSignal> keySignals = new ArrayList<>();

      for (JsonNode signalNode : companyNode.path("keySignals")) {
        String sourceUrl = sanitizeUrl(signalNode.path("sourceUrl").asText(""));
        ResearchSignal signal =
            new ResearchSignal(
                normalizeText(
                    signalNode.path("title").asText(""),
                    "公开信号覆盖有限"),
                normalizeText(
                    signalNode.path("detail").asText(""),
                    "当前没有足够可靠的近期公开证据可以支持对这家公司的判断。"),
                normalizeText(
                    signalNode.path("whyItMatters").asText(""),
                    "这意味着候选人应更多依赖面试中的实时核验，而不是公开叙事。"),
                normalizeText(
                    signalNode.path("sourceLabel").asText(""),
                    sourceUrl.isBlank() ? "暂无可靠公开来源" : "公开来源"),
                sourceUrl,
                normalizeSignalValue(signalNode.path("freshness").asText("unclear"), "unclear"));
        keySignals.add(signal);

        if (!signal.sourceUrl().isBlank()) {
          sources.putIfAbsent(
              signal.sourceUrl(),
              new ResearchSource(
                  company,
                  signal.sourceLabel(),
                  signal.sourceUrl(),
                  domainFromUrl(signal.sourceUrl())));
        }
      }

      if (keySignals.isEmpty()) {
        keySignals.add(
            new ResearchSignal(
                "公开信号覆盖有限",
                "当前没有足够可靠的近期公开证据可以支持对这家公司的判断。",
                "这意味着候选人应更多依赖面试中的实时核验，而不是公开叙事。",
                "暂无可靠公开来源",
                "",
                "unclear"));
      }

      companySignals.add(
          new LiveCompanyResearch(
              company,
              normalizeText(
                  companyNode.path("summary").asText(""),
                  "实时研究层当前尚未能为这家公司建立足够强的近期公开信号结论。"),
              normalizeSignalValue(companyNode.path("confidence").asText("medium"), "medium"),
              normalizeSignalValue(companyNode.path("hiringSignal").asText("unclear"), "unclear"),
              normalizeSignalValue(companyNode.path("businessSignal").asText("unclear"), "unclear"),
              normalizeSignalValue(companyNode.path("technicalSignal").asText("unclear"), "unclear"),
              keySignals,
              normalizeList(
                  strings(companyNode.path("opportunities")),
                  "当前尚未能明确识别出有把握的公开利好信号。"),
              normalizeList(
                  strings(companyNode.path("risks")),
                  "公开层面的负向信号仍然存在歧义，建议在面试中进一步核验。"),
              normalizeList(
                  strings(companyNode.path("mustVerify")),
                  "请核验当前团队的真实职责范围是否仍与岗位描述一致。")));
      matchedCompanies.add(normalizeCompany(company));
    }

    for (OfferInput offer : request.offers()) {
      if (!matchedCompanies.contains(normalizeCompany(offer.company()))) {
        companySignals.add(buildFallbackCompanySignal(offer.company()));
      }
    }

    return new LiveResearchReport(
        normalizeText(
            root.path("_provider").asText(""),
            hasExternalStructuringModel()
                ? DEFAULT_PROVIDER + " + " + structuringProviderLabel()
                : DEFAULT_PROVIDER),
        normalizeText(root.path("_model").asText(""), model()),
        marketTakeaway,
        companySignals,
        new ArrayList<>(sources.values()),
        elapsedMillis(startedAt),
        List.copyOf(stageTimings));
  }

  private List<JsonNode> collectCompanyNodes(JsonNode root) {
    List<JsonNode> companyNodes = new ArrayList<>();
    appendObjectNodes(root.path("companies"), companyNodes);
    appendObjectNodes(root.path("companySignals"), companyNodes);
    appendObjectNodes(root.path("offers"), companyNodes);

    if (companyNodes.isEmpty() && root.isObject()) {
      root.fields()
          .forEachRemaining(
              entry -> {
                String key = entry.getKey().toLowerCase(Locale.ROOT);
                JsonNode value = entry.getValue();
                if (!value.isObject()) {
                  return;
                }
                if (key.contains("offer") || key.contains("company")) {
                  companyNodes.add(value);
                }
              });
    }

    return companyNodes;
  }

  private void appendObjectNodes(JsonNode node, List<JsonNode> target) {
    if (!node.isArray()) {
      return;
    }
    for (JsonNode item : node) {
      if (item.isObject()) {
        target.add(item);
      }
    }
  }

  private void appendTopLevelSources(JsonNode sourceNodes, Map<String, ResearchSource> sources) {
    if (!sourceNodes.isArray()) {
      return;
    }

    for (JsonNode sourceNode : sourceNodes) {
      String url = sanitizeUrl(sourceNode.path("url").asText(""));
      if (url.isBlank()) {
        continue;
      }

      String company =
          normalizeText(sourceNode.path("company").asText(""), "整体市场信号");
      String label =
          normalizeText(
              sourceNode.path("label").asText(""),
              sourceNode.path("title").asText("公开来源"));
      sources.putIfAbsent(url, new ResearchSource(company, label, url, domainFromUrl(url)));
    }
  }

  private String resolveCompanyName(JsonNode companyNode, DecisionRequest request, int index) {
    String company = normalizeText(companyNode.path("company").asText(""), "");
    if (!company.isBlank()) {
      return company;
    }

    String offerId = normalizeText(companyNode.path("offerId").asText(""), "");
    if (!offerId.isBlank()) {
      for (OfferInput offer : request.offers()) {
        if (offer.id().equalsIgnoreCase(offerId)) {
          return offer.company();
        }
      }
    }

    if (index >= 0 && index < request.offers().size()) {
      return request.offers().get(index).company();
    }

    return "未知公司";
  }

  private LiveCompanyResearch buildFallbackCompanySignal(String company) {
    return new LiveCompanyResearch(
        company,
        "live provider 虽然完成了执行，但模型没有为这家公司返回足够结构化的公开证据，因此当前信号层仍然处于未解析状态，需要人工核验。",
        "low",
        "unclear",
        "unclear",
        "unclear",
        List.of(
            new ResearchSignal(
                "结构化研究输出不完整",
                "模型返回结果里没有包含这家公司足够可用的公司级信号。",
                "这是一条可靠性提醒：当 live 证据缺失时，应优先相信人工核验。",
                "暂无可靠公开来源",
                "",
                "unclear")),
        List.of("当前尚未能明确识别出有把握的公开利好信号。"),
        List.of("公开层面的负向信号仍然存在歧义，建议在面试中进一步核验。"),
        List.of("请核验当前团队的真实职责范围是否仍与岗位描述一致。"));
  }

  private List<String> strings(JsonNode node) {
    List<String> values = new ArrayList<>();
    if (!node.isArray()) {
      return values;
    }

    for (JsonNode item : node) {
      String value = item.asText("");
      if (!value.isBlank()) {
        values.add(value);
      }
    }

    return values;
  }

  private List<String> normalizeList(List<String> values, String fallback) {
    List<String> normalized =
        values.stream()
            .map(value -> normalizeText(value, ""))
            .filter(value -> !value.isBlank())
            .toList();

    return normalized.isEmpty() ? List.of(fallback) : normalized;
  }

  private ObjectNode message(String role, String content) {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("role", role);
    node.put("content", content);
    return node;
  }

  private ObjectNode copyAssistantMessage(JsonNode message) {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("role", message.path("role").asText("assistant"));
    if (message.has("content")) {
      node.set("content", message.get("content"));
    } else {
      node.putNull("content");
    }
    if (message.has("tool_calls")) {
      node.set("tool_calls", message.get("tool_calls"));
    }
    return node;
  }

  private HttpRequest.Builder authorizedRequest(String url) {
    return authorizedRequest(url, properties.apiKey(), requestTimeoutSeconds());
  }

  private HttpRequest.Builder authorizedRequest(String url, String apiKey) {
    return authorizedRequest(url, apiKey, requestTimeoutSeconds());
  }

  private HttpRequest.Builder authorizedRequest(String url, String apiKey, int timeoutSeconds) {
    return HttpRequest.newBuilder(URI.create(url))
        .timeout(Duration.ofSeconds(timeoutSeconds))
        .header("Authorization", "Bearer " + apiKey);
  }

  private <T> T runStage(String stage, List<ResearchStageTiming> timings, StageCall<T> call)
      throws Exception {
    long stageStartedAt = System.nanoTime();
    try {
      T result = call.execute();
      timings.add(new ResearchStageTiming(stage, elapsedMillis(stageStartedAt), stageStatus(result)));
      return result;
    } catch (Exception error) {
      timings.add(
          new ResearchStageTiming(
              stage, elapsedMillis(stageStartedAt), "failed: " + shortMessage(error)));
      throw error;
    }
  }

  private void ensureWithinResearchBudget(long startedAt) {
    long timeoutMillis = researchTimeoutSeconds() * 1000L;
    if (timeoutMillis <= 0 || elapsedMillis(startedAt) < timeoutMillis) {
      return;
    }
    throw new IllegalStateException(
        "Live research budget exceeded after " + elapsedMillis(startedAt) + " ms");
  }

  private String shortMessage(Exception error) {
    String message = error.getMessage();
    if (message == null || message.isBlank()) {
      return error.getClass().getSimpleName();
    }
    return truncate(message.replaceAll("\\s+", " "), 120);
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
        "Failed to "
            + action
            + ". status="
            + response.statusCode()
            + " body="
            + response.body());
  }

  private String buildResearchSystemPrompt() {
    return """
        You are an evidence-first research analyst helping a candidate compare two job offers.
        Use the web search tool whenever you need freshness-sensitive public signals.
        If the user message includes preflight retrieved evidence, treat it as candidate evidence:
        verify it when possible, keep full URLs, and do not overclaim beyond the snippets.
        Focus on recent hiring intensity, layoffs, funding or runway signals, product launches,
        engineering blog activity, public customer momentum, or meaningful organizational change.
        If evidence is weak, say so clearly. Do not invent facts.
        Use at most 2 web-search tool calls in total unless the first results are clearly unusable.
        Return a single compact JSON object, not markdown and not plain text.
        All user-facing explanations, summaries, opportunities, risks, and must-verify items
        must be written in Simplified Chinese.

        JSON shape:
        {
          "_provider": "kimi",
          "_model": "string",
          "marketTakeaway": "string",
          "companies": [
            {
              "offerId": "string",
              "company": "string",
              "summary": "string",
              "confidence": "low|medium|high",
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
        Keep offerId values stable as offer-a and offer-b. Keep company names exactly as provided by the user.
        Each company should have 1 to 2 keySignals only. Keep summary to 2 short sentences.
        """;
  }

  private String buildStructuringSystemPrompt() {
    return """
        You are turning a research memo into strict JSON for a job-offer decision system.
        Do not invent facts that are not present in the memo. If evidence is weak, keep the signals unclear.
        You must keep the offer identities stable: copy the exact offerId and exact company name
        supplied by the user into the final JSON, even when evidence is weak.
        All user-facing natural-language fields in the final JSON must be written in Simplified Chinese.

        Return a single JSON object with this exact shape:
        {
          "marketTakeaway": "string",
          "companies": [
            {
              "offerId": "string",
              "company": "string",
              "summary": "string",
              "confidence": "low|medium|high",
              "hiringSignal": "positive|mixed|negative|unclear",
              "businessSignal": "positive|mixed|negative|unclear",
              "technicalSignal": "positive|mixed|negative|unclear",
              "keySignals": [
                {
                  "title": "string",
                  "detail": "string",
                  "whyItMatters": "string",
                  "sourceLabel": "string",
                  "sourceUrl": "string",
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
        The sources array may be empty, but include all usable public URLs there when available.
        Each company should have 1 to 3 keySignals.
        Always set offerId to the exact id provided by the user for that offer.
        Always set company to the exact company name provided by the user for that offer.
        sourceUrl must be a full https URL when available.
        Never use placeholder text like "...", "TBD", or "N/A". If evidence is insufficient,
        explicitly say so and mark the signal as unclear.
        """;
  }

  private String buildStructuringUserPrompt(DecisionRequest request, String researchMemo) {
    StringBuilder builder = new StringBuilder();
    builder.append("请严格使用下面这组 offer 映射：\n");
    for (OfferInput offer : request.offers()) {
      builder.append("- ").append(offer.id()).append(" => ").append(offer.company()).append("\n");
    }
    builder
        .append("\n请把下面的 memo 转成要求的 JSON，并且所有说明性文本一律使用简体中文。\n\n")
        .append(researchMemo);
    return builder.toString();
  }

  private String buildInputOnlyFallbackSystemPrompt() {
    return """
        You are producing a low-confidence fallback report for a job-offer decision system.
        The live web research step failed before returning usable public evidence.
        Use only the candidate profile and offer fields provided by the user.
        Do not claim that you searched the web. Do not cite public sources. Keep every signal conservative.
        All user-facing natural-language fields must be written in Simplified Chinese.

        Return a single JSON object with this exact shape:
        {
          "_provider": "model-only fallback + openai-compatible",
          "_model": "string",
          "marketTakeaway": "string",
          "companies": [
            {
              "offerId": "string",
              "company": "string",
              "summary": "string",
              "confidence": "low",
              "hiringSignal": "unclear",
              "businessSignal": "unclear",
              "technicalSignal": "positive|mixed|unclear",
              "keySignals": [
                {
                  "title": "string",
                  "detail": "string",
                  "whyItMatters": "string",
                  "sourceLabel": "User-provided offer fields",
                  "sourceUrl": "",
                  "freshness": "unclear"
                }
              ],
              "opportunities": ["string"],
              "risks": ["string"],
              "mustVerify": ["string"]
            }
          ],
          "sources": []
        }
        The companies array must contain exactly two entries.
        Always preserve exact offerId and company names from the user.
        """;
  }

  private String buildRetrievedEvidenceFallbackSystemPrompt() {
    return """
        You are producing a retrieval-assisted fallback report for a job-offer decision system.
        The primary live research agent failed, but a separate retrieval layer returned candidate
        public evidence with titles, URLs, sources, freshness labels, and snippets.

        Use only the retrieved evidence and user-provided offer fields. Do not invent facts beyond
        those snippets. If evidence is thin, keep confidence low or medium and mark signals unclear.
        Preserve every useful URL in keySignals and sources.
        All user-facing natural-language fields must be written in Simplified Chinese.

        Return a single JSON object with this exact shape:
        {
          "_provider": "retrieval fallback + openai-compatible",
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
                  "sourceUrl": "https://example.com/source",
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
              "url": "https://example.com/source",
              "domain": "example.com"
            }
          ]
        }
        The companies array must contain exactly two entries.
        Always preserve exact offerId and company names from the user.
        If no retrieved evidence exists for a company, return an unclear low-confidence company entry.
        """;
  }

  private String buildInputOnlyFallbackUserPrompt(DecisionRequest request, String reason) {
    StringBuilder builder = new StringBuilder();
    builder
        .append("降级原因：")
        .append(reason)
        .append("\n结构化模型：")
        .append(structuringModel())
        .append("\n\n候选人目标：")
        .append(request.userProfile().target())
        .append("\n风险偏好：")
        .append(request.userProfile().riskAppetite())
        .append("\n优先项：")
        .append(String.join(", ", request.userProfile().priorities()))
        .append("\n\n");

    for (OfferInput offer : request.offers()) {
      builder
          .append("Offer ID：")
          .append(offer.id())
          .append("\n公司：")
          .append(offer.company())
          .append("\n岗位：")
          .append(offer.role())
          .append("\n城市：")
          .append(offer.city())
          .append("\n阶段：")
          .append(offer.stage())
          .append("\n业务方向：")
          .append(offer.domain())
          .append("\n技术栈：")
          .append(offer.stack())
          .append("\n办公方式：")
          .append(offer.workMode())
          .append("\nJD 信号：")
          .append(offer.jdSignals())
          .append("\n备注：")
          .append(offer.notes())
          .append("\n\n");
    }

    return builder.toString();
  }

  private String buildRetrievedEvidenceFallbackUserPrompt(
      DecisionRequest request, String reason, List<RetrievedEvidence> evidence) {
    StringBuilder builder = new StringBuilder();
    builder
        .append("降级原因：")
        .append(reason)
        .append("\n结构化模型：")
        .append(structuringModel())
        .append("\n\n候选人目标：")
        .append(request.userProfile().target())
        .append("\n风险偏好：")
        .append(request.userProfile().riskAppetite())
        .append("\n优先项：")
        .append(String.join(", ", request.userProfile().priorities()))
        .append("\n\nOffer 映射：\n");

    for (OfferInput offer : request.offers()) {
      builder
          .append("- ")
          .append(offer.id())
          .append(" => ")
          .append(offer.company())
          .append(" | role=")
          .append(offer.role())
          .append(" | domain=")
          .append(offer.domain())
          .append(" | stack=")
          .append(offer.stack())
          .append(" | stage=")
          .append(offer.stage())
          .append("\n");
    }

    builder.append("\n检索证据：\n");
    for (RetrievedEvidence item : evidence) {
      builder
          .append("- 公司：")
          .append(item.company())
          .append("\n  标题：")
          .append(item.title())
          .append("\n  URL：")
          .append(item.url())
          .append("\n  来源：")
          .append(item.source())
          .append("\n  时效性：")
          .append(item.freshness())
            .append("\n  摘要：")
            .append(item.snippet())
            .append("\n  质量分：")
            .append(item.qualityScore())
            .append(" (")
            .append(item.qualityReason())
            .append(")")
            .append("\n");
    }

    return builder.toString();
  }

  private String buildUserPrompt(DecisionRequest request, List<RetrievedEvidence> preflightEvidence) {
    StringBuilder builder = new StringBuilder();
    builder.append("候选人目标：").append(request.userProfile().target()).append("\n");
    builder
        .append("风险偏好：")
        .append(request.userProfile().riskAppetite())
        .append("\n");
    builder
        .append("优先项：")
        .append(String.join(", ", request.userProfile().priorities()))
        .append("\n\n");

    for (int index = 0; index < request.offers().size(); index++) {
      OfferInput offer = request.offers().get(index);
      builder
          .append("Offer ")
          .append(index + 1)
          .append("\n")
          .append("Offer ID：")
          .append(offer.id())
          .append("\n")
          .append("公司：")
          .append(offer.company())
          .append("\n")
          .append("岗位：")
          .append(offer.role())
          .append("\n")
          .append("城市：")
          .append(offer.city())
          .append("\n")
          .append("阶段：")
          .append(offer.stage())
          .append("\n")
          .append("业务方向：")
          .append(offer.domain())
          .append("\n")
          .append("技术栈：")
          .append(offer.stack())
          .append("\n")
          .append("办公方式：")
          .append(offer.workMode())
          .append("\n")
          .append("JD 信号：")
          .append(offer.jdSignals())
          .append("\n")
          .append("备注：")
          .append(offer.notes())
          .append("\n\n");
    }

    if (!preflightEvidence.isEmpty()) {
      builder.append("检索层预取到的证据：\n");
      for (RetrievedEvidence evidence : preflightEvidence) {
        builder
            .append("- 公司：")
            .append(evidence.company())
            .append("\n  标题：")
            .append(evidence.title())
            .append("\n  URL：")
            .append(evidence.url())
            .append("\n  来源：")
            .append(evidence.source())
            .append("\n  时效性：")
            .append(evidence.freshness())
            .append("\n  摘要：")
            .append(evidence.snippet())
            .append("\n  质量分：")
            .append(evidence.qualityScore())
            .append(" (")
            .append(evidence.qualityReason())
            .append(")")
            .append("\n");
      }
      builder.append("\n");
    }

    builder.append(
        "请使用最新的公开信号，帮助一位校招生比较这两个 Offer。"
            + "即使证据稀缺，也必须返回两个 Offer，并严格保留用户提供的 offerId 和 company。"
            + "最多使用 2 次搜索工具调用，并直接返回紧凑 JSON，不要输出 markdown 代码块。"
            + "所有面向用户的自然语言字段请使用简体中文。");
    return builder.toString();
  }

  private String domainFromUrl(String url) {
    try {
      return URI.create(url).getHost().replaceFirst("^www\\.", "");
    } catch (Exception ignored) {
      return url;
    }
  }

  private String sanitizeUrl(String value) {
    String normalized = normalizeText(value, "");
    if (normalized.isBlank()) {
      return "";
    }

    try {
      URI uri = URI.create(normalized);
      return uri.getScheme() != null && uri.getScheme().startsWith("http") ? normalized : "";
    } catch (Exception ignored) {
      return "";
    }
  }

  private String normalizeText(String value, String fallback) {
    if (value == null) {
      return fallback;
    }

    String trimmed = value.trim();
    if (trimmed.isBlank()) {
      return fallback;
    }

    String lower = trimmed.toLowerCase(Locale.ROOT);
    if ("...".equals(trimmed)
        || "…".equals(trimmed)
        || "tbd".equals(lower)
        || "n/a".equals(lower)
        || "null".equals(lower)) {
      return fallback;
    }

    return trimmed;
  }

  private String normalizeSignalValue(String value, String fallback) {
    String normalized = normalizeText(value, fallback);
    return normalized.isBlank() ? fallback : normalized;
  }

  private String normalizeCompany(String value) {
    return nullSafe(value).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
  }

  private String baseUrl() {
    String configured = nullSafe(properties.baseUrl());
    return configured.isBlank() ? DEFAULT_BASE_URL : configured;
  }

  private String model() {
    String configured = nullSafe(properties.model());
    return configured.isBlank() ? DEFAULT_MODEL : configured;
  }

  private String formulaUrl() {
    String configured = nullSafe(properties.formulaUri());
    String formulaUri = configured.isBlank() ? DEFAULT_FORMULA_URI : configured;
    return baseUrl() + "/formulas/" + formulaUri;
  }

  private boolean hasExternalStructuringModel() {
    return !nullSafe(properties.structuringApiKey()).isBlank()
        && !nullSafe(properties.structuringBaseUrl()).isBlank()
        && !nullSafe(properties.structuringModel()).isBlank();
  }

  private String structuringProviderLabel() {
    String configured = nullSafe(properties.structuringProvider());
    return configured.isBlank() ? DEFAULT_STRUCTURING_PROVIDER : configured;
  }

  private String structuringBaseUrl() {
    return nullSafe(properties.structuringBaseUrl()).replaceAll("/+$", "");
  }

  private String structuringModel() {
    return nullSafe(properties.structuringModel());
  }

  private int connectTimeoutSeconds() {
    return positiveOrDefault(properties.connectTimeoutSeconds(), 10);
  }

  private int requestTimeoutSeconds() {
    return positiveOrDefault(properties.requestTimeoutSeconds(), 20);
  }

  private int toolRequestTimeoutSeconds() {
    return Math.max(4, Math.min(requestTimeoutSeconds(), 8));
  }

  private int researchRequestTimeoutSeconds() {
    return Math.max(12, Math.min(requestTimeoutSeconds(), 24));
  }

  private int structuringRequestTimeoutSeconds() {
    return Math.max(8, Math.min(requestTimeoutSeconds(), 16));
  }

  private int researchTimeoutSeconds() {
    return properties.researchTimeoutSeconds() < 0 ? 0 : properties.researchTimeoutSeconds();
  }

  private int positiveOrDefault(int value, int fallback) {
    return value > 0 ? value : fallback;
  }

  private String formatStageTimings(List<ResearchStageTiming> stageTimings) {
    if (stageTimings == null || stageTimings.isEmpty()) {
      return "[]";
    }

    return stageTimings.stream()
        .map(
            stage ->
                stage.stage()
                    + "="
                    + stage.status()
                    + "@"
                    + stage.latencyMs()
                    + "ms")
        .reduce((left, right) -> left + ", " + right)
        .map(value -> "[" + value + "]")
        .orElse("[]");
  }

  private long elapsedMillis(long startedAt) {
    return (System.nanoTime() - startedAt) / 1_000_000;
  }

  private String nullSafe(String value) {
    return value == null ? "" : value.trim();
  }

  @FunctionalInterface
  private interface StageCall<T> {
    T execute() throws Exception;
  }
}
