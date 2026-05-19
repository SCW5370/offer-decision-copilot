package com.shichangwei.offerdecision;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.cors")
public record AppCorsProperties(List<String> allowedOrigins) {}
