package com.shichangwei.offerdecision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConfigurableLiveSearchProviderTest {

  @Test
  void parsesGenericJsonSearchResults() throws Exception {
    ObjectMapper objectMapper = new ObjectMapper();
    ConfigurableLiveSearchProvider provider =
        new ConfigurableLiveSearchProvider(
            new RetrievalProperties(
                true,
                "generic-json",
                "https://search.example/api",
                "",
                2,
                4,
                60,
                30,
                180,
                "general",
                "basic",
                "month",
                ""),
            objectMapper);

    List<RetrievedEvidence> evidence =
        provider.parseResults(
            "Nova Agents",
            objectMapper.readTree(
                """
                {
                  "results": [
                    {
                      "title": "Nova Agents launches eval platform",
                      "url": "https://example.com/nova-evals",
                      "snippet": "Nova Agents is investing in an evaluation platform for agent workflows.",
                      "source": "example.com",
                      "freshness": "2026-05-16"
                    }
                  ]
                }
                """));

    assertEquals(1, evidence.size());
    assertEquals("Nova Agents", evidence.getFirst().company());
    assertEquals("Nova Agents launches eval platform", evidence.getFirst().title());
    assertEquals("https://example.com/nova-evals", evidence.getFirst().url());
    assertTrue(evidence.getFirst().snippet().contains("platform"));
    assertTrue(evidence.getFirst().qualityScore() >= 60);
    assertTrue(evidence.getFirst().qualityReason().contains("company-match"));
  }

  @Test
  void qualityGateFiltersInvalidThinAndDuplicateEvidence() throws Exception {
    ObjectMapper objectMapper = new ObjectMapper();
    ConfigurableLiveSearchProvider provider =
        new ConfigurableLiveSearchProvider(
            new RetrievalProperties(
                true,
                "generic-json",
                "https://search.example/api",
                "",
                2,
                4,
                60,
                30,
                180,
                "general",
                "basic",
                "month",
                ""),
            objectMapper);

    List<RetrievedEvidence> evidence =
        provider.qualityGate(
            provider.parseResults(
                "Nova Agents",
                objectMapper.readTree(
                    """
                    {
                      "results": [
                        {
                          "title": "Nova Agents hiring platform engineers",
                          "url": "https://example.com/nova-hiring#section",
                          "snippet": "Nova Agents is hiring platform engineers to build evaluation and orchestration systems for agent workflows.",
                          "source": "example.com",
                          "freshness": "2026-05-16"
                        },
                        {
                          "title": "Nova Agents hiring platform engineers",
                          "url": "https://example.com/nova-hiring/",
                          "snippet": "Nova Agents expands its platform engineering hiring with new evaluation infrastructure roles.",
                          "source": "example.com",
                          "freshness": "2026-05-16"
                        },
                        {
                          "title": "Random market note",
                          "url": "notaurl",
                          "snippet": "bad",
                          "source": "unknown",
                          "freshness": "unclear"
                        },
                        {
                          "title": "Generic SaaS news",
                          "url": "https://example.com/generic",
                          "snippet": "A short unrelated note.",
                          "source": "example.com",
                          "freshness": "unclear"
                        }
                      ]
                    }
                    """)));

    assertEquals(1, evidence.size());
    assertEquals("https://example.com/nova-hiring", evidence.getFirst().url());
    assertTrue(evidence.getFirst().qualityScore() >= 60);
  }

  @Test
  void staleDatesLowerEvidenceQuality() throws Exception {
    ObjectMapper objectMapper = new ObjectMapper();
    Clock clock = Clock.fixed(Instant.parse("2026-05-16T00:00:00Z"), ZoneOffset.UTC);
    ConfigurableLiveSearchProvider provider =
        new ConfigurableLiveSearchProvider(
            new RetrievalProperties(
                true,
                "generic-json",
                "https://search.example/api",
                "",
                2,
                4,
                60,
                30,
                180,
                "general",
                "basic",
                "month",
                ""),
            objectMapper,
            clock);

    List<RetrievedEvidence> evidence =
        provider.parseResults(
            "Nova Agents",
            objectMapper.readTree(
                """
                {
                  "results": [
                    {
                      "title": "Nova Agents expands platform engineering",
                      "url": "https://example.com/nova-old",
                      "snippet": "Nova Agents expanded platform engineering hiring for evaluation and orchestration systems.",
                      "source": "example.com",
                      "freshness": "2023-01-01"
                    },
                    {
                      "title": "Nova Agents expands platform engineering",
                      "url": "https://example.com/nova-current",
                      "snippet": "Nova Agents expanded platform engineering hiring for evaluation and orchestration systems.",
                      "source": "example.com",
                      "freshness": "2026-05-16"
                    }
                  ]
                }
                """));

    RetrievedEvidence stale = evidence.get(0);
    RetrievedEvidence current = evidence.get(1);

    assertTrue(stale.qualityReason().contains("stale-date"));
    assertTrue(current.qualityReason().contains("fresh-date"));
    assertTrue(current.qualityScore() > stale.qualityScore());
  }

  @Test
  void parsesTavilySearchResults() throws Exception {
    ObjectMapper objectMapper = new ObjectMapper();
    ConfigurableLiveSearchProvider provider =
        new ConfigurableLiveSearchProvider(
            new RetrievalProperties(
                true,
                "tavily",
                "",
                "tvly-test",
                2,
                4,
                60,
                30,
                180,
                "general",
                "basic",
                "month",
                "china"),
            objectMapper);

    List<RetrievedEvidence> evidence =
        provider.parseTavilyResults(
            "Nova Agents",
            objectMapper.readTree(
                """
                {
                  "results": [
                    {
                      "title": "Nova Agents expands platform engineering hiring",
                      "url": "https://news.example.com/nova-agents-platform-hiring",
                      "content": "Nova Agents is hiring platform and evaluation engineers to improve agent orchestration infrastructure.",
                      "score": 0.91,
                      "published_date": "2026-05-12"
                    }
                  ]
                }
                """));

    assertEquals(1, evidence.size());
    assertEquals("Nova Agents expands platform engineering hiring", evidence.getFirst().title());
    assertEquals(
        "https://news.example.com/nova-agents-platform-hiring", evidence.getFirst().url());
    assertTrue(evidence.getFirst().snippet().contains("evaluation engineers"));
    assertTrue(evidence.getFirst().qualityReason().contains("fresh-date"));
  }
}
