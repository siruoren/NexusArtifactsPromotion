package com.nexus.artifacts.promotion.service;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonatype.nexus.blobstore.api.Blob;
import org.sonatype.nexus.blobstore.api.BlobRef;
import org.sonatype.nexus.repository.Format;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.storage.Asset;
import org.sonatype.nexus.repository.storage.AssetBlob;
import org.sonatype.nexus.repository.storage.Bucket;
import org.sonatype.nexus.repository.storage.Component;
import org.sonatype.nexus.repository.storage.Query;
import org.sonatype.nexus.repository.storage.StorageFacet;
import org.sonatype.nexus.repository.storage.StorageTx;
import org.sonatype.nexus.repository.view.Content;
import org.sonatype.nexus.transaction.UnitOfWork;

import org.apache.shiro.subject.Subject;
import org.apache.shiro.subject.support.SubjectThreadState;
import org.sonatype.nexus.security.SecurityHelper;

import com.google.common.collect.ImmutableList;
import com.nexus.artifacts.promotion.model.FilePreviewResponse;
import com.nexus.artifacts.promotion.model.PromotionRequest;
import com.nexus.artifacts.promotion.model.PromotionTaskResult;
import com.nexus.artifacts.promotion.model.TargetRepositoryList;
import com.nexus.artifacts.promotion.model.TaskStatus;
import com.nexus.artifacts.promotion.security.PermissionChecker;

/**
 * Service for artifact promotion between repositories.
 * Supports promotion of directories, files, and images (Docker).
 * Idempotent: same path syncs update existing content in target.
 */
@Named
@Singleton
public class PromotionService {

  private static final Logger log = LoggerFactory.getLogger(PromotionService.class);

  private final RepositoryManager repositoryManager;
  private final TaskExecutorService taskExecutor;
  private final TaskCacheManager cacheManager;
  private final PermissionChecker permissionChecker;
  private final SecurityHelper securityHelper;

  private final Map<String, PromotionTaskResult> taskResults = new ConcurrentHashMap<>();

  @Inject
  public PromotionService(final RepositoryManager repositoryManager,
                           final TaskExecutorService taskExecutor,
                           final TaskCacheManager cacheManager,
                           final PermissionChecker permissionChecker,
                           final SecurityHelper securityHelper)
  {
    this.repositoryManager = repositoryManager;
    this.taskExecutor = taskExecutor;
    this.cacheManager = cacheManager;
    this.permissionChecker = permissionChecker;
    this.securityHelper = securityHelper;
  }

  /**
   * List target repositories where the current user has write permission,
   * filtered by same format as the source repository.
   * No special permission required on source repo - if user can see it, they can promote from it.
   * Format is read from the source repository, not trusted from frontend input.
   */
  public TargetRepositoryList listTargetRepositories(final String sourceRepository, final String format) {
    Repository sourceRepo = repositoryManager.get(sourceRepository);
    if (sourceRepo == null) {
      throw new IllegalArgumentException("Source repository not found: " + sourceRepository);
    }

    // Use actual format from source repository, not the frontend-provided format
    String actualFormat = sourceRepo.getFormat().getValue();

    TargetRepositoryList result = new TargetRepositoryList();
    result.setSourceRepository(sourceRepository);
    result.setFormat(actualFormat);

    List<TargetRepositoryList.TargetRepository> targets = new ArrayList<>();

    for (Repository repo : repositoryManager.browse()) {
      if (repo.getName().equals(sourceRepository)) {
        continue;
      }
      if (!actualFormat.equals(repo.getFormat().getValue())) {
        continue;
      }
      // Only include repos where the current user has write (edit) permission
      if (!permissionChecker.hasRepositoryWritePermission(repo.getName())) {
        continue;
      }

      targets.add(new TargetRepositoryList.TargetRepository(
          repo.getName(),
          repo.getFormat().getValue(),
          repo.getType().getValue(),
          repo.getUrl()
      ));
    }

    result.setRepositories(targets);
    return result;
  }

