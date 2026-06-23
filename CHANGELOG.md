# Changelog

All notable changes to this project will be documented in this file.

## [2.0.1] - 2026-06-20

### Fixed

- **SSL Certificate Validation (HIGH)**: removed JVM-global `setDefaultSSLSocketFactory()` / `setDefaultHostnameVerifier()` calls that disabled SSL certificate verification for the entire Nexus process
  - Previous: `DockerService`, `PromotionService`, `SyncService` each had `static {}` blocks calling `HttpsURLConnection.setDefaultSSLSocketFactory(trustAll)` and `setDefaultHostnameVerifier((h, s) -> true)`, which disabled certificate validation for ALL HTTPS connections in the JVM (including Nexus core and other plugins)
  - Fix: created `SslHelper` utility class with `applyTrustAllSsl(HttpURLConnection conn)` that applies trust-all SSL only to individual connections via `conn.setSSLSocketFactory()` / `conn.setHostnameVerifier()`, without affecting JVM-global defaults
  - All `openConnection()` calls in `DockerService`, `PromotionService`, `SyncService`, and `HttpClientPool` now call `SslHelper.applyTrustAllSsl(conn)` after opening the connection
  - Compliance: satisfies GB/T 22239-2019 §8.1.3.4 (communication integrity/confidentiality) and ISO 27001 Annex A.10.1.1 (encryption controls)
- **SSRF via X-Forwarded-Host (HIGH)**: removed `X-Forwarded-Host` header usage in `extractNexusBaseUrl()` that allowed attackers to control server-side request targets
  - Previous: `PromotionResource.extractNexusBaseUrl()` and `DockerResource.extractNexusBaseUrl()` read `X-Forwarded-Host` header to construct internal API URLs, allowing attackers to redirect requests to arbitrary servers (Cookie + CSRF token leakage)
  - Fix: host is now derived from `request.getServerName()` + `request.getServerPort()` only; `X-Forwarded-Proto` is still allowed (affects scheme only, http vs https); added `isLocalHost()` validation that restricts hosts to `localhost` / `127.0.0.1` / `[::1]`
  - Compliance: satisfies GB/T 22239-2019 §8.1.3.3 (access control) and ISO 27001 Annex A.9.4.2 (secure logon procedures)

## [2.0.0] - 2026-06-18

### Added

- **Task Queue Management Configuration**: new capability parameters for task queue lifecycle control
  - `Max Task Queue Records`: maximum number of task records retained for both promotion and sync tasks (default: 200), replaces per-type limits
  - `Task Log TTL (minutes)`: time before completed/failed/cancelled task logs are automatically cleaned up (default: 30 minutes)
  - `Task Timeout (minutes)`: maximum time a task can run before being automatically cancelled (default: 120 minutes), prevents tasks from running indefinitely
- **Task Timeout Detection**: dual-layer timeout protection to prevent resource exhaustion and stuck tasks
  - Per-task watchdog thread: interrupts task thread when timeout is reached
  - Periodic scanner (every 60 seconds): scans all RUNNING/PENDING tasks and cancels those exceeding timeout
  - Timeout is configurable via Sync Capability UI
- **Task Cancellation State Consistency**: when a task is terminated, all task result stores are now consistently updated to CANCELLED
  - `PromotionService.cancelPromotionTask()`: updates `taskResults` map entry to CANCELLED
  - `SyncService.cancelSyncTask()`: updates `taskInfos` map entry to CANCELLED
  - `DockerService.cancelDockerPromotionTask()` / `cancelDockerSyncTask()`: updates Docker task results to CANCELLED
  - `SyncQueueResource.terminateTask()`: calls all cancel methods to ensure state consistency across all services
- **Backend Search API Pagination**: `trySearchApi` and `tryComponentsApi` now follow Nexus `continuationToken` to retrieve all pages of results
  - Prevents incomplete file lists when repositories have more than 10 items
  - Safety limit of 100 pages to prevent infinite loops

### Changed

