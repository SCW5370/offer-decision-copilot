package com.shichangwei.offerdecision;

record SearchQuery(String company, String query) {}

record RetrievedEvidence(
    String company,
    String title,
    String url,
    String snippet,
    String source,
    String freshness,
    int qualityScore,
    String qualityReason) {}
