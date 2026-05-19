package com.shichangwei.offerdecision;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class DecisionAnalysisControllerTest {

  @Autowired private MockMvc mockMvc;

  @Test
  void returnsAnalysisForValidPayload() throws Exception {
    String payload =
        """
        {
          "request": {
            "userProfile": {
              "target": "Become an AI platform engineer in 3 years",
              "riskAppetite": "medium",
              "priorities": ["technical depth", "growth speed"]
            },
            "offers": [
              {
                "id": "offer-a",
                "company": "Nova Agents",
                "role": "AI Platform Engineer",
                "city": "Shanghai",
                "compensation": "28k x 16",
                "stage": "startup",
                "domain": "Agent workflow platform",
                "workMode": "hybrid",
                "stack": "TypeScript, Python, Postgres, LangGraph",
                "managerSupport": "high",
                "executionStyle": "balanced",
                "jdSignals": "Own evals and orchestration",
                "notes": "small team"
              },
              {
                "id": "offer-b",
                "company": "CloudSuite",
                "role": "Backend Engineer",
                "city": "Hangzhou",
                "compensation": "24k x 16",
                "stage": "big-tech",
                "domain": "Mature SaaS collaboration suite",
                "workMode": "onsite",
                "stack": "Java, Spring, MySQL, Kafka",
                "managerSupport": "medium",
                "executionStyle": "structured",
                "jdSignals": "Core business APIs and reliability",
                "notes": "large org"
              }
            ]
          },
          "mode": "auto"
        }
        """;

    mockMvc
        .perform(
            post("/api/v1/decision/analyze")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.engine.mode").value("heuristic"))
        .andExpect(jsonPath("$.snapshots[0].company").isNotEmpty())
        .andExpect(jsonPath("$.dimensions.length()").value(5));
  }

  @Test
  void returnsCapabilities() throws Exception {
    mockMvc
        .perform(get("/api/v1/decision/capabilities"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.modes.length()").value(3))
        .andExpect(jsonPath("$.modes[0].mode").value("heuristic"))
        .andExpect(jsonPath("$.liveProvider.provider").isNotEmpty())
        .andExpect(jsonPath("$.retrieval.enabled").exists());
  }
}