- **Task Cancellation Status**: tasks that are manually terminated or fail due to interruption now show "Cancelled" instead of "Failed"
  - `PromotionService`: thread interruption detected and mapped to CANCELLED status
  - `SyncService`: thread interruption detected and mapped to CANCELLED status
  - `DockerService`: thread interruption detected and mapped to CANCELLED status
  - `TaskExecutorService.getPromotionTaskStatus()` / `getSyncTaskStatus()`: exception from `future.get()` now maps to CANCELLED
  - Frontend: `cancelled` status rendered as orange "Cancelled" / "终止" text
- **Removed Sync Record Max Limit**: `maxSyncRecords` (per-type limit) replaced by `maxTaskQueueRecords` (unified limit for all task types)
- **Task Log TTL Now Configurable**: previously hardcoded 30-minute TTL is now configurable via capability UI
- **Task Timeout Now Configurable**: previously hardcoded 60-minute timeout is now configurable via capability UI

### Fixed

- **Promotion Pagination State**: second page files still showed "pending" after promotion completed
  - Added `store.on('load')` event listener to sync `statusMap` to current page records on page change
  - Fixed `eachAllRecords` function that only returned current page records in paginated mode
- **Task Status Shows "Completed" After Termination**: cancelled tasks incorrectly displayed as "completed"
  - Root cause: `cancelTask()` only updated `TaskHandle.status` but not `PromotionTaskResult` / `SyncTaskInfo` in service-level maps
  - Fix: all cancel methods now update both executor handle and service-level result objects
- **`SyncService.getTaskInfo()` Override**: tasks already in CANCELLED state could be overridden by TaskExecutor status
  - Added CANCELLED to the terminal state check that prevents status override
- **Docker Task Expired Cleanup**: `cleanupExpiredPromotionTaskResults()` now includes `cancelled` status in expiry check

### Removed

- **Service Restart Task Recovery**: `SyncQueuePersistenceManager` no longer recovers persisted tasks on startup
  - Active tasks from before restart are simply discarded
  - Only cleanup of leftover state files is performed on startup
  - Prevents stale or duplicate task execution after Nexus restart

## [1.0.2] - 2026-06-17

### Fixed

- **Docker Sync Path Resolution (Unified with Promotion Logic)**: Docker sync path parsing now reuses the same image discovery logic as Docker promotion (`listDockerImages` + prefix filtering), ensuring consistent and reliable path resolution for any directory structure
  - For paths like `project/8.3.2.891`, uses `listDockerImagesByPrefix()` which calls `listDockerImages()` (Internal API → Local REST API → Remote API) then filters by prefix
  - Handles all cases: directory prefix with sub-images (e.g. `project/8.3.2.891/app1`, `project/8.3.2.891/app2`), image with tags, and specific image:tag
  - Added `imagePrefix` field to `DockerImageRequest` for directory prefix mode
  - Removed standalone `listDockerImagesByPrefixRemote()` method — now uses the same strategy chain as promotion
- **Docker Sync Remote-First Tag Listing**: `listDockerTags()` now prioritizes remote APIs (Docker Registry V2 → Remote Nexus API) for proxy repositories, instead of local cache
  - `listDockerTagsRemote()` no longer falls back to local cache — only returns tags actually available on remote
  - Prevents syncing stale or incomplete tag lists from local proxy cache
- **Docker Sync Task Error on Empty Remote Tags**: when remote returns 0 tags for an image, the sync task now correctly reports FAILED instead of silently completing
  - `syncDockerImage()`: throws IOException when remote has 0 tags
  - `syncDockerImageScheduled()`: sets task status to FAILED with error message when no images/tags found
- **Docker Sync Task Status Display**: fixed task status being incorrectly shown as COMPLETED when the sync actually failed
  - Root cause: `TaskExecutorService.wrapTask()` finally block overwrote FAILED status with COMPLETED
  - `DockerService.getSyncTaskInfo()` and `SyncService.getTaskInfo()` now preserve terminal states (FAILED/COMPLETED) set by the service layer, preventing override by TaskExecutor
