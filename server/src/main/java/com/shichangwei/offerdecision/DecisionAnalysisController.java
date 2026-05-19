package com.shichangwei.offerdecision;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/decision")
public class DecisionAnalysisController {

  private final DecisionAnalysisService decisionAnalysisService;
  private final DecisionCapabilitiesService decisionCapabilitiesService;

  public DecisionAnalysisController(
      DecisionAnalysisService decisionAnalysisService,
      DecisionCapabilitiesService decisionCapabilitiesService) {
    this.decisionAnalysisService = decisionAnalysisService;
    this.decisionCapabilitiesService = decisionCapabilitiesService;
  }

  @PostMapping("/analyze")
  public ResponseEntity<?> analyze(@RequestBody AnalyzeDecisionRequest payload) {
    if (!isValid(payload)) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(new ErrorResponse("决策请求参数不合法。"));
    }

    String mode = payload.mode() == null ? "auto" : payload.mode();
    return ResponseEntity.ok(decisionAnalysisService.analyze(payload.request(), mode));
  }

  @GetMapping("/capabilities")
  public ResponseEntity<DecisionCapabilities> capabilities() {
    return ResponseEntity.ok(decisionCapabilitiesService.getCapabilities());
  }

  private boolean isValid(AnalyzeDecisionRequest payload) {
    if (payload == null || payload.request() == null || payload.request().userProfile() == null) {
      return false;
    }

    DecisionRequest request = payload.request();

    if (blank(request.userProfile().target())) {
      return false;
    }

    if (request.userProfile().priorities() == null || request.userProfile().priorities().isEmpty()) {
      return false;
    }

    List<OfferInput> offers = request.offers();
    if (offers == null || offers.size() != 2) {
      return false;
    }

    return offers.stream()
        .allMatch(
            offer ->
                offer != null
                    && !blank(offer.company())
                    && !blank(offer.role())
                    && !blank(offer.stack())
                    && !blank(offer.jdSignals()));
  }

  private boolean blank(String value) {
    return value == null || value.isBlank();
  }

  private record ErrorResponse(String error) {}
}
