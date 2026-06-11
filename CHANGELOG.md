# Changelog

All notable changes to this project will be documented in this file.

## [1.0.0-SNAPSHOT] - 2026-06-11

### Added

- **Artifact Promotion**: Promote artifacts between same-format repositories with idempotent path sync
  - Support promotion of directories (all files under a directory), single files, and Docker images
  - Preview files before promotion with source/target path mapping
  - Async promotion tasks with real-time status tracking
  - Configurable promotion thread pool size via System Settings

- **Remote Repository Sync**: Sync artifacts from remote/proxy repositories
  - Support sync of directories, files, and Docker images
  - Deduplication: same source directory re-submission keeps only the latest task
  - Old duplicate tasks are terminated and marked as migrated to the new task ID
  - Configurable sync thread pool size and max queue size (default: 20) via System Settings

- **Security & Permissions**:
  - Per-repository promotion and sync privilege entries auto-created for all existing repositories
  - Auto-create promotion/sync privileges when new repositories are created
  - Auto-remove privileges when repositories are deleted
  - Wildcard permissions (`nexus:artifacts-promotion:*`, `nexus:artifacts-sync:*`) for full access
  - CSRF token handling (NX-ANTI-CSRF-TOKEN) for all POST/PUT/DELETE requests
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

### Fixed

- Fixed 401 Authentication Required error on promotion/sync POST requests by adding CSRF token and X-Nexus-UI header
- Fixed directory view not showing promotion/sync buttons by overriding ComponentFolderInfo.setModel to fire custom events
- Fixed plugin UI not loading on OSS edition due to disabled features
- Fixed privilege permission domain mismatch with Nexus wildcard permission patterns
- Fixed REST API 404 errors by ensuring Resource interface implementation and correct Sisu index