  /**
   * Preview files that will be involved in a promotion.
   */
  public FilePreviewResponse previewPromotion(final PromotionRequest request) {
    request.validate();
    // Only check write permission on target repo for preview
    permissionChecker.checkTargetWritePermission(request.getTargetRepository());

    Repository sourceRepo = repositoryManager.get(request.getSourceRepository());
    if (sourceRepo == null) {
      throw new IllegalArgumentException("Source repository not found: " + request.getSourceRepository());
    }

    FilePreviewResponse preview = new FilePreviewResponse();
    preview.setSourceRepository(request.getSourceRepository());
    preview.setTargetRepository(request.getTargetRepository());
    preview.setBasePath(request.getPath());

    List<FilePreviewResponse.FileEntry> files = new ArrayList<>();

    try {
      StorageFacet storageFacet = sourceRepo.facet(StorageFacet.class);
      final StorageTx tx = storageFacet.txSupplier().get();
      boolean committed = false;
      try {
        Query query = Query.builder()
            .where("name").like(escapeLike(request.getPath()) + "%")
            .build();

        Iterable<Asset> assets = tx.findAssets(query, Collections.singletonList(sourceRepo));

        int count = 0;
        long totalSize = 0;
        for (Asset asset : assets) {
          String assetName = asset.name();
          String type = determineType(assetName);
          long size = asset.size();
          totalSize += size;
          count++;

          boolean existsInTarget = checkExistsInTarget(request.getTargetRepository(), assetName);
          files.add(new FilePreviewResponse.FileEntry(assetName, type, size, existsInTarget));
        }
        preview.setTotalCount(count);
        preview.setTotalSize(totalSize);
        tx.commit();
        committed = true;
      }
      finally {
        if (!committed) { tx.rollback(); }
      }
    }
    catch (Exception e) {
      log.error("Failed to preview promotion files: {}", e.getMessage(), e);
      files.add(new FilePreviewResponse.FileEntry(request.getPath(),
          request.isDirectory() ? "directory" : "file", 0, false));
      preview.setTotalCount(1);
      preview.setTotalSize(0);
    }

    preview.setFiles(files);
    return preview;
  }

  /**
   * Execute artifact promotion.
   * Returns task ID for tracking.
   * Permission is checked at REST layer; the async task binds the Shiro subject
   * to avoid losing security context in the thread pool.
   */
  public String promote(final PromotionRequest request) {
    request.validate();

    // Check write permission on target repository at REST layer
    permissionChecker.checkTargetWritePermission(request.getTargetRepository());

    String username = permissionChecker.getCurrentUsername();

    // Capture current Shiro subject before submitting to thread pool
    Subject subject = securityHelper.subject();
    final SubjectThreadState threadState = (subject != null && subject.isAuthenticated())
        ? new SubjectThreadState(subject) : null;

    return taskExecutor.submitPromotionTask(() -> {
      // Bind Shiro subject to this thread to preserve security context
      if (threadState != null) {
        threadState.bind();
      }
      try {
        String taskId = null;
        PromotionTaskResult result = new PromotionTaskResult();
        result.setSourceRepository(request.getSourceRepository());
        result.setTargetRepository(request.getTargetRepository());
        result.setUsername(username);
        result.setStartTime(System.currentTimeMillis());

        try {
          taskId = cacheManager.createTaskCache("promo-" + System.currentTimeMillis()).getFileName().toString();
          result.setTaskId(taskId);

          Repository sourceRepo = repositoryManager.get(request.getSourceRepository());
          Repository targetRepo = repositoryManager.get(request.getTargetRepository());

          if (sourceRepo == null) {
            throw new IllegalArgumentException("Source repository not found: " + request.getSourceRepository());
          }
          if (targetRepo == null) {
            throw new IllegalArgumentException("Target repository not found: " + request.getTargetRepository());
          }

          // No need to re-check permission here - already checked at REST layer

          List<PromotionTaskResult.FileItem> promotedItems = new ArrayList<>();

          if (request.isDirectory()) {
            promotedItems = promoteDirectory(sourceRepo, targetRepo, request.getPath());
          }
          else {
            promotedItems = promoteFile(sourceRepo, targetRepo, request.getPath());
          }

          result.setItems(promotedItems);
          result.setStatus(TaskStatus.COMPLETED);
          result.setEndTime(System.currentTimeMillis());

          log.info("Promotion task {} completed: {} items promoted from {} to {}",
              taskId, promotedItems.size(), request.getSourceRepository(), request.getTargetRepository());

        }
        catch (Exception e) {
          log.error("Promotion task {} failed: {}", taskId, e.getMessage(), e);
          result.setStatus(TaskStatus.FAILED);
          result.setErrorMessage(sanitizeErrorMessage(e.getMessage()));
          result.setEndTime(System.currentTimeMillis());
        }

        if (taskId != null) {
          taskResults.put(taskId, result);
        }

        final String finalTaskId = taskId;
        return new TaskExecutorService.PromotionTaskCallback() {
          @Override
          public String getTaskId() { return finalTaskId; }
          @Override
          public TaskStatus getStatus() { return result.getStatus(); }
          @Override
          public String getErrorMessage() { return result.getErrorMessage(); }
        };
      }
      finally {
        // Clean up thread-local Shiro state
        if (threadState != null) {
          threadState.clear();
        }
      }
    }, String.format("Promote %s from %s to %s", request.getPath(),
        request.getSourceRepository(), request.getTargetRepository()));
  }

