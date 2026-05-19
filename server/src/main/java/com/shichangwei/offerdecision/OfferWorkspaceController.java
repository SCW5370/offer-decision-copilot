package com.shichangwei.offerdecision;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/workspace")
public class OfferWorkspaceController {

  private final OfferWorkspaceService offerWorkspaceService;

  public OfferWorkspaceController(OfferWorkspaceService offerWorkspaceService) {
    this.offerWorkspaceService = offerWorkspaceService;
  }

  @GetMapping("/default")
  public ResponseEntity<WorkspaceResponse> getDefaultWorkspace() {
    return ResponseEntity.ok(offerWorkspaceService.getDefaultWorkspace());
  }

  @PostMapping("/default/profile")
  public ResponseEntity<WorkspaceResponse> updateProfile(
      @RequestBody UpdateWorkspaceProfileRequest request) {
    return ResponseEntity.ok(offerWorkspaceService.updateProfile(request));
  }

  @PostMapping("/default/offers")
  public ResponseEntity<SavedOfferRecord> saveOffer(@RequestBody SaveWorkspaceOfferRequest request) {
    return ResponseEntity.ok(offerWorkspaceService.saveOffer(request));
  }

  @PostMapping("/default/runs")
  public ResponseEntity<WorkspaceRunDetail> createRun(@RequestBody CreateWorkspaceRunRequest request) {
    return ResponseEntity.ok(offerWorkspaceService.createRun(request));
  }

  @PostMapping("/default/run-jobs")
  public ResponseEntity<WorkspaceRunJobAccepted> enqueueRun(
      @RequestBody CreateWorkspaceRunRequest request) {
    return ResponseEntity.accepted().body(offerWorkspaceService.enqueueRun(request));
  }

  @GetMapping("/default/runs")
  public ResponseEntity<java.util.List<WorkspaceRunSummary>> recentRuns() {
    return ResponseEntity.ok(offerWorkspaceService.getRecentRuns());
  }

  @GetMapping("/default/runs/{runId}")
  public ResponseEntity<WorkspaceRunDetail> getRun(@PathVariable String runId) {
    return ResponseEntity.ok(offerWorkspaceService.getRunDetail(runId));
  }

  @GetMapping("/default/run-jobs/{runId}")
  public ResponseEntity<WorkspaceRunDetail> getRunJob(@PathVariable String runId) {
    return ResponseEntity.ok(offerWorkspaceService.getRunDetail(runId));
  }

  @DeleteMapping("/default/offers/{offerId}")
  public ResponseEntity<Void> deleteOffer(@PathVariable String offerId) {
    offerWorkspaceService.deleteOffer(offerId);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/intake/parse")
  public ResponseEntity<IntakeParseResponse> parse(@RequestBody IntakeParseRequest request) {
    return ResponseEntity.ok(offerWorkspaceService.parseOfferText(request));
  }
}
