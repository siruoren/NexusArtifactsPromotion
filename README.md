# Nexus Artifacts Promotion Plugin

Version: **1.0.0**

A Nexus 3.45.0+ plugin for artifact promotion and remote repository sync, compatible with both commercial (PRO) and community (OSS) editions.

## Features

- **Artifact Promotion**: Promote artifacts between same-format repositories via HTTP download/upload with idempotent path sync
- **Remote Repository Sync**: Sync artifacts from remote/proxy repositories with deduplication
- **Permission Integration**: Uses Nexus built-in `repository-view` permissions, no custom privilege configuration needed
- **Thread Pool Management**: Configurable thread pools for promotion and sync tasks with timeout watchdog
- **Progress Tracking**: Real-time per-file promotion status with progress bar (pending/processing/success/failed)
- **Sync Queue**: Dedicated UI page for monitoring sync tasks under Browse sidebar
- **Internationalization**: Chinese and English i18n support

---

## Design Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                        Nexus Repository UI                          │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────────┐  │
│  │ Asset Info    │  │ Folder Info  │  │ Sync Queue Menu          │  │
│  │ [Promote]    │  │ [Promote]    │  │ (Browse > Sync Queue)    │  │
│  │ [Sync]       │  │ [Sync]       │  │                          │  │
│  └──────┬───────┘  └──────┬───────┘  └───────────┬──────────────┘  │
│         │                 │                       │                  │
│  ┌──────┴─────────────────┴───────────────────────┴──────────────┐  │
│  │              PluginConfig.js (ExtJS Controller)                │  │
│  │  - CSRF token handling (NX-ANTI-CSRF-TOKEN)                   │  │
│  │  - Permission check before button display                     │  │
│  │  - Promotion dialog with target selection & file preview      │  │
│  │  - Progress window with XMLHttpRequest polling                │  │
│  │  - Per-file status: pending → processing → success/failed     │  │
│  │  - i18n support (dot-format keys, zh/en)                      │  │
│  └──────────────────────────┬────────────────────────────────────┘  │
└─────────────────────────────┼───────────────────────────────────────┘
                              │ REST API (Siesta/JAX-RS)
                              ▼
