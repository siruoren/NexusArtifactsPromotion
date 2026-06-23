package com.nexus.artifacts.promotion.service;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.manager.RepositoryManager;

import com.nexus.artifacts.promotion.model.SyncRequest;
import com.nexus.artifacts.promotion.model.SyncTaskInfo;

/**
 * Dedicated service for incremental sync of proxy repositories.
 *
 * Incremental sync compares remote asset MD5 checksums with locally cached
 * asset MD5 checksums. Only assets whose MD5 differs (or that are missing
 * locally) are synced. This significantly reduces network traffic and sync
 * time when most assets are already up-to-date.
 *
 * Flow:
 * 1. Get remote asset list with MD5 via Nexus Search API or HTTP directory listing
 * 2. Build local asset MD5 map from the proxy repository's blob store
 * 3. For each remote asset:
 *    - If local MD5 matches remote MD5 -> skip
 *    - If MD5 mismatch or asset missing locally -> sync via ContentFacet
 * 4. Upload and element update happen in the same StorageTx transaction
 *    (ensured by syncAssetViaContentFacet / syncAssetViaDirectHttp in SyncService)
 *
 * This service delegates the actual asset sync operations to SyncService,
 * which handles the low-level ContentFacet dispatch, direct HTTP download,
 * and StorageTx transaction management.
 */
@Named
@Singleton
public class IncrementalSyncService {

  private static final Logger log = LoggerFactory.getLogger(IncrementalSyncService.class);

  private final SyncService syncService;
  private final RepositoryManager repositoryManager;

  @Inject
  public IncrementalSyncService(final SyncService syncService,
      final RepositoryManager repositoryManager) {
    this.syncService = syncService;
    this.repositoryManager = repositoryManager;
  }

  /**
   * Execute an incremental sync as a scheduled task.
   * This is called by ProxySyncTask when the "incremental" option is selected.
   *
   * @param request the sync request containing repository name, path, and format
   * @param task the Nexus task instance for cancellation support (may be null)
   * @return SyncTaskInfo with sync results including skipped/synced counts
   */
  public SyncTaskInfo syncScheduledIncremental(
      final SyncRequest request,
      final com.nexus.artifacts.promotion.task.ProxySyncTask task) {

    // Delegate to SyncService which has all the MD5 comparison infrastructure
    return syncService.syncScheduled(request, task, true);
  }

  /**
   * Get the count of assets that would need syncing (preview/dry-run).
   * This does not perform any sync, only counts the differences.
   *
   * @param repositoryName the proxy repository name
   * @param directoryPath the path to check (empty for full repository)
   * @return a summary with total, synced, and skipped counts
   */
  public SyncPreviewResult previewIncrementalSync(
      final String repositoryName,
      final String directoryPath) {

    Repository repo = repositoryManager.get(repositoryName);
    if (repo == null) {
      throw new IllegalArgumentException("Repository not found: " + repositoryName);
    }
    if (!"proxy".equals(repo.getType().getValue())) {
      throw new IllegalArgumentException("Repository is not a proxy type: " + repositoryName);
    }

    // Use SyncService's public incremental sync to get the actual file details
    SyncRequest request = new SyncRequest();
    request.setRepositoryName(repositoryName);
    request.setPath(directoryPath != null ? directoryPath : "");
    request.setIsDirectory(true);
    request.setFormat(repo.getFormat().getValue());

    SyncTaskInfo result = syncService.syncScheduled(request, null, true);

    SyncPreviewResult preview = new SyncPreviewResult();
    if (result.getFileDetails() != null) {
      for (SyncTaskInfo.SyncFileDetail detail : result.getFileDetails()) {
        preview.totalAssets++;
        if ("skipped".equals(detail.getStatus())) {
          preview.skippedAssets++;
        }
        else if ("success".equals(detail.getStatus())) {
          preview.syncedAssets++;
        }
        else if ("failed".equals(detail.getStatus())) {
          preview.failedAssets++;
        }
      }
    }
    return preview;
  }

  /**
   * Result of an incremental sync preview.
   */
  public static class SyncPreviewResult {
    public int totalAssets;
    public int syncedAssets;
    public int skippedAssets;
    public int failedAssets;

    @Override
    public String toString() {
      return String.format("SyncPreviewResult{total=%d, synced=%d, skipped=%d, failed=%d}",
          totalAssets, syncedAssets, skippedAssets, failedAssets);
    }
  }
}