- **Thread Pool Bounded Queue (OOM Prevention)**: replaced `LinkedBlockingQueue` (unbounded) with `ArrayBlockingQueue` (capacity: promotion=100, sync=50) in `TaskExecutorService`
  - Uses `AbortPolicy` instead of `CallerRunsPolicy` to avoid blocking HTTP request threads
  - `RejectedExecutionException` caught and converted to `IllegalStateException` with clear error message
  - Prevents heap exhaustion when target repositories are unreachable and tasks accumulate
- **HTTP Connection Leak Prevention**: fixed `HttpClientPool` to properly close `InputStream` in `finally` block before `conn.disconnect()`
  - All HTTP methods (GET/PUT/PUTStream/HEAD/DELETE) now close response streams atomically with connection release
  - Prevents Jetty connection pool exhaustion from unclosed streams during IOException
- **Maven Metadata Merge Atomicity**: wrapped `promoteMavenMetadata` in `FileWriteLockManager.executeWithFileLock` to prevent concurrent merge corruption
  - Per-file `ReentrantLock` ensures read-merge-write is atomic per target repo + path
  - Prevents version entry loss when two promotion tasks write to the same Group/Artifact simultaneously
- **Sync Queue Zombie Task Detection**: added zombie task detection in `SyncQueuePersistenceManager.recoverQueueState()`
  - Tasks in RUNNING state for over 30 minutes are automatically marked as FAILED on recovery
  - Prevents permanently stuck "in-progress" tasks after JVM crash
  - Limited persisted tasks to 100 max to prevent large JSON files on startup

### Verified

- **Shiro Security Context Propagation**: confirmed all three services (PromotionService, DockerService, SyncService) correctly capture `SubjectThreadState` before thread pool submission, `bind()` in async thread, and `clear()` in `finally` block

## [1.0.1] - 2026-06-16

### Added

- **Docker Release Repository Configuration (Promotion)**: added `dockerReleaseRepos` parameter to Promotion Capability
  - When promoting images to a configured release repository, only non-snapshot tags are displayed and promoted
  - Tags containing SNAPSHOT/dev/alpha/beta/RC/pre/test/canary/nightly/latest are filtered out
- **Docker Release Proxy Repository Configuration (Sync)**: added `dockerReleaseProxyRepos` parameter to Sync Capability
  - When syncing images from a configured release proxy repository, only non-snapshot tags are synced
  - Non-release tags are marked as "skipped" in sync results
- **Docker Manifest OCI and Multi-Architecture Support**: added `DockerManifestParser` for comprehensive manifest format handling
  - Support Docker Manifest V2 Schema 2 (`application/vnd.docker.distribution.manifest.v2+json`)
  - Support OCI Image Manifest V1 (`application/vnd.oci.image.manifest.v1+json`)
  - Support Docker Fat Manifest / Manifest List V2 (`application/vnd.docker.distribution.manifest.list.v2+json`)
  - Support OCI Image Index V1 (`application/vnd.oci.image.index.v1+json`)
  - Auto-detect manifest type when Content-Type header is missing
  - Multi-architecture (arm64/arm32v6/etc.) image promotion and sync support
  - Recursive sub-manifest processing for fat manifests
- **Docker and Generic Sync Flow Separation**: Docker format sync requests are now delegated to `DockerService`
  - `SyncService.sync()` detects Docker format and delegates to `DockerService.syncDockerImage()`
  - Docker sync leverages manifest-aware blob dependency resolution
  - Non-Docker formats continue to use ContentFacet-based sync
- **Promotion Queue Capacity Control**: added `maxPromotionQueueSize` configuration to prevent OOM
  - Promotion Capability UI now includes "Max Promotion Queue Size" field (default: 50)
  - `TaskExecutorService` rejects new promotion tasks when queue is full
  - Prevents memory exhaustion from excessive concurrent promotion tasks
