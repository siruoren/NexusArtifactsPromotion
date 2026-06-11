# Nexus Artifacts Promotion Plugin

Version: 1.0.0-SNAPSHOT

A Nexus 3.45.0+ plugin for artifact promotion and remote repository sync, compatible with both commercial (PRO) and community (OSS) editions.

## Features

- **Artifact Promotion**: Promote artifacts between same-format repositories with idempotent path sync
- **Remote Repository Sync**: Sync artifacts from remote/proxy repositories with deduplication
- **Auto Privilege Management**: Per-repository promotion/sync privileges auto-created with repositories
- **Enterprise Security**: CSRF protection, permission checks at UI and API levels
- **Thread Pool Management**: Configurable thread pools for promotion and sync tasks
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
│  │  - Promotion/Sync dialog management                           │  │
│  │  - i18n support (dot-format keys)                             │  │
│  └──────────────────────────┬────────────────────────────────────┘  │
└─────────────────────────────┼───────────────────────────────────────┘
                              │ REST API (Siesta)
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
│  │  - Shiro-based permission validation                           │ │
│  │  - Per-repository + wildcard permission support                │ │
│  │  - Check at both UI button and API execution levels            │ │
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
│  └──────────────────┘  └──────────────────┘  └──────────────────┘  │
│                                                                      │
│  ┌──────────────────┐  ┌──────────────────────────────────────────┐ │
│  │TaskCacheManager  │  │ Security Components                       │ │
│  │ - Isolated cache │  │ ┌────────────────────────────────────┐   │ │
│  │ - Auto cleanup   │  │ │ PromotionSecurityContributor       │   │ │
│  └──────────────────┘  │ │ SyncSecurityContributor            │   │ │
│                         │ │ RepositoryPrivilegeListener        │   │ │
│                         │ │ PromotionPrivilegeDescriptor       │   │ │
│                         │ │ SyncPrivilegeDescriptor            │   │ │
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
    → Checks user promotion permission (GET /v1/promotion/permission)
    → If permitted, shows [Promote] button
    → User clicks [Promote]
    → Dialog opens: fetch target repositories (GET /v1/promotion/targets)
    → User selects target repository
    → Preview files to be promoted (GET /v1/promotion/preview)
    → User confirms promotion
    → Execute promotion (POST /v1/promotion/execute)
        → CSRF token (NX-ANTI-CSRF-TOKEN) added to request header
        → X-Nexus-UI: true header added
        → Permission re-checked server-side
        → Task submitted to promotion thread pool
        → Task ID returned
    → Frontend polls task status (GET /v1/promotion/status/{taskId})
    → On completion, dialog shows results (added/updated files)
```

### 2. Remote Repository Sync Flow

```
User browses remote/proxy repository → Clicks file/folder detail
    → PluginConfig detects panel and checks sync permission
    → If permitted, shows [Sync] button
    → User clicks [Sync]
    → Execute sync (POST /v1/sync/execute)
        → CSRF token added to request header
        → Permission re-checked server-side
        → Check for duplicate source directory sync
            → If duplicate found: cancel old task, mark as MIGRATED
        → Task submitted to sync thread pool
        → Task ID returned
    → Dialog shows: "Sync queue created, ID: {taskId}"
    → User can view all sync tasks in Sync Queue menu (Browse > Sync Queue)
```

### 3. Auto Privilege Management Flow

```
Plugin startup:
    → PromotionSecurityContributor.getContribution()
        → Creates wildcard privilege: nexus:artifacts-promotion:*
        → Creates per-repository privilege for each existing repository
    → SyncSecurityContributor.getContribution()
        → Creates wildcard privilege: nexus:artifacts-sync:*
        → Creates per-repository privilege for each existing repository

Repository created event:
    → RepositoryPrivilegeListener.onRepositoryCreated()
        → Creates promotion privilege for new repository
        → Creates sync privilege for new repository

Repository deleted event:
    → RepositoryPrivilegeListener.onRepositoryDeleted()
        → Removes promotion privilege for deleted repository
        → Removes sync privilege for deleted repository
```

### 4. Task Execution Flow

```
Task submitted → TaskExecutorService
    → Task wrapped with timeout watchdog (60 min)
    → Submitted to dedicated thread pool (promotion or sync)
    → TaskHandle stored in ConcurrentHashMap
    → On completion:
        → Task status updated (COMPLETED/FAILED)
        → Task cache auto-cleaned
        → Timeout watchdog stopped
    → On timeout:
        → Thread interrupted
        → Task status set to FAILED
        → Cache cleaned up
    → On shutdown:
        → Graceful thread pool shutdown (30s timeout)
        → All task caches cleaned
