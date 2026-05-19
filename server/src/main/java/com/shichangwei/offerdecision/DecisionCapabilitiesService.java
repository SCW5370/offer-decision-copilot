package com.shichangwei.offerdecision;

import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class DecisionCapabilitiesService {

  private final LiveResearchProperties liveResearchProperties;
  private final RetrievalProperties retrievalProperties;
  private final RoutingLiveResearchProvider routingLiveResearchProvider;

  public DecisionCapabilitiesService(
      LiveResearchProperties liveResearchProperties,
      RetrievalProperties retrievalProperties,
      RoutingLiveResearchProvider routingLiveResearchProvider) {
    this.liveResearchProperties = liveResearchProperties;
    this.retrievalProperties = retrievalProperties;
    this.routingLiveResearchProvider = routingLiveResearchProvider;
  }

  public DecisionCapabilities getCapabilities() {
    return new DecisionCapabilities(
        Instant.now().toString(),
        List.of(
            new ModeCapability(
                "heuristic",
                true,
                "离线规则分析",
                "始终可用，完全运行在 Java 决策引擎内部，不依赖实时研究引擎。",
                "java"),
            buildAutoModeCapability(),
            buildDemoModeCapability()),
        buildLiveProviderStatus(),
        buildRetrievalStatus());
  }

  private ModeCapability buildAutoModeCapability() {
    boolean enabled = routingLiveResearchProvider.isEnabled("auto");
    String configuredProvider = routingLiveResearchProvider.providerLabel("auto");
    String detail;
    if (!enabled) {
      detail = "真实实时研究引擎还没有完整配置好，因此自动模式会安全降级到规则路径，而不是直接失败。";
    } else if (configuredProvider.startsWith("retrieval-first")) {
      detail = "当前自动模式会先通过检索层拿公开证据，再交给结构化模型做受控总结，而不是把搜网与推理都压在单个模型上。";
    } else {
      detail = "当前配置的实时研究引擎可以执行时效敏感的研究流程，并把最新公开证据合并进评分。";
    }
    return new ModeCapability(
        "auto",
        enabled,
        enabled ? "真实实时研究" : "自动安全降级",
        detail,
        configuredProvider);
  }

  private ModeCapability buildDemoModeCapability() {
    boolean enabled = routingLiveResearchProvider.isEnabled("demo");
    return new ModeCapability(
        "demo",
        enabled,
        enabled ? "可复现演示实时" : "演示模式不可用",
        enabled
            ? "会运行一条可回放、带检索证据的实时链路，适合本地演示和面试展示，不依赖外部 LLM 厂商。"
            : "当前本地可复现演示链路不可用。",
        "deterministic retrieval");
  }

  private LiveProviderStatus buildLiveProviderStatus() {
    boolean enabled = liveResearchProperties.enabled();
    String provider = routingLiveResearchProvider.providerLabel("auto");
    String detail;
    if (!enabled) {
      detail = "后端当前没有开启实时研究能力。";
    } else if ("deterministic".equalsIgnoreCase(provider)) {
      detail =
          "后端当前固定使用可复现实时引擎，适合稳定演示，但并不代表真实公网实时信息。";
    } else if (provider.startsWith("retrieval-first")) {
      detail =
          "后端当前优先使用检索层收集公开证据，再交给结构化模型做受控总结；这条路径比单模型搜网更稳定。";
    } else if (routingLiveResearchProvider.isEnabled("auto")) {
      detail =
          "后端当前可以在自动模式下尝试真实实时研究；如果引擎超时或结果不可用，也会安全回退。";
    } else {
      detail =
          "配置层面已开启实时研究，但当前引擎还无法完整运行，因此自动模式会回退到规则路径。";
    }

    return new LiveProviderStatus(
        enabled,
        provider,
        safeValue(liveResearchProperties.model(), "未设置"),
        safeValue(liveResearchProperties.structuringProvider(), "未设置"),
        detail);
  }

  private RetrievalStatus buildRetrievalStatus() {
    boolean enabled = retrievalProperties.enabled();
    String provider = safeValue(retrievalProperties.provider(), "未设置");
    String detail =
        enabled
            ? "检索层已开启，证据进入 live 层前会先经过质量门控、缓存 TTL 和时效性惩罚。"
            : "检索层当前关闭，因此除演示模式外，live 研究会更依赖模型本身。";

    return new RetrievalStatus(
        enabled,
        provider,
        retrievalProperties.maxResults(),
        retrievalProperties.minQualityScore(),
        retrievalProperties.cacheTtlMinutes(),
        retrievalProperties.maxFreshnessAgeDays(),
        detail);
  }

  private String safeValue(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value.trim();
  }
}
