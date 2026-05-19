package com.shichangwei.offerdecision;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

@Entity
@Table(name = "workspace_profile")
class WorkspaceEntity {

  @Id private String id;

  @Column(nullable = false)
  private String name;

  @Column(length = 2000)
  private String target;

  @Column(length = 32)
  private String riskAppetite;

  @Column(length = 1000)
  private String priorities;

  @Column(nullable = false, length = 64)
  private String createdAt;

  @Column(nullable = false, length = 64)
  private String updatedAt;

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getTarget() {
    return target;
  }

  public void setTarget(String target) {
    this.target = target;
  }

  public String getRiskAppetite() {
    return riskAppetite;
  }

  public void setRiskAppetite(String riskAppetite) {
    this.riskAppetite = riskAppetite;
  }

  public String getPriorities() {
    return priorities;
  }

  public void setPriorities(String priorities) {
    this.priorities = priorities;
  }

  public String getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(String createdAt) {
    this.createdAt = createdAt;
  }

  public String getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(String updatedAt) {
    this.updatedAt = updatedAt;
  }
}

@Entity
@Table(name = "workspace_offer")
class WorkspaceOfferEntity {

  @Id private String id;

  @Column(nullable = false, length = 128)
  private String workspaceId;

  @Column(nullable = false, length = 255)
  private String company;

  @Column(nullable = false, length = 255)
  private String role;

  @Column(length = 255)
  private String city;

  @Column(length = 255)
  private String compensation;

  @Column(length = 64)
  private String stage;

  @Column(length = 500)
  private String domain;

  @Column(length = 64)
  private String workMode;

  @Column(length = 1000)
  private String stack;

  @Column(length = 64)
  private String managerSupport;

  @Column(length = 64)
  private String executionStyle;

  @Column(length = 2000)
  private String jdSignals;

  @Column(length = 2000)
  private String notes;

  @Lob private String rawText;

  @Column(length = 64)
  private String source;

  @Column(nullable = false, length = 64)
  private String createdAt;

  @Column(nullable = false, length = 64)
  private String updatedAt;

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

  public String getCompany() {
    return company;
  }

  public void setCompany(String company) {
    this.company = company;
  }

  public String getRole() {
    return role;
  }

  public void setRole(String role) {
    this.role = role;
  }

  public String getCity() {
    return city;
  }

  public void setCity(String city) {
    this.city = city;
  }

  public String getCompensation() {
    return compensation;
  }

  public void setCompensation(String compensation) {
    this.compensation = compensation;
  }

  public String getStage() {
    return stage;
  }

  public void setStage(String stage) {
    this.stage = stage;
  }

  public String getDomain() {
    return domain;
  }

  public void setDomain(String domain) {
    this.domain = domain;
  }

  public String getWorkMode() {
    return workMode;
  }

  public void setWorkMode(String workMode) {
    this.workMode = workMode;
  }

  public String getStack() {
    return stack;
  }

  public void setStack(String stack) {
    this.stack = stack;
  }

  public String getManagerSupport() {
    return managerSupport;
  }

  public void setManagerSupport(String managerSupport) {
    this.managerSupport = managerSupport;
  }

  public String getExecutionStyle() {
    return executionStyle;
  }

  public void setExecutionStyle(String executionStyle) {
    this.executionStyle = executionStyle;
  }

  public String getJdSignals() {
    return jdSignals;
  }

  public void setJdSignals(String jdSignals) {
    this.jdSignals = jdSignals;
  }

  public String getNotes() {
    return notes;
  }

  public void setNotes(String notes) {
    this.notes = notes;
  }

  public String getRawText() {
    return rawText;
  }

  public void setRawText(String rawText) {
    this.rawText = rawText;
  }

  public String getSource() {
    return source;
  }

  public void setSource(String source) {
    this.source = source;
  }

  public String getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(String createdAt) {
    this.createdAt = createdAt;
  }

  public String getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(String updatedAt) {
    this.updatedAt = updatedAt;
  }
}
