package com.shichangwei.offerdecision;

import java.util.Optional;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Primary
@Component
public class RoutingLiveResearchProvider implements LiveResearchProvider {

  private final LiveResearchProperties properties;
  private final RetrievalFirstLiveResearchProvider retrievalFirstLiveResearchProvider;
  private final KimiLiveResearchProvider kimiLiveResearchProvider;
  private final DeterministicLiveResearchProvider deterministicLiveResearchProvider;

  public RoutingLiveResearchProvider(
      LiveResearchProperties properties,
      RetrievalFirstLiveResearchProvider retrievalFirstLiveResearchProvider,
      KimiLiveResearchProvider kimiLiveResearchProvider,
      DeterministicLiveResearchProvider deterministicLiveResearchProvider) {
    this.properties = properties;
    this.retrievalFirstLiveResearchProvider = retrievalFirstLiveResearchProvider;
    this.kimiLiveResearchProvider = kimiLiveResearchProvider;
    this.deterministicLiveResearchProvider = deterministicLiveResearchProvider;
  }

  @Override
  public boolean isEnabled() {
    return delegate("").isEnabled();
  }

  @Override
  public Optional<LiveResearchReport> research(DecisionRequest request) {
    return delegate("").research(request);
  }

  @Override
  public boolean isEnabled(String requestedMode) {
    return delegate(requestedMode).isEnabled();
  }

  @Override
  public Optional<LiveResearchReport> research(DecisionRequest request, String requestedMode) {
    return delegate(requestedMode).research(request);
  }

  public String providerLabel(String requestedMode) {
    if ("demo".equalsIgnoreCase(nullSafe(requestedMode))) {
      return "deterministic retrieval";
    }
    if (retrievalFirstLiveResearchProvider.isEnabled()) {
      return retrievalFirstLiveResearchProvider.providerLabel();
    }
    if ("deterministic".equalsIgnoreCase(nullSafe(properties.provider()))) {
      return "deterministic retrieval";
    }
    return "kimi";
  }

  private LiveResearchProvider delegate(String requestedMode) {
    if ("demo".equalsIgnoreCase(nullSafe(requestedMode))) {
      return new LiveResearchProvider() {
        @Override
        public boolean isEnabled() {
          return deterministicLiveResearchProvider.isDemoAvailable();
        }

        @Override
        public Optional<LiveResearchReport> research(DecisionRequest request) {
          return deterministicLiveResearchProvider.researchDemo(request);
        }
      };
    }
    if (retrievalFirstLiveResearchProvider.isEnabled()) {
      return retrievalFirstLiveResearchProvider;
    }
    if ("deterministic".equalsIgnoreCase(nullSafe(properties.provider()))) {
      return new LiveResearchProvider() {
        @Override
        public boolean isEnabled() {
          return deterministicLiveResearchProvider.isEnabled();
        }

        @Override
        public Optional<LiveResearchReport> research(DecisionRequest request) {
          return deterministicLiveResearchProvider.research(request);
        }
      };
    }
    return kimiLiveResearchProvider;
  }

  private String nullSafe(String value) {
    return value == null ? "" : value.trim();
  }
}
