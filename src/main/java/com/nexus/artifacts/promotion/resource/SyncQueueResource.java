package com.nexus.artifacts.promotion.resource;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.apache.shiro.SecurityUtils;
import org.apache.shiro.subject.Subject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonatype.nexus.rest.Resource;

import com.nexus.artifacts.promotion.model.PromotionTaskResult;
import com.nexus.artifacts.promotion.model.SyncTaskInfo;
import com.nexus.artifacts.promotion.model.TaskStatus;
import com.nexus.artifacts.promotion.service.DockerService;
import com.nexus.artifacts.promotion.service.PromotionService;
import com.nexus.artifacts.promotion.service.ServiceUtils;
import com.nexus.artifacts.promotion.service.SyncService;
import com.nexus.artifacts.promotion.service.TaskExecutorService;
import com.nexus.artifacts.promotion.security.PermissionChecker;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;

/**
 * REST API for the unified task queue management page.
 * Displayed under Browse menu as "Task Queue".
 * Shows both sync and promotion tasks.
 */
@Api("Task Queue")
@Path("/v1/sync/queue")
@Named
@Singleton
public class SyncQueueResource implements Resource {

  private static final Logger log = LoggerFactory.getLogger(SyncQueueResource.class);

  private final SyncService syncService;
  private final PromotionService promotionService;
  private final DockerService dockerService;
  private final TaskExecutorService taskExecutor;
  private final PermissionChecker permissionChecker;

  @Inject
  public SyncQueueResource(final SyncService syncService,
                            final PromotionService promotionService,
                            final DockerService dockerService,
                            final TaskExecutorService taskExecutor,
                            final PermissionChecker permissionChecker) {
    this.syncService = syncService;
    this.promotionService = promotionService;
    this.dockerService = dockerService;
    this.taskExecutor = taskExecutor;
    this.permissionChecker = permissionChecker;
  }

  /**
   * Check if the current user is authenticated (not anonymous).
   * Returns 401 if not authenticated.
   */
  private Response requireAuthenticated() {
    Subject subject = SecurityUtils.getSubject();
    if (subject == null || !subject.isAuthenticated() || isAnonymous(subject)) {
      log.warn("Unauthenticated access attempt to task queue API");
      return Response.status(Response.Status.UNAUTHORIZED)
          .entity("{\"error\":\"Authentication required\",\"message\":\"Please log in to access task queue\"}")
          .build();
    }
    return null;
  }

  /**
   * Check if the subject is the anonymous user.
   */
  private boolean isAnonymous(final Subject subject) {
    Object principal = subject.getPrincipal();
    if (principal == null) {
      return true;
    }
    String username = principal.toString();
    return "anonymous".equalsIgnoreCase(username);
  }

  /**
   * Check if the current user is an admin.
   */
  private boolean isAdmin() {
    Subject subject = SecurityUtils.getSubject();
    if (subject == null) return false;
    return subject.hasRole("admin") || subject.isPermitted("nexus:*");
  }

  /**
   * Check if the current user can terminate a task.
   * Only the task creator or an admin can terminate.
   */
  private boolean canTerminateTask(final String taskCreator) {
    if (isAdmin()) return true;
    String currentUser = permissionChecker.getCurrentUsername();
    return currentUser != null && currentUser.equals(taskCreator);
  }

  /**
   * Get all tasks (sync + promotion) for the unified task queue.
   */
  @GET
  @Produces(MediaType.APPLICATION_JSON)
  @ApiOperation("List all tasks (sync + promotion)")
  public Response listAllTasks(
      @QueryParam("page") @DefaultValue("1") int page,
      @QueryParam("start") @DefaultValue("0") int start,
      @QueryParam("limit") @DefaultValue("25") int limit
  ) {
    Response authError = requireAuthenticated();
    if (authError != null) {
      return authError;
    }
    try {
      List<SyncTaskInfo> tasks = getAllTasks();
      return Response.ok(tasks).build();
    }
    catch (Exception e) {
      log.error("Failed to list tasks: {}", e.getMessage());
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
          .entity("{\"error\":\"Failed to list tasks\"}")
          .build();
    }
  }

