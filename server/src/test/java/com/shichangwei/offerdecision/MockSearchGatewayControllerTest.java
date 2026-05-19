package com.shichangwei.offerdecision;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
class MockSearchGatewayControllerTest {

  @Autowired private MockMvc mockMvc;

  @Test
  void returnsCompanySpecificMockResults() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/mock-search/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "query": "Nova Agents hiring platform engineering",
                      "company": "Nova Agents",
                      "maxResults": 2
                    }
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.results.length()").value(2))
        .andExpect(jsonPath("$.results[0].title").value("Nova Agents 扩招平台工程团队"))
        .andExpect(jsonPath("$.results[0].url").value("https://news.example.com/nova-agents-platform-hiring"))
        .andExpect(jsonPath("$.results[0].freshness").value("2026-05-12"));
  }
}
