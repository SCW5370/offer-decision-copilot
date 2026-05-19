package com.shichangwei.offerdecision;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

@Service
public class MockSearchFixtureService {

  public List<MockSearchDocument> search(String company, int maxResults) {
    String normalized = company == null ? "" : company.toLowerCase(Locale.ROOT);
    List<MockSearchDocument> results =
        normalized.contains("nova")
            ? novaAgentsResults()
            : normalized.contains("cloudsuite") ? cloudSuiteResults() : genericResults(company);
    return results.stream().limit(Math.max(1, maxResults)).toList();
  }

  private List<MockSearchDocument> novaAgentsResults() {
    return List.of(
        new MockSearchDocument(
            "Nova Agents 扩招平台工程团队",
            "https://news.example.com/nova-agents-platform-hiring",
            "Nova Agents 正在招聘平台工程师和评测工程师，以持续完善 Agent 编排基础设施。",
            "news.example.com",
            "2026-05-12"),
        new MockSearchDocument(
            "Nova Agents 发布评测工作流工程文章",
            "https://engineering.example.com/nova-evals",
            "工程博客介绍了内部评测工具链、工作流可观测性和回放系统。",
            "engineering.example.com",
            "2026-04-28"),
        new MockSearchDocument(
            "Nova Agents 产品更新聚焦企业落地",
            "https://product.example.com/nova-enterprise-rollout",
            "最近的产品更新强调企业采用、上线控制能力和稳定性建设。",
            "product.example.com",
            "2026-03-30"));
  }

  private List<MockSearchDocument> cloudSuiteResults() {
    return List.of(
        new MockSearchDocument(
            "CloudSuite 稳定性团队分享事故下降进展",
            "https://engineering.example.com/cloudsuite-reliability",
            "CloudSuite 介绍了稳定性投入、事故复盘机制变化以及后端平台加固工作。",
            "engineering.example.com",
            "2026-05-05"),
        new MockSearchDocument(
            "CloudSuite 后端招聘保持谨慎",
            "https://careers.example.com/cloudsuite-backend-hiring",
            "当前后端岗位更强调核心 API 负责制和系统稳定性，而不是快速扩张。",
            "careers.example.com",
            "2026-04-18"),
        new MockSearchDocument(
            "CloudSuite 发布成熟企业功能更新",
            "https://news.example.com/cloudsuite-enterprise-refresh",
            "版本说明体现出较稳定的产品路线图，以及渐进式的企业能力升级。",
            "news.example.com",
            "2026-02-10"));
  }

  private List<MockSearchDocument> genericResults(String company) {
    List<MockSearchDocument> results = new ArrayList<>();
    results.add(
        new MockSearchDocument(
            company + " 最近招聘与产品更新",
            "https://news.example.com/generic-hiring-update",
            company
                + " 在公开资料里出现了近期招聘或产品动态，但这些信号仍然需要人工进一步核验。",
            "news.example.com",
            "2026-04-01"));
    return results;
  }
}

record MockSearchDocument(
    String title, String url, String snippet, String source, String freshness) {}
