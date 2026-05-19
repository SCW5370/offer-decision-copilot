package com.shichangwei.offerdecision;

import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@EnableConfigurationProperties({
  AppCorsProperties.class,
  LiveResearchProperties.class,
  RetrievalProperties.class
})
public class AppConfig {

  @Bean
  WebMvcConfigurer corsConfigurer(AppCorsProperties properties) {
    List<String> allowedOrigins =
        properties.allowedOrigins() == null || properties.allowedOrigins().isEmpty()
            ? List.of("http://localhost:3000")
            : properties.allowedOrigins();

    return new WebMvcConfigurer() {
      @Override
      public void addCorsMappings(CorsRegistry registry) {
        registry
            .addMapping("/api/**")
            .allowedOrigins(allowedOrigins.toArray(String[]::new))
            .allowedMethods("GET", "POST", "OPTIONS")
            .allowedHeaders("*");
      }
    };
  }
}
