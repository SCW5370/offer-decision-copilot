package com.shichangwei.offerdecision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ConfigurableLiveSearchProvider implements LiveSearchProvider {

  private static final Logger log = LoggerFactory.getLogger(ConfigurableLiveSearchProvider.class);
  private static final String GENERIC_JSON_PROVIDER = "generic-json";
  private static final String TAVILY_PROVIDER = "tavily";
  private static final String TAVILY_ENDPOINT = "https://api.tavily.com/search";

  private final RetrievalProperties properties;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;
  private final Clock clock;
  private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

  @Autowired
  public ConfigurableLiveSearchProvider(
      RetrievalProperties properties, ObjectMapper objectMapper) {
    this(properties, objectMapper, Clock.systemUTC());
  }

  ConfigurableLiveSearchProvider(
      RetrievalProperties properties, ObjectMapper objectMapper, Clock clock) {
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.clock = clock;
    this.httpClient =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(requestTimeoutSeconds())).build();
  }

  @Override
  public boolean isEnabled() {
    String provider = provider();
    if (!properties.enabled()) {
      return false;
    }
    if (GENERIC_JSON_PROVIDER.equalsIgnoreCase(provider)) {
      return !nullSafe(properties.endpoint()).isBlank();
    }
    if (TAVILY_PROVIDER.equalsIgnoreCase(provider)) {
      return !nullSafe(properties.apiKey()).isBlank();
    }
    return false;
  }

  @Override
  public List<RetrievedEvidence> search(DecisionRequest request) {
    if (!isEnabled()) {
      return List.of();
    }

    List<RetrievedEvidence> evidence = new ArrayList<>();
    for (SearchQuery query : buildQueries(request)) {
      if (evidence.size() >= maxResults()) {
        break;
      }
      try {
        evidence.addAll(searchOne(query));
      } catch (Exception error) {
        log.warn("Live search failed for company={} query={}: {}", query.company(), query.query(), error.getMessage());
      }
    }

    return qualityGate(evidence).stream().limit(maxResults()).toList();
  }

  private List<SearchQuery> buildQueries(DecisionRequest request) {
    List<SearchQuery> queries = new ArrayList<>();
    for (OfferInput offer : request.offers()) {
      queries.add(
          new SearchQuery(
              offer.company(),
              offer.company()
                  + " "
                  + offer.domain()
                  + " hiring layoffs funding product launch engineering blog recent"));
    }
    return queries;
  }

  private List<RetrievedEvidence> searchOne(SearchQuery query)
      throws IOException, InterruptedException {
    Optional<List<RetrievedEvidence>> cached = cachedEvidence(query);
    if (cached.isPresent()) {
      return cached.get();
    }

    List<RetrievedEvidence> results =
        TAVILY_PROVIDER.equalsIgnoreCase(provider()) ? searchOneTavily(query) : searchOneGenericJson(query);
    writeCache(query, results);
    return results;
  }

  private List<RetrievedEvidence> searchOneGenericJson(SearchQuery query)
      throws IOException, InterruptedException {
    ObjectNode requestBody = objectMapper.createObjectNode();
    requestBody.put("query", query.query());
    requestBody.put("company", query.company());
    requestBody.put("maxResults", Math.max(1, maxResults() / 2));

    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(properties.endpoint()))
            .timeout(Duration.ofSeconds(requestTimeoutSeconds()))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)));

    if (!nullSafe(properties.apiKey()).isBlank()) {
      builder.header("Authorization", "Bearer " + properties.apiKey());
    }

    HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new IllegalStateException(
          "status=" + response.statusCode() + " body=" + truncate(response.body(), 180));
    }

    return parseResults(query.company(), objectMapper.readTree(response.body()));
  }

  private List<RetrievedEvidence> searchOneTavily(SearchQuery query)
      throws IOException, InterruptedException {
    ObjectNode requestBody = objectMapper.createObjectNode();
    requestBody.put("query", query.query());
    requestBody.put("topic", tavilyTopic());
    requestBody.put("search_depth", tavilySearchDepth());
    requestBody.put("time_range", tavilyTimeRange());
    requestBody.put("max_results", Math.max(1, Math.min(20, maxResults())));
    requestBody.put("include_answer", false);
    requestBody.put("include_raw_content", false);
    requestBody.put("include_images", false);
    requestBody.put("include_favicon", false);
    if (!nullSafe(properties.tavilyCountry()).isBlank()) {
      requestBody.put("country", properties.tavilyCountry().trim().toLowerCase(Locale.ROOT));
    }

    HttpRequest request =
        HttpRequest.newBuilder(URI.create(tavilyEndpoint()))
            .timeout(Duration.ofSeconds(requestTimeoutSeconds()))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + properties.apiKey())
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
            .build();

    HttpResponse<String> response =
        httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new IllegalStateException(
          "status=" + response.statusCode() + " body=" + truncate(response.body(), 180));
    }

    return parseTavilyResults(query.company(), objectMapper.readTree(response.body()));
  }

  List<RetrievedEvidence> parseResults(String company, JsonNode root) {
    JsonNode results = root.path("results");
    if (!results.isArray()) {
      results = root.path("data");
    }
    if (!results.isArray()) {
      return List.of();
    }

    List<RetrievedEvidence> parsed = new ArrayList<>();
    for (JsonNode item : results) {
      String url = normalizeText(firstText(item, "url", "link"), "");
      if (url.isBlank()) {
        continue;
      }
      String normalizedUrl = normalizeUrl(url);
      if (normalizedUrl.isBlank()) {
        continue;
      }
      parsed.add(
          toRetrievedEvidence(
              company,
              normalizeText(firstText(item, "title", "name"), "Untitled source"),
              normalizedUrl,
              normalizeText(
                  firstText(item, "snippet", "content", "description"), "No snippet returned."),
              normalizeText(firstText(item, "source", "domain"), domainFromUrl(normalizedUrl)),
              normalizeText(firstText(item, "freshness", "publishedAt", "date"), "unclear")));
    }
    return parsed;
  }

  List<RetrievedEvidence> parseTavilyResults(String company, JsonNode root) {
    JsonNode results = root.path("results");
    if (!results.isArray()) {
      return List.of();
    }

    List<RetrievedEvidence> parsed = new ArrayList<>();
    for (JsonNode item : results) {
      parsed.add(
          toRetrievedEvidence(
              company,
              normalizeText(firstText(item, "title", "name"), "Untitled source"),
              normalizeText(firstText(item, "url", "link"), ""),
              normalizeText(firstText(item, "content", "snippet", "description"), "No snippet returned."),
              normalizeText(firstText(item, "source", "domain"), domainFromUrl(firstText(item, "url", "link"))),
              normalizeText(
                  firstText(item, "published_date", "publishedAt", "publishedDate", "date", "freshness"),
                  "unclear")));
    }
    return parsed;
  }

  RetrievedEvidence toRetrievedEvidence(
      String company, String title, String url, String snippet, String source, String freshness) {
    String normalizedUrl = normalizeUrl(url);
    if (normalizedUrl.isBlank()) {
      return new RetrievedEvidence(
          company,
          normalizeText(title, "Untitled source"),
          "",
          normalizeText(snippet, "No snippet returned."),
          normalizeText(source, "source"),
          normalizeText(freshness, "unclear"),
          0,
          "invalid-url");
    }

    String normalizedTitle = normalizeText(title, "Untitled source");
    String normalizedSnippet = normalizeText(snippet, "No snippet returned.");
    String normalizedSource = normalizeText(source, domainFromUrl(normalizedUrl));
    String normalizedFreshness = normalizeText(freshness, "unclear");
    EvidenceQuality quality =
        scoreEvidence(
            company,
            normalizedTitle,
            normalizedUrl,
            normalizedSnippet,
            normalizedSource,
            normalizedFreshness);
    return new RetrievedEvidence(
        company,
        normalizedTitle,
        normalizedUrl,
        normalizedSnippet,
        normalizedSource,
        normalizedFreshness,
        quality.score(),
        quality.reason());
  }

  List<RetrievedEvidence> qualityGate(List<RetrievedEvidence> evidence) {
    Map<String, RetrievedEvidence> dedupedByUrl = new LinkedHashMap<>();
    for (RetrievedEvidence item : evidence) {
      if (item.qualityScore() < minQualityScore()) {
        log.debug(
            "Dropped low-quality retrieval evidence company={} url={} score={} reason={}",
            item.company(),
            item.url(),
            item.qualityScore(),
            item.qualityReason());
        continue;
      }
      dedupedByUrl.merge(
          item.url(),
          item,
          (current, candidate) ->
              candidate.qualityScore() > current.qualityScore() ? candidate : current);
    }

    return dedupedByUrl.values().stream()
        .sorted((left, right) -> Integer.compare(right.qualityScore(), left.qualityScore()))
        .toList();
  }

  private EvidenceQuality scoreEvidence(
      String company, String title, String url, String snippet, String source, String freshness) {
    int score = 0;
    List<String> reasons = new ArrayList<>();

    if (!normalizeUrl(url).isBlank()) {
      score += 35;
      reasons.add("valid-url");
    }
    if (snippet.length() >= 80) {
      score += 20;
      reasons.add("substantive-snippet");
    } else if (snippet.length() >= 40) {
      score += 10;
      reasons.add("thin-snippet");
    }
    if (mentionsCompany(company, title + " " + snippet + " " + source + " " + url)) {
      score += 25;
      reasons.add("company-match");
    }
    FreshnessAssessment freshnessAssessment = assessFreshness(freshness);
    score += freshnessAssessment.scoreDelta();
    if (!freshnessAssessment.reason().isBlank()) {
      reasons.add(freshnessAssessment.reason());
    }
    if (!"source".equalsIgnoreCase(source) && !source.isBlank()) {
      score += 10;
      reasons.add("source-label");
    }

    return new EvidenceQuality(Math.min(score, 100), String.join(",", reasons));
  }

  private String firstText(JsonNode node, String... fields) {
    for (String field : fields) {
      if (node.hasNonNull(field)) {
        String value = node.path(field).asText("");
        if (!value.isBlank()) {
          return value;
        }
      }
    }
    return "";
  }

  private String domainFromUrl(String url) {
    try {
      String host = URI.create(url).getHost();
      return host == null ? "source" : host.replaceFirst("^www\\.", "");
    } catch (Exception ignored) {
      return "source";
    }
  }

  private String normalizeUrl(String value) {
    String normalized = normalizeText(value, "");
    if (normalized.isBlank()) {
      return "";
    }

    try {
      URI uri = URI.create(normalized);
      if (uri.getScheme() == null || uri.getHost() == null) {
        return "";
      }
      String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
      if (!"http".equals(scheme) && !"https".equals(scheme)) {
        return "";
      }
      URI cleaned =
          new URI(
              scheme,
              uri.getUserInfo(),
              uri.getHost().toLowerCase(Locale.ROOT),
              uri.getPort(),
              stripTrailingSlash(uri.getPath()),
              uri.getQuery(),
              null);
      return cleaned.toString();
    } catch (Exception ignored) {
      return "";
    }
  }

  private String stripTrailingSlash(String path) {
    if (path == null || path.isBlank() || "/".equals(path)) {
      return "";
    }
    return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
  }

  private boolean mentionsCompany(String company, String haystack) {
    String normalizedHaystack = normalizeCompany(haystack);
    for (String token : companyTokens(company)) {
      if (normalizedHaystack.contains(token)) {
        return true;
      }
    }
    return false;
  }

  private List<String> companyTokens(String company) {
    return List.of(normalizeCompany(company).split("\\s+")).stream()
        .filter(token -> token.length() >= 3)
        .filter(token -> !List.of("inc", "ltd", "llc", "corp", "company", "tech").contains(token))
        .toList();
  }

  private String normalizeCompany(String value) {
    return normalizeText(value, "")
        .toLowerCase(Locale.ROOT)
        .replaceAll("[^a-z0-9]+", " ")
        .trim();
  }

  private FreshnessAssessment assessFreshness(String freshness) {
    String normalized = normalizeText(freshness, "").toLowerCase(Locale.ROOT);
    if (normalized.isBlank() || "unclear".equals(normalized)) {
      return new FreshnessAssessment(0, "freshness-unclear");
    }
    if (normalized.contains("recent") || normalized.contains("fresh")) {
      return new FreshnessAssessment(10, "freshness-hint");
    }

    Optional<LocalDate> date = parseDate(normalized);
    if (date.isEmpty()) {
      return new FreshnessAssessment(0, "freshness-unparsed");
    }

    LocalDate cutoff = LocalDate.now(clock).minusDays(maxFreshnessAgeDays());
    if (date.get().isBefore(cutoff)) {
      return new FreshnessAssessment(-25, "stale-date");
    }
    return new FreshnessAssessment(10, "fresh-date");
  }

  private Optional<LocalDate> parseDate(String value) {
    for (String token : value.split("[^0-9-]+")) {
      if (token.matches("\\d{4}-\\d{1,2}-\\d{1,2}")) {
        try {
          return Optional.of(LocalDate.parse(token));
        } catch (Exception ignored) {
          return Optional.empty();
        }
      }
      if (token.matches("\\d{4}-\\d{1,2}")) {
        try {
          return Optional.of(LocalDate.parse(token + "-01"));
        } catch (Exception ignored) {
          return Optional.empty();
        }
      }
      if (token.matches("\\d{4}")) {
        try {
          return Optional.of(LocalDate.parse(token + "-01-01"));
        } catch (Exception ignored) {
          return Optional.empty();
        }
      }
    }
    return Optional.empty();
  }

  private int requestTimeoutSeconds() {
    return properties.requestTimeoutSeconds() > 0 ? properties.requestTimeoutSeconds() : 8;
  }

  private int maxResults() {
    return properties.maxResults() > 0 ? properties.maxResults() : 6;
  }

  private int minQualityScore() {
    return properties.minQualityScore() > 0 ? properties.minQualityScore() : 60;
  }

  private int cacheTtlMinutes() {
    return properties.cacheTtlMinutes() < 0 ? 0 : properties.cacheTtlMinutes();
  }

  private int maxFreshnessAgeDays() {
    return properties.maxFreshnessAgeDays() > 0 ? properties.maxFreshnessAgeDays() : 180;
  }

  private String provider() {
    return nullSafe(properties.provider());
  }

  private String tavilyEndpoint() {
    return nullSafe(properties.endpoint()).isBlank() ? TAVILY_ENDPOINT : properties.endpoint().trim();
  }

  private String tavilyTopic() {
    String configured = nullSafe(properties.tavilyTopic()).toLowerCase(Locale.ROOT);
    return switch (configured) {
      case "news", "finance" -> configured;
      default -> "general";
    };
  }

  private String tavilySearchDepth() {
    String configured = nullSafe(properties.tavilySearchDepth()).toLowerCase(Locale.ROOT);
    return switch (configured) {
      case "advanced", "fast", "ultra-fast" -> configured;
      default -> "basic";
    };
  }

  private String tavilyTimeRange() {
    String configured = nullSafe(properties.tavilyTimeRange()).toLowerCase(Locale.ROOT);
    return switch (configured) {
      case "day", "week", "month", "year", "d", "w", "m", "y" -> configured;
      default -> "month";
    };
  }

  private Optional<List<RetrievedEvidence>> cachedEvidence(SearchQuery query) {
    if (cacheTtlMinutes() == 0) {
      return Optional.empty();
    }

    String key = cacheKey(query);
    CacheEntry entry = cache.get(key);
    if (entry == null) {
      return Optional.empty();
    }

    Instant expiresAt = entry.createdAt().plus(Duration.ofMinutes(cacheTtlMinutes()));
    if (Instant.now(clock).isAfter(expiresAt)) {
      cache.remove(key);
      return Optional.empty();
    }

    log.debug("Retrieval cache hit company={} query={}", query.company(), query.query());
    return Optional.of(entry.evidence());
  }

  private void writeCache(SearchQuery query, List<RetrievedEvidence> evidence) {
    if (cacheTtlMinutes() == 0) {
      return;
    }
    cache.put(cacheKey(query), new CacheEntry(List.copyOf(evidence), Instant.now(clock)));
  }

  private String cacheKey(SearchQuery query) {
    return normalizeCompany(query.company()) + "::" + query.query().toLowerCase(Locale.ROOT).trim();
  }

  private String normalizeText(String value, String fallback) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    return value.trim();
  }

  private String truncate(String value, int maxLength) {
    if (value == null || value.length() <= maxLength) {
      return value == null ? "" : value;
    }
    return value.substring(0, maxLength - 3).trim() + "...";
  }

  private String nullSafe(String value) {
    return value == null ? "" : value.trim();
  }

  private record EvidenceQuality(int score, String reason) {}

  private record FreshnessAssessment(int scoreDelta, String reason) {}

  private record CacheEntry(List<RetrievedEvidence> evidence, Instant createdAt) {}
}
