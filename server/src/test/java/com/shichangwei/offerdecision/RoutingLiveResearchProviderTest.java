package com.shichangwei.offerdecision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RoutingLiveResearchProviderTest {

  @Test
  void prefersRetrievalFirstProviderForAutoModeWhenAvailable() {
    RetrievalFirstLiveResearchProvider retrievalFirst =
        mock(RetrievalFirstLiveResearchProvider.class);
    KimiLiveResearchProvider kimi = mock(KimiLiveResearchProvider.class);
    DeterministicLiveResearchProvider deterministic =
        mock(DeterministicLiveResearchProvider.class);

    LiveResearchProperties properties =
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
            "https://api.qnaigc.com/v1");

    LiveResearchReport report =
        new LiveResearchReport(
            "retrieval-first + openai-compatible",
            "minimax/minimax-m2.5",
            "retrieval-first result",
            List.of(),
            List.of(),
            123,
            List.of());

    when(retrievalFirst.isEnabled()).thenReturn(true);
    when(retrievalFirst.providerLabel()).thenReturn("retrieval-first + openai-compatible");
    when(retrievalFirst.research(any())).thenReturn(Optional.of(report));

    RoutingLiveResearchProvider provider =
        new RoutingLiveResearchProvider(properties, retrievalFirst, kimi, deterministic);

    Optional<LiveResearchReport> result = provider.research(mockDecisionRequest(), "auto");

    assertTrue(result.isPresent());
    assertSame(report, result.get());
    assertEquals("retrieval-first + openai-compatible", provider.providerLabel("auto"));
  }

  @Test
  void keepsDemoModeOnDeterministicPathEvenWhenRetrievalFirstIsAvailable() {
    RetrievalFirstLiveResearchProvider retrievalFirst =
        mock(RetrievalFirstLiveResearchProvider.class);
    KimiLiveResearchProvider kimi = mock(KimiLiveResearchProvider.class);
    DeterministicLiveResearchProvider deterministic =
        mock(DeterministicLiveResearchProvider.class);

    LiveResearchProperties properties =
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
            "https://api.qnaigc.com/v1");

    LiveResearchReport report =
        new LiveResearchReport(
            "deterministic retrieval",
            "none",
            "demo result",
            List.of(),
            List.of(),
            12,
            List.of());

    when(retrievalFirst.isEnabled()).thenReturn(true);
    when(deterministic.isDemoAvailable()).thenReturn(true);
    when(deterministic.researchDemo(any())).thenReturn(Optional.of(report));

    RoutingLiveResearchProvider provider =
        new RoutingLiveResearchProvider(properties, retrievalFirst, kimi, deterministic);

    Optional<LiveResearchReport> result = provider.research(mockDecisionRequest(), "demo");

    assertTrue(result.isPresent());
    assertSame(report, result.get());
    assertEquals("deterministic retrieval", provider.providerLabel("demo"));
  }

  private DecisionRequest mockDecisionRequest() {
    return new DecisionRequest(
        new UserProfile("AI 平台工程师", "medium", List.of("technical depth")),
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
}
