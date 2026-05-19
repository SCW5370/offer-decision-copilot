package com.shichangwei.offerdecision;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.isOneOf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class OfferWorkspaceControllerTest {

  @Autowired private MockMvc mockMvc;

  @Test
  void createsWorkspaceRunAndSurfacesRecentRun() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/workspace/default/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "mode": "demo",
                      "request": {
                        "userProfile": {
                          "target": "希望 3 年内成长为 AI 平台/后端工程师",
                          "riskAppetite": "medium",
                          "priorities": ["technical depth", "growth speed", "mentorship"]
                        },
                        "offers": [
                          {
                            "id": "offer-a",
                            "company": "Nova Agents",
                            "role": "AI 平台工程师",
                            "city": "上海",
                            "compensation": "28k x 16",
                            "stage": "startup",
                            "domain": "Agent 工作流平台",
                            "workMode": "hybrid",
                            "stack": "TypeScript, Python, Postgres, LangGraph",
                            "managerSupport": "high",
                            "executionStyle": "balanced",
                            "jdSignals": "负责评测、编排与平台能力建设",
                            "notes": "小团队"
                          },
                          {
                            "id": "offer-b",
                            "company": "CloudSuite",
                            "role": "后端工程师",
                            "city": "杭州",
                            "compensation": "24k x 16",
                            "stage": "big-tech",
                            "domain": "成熟协作 SaaS",
                            "workMode": "onsite",
                            "stack": "Java, Spring, MySQL, Kafka",
                            "managerSupport": "medium",
                            "executionStyle": "structured",
                            "jdSignals": "负责核心业务 API 与稳定性建设",
                            "notes": "大组织"
                          }
                        ]
                      }
                    }
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.analysis.engine.mode").value("live"))
        .andExpect(jsonPath("$.analysis.recommendation.winner").value("Nova Agents"));

    mockMvc
        .perform(get("/api/v1/workspace/default"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.recentRuns.length()").value(greaterThanOrEqualTo(1)));
  }

  @Test
  void enqueuesWorkspaceRunJobAndEventuallyCompletes() throws Exception {
    MvcResult enqueueResult =
        mockMvc
            .perform(
                post("/api/v1/workspace/default/run-jobs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "mode": "demo",
                          "request": {
                            "userProfile": {
                              "target": "希望 3 年内成长为 AI 平台/后端工程师",
                              "riskAppetite": "medium",
                              "priorities": ["technical depth", "growth speed", "mentorship"]
                            },
                            "offers": [
                              {
                                "id": "offer-a",
                                "company": "Nova Agents",
                                "role": "AI 平台工程师",
                                "city": "上海",
                                "compensation": "28k x 16",
                                "stage": "startup",
                                "domain": "Agent 工作流平台",
                                "workMode": "hybrid",
                                "stack": "TypeScript, Python, Postgres, LangGraph",
                                "managerSupport": "high",
                                "executionStyle": "balanced",
                                "jdSignals": "负责评测、编排与平台能力建设",
                                "notes": "小团队"
                              },
                              {
                                "id": "offer-b",
                                "company": "CloudSuite",
                                "role": "后端工程师",
                                "city": "杭州",
                                "compensation": "24k x 16",
                                "stage": "big-tech",
                                "domain": "成熟协作 SaaS",
                                "workMode": "onsite",
                                "stack": "Java, Spring, MySQL, Kafka",
                                "managerSupport": "medium",
                                "executionStyle": "structured",
                                "jdSignals": "负责核心业务 API 与稳定性建设",
                                "notes": "大组织"
                              }
                            ]
                          }
                        }
                        """))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.status").value(isOneOf("queued", "completed")))
            .andReturn();

    String runId = JsonPath.read(enqueueResult.getResponse().getContentAsString(), "$.id");

    boolean completed = false;
    for (int attempt = 0; attempt < 30; attempt++) {
      MvcResult pollResult =
          mockMvc
              .perform(get("/api/v1/workspace/default/run-jobs/{runId}", runId))
              .andExpect(status().isOk())
              .andReturn();

      String payload = pollResult.getResponse().getContentAsString();
      String statusValue = JsonPath.read(payload, "$.status");
      if ("completed".equals(statusValue)) {
        completed = true;
        break;
      }

      Thread.sleep(100);
    }

    if (!completed) {
      throw new AssertionError("异步研究任务没有在预期时间内完成。");
    }
  }
}