  /**
   * Get promotion task result.
   */
  public PromotionTaskResult getTaskResult(final String taskId) {
    return taskResults.get(taskId);
  }

  /**
   * Promote all files in a directory.
   */
  private List<PromotionTaskResult.FileItem> promoteDirectory(final Repository sourceRepo,
                                                               final Repository targetRepo,
                                                               final String directoryPath)
  {
    List<PromotionTaskResult.FileItem> items = new ArrayList<>();

    try {
      StorageFacet sourceStorage = sourceRepo.facet(StorageFacet.class);
      final StorageTx tx = sourceStorage.txSupplier().get();
      boolean committed = false;
      try {
        Query query = Query.builder()
            .where("name").like(escapeLike(directoryPath) + "%")
            .build();

        Iterable<Asset> assets = tx.findAssets(query, Collections.singletonList(sourceRepo));

        for (Asset asset : assets) {
          try {
            PromotionTaskResult.FileItem item = promoteSingleAsset(sourceRepo, targetRepo, tx, asset);
            items.add(item);
          }
          catch (Exception e) {
            log.error("Failed to promote asset {}: {}", asset.name(), e.getMessage());
            PromotionTaskResult.FileItem item = new PromotionTaskResult.FileItem(
                asset.name(), PromotionTaskResult.FileAction.UPDATED, determineType(asset.name()));
            item.setStatus("failed");
            item.setErrorMessage(sanitizeErrorMessage(e.getMessage()));
            items.add(item);
          }
        }
        tx.commit();
        committed = true;
      }
      finally {
        if (!committed) { tx.rollback(); }
      }
    }
    catch (Exception e) {
      log.error("Failed to promote directory {}: {}", directoryPath, e.getMessage(), e);
      throw new RuntimeException("Directory promotion failed", e);
    }

    return items;
  }

  /**
   * Promote a single file.
   */
  private List<PromotionTaskResult.FileItem> promoteFile(final Repository sourceRepo,
                                                          final Repository targetRepo,
                                                          final String filePath)
  {
    List<PromotionTaskResult.FileItem> items = new ArrayList<>();

    try {
      StorageFacet sourceStorage = sourceRepo.facet(StorageFacet.class);
      final StorageTx tx = sourceStorage.txSupplier().get();
      boolean committed = false;
      try {
        Bucket bucket = tx.findBucket(sourceRepo);
        Asset asset = tx.findAssetWithProperty("name", filePath, bucket);
        if (asset == null) {
          throw new IllegalArgumentException("Asset not found: " + filePath);
        }
        items.add(promoteSingleAsset(sourceRepo, targetRepo, tx, asset));
        tx.commit();
        committed = true;
      }
      finally {
        if (!committed) { tx.rollback(); }
      }
    }
    catch (Exception e) {
      log.error("Failed to promote file {}: {}", filePath, e.getMessage(), e);
      throw new RuntimeException("File promotion failed", e);
    }

    return items;
  }