  /**
   * Get all tasks (alias /list for backward compatibility).
   */
  @GET
  @Path("/list")
  @Produces(MediaType.APPLICATION_JSON)
  @ApiOperation("List all tasks")
  public Response listAllTasksAlias() {
    Response authError = requireAuthenticated();
    if (authError != null) {
      return authError;
    }
    try {
      List<SyncTaskInfo> tasks = getAllTasks();
      return Response.ok(tasks).build();
    }
    catch (Exception e) {
      log.error("Failed to list tasks: {}", e.getMessage());
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
          .entity("{\"error\":\"Failed to list tasks\"}")
          .build();
    }
  }

  /**
   * Get active (pending/running) tasks only.
   */
  @GET
  @Path("/active")
  @Produces(MediaType.APPLICATION_JSON)
  @ApiOperation("List active tasks")
  public Response listActiveTasks() {
    Response authError = requireAuthenticated();
    if (authError != null) {
      return authError;
    }
    try {
      List<SyncTaskInfo> allTasks = getAllTasks();
      List<SyncTaskInfo> activeTasks = new ArrayList<>();
      for (SyncTaskInfo task : allTasks) {
        TaskStatus status = task.getStatus();
        if (status == TaskStatus.PENDING || status == TaskStatus.RUNNING) {
          activeTasks.add(task);
        }
      }
      return Response.ok(activeTasks).build();
    }
    catch (Exception e) {
      log.error("Failed to list active tasks: {}", e.getMessage());
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
          .entity("{\"error\":\"Failed to list active tasks\"}")
          .build();
    }
  }

  /**
   * Get a specific task by ID.
   */
  @GET
  @Path("/{taskId}")
  @Produces(MediaType.APPLICATION_JSON)
  @ApiOperation("Get task details")
  public Response getTask(@PathParam("taskId") final String taskId) {
    Response authError = requireAuthenticated();
    if (authError != null) {
      return authError;
    }
    try {
      SyncTaskInfo info = syncService.getTaskInfo(ServiceUtils.sanitize(taskId));
      if (info == null) {
        return Response.status(Response.Status.NOT_FOUND)
            .entity("{\"error\":\"Task not found\"}")
            .build();
      }
      return Response.ok(info).build();
    }
    catch (Exception e) {
      log.error("Failed to get task: {}", e.getMessage());
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
          .entity("{\"error\":\"Failed to get task\"}")
          .build();
    }
  }

