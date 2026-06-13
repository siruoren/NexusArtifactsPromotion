# Changelog

All notable changes to this project will be documented in this file.

## [1.0.0-SNAPSHOT] - 2026-06-13

### Added

- **Artifact Promotion**: promote artifacts between repositories via HTTP download/upload
  - Support single file and directory promotion
  - MD5 incremental check: skip unchanged files automatically
  - Permission control: requires write permission on target repository
  - Async execution with real-time progress polling
  - File preview before promotion
- **Proxy Repository Sync**: sync remote proxy repositories using Nexus ContentFacet API
  - Support single file, directory and full repository sync
  - MD5 incremental sync: compare remote/local MD5, skip unchanged assets
  - Multiple remote file list strategies: local Nexus REST API → HTTP directory listing fallback
  - Negative cache invalidation: retry previously 404'd assets
  - Delete local cache before sync to force fresh download from remote
  - Permission control: requires delete permission on proxy repository
  - Async execution with queue management and progress tracking
- **Docker Image Promotion**: efficient Docker image promotion between repositories
  - Parse Docker manifest to identify all referenced blobs (config + layers)
  - Only promote blobs actually needed by the manifest (not all blobs in repo)
  - Support promoting all tags or specific tags of an image
  - MD5 incremental check for blobs: skip unchanged layers automatically
  - Manifest-aware: promote manifest after all blobs are transferred
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
- **Capability Configuration**:
  - `Promotion Capability`: configurable thread pool size
  - `Sync Capability`: configurable thread pool size and admin credentials
- **Security**: Shiro-based permission integration
  - Promotion: requires `repository-view:edit` on target repository
  - Sync: requires `repository-view:delete` on proxy repository
- **Self-signed HTTPS support**: trust all SSL certificates for promotion HTTP calls
- **Reverse proxy support**: extract base URL from `X-Forwarded-*` headers
