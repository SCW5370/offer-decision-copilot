package com.shichangwei.offerdecision;

import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/mock-search")
public class MockSearchGatewayController {

  private final MockSearchFixtureService mockSearchFixtureService;

  public MockSearchGatewayController(MockSearchFixtureService mockSearchFixtureService) {
    this.mockSearchFixtureService = mockSearchFixtureService;
  }

  @PostMapping("/search")
  public MockSearchResponse search(@RequestBody MockSearchRequest request) {
    String company = request == null || request.company() == null ? "" : request.company().trim();
    int maxResults = request == null || request.maxResults() <= 0 ? 3 : request.maxResults();

    List<MockSearchResult> results =
        mockSearchFixtureService.search(company, maxResults).stream()
            .map(
                item ->
                    new MockSearchResult(
                        item.title(),
                        item.url(),
                        item.snippet(),
                        item.source(),
                        item.freshness()))
            .toList();

    return new MockSearchResponse(results);
  }

  private record MockSearchRequest(String query, String company, int maxResults) {}

  private record MockSearchResponse(List<MockSearchResult> results) {}

  private record MockSearchResult(
      String title, String url, String snippet, String source, String freshness) {}
}