  /**
   * Terminate a running or pending task.
   * Only the task creator or an admin can terminate a task.
   */
  @POST
  @Path("/terminate/{taskId}")
  @Produces(MediaType.APPLICATION_JSON)
  @ApiOperation("Terminate a task")
  public Response terminateTask(@PathParam("taskId") final String taskId) {
    Response authError = requireAuthenticated();
    if (authError != null) {
      return authError;
    }
    try {
      String safeTaskId = ServiceUtils.sanitize(taskId);

      // Check if task exists and is cancellable via TaskExecutorService
      boolean executorCancellable = taskExecutor.isTaskCancellable(safeTaskId);

      // Also check if it's a scheduled sync task (running in SyncService.taskInfos)
      SyncTaskInfo syncInfo = syncService.getTaskInfo(safeTaskId);
      boolean scheduledSyncRunning = (syncInfo != null
          && (syncInfo.getStatus() == TaskStatus.PENDING || syncInfo.getStatus() == TaskStatus.RUNNING));

      if (!executorCancellable && !scheduledSyncRunning) {
        // Task not found in executor or not running in sync service
        if (syncInfo != null && (syncInfo.getStatus() == TaskStatus.COMPLETED
            || syncInfo.getStatus() == TaskStatus.FAILED
            || syncInfo.getStatus() == TaskStatus.CANCELLED
            || syncInfo.getStatus() == TaskStatus.MIGRATED)) {
          return Response.status(Response.Status.BAD_REQUEST)
              .entity("{\"error\":\"Task already finished\",\"status\":\"" + syncInfo.getStatus().getValue() + "\"}")
              .build();
        }

        // Check promotion task result
        PromotionTaskResult promoResult = promotionService.getTaskResult(safeTaskId);
        if (promoResult != null) {
          return Response.status(Response.Status.BAD_REQUEST)
              .entity("{\"error\":\"Task already finished\",\"status\":\"" + promoResult.getStatus() + "\"}")
              .build();
        }

        return Response.status(Response.Status.NOT_FOUND)
            .entity("{\"error\":\"Task not found\"}")
            .build();
      }

      // Check permission: only task creator or admin can terminate
      String taskUsername = taskExecutor.getTaskUsername(safeTaskId);
      // Also check from sync task info
      if (taskUsername == null && syncInfo != null) {
        taskUsername = syncInfo.getUsername();
      }
      // Also check from promotion task result
      if (taskUsername == null) {
        PromotionTaskResult promoResult2 = promotionService.getTaskResult(safeTaskId);
        if (promoResult2 != null) {
          taskUsername = promoResult2.getUsername();
        }
      }

      if (!canTerminateTask(taskUsername)) {
        String currentUser = permissionChecker.getCurrentUsername();
        log.warn("User {} attempted to terminate task {} owned by {} - permission denied",
            currentUser, safeTaskId, taskUsername);
        return Response.status(Response.Status.FORBIDDEN)
            .entity("{\"error\":\"Permission denied\",\"message\":\"Only the task creator or admin can terminate this task\"}")
            .build();
      }

      // Cancel via executor (for tasks submitted to thread pool)
      boolean cancelled = taskExecutor.cancelTask(safeTaskId);
      // Also disconnect any active HTTP connections for promotion tasks
      promotionService.cancelPromotionTask(safeTaskId);
      // Cancel scheduled sync tasks (interrupts the running thread)
      syncService.cancelSyncTask(safeTaskId);
      // Cancel Docker tasks
      dockerService.cancelDockerPromotionTask(safeTaskId);
      dockerService.cancelDockerSyncTask(safeTaskId);

      if (cancelled || scheduledSyncRunning) {
        log.info("Task {} terminated by user {}", safeTaskId, permissionChecker.getCurrentUsername());

        return Response.ok()
            .entity("{\"taskId\":\"" + ServiceUtils.jsonEscape(safeTaskId) + "\",\"status\":\"cancelled\",\"message\":\"Task terminated successfully\"}")
            .build();
      }
      else {
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
            .entity("{\"error\":\"Failed to terminate task\"}")
            .build();
      }
    }
    catch (Exception e) {
      log.error("Failed to terminate task {}: {}", taskId, e.getMessage());
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
          .entity("{\"error\":\"Failed to terminate task\"}")
          .build();
    }
  }

  /**
   * Get current user info for frontend permission checks.
   */
  @GET
  @Path("/currentUser")
  @Produces(MediaType.APPLICATION_JSON)
  @ApiOperation("Get current user info")
  public Response getCurrentUser() {
    Response authError = requireAuthenticated();
    if (authError != null) {
      return authError;
    }
    try {
      String username = permissionChecker.getCurrentUsername();
      boolean admin = isAdmin();
      return Response.ok()
          .entity("{\"username\":\"" + ServiceUtils.jsonEscape(username) + "\",\"isAdmin\":" + admin + "}")
          .build();
    }
    catch (Exception e) {
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
          .entity("{\"error\":\"Failed to get user info\"}")
          .build();
    }
  }

  /**
   * Get all tasks (sync + promotion) combined and sorted.
   */
  private List<SyncTaskInfo> getAllTasks() {
    List<SyncTaskInfo> tasks = new ArrayList<>();

    // Get sync tasks
    try {
      List<SyncTaskInfo> syncTasks = syncService.getAllSyncTasks();
      tasks.addAll(syncTasks);
    }
    catch (Exception e) {
      log.warn("Failed to retrieve sync tasks: {}", e.getMessage());
    }

    // Get promotion tasks
    try {
      List<SyncTaskInfo> promoTasks = promotionService.getAllPromotionTasksAsSyncTaskInfo();
      tasks.addAll(promoTasks);
    }
    catch (Exception e) {
      log.warn("Failed to retrieve promotion tasks: {}", e.getMessage());
    }

    // Sort by start time descending
    tasks.sort((a, b) -> Long.compare(b.getStartTime(), a.getStartTime()));

    return tasks;
  }

}