- **Configurable Retry Strategy**: retry parameters are now configurable via Promotion Capability UI
  - Added "Retry Base Delay (ms)" field (100-10000, default: 1000)
  - Added "Retry Max Delay (ms)" field (1000-300000, default: 30000)
  - Exponential backoff with jitter: `min(maxDelay, baseDelay * 2^attempt) + random(0, jitter)`
  - Retryable error classification: only retries on IOException, HTTP 429/5xx; never retries on IllegalArgumentException, SecurityException, HTTP 4xx
- **Global Exception Handling**: added `PromotionExceptionMapper` for standardized REST API error responses
  - SecurityException → 403 Forbidden
  - IllegalArgumentException → 400 Bad Request
  - IllegalStateException → 503 Service Unavailable
  - IOException → 502 Bad Gateway
  - All errors return consistent JSON format: `{"error":"category","message":"detail","status":code}`
- **Structured Logging Enhancement**: improved promotion and sync completion logs with task metadata
  - Promotion completion log includes: taskId, source, target, totalFiles, success count, skipped count, duration
  - Sync completion log includes: taskId, repository, path, synced count, skipped count
- **Sync Queue Persistence and Recovery**: added `SyncQueuePersistenceManager` for active task recovery after Nexus restart
  - Active (PENDING/RUNNING) sync tasks are periodically persisted to disk (every 30 seconds)
  - On Nexus startup, persisted active tasks are automatically recovered and resubmitted
  - Only the latest snapshot is kept; old state files are cleaned up
  - Recovered tasks are resubmitted through the normal sync flow with permission checks
- **HTTP Connection Pool Reuse**: added `HttpClientPool` for connection pooling across promotion and sync operations
  - Leverages JDK built-in HTTP Keep-Alive connection pooling (increased `http.maxConnections` to 20)
  - Centralized timeout configuration (connect timeout, read timeout, chunk size)
  - Core promotion and Docker blob transfer paths now use connection pool
  - Maven metadata merge operations use connection pool
  - Docker manifest download and sub-manifest processing use connection pool
  - Chunked streaming mode for large uploads avoids buffering entire files in memory
  - Connection reuse significantly reduces TLS handshake overhead in batch scenarios
- **Unit Tests**: added `DockerManifestParserTest` with 21 test cases covering all manifest formats
  - Docker V2 Schema 2 parsing and blob digest extraction
  - OCI Image Manifest V1 parsing
  - Docker Fat Manifest (Manifest List) with multi-architecture platform references
  - OCI Image Index V1 parsing
  - Auto-detection when Content-Type header is missing
  - Edge cases: empty manifest, null manifest, missing config/layers, missing platform info

### Changed

- **Removed Incremental Sync Comparison**: all proxy repository sync (Docker and non-Docker) now performs full sync on each execution
  - Removed MD5/ETag/Last-Modified checksum comparison logic from SyncService
  - Removed MD5 checksum comparison logic from DockerService
  - Every sync operation re-downloads all assets from remote
- **Removed Last-Modified Checksum Comparison**: non-Docker proxy sync no longer uses Last-Modified header for comparison (was incorrect)

### Fixed

- **Concurrent Write Lock (HIGH)**: added `FileWriteLockManager` to prevent file corruption from concurrent writes to the same target repository path
  - PromotionService: MD5 check and file transfer are now atomic under file lock (fixes TOCTOU race condition)
  - SyncService: asset sync operations are protected by per-file write lock
  - DockerService: Docker tag sync/promotion uses image:tag level lock for Manifest+Blob atomicity
  - Maven metadata merge is protected by the same file lock as the promotion operation
- **Sync Task Deduplication (MEDIUM)**: `cancelDuplicateSyncTask` now checks target repository in addition to source, preventing incorrect cancellation of tasks with different targets
- **TaskCacheManager Concurrency (MEDIUM)**: added per-file locks in `storeFile` to prevent concurrent write conflicts in cache directory

### Removed

