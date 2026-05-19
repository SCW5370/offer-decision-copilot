package com.shichangwei.offerdecision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class RetrievalFirstLiveResearchProviderTest {

  @Test
  void compactsEvidencePerCompanyAndTruncatesLongFields() {
    RetrievalFirstLiveResearchProvider provider =
        new RetrievalFirstLiveResearchProvider(
            new LiveResearchProperties(
                true,
                "kimi",
                "kimi-k2.6",
                "key",
                "https://api.moonshot.cn/v1",
                "moonshot/web-search:latest",
                10,
                24,
                42,
                "openai-compatible",
                "minimax/minimax-m2.5",
                "struct-key",
                "https://api.qnaigc.com/v1"),
            mock(LiveSearchProvider.class),
            new ObjectMapper());

    List<RetrievedEvidence> compacted =
        provider.compactEvidenceForStructuring(
            mockDecisionRequest(),
            List.of(
                evidence(
                    "Nova Agents",
                    "https://nova.example/fresh-1",
                    88,
                    "fresh",
                    "A".repeat(140),
                    "B".repeat(260)),
                evidence(
                    "Nova Agents",
                    "https://nova.example/fresh-2",
                    82,
                    "fresh",
                    "C".repeat(120),
                    "D".repeat(220)),
                evidence(
                    "Nova Agents",
                    "https://nova.example/older",
                    95,
                    "unclear",
                    "Older title",
                    "Older snippet"),
                evidence(
                    "CloudSuite",
                    "https://cloud.example/fresh-1",
                    79,
                    "fresh",
                    "E".repeat(110),
                    "F".repeat(240)),
                evidence(
                    "CloudSuite",
                    "https://cloud.example/fresh-2",
                    72,
                    "fresh",
                    "Short title",
                    "Short snippet"),
                evidence(
                    "CloudSuite",
                    "https://cloud.example/older",
                    90,
                    "unclear",
                    "Another old title",
                    "Another old snippet")));

    assertEquals(4, compacted.size());
    assertEquals(2, compacted.stream().filter(item -> item.company().equals("Nova Agents")).count());
    assertEquals(2, compacted.stream().filter(item -> item.company().equals("CloudSuite")).count());
    assertTrue(compacted.stream().noneMatch(item -> item.url().endsWith("/older")));
    assertTrue(compacted.stream().allMatch(item -> item.title().length() <= 90));
    assertTrue(compacted.stream().allMatch(item -> item.snippet().length() <= 180));
  }

  @Test
  void parsesJsonWrappedInCodeFence() throws Exception {
    RetrievalFirstLiveResearchProvider provider = provider();

    JsonNode parsed =
        provider.parseStructuredText(
            """
            ```json
            {"marketTakeaway":"ok","companies":[],"sources":[]}
            ```
            """);

    assertNotNull(parsed);
    assertEquals("ok", parsed.path("marketTakeaway").asText());
  }

  @Test
  void parsesJsonWhenProviderAddsLeadingExplanation() throws Exception {
    RetrievalFirstLiveResearchProvider provider = provider();

    JsonNode parsed =
        provider.parseStructuredText(
            """
            以下是结构化结果，请直接使用：
            {"marketTakeaway":"rescued","companies":[],"sources":[]}
            以上为最终 JSON。
            """);

    assertNotNull(parsed);
    assertEquals("rescued", parsed.path("marketTakeaway").asText());
  }

  private RetrievalFirstLiveResearchProvider provider() {
    return new RetrievalFirstLiveResearchProvider(
        new LiveResearchProperties(
            true,
            "kimi",
            "kimi-k2.6",
            "key",
            "https://api.moonshot.cn/v1",
            "moonshot/web-search:latest",
            10,
            24,
            42,
            "openai-compatible",
            "minimax/minimax-m2.5",
            "struct-key",
            "https://api.qnaigc.com/v1"),
        mock(LiveSearchProvider.class),
        new ObjectMapper());
  }

  private DecisionRequest mockDecisionRequest() {
    return new DecisionRequest(
        new UserProfile("AI 平台工程师", "medium", List.of("technical depth", "career upside")),
        List.of(
            new OfferInput(
                "offer-a",
                "Nova Agents",
                "AI 平台工程师",
                "上海",
                "28k x 16",
                "startup",
                "Agent infra",
                "hybrid",
                "Java, Spring",
                "high",
                "balanced",
                "Agent 平台",
                "小团队"),
            new OfferInput(
                "offer-b",
                "CloudSuite",
                "后端工程师",
                "杭州",
                "30k x 15",
                "big-tech",
                "Cloud platform",
                "onsite",
                "Java, MySQL",
                "medium",
                "structured",
                "稳定性治理",
                "大组织")));
  }

  private RetrievedEvidence evidence(
      String company,
      String url,
      int qualityScore,
      String freshness,
      String title,
      String snippet) {
    return new RetrievedEvidence(company, title, url, snippet, "公开来源标签可能很长需要截断", freshness, qualityScore, "ok");
  }
}
