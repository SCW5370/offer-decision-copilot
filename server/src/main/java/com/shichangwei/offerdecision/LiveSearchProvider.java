package com.shichangwei.offerdecision;

import java.util.List;

public interface LiveSearchProvider {
  boolean isEnabled();

  List<RetrievedEvidence> search(DecisionRequest request);
}
