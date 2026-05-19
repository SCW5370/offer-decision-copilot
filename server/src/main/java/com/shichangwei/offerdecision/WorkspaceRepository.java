package com.shichangwei.offerdecision;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface WorkspaceRepository extends JpaRepository<WorkspaceEntity, String> {}

interface WorkspaceOfferRepository extends JpaRepository<WorkspaceOfferEntity, String> {

  List<WorkspaceOfferEntity> findByWorkspaceIdOrderByUpdatedAtDesc(String workspaceId);
}

interface WorkspaceAnalysisRunRepository extends JpaRepository<WorkspaceAnalysisRunEntity, String> {

  List<WorkspaceAnalysisRunEntity> findTop6ByWorkspaceIdOrderByCreatedAtDesc(String workspaceId);
}
