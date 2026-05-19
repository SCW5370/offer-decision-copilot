package com.shichangwei.offerdecision;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

@Entity
@Table(name = "workspace_analysis_run")
class WorkspaceAnalysisRunEntity {

  @Id private String id;

  @Column(nullable = false, length = 128)
  private String workspaceId;

  @Column(nullable = false, length = 32)
  private String requestedMode;

  @Column(length = 32)
  private String engineMode;

  @Column(length = 32)
  private String status;

  @Column(length = 2000)
  private String progressDetail;

  @Column(length = 255)
  private String winner;

  @Column(length = 2000)
  private String summary;

  @Lob private String requestJson;

  @Lob private String analysisJson;

  @Column(nullable = false, length = 64)
  private String createdAt;

  @Column(length = 64)
  private String startedAt;

  @Column(length = 64)
  private String completedAt;

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getWorkspaceId() {
    return workspaceId;
  }

  public void setWorkspaceId(String workspaceId) {
    this.workspaceId = workspaceId;
  }

  public String getRequestedMode() {
    return requestedMode;
  }

  public void setRequestedMode(String requestedMode) {
    this.requestedMode = requestedMode;
  }

  public String getEngineMode() {
    return engineMode;
  }

  public void setEngineMode(String engineMode) {
    this.engineMode = engineMode;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public String getProgressDetail() {
    return progressDetail;
  }

  public void setProgressDetail(String progressDetail) {
    this.progressDetail = progressDetail;
  }

  public String getWinner() {
    return winner;
  }

  public void setWinner(String winner) {
    this.winner = winner;
  }

  public String getSummary() {
    return summary;
  }

  public void setSummary(String summary) {
    this.summary = summary;
  }

  public String getRequestJson() {
    return requestJson;
  }

  public void setRequestJson(String requestJson) {
    this.requestJson = requestJson;
  }

  public String getAnalysisJson() {
    return analysisJson;
  }

  public void setAnalysisJson(String analysisJson) {
    this.analysisJson = analysisJson;
  }

  public String getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(String createdAt) {
    this.createdAt = createdAt;
  }

  public String getStartedAt() {
    return startedAt;
  }

  public void setStartedAt(String startedAt) {
    this.startedAt = startedAt;
  }

  public String getCompletedAt() {
    return completedAt;
  }

  public void setCompletedAt(String completedAt) {
    this.completedAt = completedAt;
  }
}
