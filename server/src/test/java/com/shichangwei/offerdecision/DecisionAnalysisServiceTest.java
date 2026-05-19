package com.shichangwei.offerdecision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DecisionAnalysisServiceTest {

  @Test
  void mergesLiveResearchIntoScoresAndFreshness() {
    HeuristicDecisionAnalyzer analyzer = new HeuristicDecisionAnalyzer();
    LiveResearchProvider provider =
        new LiveResearchProvider() {
          @Override
          public boolean isEnabled() {
            return true;
          }

          @Override
          public Optional<LiveResearchReport> research(DecisionRequest request) {
            return Optional.of(
                new LiveResearchReport(
                    "kimi",
                    "kimi-k2.6",
                    "Recent public signals are more supportive for Nova Agents than CloudSuite.",
                    List.of(
                        new LiveCompanyResearch(
                            "Nova Agents",
                            "Hiring and product momentum are supportive.",
                            "high",
                            "positive",
                            "positive",
                            "positive",
                            List.of(
                                new ResearchSignal(
                                    "Recent hiring push",
                                    "The company is still hiring for platform roles.",
                                    "This suggests the team is still investing in core engineering capacity.",
                                    "Official careers page",
                                    "https://example.com/nova",
                                    "fresh")),
                            List.of("Visible investment in platform work."),
                            List.of("Need to verify whether headcount is concentrated in one team."),
                            List.of("Ask whether the platform team is still expanding this quarter.")),
                        new LiveCompanyResearch(
                            "CloudSuite",
                            "Public signals are more cautious than bullish.",
                            "medium",
                            "negative",
                            "mixed",
                            "mixed",
                            List.of(
                                new ResearchSignal(
                                    "Mixed public signal",
                                    "The team narrative looks stable but not especially expansionary.",
                                    "This lowers the chance that the role will broaden quickly for a new grad.",
                                    "News coverage",
                                    "https://example.com/cloudsuite",
                                    "fresh")),
                            List.of("Potentially steadier onboarding."),
                            List.of("Recent hiring pace may be slowing."),
                            List.of("Ask how much the role scope changed in the last 6 months."))),
                    List.of(
                        new ResearchSource(
                            "Nova Agents",
                            "Official careers page",
                            "https://example.com/nova",
                            "example.com"),
                        new ResearchSource(
                            "CloudSuite",
                            "News coverage",
                            "https://example.com/cloudsuite",
                            "example.com")),
                    1830,
                    List.of(new ResearchStageTiming("kimi.research-conversation", 1830, "done"))));
          }
        };

    DecisionAnalysisService service = new DecisionAnalysisService(analyzer, provider);
    DecisionRequest request =
        new DecisionRequest(
            new UserProfile(
                "Become an AI platform engineer in 3 years",
                "medium",
                List.of("technical depth", "growth speed")),
            List.of(
                new OfferInput(
                    "offer-a",
                    "Nova Agents",
                    "AI Platform Engineer",
                    "Shanghai",
                    "28k x 16",
                    "startup",
                    "Agent workflow platform",
                    "hybrid",
                    "TypeScript, Python, Postgres, LangGraph",
                    "high",
                    "balanced",
                    "Own evals and orchestration",
                    "small team"),
                new OfferInput(
                    "offer-b",
                    "CloudSuite",
                    "Backend Engineer",
                    "Hangzhou",
                    "24k x 16",
                    "big-tech",
                    "Mature SaaS collaboration suite",
                    "onsite",
                    "Java, Spring, MySQL, Kafka",
                    "medium",
                    "structured",
                    "Core business APIs and reliability",
                    "large org")));

    DecisionAnalysis analysis = service.analyze(request, "auto");

    assertEquals("live", analysis.engine().mode());
    assertNotNull(analysis.liveResearch());
    assertTrue(
        analysis.pipeline().stream().anyMatch(step -> "实时核验".equals(step.title())));
    assertTrue(
        analysis.dimensions().stream()
            .flatMap(dimension -> dimension.evidence().stream())
            .anyMatch(evidence -> "web research".equals(evidence.sourceType())));
    assertTrue(
        analysis.freshness().needsVerification().stream()
            .anyMatch(item -> item.contains("platform team is still expanding")));
  }
}
