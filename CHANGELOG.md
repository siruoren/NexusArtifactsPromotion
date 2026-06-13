# Changelog

All notable changes to this project will be documented in this file.

## [1.0.0] - 2026-06-13

### Added

- **Artifact Promotion**: Promote artifacts between same-format repositories via HTTP download/upload
  - Support promotion of directories (all files under a directory), single files, and Docker images
  - Preview files before promotion with source/target path mapping
  - Async promotion tasks with real-time per-file status tracking
  - Progress window with per-file status (pending/processing/success/failed)
  - Progress window stays open after completion, user closes manually
  - Configurable promotion thread pool size via System Settings

- **Remote Repository Sync**: Sync artifacts from remote/proxy repositories via StorageTx direct copy
  - Support sync of directories, files, and Docker images
  - Deduplication: same source directory re-submission keeps only the latest task
  - Old duplicate tasks are terminated and marked as migrated to the new task ID
  - Configurable sync thread pool size and max queue size (default: 20) via System Settings
  - SyncQueueSizeTask scheduled task for runtime queue size updates

- **Permission Integration**:
  - Uses Nexus built-in `repository-view` permissions (no custom privilege configuration needed)
  - `nexus:repository-view:{format}:{repo}:edit` for write (promote/sync to target)
  - `nexus:repository-view:{format}:{repo}:read` for read (view/promote from source)
  - Permission checks at both UI button and API execution levels

- **UI Extensions**:
  - "Promote" button on file and directory detail panels (ComponentAssetInfo & ComponentFolderInfo)
  - "Sync" button on remote repository file and directory detail panels
  - Promotion dialog with target repository selection and file list preview
  - Sync queue menu under Browse sidebar (login required)
  - Sync queue page showing task ID, source/target, status, timestamps, and user

- **System Settings**:
  - Promotion Management capability: configure promotion thread pool size
  - Sync Management capability: configure sync thread pool size and max queue size
  - Task cache auto-cleanup on task completion with directory isolation

- **Internationalization**: Chinese and English i18n support using dot-format keys

- **Compatibility**: Works with both Nexus PRO and OSS editions (3.45.0+)

- **Build**: Auto-generate prod.js/debug.js from PluginConfig.js during Maven build (initialize phase)

### Fixed

- Fixed 401 Authentication Required error on promotion/sync POST requests by adding CSRF token and X-Nexus-UI header
- Fixed directory view not showing promotion/sync buttons by overriding ComponentFolderInfo.setModel to fire custom events
- Fixed plugin UI not loading on OSS edition due to disabled features
- Fixed REST API 404 errors by ensuring Resource interface implementation and correct Sisu index
- Fixed directory promotion going to wrong branch (404) by adding @JsonProperty("isDirectory") annotation
- Fixed task ID lookup failure caused by JSON escaping of task IDs
- Fixed promotion progress showing "已完成:0/N" despite successful backend processing:
  - Root cause: ExtJS 4.2 ProgressBar uses `updateText()` not `setText()`
  - Root cause: maven-bundle-plugin did not include antrun-generated prod.js/debug.js in JAR
  - Switched doPoll to use raw XMLHttpRequest instead of apiRequest for reliability
  - Use backend item count directly for progress display instead of relying on store state
- Fixed continuous background polling after task completion by properly stopping interval and setting isFinished flag
- Fixed "Uncaught ReferenceError: pollInterval is not defined" by storing interval ID on window object
- Fixed promotion status display: only show pending/processing/success/failed, removed change type detection
- Fixed directory promotion to process files directly from popup list without downloading directories
