package com.shichangwei.offerdecision;

import java.util.Optional;

public interface LiveResearchProvider {

  boolean isEnabled();

  Optional<LiveResearchReport> research(DecisionRequest request);

  default boolean isEnabled(String requestedMode) {
    return isEnabled();
  }

  default Optional<LiveResearchReport> research(DecisionRequest request, String requestedMode) {
    return research(request);
  }
}
