# Changelog

All notable changes to this project will be documented in this file.

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
  - `Promotion Capability`: configurable thread pool size
  - `Sync Capability`: configurable thread pool size, max sync queue size, max sync queue records, Docker release repositories
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