┌─────────────────────────────────────────────────────────────────────┐
│                         REST API Layer                               │
│  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐  │
│  │ PromotionResource│  │ SyncResource     │  │ SyncQueueResource│  │
│  │ /v1/promotion/*  │  │ /v1/sync/*       │  │ /v1/sync/queue/* │  │
│  └────────┬─────────┘  └────────┬─────────┘  └────────┬─────────┘  │
│           │                      │                      │            │
│  ┌────────┴──────────────────────┴──────────────────────┴─────────┐ │
│  │                    PermissionChecker                            │ │
│  │  - Uses Nexus built-in repository-view permissions             │ │
│  │  - nexus:repository-view:{format}:{repo}:edit (write)          │ │
│  │  - nexus:repository-view:{format}:{repo}:read (read)           │ │
│  │  - Check at both UI button and API execution levels            │ │
│  └────────────────────────────────────────────────────────────────┘ │
│                                                                      │
│  ┌────────────────────────────────────────────────────────────────┐ │
│  │ SystemConfigResource /v1/config/*                              │ │
│  │  - Read system settings                                        │ │
│  │  - Update promotion/sync thread pool sizes                     │ │
│  └────────────────────────────────────────────────────────────────┘ │
└─────────────────────────────┼───────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────────┐
│                       Service Layer                                  │
│  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐  │
│  │ PromotionService │  │ SyncService      │  │TaskExecutorService│  │
│  │ - List targets   │  │ - Execute sync   │  │ - Thread pools   │  │
│  │ - Preview files  │  │ - Dedup tasks    │  │ - Task tracking  │  │
│  │ - Execute promote│  │ - Track status   │  │ - Timeout detect │  │
│  │ - HTTP copy      │  │ - StorageTx copy │  │ - Shiro context  │  │
│  └──────────────────┘  └──────────────────┘  └──────────────────┘  │
│                                                                      │
│  ┌──────────────────┐  ┌──────────────────────────────────────────┐ │
│  │TaskCacheManager  │  │ Scheduled Tasks                           │ │
│  │ - Isolated cache │  │ ┌────────────────────────────────────┐   │ │
│  │ - Auto cleanup   │  │ │ SyncQueueSizeTask                  │   │ │
│  └──────────────────┘  │ │ (Nexus Tasks UI template)          │   │ │
│                         │ └────────────────────────────────────┘   │ │
│                         └──────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────┘
```

---

## Feature Flows

### 1. Artifact Promotion Flow

```
User browses repository → Clicks file/folder detail
    → PluginConfig detects ComponentAssetInfo/ComponentFolderInfo panel
    → Checks user write permission via PermissionChecker (repository-view:edit)
    → If permitted, shows [Promote] button
    → User clicks [Promote]
    → Dialog opens: fetch target repositories (GET /v1/promotion/targets)
        → Filters same-format repos where user has write permission
    → User selects target repository
    → Preview files to be promoted (POST /v1/promotion/preview)
    → User confirms promotion
    → Execute promotion (POST /v1/promotion/execute)
        → CSRF token (NX-ANTI-CSRF-TOKEN) + X-Nexus-UI header
        → Permission re-checked server-side
        → Task submitted to promotion thread pool
        → Shiro subject propagated to async thread
        → Task ID returned
    → Frontend polls task status (GET /v1/promotion/task/{taskId})
        → Uses XMLHttpRequest for reliable async polling
        → Progress bar updates via Ext.ProgressBar.updateText()
    → Per-file status updates: pending → processing → success/failed
    → On completion, window stays open for user to review results
    → User closes window manually
```

### 2. Remote Repository Sync Flow

```
User browses remote/proxy repository → Clicks file/folder detail
    → PluginConfig detects panel and checks write permission
    → If permitted, shows [Sync] button
    → User clicks [Sync]
    → Execute sync (POST /v1/sync/execute)
        → CSRF token + X-Nexus-UI header
        → Permission re-checked server-side
        → Check for duplicate source directory sync
            → If duplicate found: cancel old task, mark as MIGRATED
        → Task submitted to sync thread pool
        → Task ID returned
    → Dialog shows: "Sync queue created, ID: {taskId}"
    → User can view all sync tasks in Sync Queue (Browse > Sync Queue)
        → SyncQueueResource provides list/active/detail/config APIs
        → Configurable max queue size via SyncQueueSizeTask
```

### 3. Task Execution Flow

```
Task submitted → TaskExecutorService
    → Task wrapped with timeout watchdog (60 min)
    → Submitted to dedicated thread pool (promotion or sync)
    → Shiro Subject bound to async thread for permission context
    → TaskHandle stored in ConcurrentHashMap
    → On completion:
        → Task status updated (COMPLETED/FAILED)
        → Task result cached in TaskCacheManager
        → Timeout watchdog stopped
    → On timeout:
        → Thread interrupted
        → Task status set to FAILED
        → Cache cleaned up
    → On shutdown:
        → Graceful thread pool shutdown (30s timeout)
        → All task caches cleaned
```

### 4. System Configuration Flow

```
System Settings UI / REST API:
    → GET /v1/config/settings → Read all settings
    → PUT /v1/config/promotion/poolSize → Update promotion pool size
    → PUT /v1/config/sync/poolSize → Update sync pool size
    → PUT /v1/config/sync/maxQueueSize → Update max sync queue size

Nexus Scheduled Tasks UI:
    → SyncQueueSizeTask template available
    → Admin can create scheduled task to update queue size
    → Task updates TaskExecutorService.maxSyncQueueSize at runtime
```

---

## Project Structure

```
src/main/java/com/nexus/artifacts/promotion/
├── NexusArtifactsPromotionPlugin.java          # Plugin identity (OSGi Bundle-SymbolicName)
├── NexusArtifactsPromotionModule.java          # Guice module (auto-discovered via Sisu)
├── ArtifactsPromotionUiPluginDescriptor.java   # UI plugin registration (Rapture)
├── config/
│   ├── PromotionCapability.java                # Promotion system settings descriptor
│   ├── PromotionCapabilityDescriptor.java      # Promotion settings UI
│   ├── SyncCapability.java                     # Sync system settings descriptor
│   └── SyncCapabilityDescriptor.java           # Sync settings UI
├── exception/
│   ├── GlobalExceptionMapper.java              # JAX-RS global exception → HTTP status mapping
│   ├── PermissionDeniedException.java          # 403 permission denied
│   ├── QueueFullException.java                 # 429 sync queue full
│   ├── TaskExecutionException.java             # 500 task execution failure
│   └── TaskTimeoutException.java               # 504 task timeout
├── model/
│   ├── FilePreviewResponse.java                # Preview result with file list
│   ├── PromotionRequest.java                   # Promotion request (source/target/path/files)
│   ├── PromotionTaskResult.java                # Task result with per-file status items
│   ├── SyncRequest.java                        # Sync request (source/path/format)
│   ├── SyncTaskInfo.java                       # Sync task info for queue display
│   ├── TargetRepositoryList.java               # Target repository list response
│   └── TaskStatus.java                         # Task status enum (RUNNING/COMPLETED/FAILED)
├── resource/
│   ├── PromotionResource.java                  # /v1/promotion/* REST API
│   ├── SyncResource.java                       # /v1/sync/* REST API
│   ├── SyncQueueResource.java                  # /v1/sync/queue/* REST API
│   └── SystemConfigResource.java               # /v1/config/* REST API
├── security/
│   └── PermissionChecker.java                  # Nexus repository-view permission validation
├── service/
│   ├── PromotionService.java                   # Promotion logic (HTTP download/upload)
│   ├── SyncService.java                        # Sync logic (StorageTx direct copy)
│   ├── TaskCacheManager.java                   # Task result cache with directory isolation
│   └── TaskExecutorService.java                # Thread pool + timeout + Shiro context
└── task/
    ├── SyncQueueSizeTask.java                  # Scheduled task: update sync queue size
    └── SyncQueueSizeTaskDescriptor.java        # Task template for Nexus Tasks UI

src/main/resources/
├── static/rapture/
│   ├── NX/artifactsPromotion/app/
│   │   └── PluginConfig.js                     # Frontend UI controller (ExtJS)
│   ├── nexus-artifacts-promotion-plugin-prod.js  # Auto-generated from PluginConfig.js
│   ├── nexus-artifacts-promotion-plugin-debug.js # Auto-generated from PluginConfig.js
│   └── i18n/
│       ├── en.json                             # English i18n
│       └── zh.json                             # Chinese i18n
├── META-INF/
│   ├── sisu/javax.inject.Named                 # Sisu component index
│   └── nexus/plugin.properties                 # Plugin metadata
```

---

## Build & Deploy

### Prerequisites

- JDK 8
- Maven 3.6+
- Nexus Repository 3.45.0+

### Build

```bash
mvn clean package -DskipTests
```

The build automatically generates `prod.js` and `debug.js` from `PluginConfig.js` during the `initialize` phase.

Output: `target/nexus-artifacts-promotion-plugin-1.0.0.jar`

### Deploy

```bash
cp target/nexus-artifacts-promotion-plugin-1.0.0.jar <nexus-home>/deploy/
<nexus-home>/bin/nexus restart
```

### Verify

```bash
# Check permission
curl -u admin:admin123 http://localhost:8081/service/rest/v1/promotion/permission?repository=maven-releases&format=maven2

# List target repositories
curl -u admin:admin123 http://localhost:8081/service/rest/v1/promotion/targets?sourceRepository=maven-releases&format=maven2
```

---

## Configuration

### System Settings (Capabilities)

| Setting | Description | Default |
|---------|-------------|---------|
| Promotion Thread Pool Size | Concurrent promotion task threads | 4 |
| Sync Thread Pool Size | Concurrent sync task threads | 4 |
| Max Sync Queue Size | Max concurrent sync queue tasks | 20 |

### Permissions

The plugin uses Nexus built-in `repository-view` permissions. No custom privilege configuration needed.

| Permission | Format | Description |
|-----------|--------|-------------|
| Read source repo | `nexus:repository-view:{format}:{repo}:read` | Required to view/promote from source |
| Write target repo | `nexus:repository-view:{format}:{repo}:edit` | Required to promote/sync to target |

### Scheduled Tasks

| Task | Description |
|------|-------------|
| Update Sync Queue Size | Dynamically update max sync queue size at runtime |

---

## REST API

### Promotion API (`/v1/promotion`)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/v1/promotion/permission` | Check if user has promotion permission on a repository |
| GET | `/v1/promotion/targets` | List eligible target repositories (same format + write permission) |
| POST | `/v1/promotion/preview` | Preview files to be promoted with path mapping |
| POST | `/v1/promotion/execute` | Execute promotion (async, returns task ID) |
| GET | `/v1/promotion/task/{taskId}` | Poll promotion task status and per-file results |

### Sync API (`/v1/sync`)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/v1/sync/permission` | Check if user has sync permission on a repository |
| POST | `/v1/sync/execute` | Execute sync (async, returns task ID) |
| GET | `/v1/sync/task/{taskId}` | Get sync task status |

### Sync Queue API (`/v1/sync/queue`)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/v1/sync/queue` | List all sync queue tasks (summary) |
| GET | `/v1/sync/queue/list` | List all sync queue tasks (detailed) |
| GET | `/v1/sync/queue/active` | List active (running) sync tasks |
| GET | `/v1/sync/queue/{taskId}` | Get specific sync task detail |
| GET | `/v1/sync/queue/config` | Get sync queue configuration |
| PUT | `/v1/sync/queue/config` | Update sync queue configuration |

### System Config API (`/v1/config`)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/v1/config/settings` | Get all system settings |
| PUT | `/v1/config/promotion/poolSize` | Update promotion thread pool size |
| PUT | `/v1/config/sync/poolSize` | Update sync thread pool size |
| PUT | `/v1/config/sync/maxQueueSize` | Update max sync queue size |