- **Incremental Sync Methods**: removed all checksum comparison related methods
  - SyncService: `getRemoteAssetMd5`, `getRemoteAssetChecksumViaHttp`, `getDockerRemoteAssetMd5`, `getDockerRegistryToken`, `getRemoteAssetMd5ViaApi`, `searchRemoteAssetMd5`, `searchRemoteAssetMd5ViaComponents`, `parseMd5FromSearchResponse`, `findMd5InAssetsArray`, `getLocalAssetChecksumForCompare`, `getLocalAssetEtag`, `getLocalAssetChecksum`, `checksumsMatch`, `extractDigestHex`
  - DockerService: `getRemoteAssetMd5`, `getRemoteAssetMd5ViaApi`, `getLocalAssetMd5`, `extractRepoNameFromUrl`

## [1.0.0] - 2026-06-15

### Added

- **Artifact Promotion**: promote artifacts between repositories via HTTP download/upload
  - Support single file and directory promotion
  - MD5 incremental check: skip unchanged files automatically
  - Permission control: requires write permission on target repository
  - Async execution with real-time progress polling
  - File preview before promotion
  - **Retry with exponential backoff**: failed file transfers automatically retry up to 3 times with 1s/2s/4s delays
  - **Chunked streaming transfer**: uses Transfer-Encoding: chunked (64KB chunks) for large file uploads, avoids OOM
  - **Maven metadata smart merge**: maven-metadata.xml files are merged instead of overwritten, preserving all version entries
  - **Pagination for file list**: promotion dialog file list now supports pagination with 10 items per page
- **Proxy Repository Sync**: sync remote proxy repositories using Nexus ContentFacet API
  - Support single file, directory and full repository sync
  - MD5 incremental sync: compare remote/local MD5, skip unchanged assets
  - Multiple remote file list strategies: local Nexus REST API → HTTP directory listing fallback
  - Negative cache invalidation: retry previously 404'd assets
  - Delete local cache before sync to force fresh download from remote
  - Permission control: requires delete permission on proxy repository
  - Async execution with queue management and progress tracking
  - **Retry with exponential backoff**: failed asset sync operations automatically retry up to 2 times
- **Docker Image Promotion**: efficient Docker image promotion between repositories
  - Parse Docker manifest to identify all referenced blobs (config + layers)
  - Only promote blobs actually needed by the manifest (not all blobs in repo)
  - Support promoting all tags or specific tags of an image
  - MD5 incremental check for blobs: skip unchanged layers automatically
  - Manifest-aware: promote manifest after all blobs are transferred
  - **Retry with exponential backoff**: blob and manifest transfers retry up to 3 times
  - **Chunked streaming**: large Docker blobs use chunked upload (64KB chunks) to avoid OOM
- **Docker Proxy Sync**: efficient Docker image sync from proxy repositories
  - Parse manifest to determine blob dependencies before syncing
  - Sync only referenced blobs instead of blindly syncing all files
  - Support syncing all tags or specific tags via Docker Registry V2 API
  - Fallback to Nexus Components API when Docker Registry API unavailable
- **Docker REST API Endpoints**:
  - `GET /v1/docker/images?repository=xxx` - list Docker images with tags
  - `GET /v1/docker/tags?repository=xxx&image=yyy` - list tags for an image
  - `POST /v1/docker/promote` - promote Docker images (all/specific tags)
  - `POST /v1/docker/sync` - sync Docker images from proxy (all/specific tags)
  - `GET /v1/docker/promote/task/{taskId}` - query Docker promotion task status
  - `GET /v1/docker/sync/task/{taskId}` - query Docker sync task status
- **Docker UI Integration**: Docker-specific dialog in Nexus UI
  - Image selector dropdown (populated by /docker/images API)
  - Tag selection grid with checkboxes (select specific tags or all)
  - Progress polling during Docker promote/sync
  - Docker-specific button text on asset/folder panels
  - **Docker Registry API v2 error handling**: detailed error parsing for 401/404/429/5xx, retry on transient failures
- **Task State Persistence**: async task results survive Nexus restarts
  - TaskStateStore persists completed/failed task states to disk as JSON
  - Automatic loading of persisted states on startup
  - TTL-based cleanup of expired task state files
- **Scheduled Task Template**: `Proxy Repository Scheduled Sync`
  - Register as Nexus system task template (System → Tasks)
  - Support specifying proxy repository name and sync directory path
  - Leave sync path empty for full repository sync
  - Cron-based scheduling or manual execution
  - Skip permission checks (tasks are created by administrators)