  /**
   * Promote a single asset from source to target repository.
   * Idempotent: if the asset already exists in target, it is updated.
   */
  private PromotionTaskResult.FileItem promoteSingleAsset(final Repository sourceRepo,
                                                           final Repository targetRepo,
                                                           final StorageTx sourceTx,
                                                           final Asset sourceAsset)
  {
    String assetName = sourceAsset.name();
    String type = determineType(assetName);

    boolean existsInTarget = checkExistsInTarget(targetRepo.getName(), assetName);
    PromotionTaskResult.FileAction action = existsInTarget
        ? PromotionTaskResult.FileAction.UPDATED
        : PromotionTaskResult.FileAction.CREATED;

    try {
      BlobRef blobRef = sourceAsset.blobRef();
      if (blobRef == null) {
        throw new IllegalStateException("Asset has no blob: " + assetName);
      }

      Blob blob = sourceTx.getBlob(blobRef);
      if (blob == null) {
        throw new IllegalStateException("Blob not found for asset: " + assetName);
      }

      // Stream content to target repository
      StorageFacet targetStorage = targetRepo.facet(StorageFacet.class);
      final StorageTx targetTx = targetStorage.txSupplier().get();
      boolean targetCommitted = false;
      try {
        Bucket targetBucket = targetTx.findBucket(targetRepo);

        try (InputStream inputStream = blob.getInputStream()) {
          saveToTarget(targetRepo, targetTx, targetBucket, assetName, sourceAsset, inputStream, blob.getMetrics().getContentSize());
        }

        targetTx.commit();
        targetCommitted = true;
      }
      finally {
        if (!targetCommitted) { targetTx.rollback(); }
      }
    }
    catch (Exception e) {
      throw new RuntimeException("Failed to promote asset: " + assetName, e);
    }

    return new PromotionTaskResult.FileItem(assetName, action, type);
  }

  /**
   * Save content to target repository using the StorageTx API.
   * Handles both new and existing assets (idempotent).
   */
  private void saveToTarget(final Repository targetRepo,
                             final StorageTx tx,
                             final Bucket bucket,
                             final String assetName,
                             final Asset sourceAsset,
                             final InputStream inputStream,
                             final long size) throws IOException
  {
    // Find or create component
    Component component = tx.findComponentWithProperty("name", assetName, bucket);
    if (component == null) {
      Format format = targetRepo.getFormat();
      component = tx.createComponent(bucket, format);
      component.group("promoted");
      component.name(assetName);
      tx.saveComponent(component);
    }

    // Find or create asset
    Asset targetAsset = tx.findAssetWithProperty("name", assetName, bucket);
    if (targetAsset == null) {
      targetAsset = tx.createAsset(bucket, component);
      targetAsset.name(assetName);
    }

    // Attach blob using setBlob
    AssetBlob assetBlob = tx.setBlob(
        targetAsset,
        assetName,
        () -> inputStream,
        ImmutableList.of(),
        null,
        sourceAsset.contentType(),
        true
    );

    tx.saveAsset(targetAsset);
  }

  /**
   * Check if an asset exists in the target repository.
   */
  private boolean checkExistsInTarget(final String targetRepoName, final String assetName) {
    try {
      Repository targetRepo = repositoryManager.get(targetRepoName);
      if (targetRepo == null) {
        return false;
      }
      StorageFacet storageFacet = targetRepo.facet(StorageFacet.class);
      final StorageTx tx = storageFacet.txSupplier().get();
      try {
        Bucket bucket = tx.findBucket(targetRepo);
        return tx.findAssetWithProperty("name", assetName, bucket) != null;
      }
      finally {
        tx.rollback();
      }
    }
    catch (Exception e) {
      return false;
    }
  }

  private String determineType(final String path) {
    if (path == null) return "file";
    if (path.contains("/blobs/") || path.contains("/manifests/") || path.endsWith(".manifest")) {
      return "image";
    }
    if (path.endsWith("/")) {
      return "directory";
    }
    return "file";
  }

  private String escapeLike(final String input) {
    if (input == null) return "";
    return input.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  private String sanitizeErrorMessage(final String message) {
    if (message == null) return "Unknown error";
    return message.replaceAll("(?i)(password|token|secret|credential)\\s*[:=]\\s*\\S+", "$1:***");
  }
}