```

---

## Project Structure

```
src/main/java/com/nexus/artifacts/promotion/
├── NexusArtifactsPromotionPlugin.java          # Plugin identity
├── NexusArtifactsPromotionModule.java          # Guice module
├── ArtifactsPromotionUiPluginDescriptor.java   # UI plugin registration
├── config/
│   ├── PromotionCapability.java                # Promotion system settings
│   ├── PromotionCapabilityDescriptor.java
│   ├── SyncCapability.java                     # Sync system settings
│   └── SyncCapabilityDescriptor.java
├── exception/
│   ├── GlobalExceptionMapper.java              # JAX-RS exception handling
│   ├── PermissionDeniedException.java
│   ├── QueueFullException.java
│   ├── TaskExecutionException.java
│   └── TaskTimeoutException.java
├── model/
│   ├── FilePreviewResponse.java
│   ├── PromotionRequest.java
│   ├── PromotionTaskResult.java
│   ├── SyncRequest.java
│   ├── SyncTaskInfo.java
│   ├── TargetRepositoryList.java
│   └── TaskStatus.java
├── resource/
│   ├── PromotionResource.java                  # /v1/promotion/* REST API
│   ├── SyncResource.java                       # /v1/sync/* REST API
│   ├── SyncQueueResource.java                  # /v1/sync/queue/* REST API
│   └── SystemConfigResource.java               # /v1/config/* REST API
├── security/
│   ├── PermissionChecker.java                  # Shiro permission validation
│   ├── PromotionPrivilegeDescriptor.java       # Promotion privilege type
│   ├── PromotionSecurityContributor.java       # Auto-create promotion privileges
│   ├── SyncPrivilegeDescriptor.java            # Sync privilege type
│   ├── SyncSecurityContributor.java            # Auto-create sync privileges
│   └── RepositoryPrivilegeListener.java        # Repository event listener
└── service/
    ├── PromotionService.java                   # Promotion business logic
    ├── SyncService.java                        # Sync business logic
    ├── TaskCacheManager.java                   # Task cache with isolation
    └── TaskExecutorService.java                # Thread pool management

src/main/resources/
├── static/rapture/NX/artifactsPromotion/app/
│   └── PluginConfig.js                         # Frontend UI controller
└── com/nexus/artifacts/promotion/
    └── i18n/
        ├── messages_en.properties              # English i18n
        └── messages_zh.properties              # Chinese i18n
```

---

## Build & Deploy

### Prerequisites

- JDK 8
- Maven 3.6+
- Nexus Repository 3.45.0+

### Build

```bash
./build.sh clean package
```

Output: `target/nexus-artifacts-promotion-plugin-1.0.0-SNAPSHOT.jar`

### Deploy

```bash
cp target/nexus-artifacts-promotion-plugin-1.0.0-SNAPSHOT.jar <nexus-home>/deploy/
<nexus-home>/bin/nexus restart
```

### Verify

```bash
curl -u admin:admin http://localhost:8081/service/rest/v1/promotion/permission?repository=maven-releases&format=maven2
```

---

## Configuration

### System Settings

| Setting | Description | Default |
|---------|-------------|---------|
| Promotion Thread Pool Size | Number of threads for concurrent promotion tasks | 4 |
| Sync Thread Pool Size | Number of threads for concurrent sync tasks | 4 |
| Max Sync Queue Size | Maximum number of concurrent sync queue tasks | 20 |

### Permissions

| Privilege | Pattern | Description |
|-----------|---------|-------------|
| nx-artifacts-promotion-all | `nexus:artifacts-promotion:*` | Full promotion permission for all repositories |
| Promote from {repo} ({format}) | `nexus:artifacts-promotion:{repo}:{format}` | Per-repository promotion permission |
| nx-artifacts-sync-all | `nexus:artifacts-sync:*` | Full sync permission for all repositories |
| Sync from {repo} ({format}) | `nexus:artifacts-sync:{repo}:{format}` | Per-repository sync permission |

---

## REST API

| Method | Path | Description |
|--------|------|-------------|
| GET | `/v1/promotion/permission` | Check promotion permission |
| GET | `/v1/promotion/targets` | List eligible target repositories |
| GET | `/v1/promotion/preview` | Preview files to be promoted |
| POST | `/v1/promotion/execute` | Execute promotion |
| GET | `/v1/promotion/status/{taskId}` | Get promotion task status |
| GET | `/v1/sync/permission` | Check sync permission |
| POST | `/v1/sync/execute` | Execute sync |
| GET | `/v1/sync/status/{taskId}` | Get sync task status |
| GET | `/v1/sync/queue` | List sync queue tasks |
| GET | `/v1/config/promotion` | Get promotion config |
| PUT | `/v1/config/promotion` | Update promotion config |
| GET | `/v1/config/sync` | Get sync config |
| PUT | `/v1/config/sync` | Update sync config |