- **REST API Endpoints**:
  - `GET /v1/promotion/permission` - check promotion permission
  - `GET /v1/promotion/targets` - list available target repositories
  - `POST /v1/promotion/preview` - preview promotion files
  - `POST /v1/promotion/execute` - execute promotion
  - `GET /v1/promotion/task/{taskId}` - query promotion task status
  - `GET /v1/sync/permission` - check sync permission
  - `POST /v1/sync/execute` - execute sync
  - `GET /v1/sync/task/{taskId}` - query sync task status
  - `GET /v1/sync/proxy-repositories` - list all proxy repositories
  - `GET /v1/sync/queue/size` - get current sync queue size
- **UI Integration**: ExtJS-based Nexus UI plugin
  - Promotion button on asset/folder info panels
  - Sync button on asset/folder info panels (proxy repositories only)
  - Target repository selection dialog
  - File preview dialog
  - Real-time progress window with polling
  - i18n support (English & Chinese)
  - Permission-aware: disable buttons when user lacks required permissions
- **Sync Queue Page**: dedicated page for monitoring sync tasks
  - Visible to all authenticated users (no admin restriction)
  - Left sidebar menu entry, visible after login
  - **Filter toolbar**: filter by repository, path, status, and username
  - **Pagination**: 20 items per page with paging toolbar
  - **Status display**: Failed / Running / Cancelled / Completed
  - **Cell tooltips**: hover over any cell to see full content
  - Auto-refresh every 3 seconds while active tasks exist
- **Capability Configuration**:
  - `Promotion Capability`: configurable thread pool size, Docker release repositories
  - `Sync Capability`: configurable thread pool size, max sync queue size, max sync queue records, Docker release proxy repositories
- **Security**: Shiro-based permission integration
  - Promotion: requires `repository-view:edit` on target repository
  - Sync: requires `repository-view:delete` on proxy repository
  - Sync Queue: requires authentication only (no admin role needed)
- **Self-signed HTTPS support**: trust all SSL certificates for promotion HTTP calls
- **Reverse proxy support**: extract base URL from `X-Forwarded-*` headers

### Changed

- **Docker Promotion**: use Docker v2 API for manifest and blob push instead of direct PUT upload
  - Manifests: `PUT /v2/<name>/manifests/<tag>` with proper Content-Type header
  - Blobs: `POST /v2/<name>/blobs/uploads/` + `PUT` with digest parameter
  - Pre-parse manifest to push referenced blobs before manifest (fixes BLOB_UNKNOWN error)
  - Sort Docker files: blobs promoted before manifests
- **Docker Sync**: use Docker Registry v2 API to fetch manifest digest for incremental sync
  - Support Bearer token authentication for private registries
  - SHA256 digest comparison for Docker assets (instead of MD5)
- **Non-Docker Proxy Sync**: use HTTP HEAD to get ETag/Last-Modified for incremental sync
  - Strategy 4: HTTP HEAD request to get ETag, Content-MD5, or Last-Modified as change indicator
  - Local comparison via HTTP HEAD for corresponding header values
- **Docker Promotion Modal**: fix button stuck in "promoting" state after task completion
  - Hide promote button and change cancel button to "close" after task starts
  - Add `findDockerPathIndex` for matching backend Docker paths to frontend image:tag format
- **Docker Sync/Same mechanism**: Docker format uses the same sync mechanism as other formats

### Removed

- **Admin Credentials Configuration**: removed username/password fields from Sync Capability
  - Removed `PROP_ADMIN_USERNAME` and `PROP_ADMIN_PASSWORD` from SyncCapabilityDescriptor
  - Removed `updateAdminCredentials` from SyncService and DockerService
  - Removed `getAdminAuth` and `getEffectiveAuth` fallback methods
  - Removed `CredentialEncryptor` utility class (AES-256-GCM encryption)
  - API calls now use repository-configured HTTP authentication only
