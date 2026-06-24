/**
 * Nexus Artifacts Promotion Plugin - Plugin Configuration
 *
 * Registers UI extensions using Nexus Rapture (ExtJS) framework:
 * - Sync Queue menu under Browse
 * - Promotion/Sync buttons on asset details
 *
 * i18n: Uses dot-format keys with en/zh bundles.
 */
/*global Ext, NX*/

Ext.define('NX.artifactsPromotion.app.PluginConfig', {
  '@aggregate_priority': 100,

  namespaces: [
    'NX.artifactsPromotion'
  ],

  controllers: [
    {
      id: 'NX.artifactsPromotion.controller.Promotion',
      active: function () {
        try {
          return NX.app.Application.bundleActive('nexus-artifacts-promotion-plugin');
        }
        catch (e) {
          console.error('Promotion plugin load failed', e);
          return false;
        }
      }
    }
  ]
});

// ==================== i18n ====================

Ext.define('NX.artifactsPromotion.I18n', {
  singleton: true,

  bundles: {},

  currentLocale: 'en',

  en: {
    "promotion.button.text": "Promote Artifact",
    "promotion.button.text.directory": "Promote Directory",
    "promotion.modal.title": "Promote Artifact",
    "promotion.modal.targetRepository": "Target Repository:",
    "promotion.modal.filesToPromote": "Files to Promote:",
    "promotion.modal.cancel": "Cancel",
    "promotion.modal.promote": "Promote",
    "promotion.modal.promoting": "Promoting...",
    "promotion.modal.action.new": "NEW",
    "promotion.modal.action.update": "UPDATE",
    "promotion.progress.total": "Total: {0} files",
    "promotion.progress.completed": "Completed: {0}/{1}",
    "promotion.progress.processing": "Processing",
    "promotion.progress.pending": "Pending",
    "promotion.progress.success": "Success",
    "promotion.progress.failed": "Failed",
    "promotion.progress.cancelled": "Cancelled",
    "promotion.progress.skipped": "Skipped",
    "promotion.result.title.success": "Promotion Success",
    "promotion.result.title.failed": "Promotion Failed",
    "promotion.result.title.cancelled": "Promotion Cancelled",
    "promotion.result.targetRepository": "Target Repository:",
    "promotion.result.status": "Status:",
    "promotion.result.error": "Error:",
    "promotion.result.items": "Promoted Items:",
    "promotion.result.close": "Close",
    "promotion.permission.denied": "You do not have promotion permission for this repository.",
    "promotion.permission.denied.admin": "You do not have promotion permission. Please contact your administrator.",
    "promotion.permission.write.denied": "User '{0}' does not have promotion write permission for repository '{1}'.",
    "promotion.noTargets.title": "No Target Repositories",
    "promotion.noTargets.message": "No available target repositories with the same format and your write permission.",
    "promotion.preview.failed": "Preview Failed",
    "promotion.execute.failed": "Promotion Failed",

    "sync.button.text": "Sync",
    "sync.button.text.directory": "Sync Directory",
    "sync.incremental.title": "Sync Mode",
    "sync.incremental.message": "Choose sync mode for this operation:",
    "sync.incremental.full": "Full Sync",
    "sync.incremental.full.desc": "Re-download all files from remote",
    "sync.incremental.incremental": "Incremental Sync",
    "sync.incremental.incremental.desc": "Only sync files with different MD5 checksums (skips unchanged files)",
    "sync.permission.denied": "You do not have sync permission for this repository.",
    "sync.permission.denied.admin": "You do not have sync permission. Please contact your administrator.",
    "sync.permission.disabled.tooltip": "You do not have delete permission for this repository. Sync requires delete permission.",
    "sync.execute.failed": "Sync Failed",
    "sync.queue.created.title": "Sync Queue Created",
    "sync.queue.created.queueId": "Queue ID:",
    "sync.queue.created.repository": "Repository:",
    "sync.queue.created.path": "Path:",
    "sync.queue.created.message": "Sync task has been submitted to the queue.",
    "sync.queue.created.viewQueue": "View Queue",
    "sync.queue.created.close": "Close",
    "sync.progress.processing": "Syncing",
    "sync.progress.success": "Synced",
    "sync.progress.failed": "Sync Failed",
    "sync.progress.pending": "Pending",

    "sync.queue.page.title": "Task Queue",
    "sync.queue.page.loginRequired": "Please log in to view task queue.",
    "sync.queue.page.loading": "Loading tasks...",
    "sync.queue.page.noTasks": "No tasks found.",
    "sync.queue.page.loadFailed": "Failed to load tasks:",
    "sync.queue.table.queueId": "Queue ID",
    "sync.queue.table.sourceRepository": "Source Repository",
    "sync.queue.table.targetRepository": "Target Repository",
    "sync.queue.table.path": "Path",
    "sync.queue.table.fileDetails": "File Details",
    "sync.queue.table.status": "Status",
    "sync.queue.table.startTime": "Start Time",
    "sync.queue.table.endTime": "End Time",
    "sync.queue.table.username": "User",
    "sync.queue.table.result": "Result",
    "sync.queue.table.items": "items",
    "sync.queue.table.refresh": "Refresh",
    "sync.queue.filter.repository": "Repository",
    "sync.queue.filter.path": "Path",
    "sync.queue.filter.status": "Status",
    "sync.queue.filter.username": "User",
    "sync.queue.filter.taskId": "Queue ID",
    "sync.queue.filter.all": "All",
    "sync.queue.filter.search": "Search",
    "sync.queue.filter.reset": "Reset",
    "sync.queue.status.failed": "Failed",
    "sync.queue.status.running": "Running",
    "sync.queue.status.cancelled": "Terminated",
    "sync.queue.status.completed": "Completed",
    "sync.queue.table.duration": "Duration (min)",
    "sync.queue.table.taskType": "Type",
    "sync.queue.table.terminate": "Terminate",
    "sync.queue.table.terminateConfirm": "Are you sure you want to terminate this task?",
    "sync.queue.table.terminateSuccess": "Task terminated successfully",
    "sync.queue.table.terminateFailed": "Failed to terminate task",
    "sync.queue.table.terminateNoPermission": "No permission to terminate this task",
    "sync.queue.taskType.sync": "Sync",
    "sync.queue.taskType.promotion": "Promotion",
    "sync.queue.filter.taskType": "Type",
    "sync.queue.config.title": "Queue Configuration",
    "sync.queue.config.maxSyncQueueSize": "Max Sync Queue Size",
    "sync.queue.config.syncPoolSize": "Sync Pool Size",
    "sync.queue.config.promotionPoolSize": "Promotion Pool Size",
    "sync.queue.config.save": "Save",
    "sync.queue.config.saved": "Configuration saved",
    "sync.queue.config.saveFailed": "Failed to save configuration",

    "docker.promote.button": "Docker Promote",
    "docker.sync.button": "Docker Sync",
    "docker.modal.title.promote": "Promote Docker Image",
    "docker.modal.title.sync": "Sync Docker Image",
    "docker.modal.image": "Image:",
    "docker.modal.tags": "Tags:",
    "docker.modal.tags.all": "All Tags",
    "docker.modal.tags.select": "Select Tags",
    "docker.modal.loading": "Loading...",
    "docker.modal.noImages": "No Docker images found in this repository.",
    "docker.modal.noTags": "No tags found for this image.",
    "docker.modal.targetRepository": "Target Repository:",
    "docker.modal.promote": "Promote",
    "docker.modal.sync": "Sync",
    "docker.modal.promoting": "Promoting...",
    "docker.modal.syncing": "Syncing...",
    "docker.modal.cancel": "Cancel",
    "docker.modal.close": "Close",
    "docker.modal.allImages": "All Images (Directory Level)",
    "docker.modal.specificImage": "Specific Image",
    "docker.modal.scope": "Scope:",
    "docker.modal.filesToPromote": "Images to Promote",
    "docker.modal.filesToSync": "Images to Sync",
    "docker.progress.title": "Docker Operation Progress",
    "docker.progress.status": "Status:",
    "docker.progress.processing": "Processing",
    "docker.progress.success": "Success",
    "docker.progress.failed": "Failed",
    "docker.progress.skipped": "Skipped",
    "docker.progress.pending": "Pending",

    "common.noPermission": "No Permission",
    "common.error": "Error",
    "common.ok": "OK"
  },

  zh: {
    "promotion.button.text": "\u664b\u7ea7\u5de5\u4ef6",
    "promotion.button.text.directory": "\u664b\u7ea7\u76ee\u5f55",
    "promotion.modal.title": "\u664b\u7ea7\u5de5\u4ef6",
    "promotion.modal.targetRepository": "\u76ee\u6807\u4ed3\u5e93\uff1a",
    "promotion.modal.filesToPromote": "\u5f85\u664b\u7ea7\u6587\u4ef6\uff1a",
    "promotion.modal.cancel": "\u53d6\u6d88",
    "promotion.modal.promote": "\u664b\u7ea7",
    "promotion.modal.promoting": "\u664b\u7ea7\u4e2d...",
    "promotion.modal.action.new": "\u65b0\u589e",
    "promotion.modal.action.update": "\u66f4\u65b0",
    "promotion.progress.total": "\u5178\u603b: {0} \u4e2a\u6587\u4ef6",
    "promotion.progress.completed": "\u5df2\u5b8c\u6210: {0}/{1}",
    "promotion.progress.processing": "\u664b\u7ea7\u4e2d",
    "promotion.progress.pending": "\u7b49\u5f85\u4e2d",
    "promotion.progress.success": "\u6210\u529f",
    "promotion.progress.failed": "\u5931\u8d25",
    "promotion.progress.cancelled": "\u7ec8\u6b62",
    "promotion.progress.skipped": "\u6210\u529f",
    "promotion.result.title.success": "\u664b\u7ea7\u6210\u529f",
    "promotion.result.title.failed": "\u664b\u7ea7\u5931\u8d25",
    "promotion.result.title.cancelled": "\u664b\u7ea7\u7ec8\u6b62",
    "promotion.result.targetRepository": "\u76ee\u6807\u4ed3\u5e93\uff1a",
    "promotion.result.status": "\u72b6\u6001\uff1a",
    "promotion.result.error": "\u9519\u8bef\uff1a",
    "promotion.result.items": "\u664b\u7ea7\u9879\u76ee\uff1a",
    "promotion.result.close": "\u5173\u95ed",
    "promotion.permission.denied": "\u60a8\u6ca1\u6709\u8be5\u4ed3\u5e93\u7684\u664b\u7ea7\u6743\u9650\u3002",
    "promotion.permission.denied.admin": "\u60a8\u6ca1\u6709\u664b\u7ea7\u6743\u9650\uff0c\u8bf7\u8054\u7cfb\u7ba1\u7406\u5458\u3002",
    "promotion.permission.write.denied": "\u7528\u6237 '{0}' \u6ca1\u6709 '{1}' \u4ed3\u5e93\u7684\u664b\u7ea7\u5199\u5165\u6743\u9650\u3002",
    "promotion.noTargets.title": "\u65e0\u53ef\u7528\u76ee\u6807\u4ed3\u5e93",
    "promotion.noTargets.message": "\u6ca1\u6709\u76f8\u540c\u683c\u5f0f\u4e14\u60a8\u62e5\u6709\u5199\u5165\u6743\u9650\u7684\u76ee\u6807\u4ed3\u5e93\u3002",
    "promotion.preview.failed": "\u9884\u89c8\u5931\u8d25",
    "promotion.execute.failed": "\u664b\u7ea7\u5931\u8d25",

    "sync.button.text": "\u540c\u6b65",
    "sync.button.text.directory": "\u540c\u6b65\u76ee\u5f55",
    "sync.incremental.title": "\u540c\u6b65\u6a21\u5f0f",
    "sync.incremental.message": "\u8bf7\u9009\u62e9\u540c\u6b65\u6a21\u5f0f\uff1a",
    "sync.incremental.full": "\u5168\u91cf\u540c\u6b65",
    "sync.incremental.full.desc": "\u91cd\u65b0\u4e0b\u8f7d\u8fdc\u7a0b\u4ed3\u5e93\u7684\u6240\u6709\u6587\u4ef6",
    "sync.incremental.incremental": "\u589e\u91cf\u540c\u6b65",
    "sync.incremental.incremental.desc": "\u4ec5\u540c\u6b65MD5\u4e0d\u4e00\u81f4\u7684\u6587\u4ef6\uff08\u8df3\u8fc7\u672a\u53d8\u66f4\u7684\u6587\u4ef6\uff09",
    "sync.permission.denied": "\u60a8\u6ca1\u6709\u8be5\u4ed3\u5e93\u7684\u540c\u6b65\u6743\u9650\u3002",
    "sync.permission.denied.admin": "\u60a8\u6ca1\u6709\u540c\u6b65\u6743\u9650\uff0c\u8bf7\u8054\u7cfb\u7ba1\u7406\u5458\u3002",
    "sync.permission.disabled.tooltip": "\u60a8\u6ca1\u6709\u8be5\u4ed3\u5e93\u7684\u5220\u9664\u6743\u9650\uff0c\u540c\u6b65\u9700\u8981\u5220\u9664\u6743\u9650\u624d\u80fd\u6267\u884c\u3002",
    "sync.execute.failed": "\u540c\u6b65\u5931\u8d25",
    "sync.queue.created.title": "\u540c\u6b65\u961f\u5217\u5df2\u521b\u5efa",
    "sync.queue.created.queueId": "\u961f\u5217ID\uff1a",
    "sync.queue.created.repository": "\u4ed3\u5e93\uff1a",
    "sync.queue.created.path": "\u8def\u5f84\uff1a",
    "sync.queue.created.message": "\u540c\u6b65\u4efb\u52a1\u5df2\u63d0\u4ea4\u5230\u961f\u5217\u3002",
    "sync.queue.created.viewQueue": "\u67e5\u770b\u961f\u5217",
    "sync.queue.created.close": "\u5173\u95ed",
    "sync.progress.processing": "\u540c\u6b65\u4e2d",
    "sync.progress.success": "\u540c\u6b65\u6210\u529f",
    "sync.progress.failed": "\u540c\u6b65\u5931\u8d25",
    "sync.progress.pending": "\u7b49\u5f85\u4e2d",

    "sync.queue.page.title": "\u4efb\u52a1\u961f\u5217",
    "sync.queue.page.loginRequired": "\u8bf7\u767b\u5f55\u540e\u67e5\u770b\u4efb\u52a1\u961f\u5217\u3002",
    "sync.queue.page.loading": "\u52a0\u8f7d\u4efb\u52a1\u4e2d...",
    "sync.queue.page.noTasks": "\u6682\u65e0\u4efb\u52a1\u3002",
    "sync.queue.page.loadFailed": "\u52a0\u8f7d\u4efb\u52a1\u5931\u8d25\uff1a",
    "sync.queue.table.queueId": "\u961f\u5217ID",
    "sync.queue.table.sourceRepository": "\u6e90\u4ed3\u5e93",
    "sync.queue.table.targetRepository": "\u76ee\u6807\u4ed3\u5e93",
    "sync.queue.table.path": "\u8def\u5f84",
    "sync.queue.table.fileDetails": "\u6587\u4ef6\u8be6\u60c5",
    "sync.queue.table.status": "\u72b6\u6001",
    "sync.queue.table.startTime": "\u5f00\u59cb\u65f6\u95f4",
    "sync.queue.table.endTime": "\u7ed3\u675f\u65f6\u95f4",
    "sync.queue.table.username": "\u7528\u6237",
    "sync.queue.table.result": "\u7ed3\u679c",
    "sync.queue.table.items": "\u9879",
    "sync.queue.table.refresh": "\u5237\u65b0",
    "sync.queue.filter.repository": "\u4ed3\u5e93",
    "sync.queue.filter.path": "\u8def\u5f84",
    "sync.queue.filter.status": "\u72b6\u6001",
    "sync.queue.filter.username": "\u7528\u6237",
    "sync.queue.filter.taskId": "\u961f\u5217ID",
    "sync.queue.filter.all": "\u5168\u90e8",
    "sync.queue.filter.search": "\u641c\u7d22",
    "sync.queue.filter.reset": "\u91cd\u7f6e",
    "sync.queue.status.failed": "\u5931\u8d25",
    "sync.queue.status.running": "\u8fdb\u884c\u4e2d",
    "sync.queue.status.cancelled": "\u7ec8\u6b62",
    "sync.queue.status.completed": "\u5b8c\u6210",
    "sync.queue.table.duration": "\u8017\u65f6\uff08\u5206\u949f\uff09",
    "sync.queue.table.taskType": "\u7c7b\u578b",
    "sync.queue.table.terminate": "\u7ec8\u6b62",
    "sync.queue.table.terminateConfirm": "\u786e\u5b9a\u8981\u7ec8\u6b62\u8be5\u4efb\u52a1\u5417\uff1f",
    "sync.queue.table.terminateSuccess": "\u4efb\u52a1\u5df2\u7ec8\u6b62",
    "sync.queue.table.terminateFailed": "\u7ec8\u6b62\u4efb\u52a1\u5931\u8d25",
    "sync.queue.table.terminateNoPermission": "\u65e0\u6743\u7ec8\u6b62\u8be5\u4efb\u52a1",
    "sync.queue.taskType.sync": "\u540c\u6b65",
    "sync.queue.taskType.promotion": "\u664b\u7ea7",
    "sync.queue.filter.taskType": "\u7c7b\u578b",
    "sync.queue.config.title": "\u961f\u5217\u914d\u7f6e",
    "sync.queue.config.maxSyncQueueSize": "\u6700\u5927\u540c\u6b65\u961f\u5217\u6570",
    "sync.queue.config.syncPoolSize": "\u540c\u6b65\u7ebf\u7a0b\u6c60\u5927\u5c0f",
    "sync.queue.config.promotionPoolSize": "\u664b\u7ea7\u7ebf\u7a0b\u6c60\u5927\u5c0f",
    "sync.queue.config.save": "\u4fdd\u5b58",
    "sync.queue.config.saved": "\u914d\u7f6e\u5df2\u4fdd\u5b58",
    "sync.queue.config.saveFailed": "\u4fdd\u5b58\u914d\u7f6e\u5931\u8d25",

    "docker.promote.button": "Docker\u664b\u7ea7",
    "docker.sync.button": "Docker\u540c\u6b65",
    "docker.modal.title.promote": "\u664b\u7ea7Docker\u955c\u50cf",
    "docker.modal.title.sync": "\u540c\u6b65Docker\u955c\u50cf",
    "docker.modal.image": "\u955c\u50cf\uff1a",
    "docker.modal.tags": "\u6807\u7b7e\uff1a",
    "docker.modal.tags.all": "\u5168\u90e8\u6807\u7b7e",
    "docker.modal.tags.select": "\u9009\u62e9\u6807\u7b7e",
    "docker.modal.loading": "\u52a0\u8f7d\u4e2d...",
    "docker.modal.noImages": "\u672a\u627e\u5230Docker\u955c\u50cf\u3002",
    "docker.modal.noTags": "\u672a\u627e\u5230\u8be5\u955c\u50cf\u7684\u6807\u7b7e\u3002",
    "docker.modal.targetRepository": "\u76ee\u6807\u4ed3\u5e93\uff1a",
    "docker.modal.promote": "\u664b\u7ea7",
    "docker.modal.sync": "\u540c\u6b65",
    "docker.modal.promoting": "\u664b\u7ea7\u4e2d...",
    "docker.modal.syncing": "\u540c\u6b65\u4e2d...",
    "docker.modal.cancel": "\u53d6\u6d88",
    "docker.modal.close": "\u5173\u95ed",
    "docker.modal.allImages": "\u6240\u6709\u955c\u50cf\uff08\u76ee\u5f55\u7ea7\u522b\uff09",
    "docker.modal.specificImage": "\u6307\u5b9a\u955c\u50cf",
    "docker.modal.scope": "\u8303\u56f4\uff1a",
    "docker.modal.filesToPromote": "\u664b\u7ea7\u955c\u50cf\u5217\u8868",
    "docker.modal.filesToSync": "\u540c\u6b65\u955c\u50cf\u5217\u8868",
    "docker.progress.title": "Docker\u64cd\u4f5c\u8fdb\u5ea6",
    "docker.progress.status": "\u72b6\u6001\uff1a",
    "docker.progress.processing": "\u5904\u7406\u4e2d",
    "docker.progress.success": "\u6210\u529f",
    "docker.progress.failed": "\u5931\u8d25",
    "docker.progress.skipped": "\u672a\u66f4\u65b0",
    "docker.progress.pending": "\u7b49\u5f85\u4e2d",

    "common.noPermission": "\u65e0\u6743\u9650",
    "common.error": "\u9519\u8bef",
    "common.ok": "\u786e\u5b9a"
  },

  constructor: function () {
    this.bundles['en'] = this.en;
    this.bundles['zh'] = this.zh;
    this.currentLocale = this.detectLocale();
  },

  detectLocale: function () {
    try {
      if (NX && NX.State && NX.State.getValue) {
        var locale = NX.State.getValue('locale');
        if (locale) {
          return locale.startsWith('zh') ? 'zh' : 'en';
        }
      }
    } catch (e) { /* ignore */ }
    try {
      var nav = navigator.language || navigator.userLanguage || 'en';
      return nav.startsWith('zh') ? 'zh' : 'en';
    } catch (e) { return 'en'; }
  },

  t: function (key) {
    var bundle = this.bundles[this.currentLocale] || this.bundles['en'];
    var template;
    if (bundle && bundle.hasOwnProperty(key)) {
      template = bundle[key];
    } else {
      var enBundle = this.bundles['en'];
      if (enBundle && enBundle.hasOwnProperty(key)) {
        template = enBundle[key];
      } else {
        return key;
      }
    }
    // Support {0}, {1}, ... parameter substitution
    if (arguments.length > 1) {
      for (var i = 1; i < arguments.length; i++) {
        template = template.replace('{' + (i - 1) + '}', arguments[i]);
      }
    }
    return template;
  }
});

// Shorthand with parameter substitution: _t('key', arg0, arg1) replaces {0}, {1} etc.
function _t(key) {
  var text = NX.artifactsPromotion.I18n.t(key);
  for (var i = 1; i < arguments.length; i++) {
    text = text.replace(new RegExp('\\{' + (i - 1) + '\\}', 'g'), arguments[i]);
  }
  return text;
}

// ==================== Utility ====================

function sanitize(str) {
  if (str === null || str === undefined) return '';
  var div = document.createElement('div');
  div.appendChild(document.createTextNode(String(str)));
  return div.innerHTML;
}

/**
 * Find a store record index by matching a Docker asset path to image:tag format.
 * Backend returns paths like "v2/myapp/manifests/latest" but store has "myapp:latest".
 * Also tries matching by originalPath field.
 */
function findDockerPathIndex(store, backendPath) {
  if (!backendPath || !store) return -1;

  // Try matching by originalPath field first (Docker promotion modal stores original paths)
  var idx = store.findExact('originalPath', backendPath);
  if (idx >= 0) return idx;

  // Try converting Docker path to image:tag format
  // Pattern: v2/<image>/manifests/<tag>
  var manifestMatch = backendPath.match(/^v2\/(.+?)\/manifests\/(.+)$/);
  if (manifestMatch) {
    var imageTag = manifestMatch[1] + ':' + manifestMatch[2];
    idx = store.findExact('path', imageTag);
    if (idx >= 0) return idx;
  }

  // Pattern: v2/<image>/blobs/<digest> - match by image name prefix
  var blobsMatch = backendPath.match(/^v2\/(.+?)\/blobs\//);
  if (blobsMatch) {
    var imageName = blobsMatch[1];
    store.each(function (rec) {
      var p = rec.get('path') || '';
      if (p.indexOf(imageName + ':') === 0 || p.indexOf(imageName + '/') === 0) {
        idx = store.indexOf(rec);
        return false;
      }
    });
    if (idx >= 0) return idx;
  }

  return -1;
}

function apiRequest(method, path, data) {
  return new Ext.Promise(function (resolve, reject) {
    // Build request options - inherit from Ext.Ajax defaultHeaders to get
    // Nexus-native auth/CSRF tokens already configured by the framework
    var defaultHeaders = Ext.Ajax.defaultHeaders || {};
    var csrfToken = null;
    var csrfSource = 'none';

    // Try multiple sources for CSRF token (Nexus version dependent)
    // Source 1: Already set in Ext.Ajax.defaultHeaders
    if (defaultHeaders['NX-ANTI-CSRF-TOKEN']) {
      csrfToken = defaultHeaders['NX-ANTI-CSRF-TOKEN'];
      csrfSource = 'defaultHeaders';
    }
    // Source 2: Cookie (older Nexus versions)
    if (!csrfToken) {
      var cookies = document.cookie.split(';');
      for (var i = 0; i < cookies.length; i++) {
        var cookie = cookies[i].trim();
        if (cookie.indexOf('NX-ANTI-CSRF-TOKEN=') === 0) {
          csrfToken = cookie.substring('NX-ANTI-CSRF-TOKEN='.length);
          csrfSource = 'cookie';
          break;
        }
      }
    }

    var opts = {
      method: method,
      url: '/service/rest/v1' + path,
      headers: {
        'Accept': 'application/json',
        'X-Nexus-UI': 'true'
        // CSRF token added below if available
      },
      success: function (response) {
        var status = response.status;
        if (status === 204 || !response.responseText) {
          resolve({});
          return;
        }
        try {
          var data = Ext.decode(response.responseText);
          resolve(data);
        }
        catch (e) {
          console.warn('apiRequest: failed to decode JSON response for', path, e);
          resolve({});
        }
      },
      failure: function (response) {
        var status = response.status;
        if (status === 401) {
          reject(new Error('Authentication required (401): please refresh the page and try again'));
          return;
        }
        if (status === 403) {
          try {
            var errBody = Ext.decode(response.responseText);
            var permErr = new Error(errBody.error || 'No permission');
            permErr.username = errBody.username;
            permErr.repository = errBody.repository;
            permErr.responseData = errBody;
            reject(permErr);
          } catch (e) {
            reject(new Error('No permission'));
          }
          return;
        }
        try {
          var err = Ext.decode(response.responseText);
          reject(new Error(err.error || 'Request failed: ' + status));
        }
        catch (e) {
          // Response is not JSON (e.g., HTML 404 page from Nexus)
          var errorMsg = 'Request failed: ' + status;
          if (response.responseText && response.responseText.indexOf('cannot find resource') >= 0) {
            errorMsg = 'API endpoint not found (' + status + '): ' + path + '. Please ensure the plugin is properly deployed and Nexus has been restarted.';
          }
          reject(new Error(errorMsg));
        }
      }
    };

    // Merge any existing default headers (auth, session, etc.)
    Ext.applyIf(opts.headers, defaultHeaders);

    if (data) {
      opts.jsonData = data;
      opts.headers['Content-Type'] = 'application/json';
    }
    // Always add CSRF token if found (overrides default)
    if (csrfToken) {
      opts.headers['NX-ANTI-CSRF-TOKEN'] = csrfToken;
    }

    Ext.Ajax.request(opts);
  });
}

// ==================== Permission Check ====================

function checkPromotionPermission(repository, format) {
  return apiRequest('GET', '/promotion/permission?repository=' +
    encodeURIComponent(repository) + '&format=' + encodeURIComponent(format))
    .then(function (result) { return result.hasPermission === true; })
    .catch(function () { return false; });
}

function checkSyncPermission(repository, format) {
  return apiRequest('GET', '/sync/permission?repository=' +
    encodeURIComponent(repository) + '&format=' + encodeURIComponent(format))
    .then(function (result) { return result.hasPermission === true; })
    .catch(function () { return false; });
}

// ==================== Dialog Helpers ====================

function showAlertDialog(title, message) {
  if (NX && NX.Dialogs && NX.Dialogs.showError) {
    NX.Dialogs.showError(title, message);
    return;
  }
  alert(title + '\n' + message);
}

// ==================== Sync Queue View ====================

Ext.define('NX.artifactsPromotion.view.SyncQueue', {
  extend: 'Ext.panel.Panel',
  alias: 'widget.nx-artifacts-promotion-syncqueue',

  title: _t('sync.queue.page.title'),
  layout: 'fit',

  initComponent: function () {
    var me = this;

    me.allDataStore = Ext.create('Ext.data.Store', {
      fields: ['taskId', 'taskType', 'sourceRepository', 'targetRepository', 'path', 'fileDetails',
        'status', 'startTime', 'endTime', 'username', 'result', 'errorMessage'],
      proxy: {
        type: 'ajax',
        url: '/service/rest/v1/sync/queue',
        reader: { type: 'json' }
      },
      autoLoad: true,
      listeners: {
        load: function (store) {
          me.applyFilters();
          me.checkAllFinished();
        }
      }
    });

    me.store = Ext.create('Ext.data.Store', {
      fields: ['taskId', 'taskType', 'sourceRepository', 'targetRepository', 'path', 'fileDetails',
        'status', 'startTime', 'endTime', 'username', 'result', 'errorMessage'],
      pageSize: 20,
      proxy: {
        type: 'memory',
        enablePaging: true
      }
    });

    var statusRenderer = function (val) {
      switch ((val || '').toLowerCase()) {
        case 'running':
          return '<span style="color:#337ab7;font-weight:bold;">' + _t('sync.queue.status.running') + '</span>';
        case 'completed':
          return '<span style="color:#5cb85c;font-weight:bold;">' + _t('sync.queue.status.completed') + '</span>';
        case 'failed':
          return '<span style="color:#d9534f;font-weight:bold;">' + _t('sync.queue.status.failed') + '</span>';
        case 'cancelled':
          return '<span style="color:#f0ad4e;font-weight:bold;">' + _t('sync.queue.status.cancelled') + '</span>';
        case 'pending':
          return '<span style="color:#999;">' + _t('sync.queue.status.running') + '</span>';
        case 'migrated':
          return '<span style="color:#5cb85c;">' + _t('sync.queue.status.completed') + '</span>';
        default:
          return '<span style="color:#999;">' + sanitize(val || '') + '</span>';
      }
    };

    var timeRenderer = function (val) {
      if (!val) return '';
      var d = new Date(val);
      return d.toLocaleString();
    };

    var tipRenderer = function (value, meta) {
      meta.tdAttr = 'data-qtip="' + sanitize(value || '') + '"';
      return value;
    };

    var tipStatusRenderer = function (value, meta) {
      meta.tdAttr = 'data-qtip="' + sanitize(value || '') + '"';
      return statusRenderer(value);
    };

    var tipTimeRenderer = function (value, meta) {
      var display = timeRenderer(value);
      meta.tdAttr = 'data-qtip="' + sanitize(display) + '"';
      return display;
    };

    var durationRenderer = function (value, meta, record) {
      var start = record.get('startTime');
      var end = record.get('endTime');
      var duration = '';
      if (start) {
        var endTime = end || Date.now();
        var minutes = ((endTime - start) / 60000).toFixed(1);
        duration = minutes;
      }
      meta.tdAttr = 'data-qtip="' + sanitize(duration) + '"';
      return duration;
    };

    Ext.apply(me, {
      items: [
        {
          xtype: 'gridpanel',
          store: me.store,
          columns: [
            { text: _t('sync.queue.table.queueId'), dataIndex: 'taskId', width: 180, renderer: tipRenderer },
            { text: _t('sync.queue.table.taskType'), dataIndex: 'taskType', width: 80, renderer: function(v) {
              if (v === 'promotion') return '<span style="color:#e67e22;font-weight:bold;">' + _t('sync.queue.taskType.promotion') + '</span>';
              if (v === 'sync') return '<span style="color:#3498db;font-weight:bold;">' + _t('sync.queue.taskType.sync') + '</span>';
              return v || '-';
            }},
            { text: _t('sync.queue.table.sourceRepository'), dataIndex: 'sourceRepository', flex: 1, renderer: tipRenderer },
            { text: _t('sync.queue.table.path'), dataIndex: 'path', flex: 1, renderer: tipRenderer },
            { text: _t('sync.queue.table.status'), dataIndex: 'status', width: 100, renderer: tipStatusRenderer },
            { text: _t('sync.queue.table.startTime'), dataIndex: 'startTime', width: 150, renderer: tipTimeRenderer },
            { text: _t('sync.queue.table.duration'), dataIndex: 'endTime', width: 100, renderer: durationRenderer },
            { text: _t('sync.queue.table.username'), dataIndex: 'username', width: 100, renderer: tipRenderer },
            { text: _t('sync.queue.table.result'), dataIndex: 'result', flex: 1, renderer: tipRenderer },
            {
              text: _t('sync.queue.table.terminate'),
              width: 80,
              align: 'center',
              sortable: false,
              renderer: function(value, metaData, record) {
                var status = record.get('status');
                var taskId = record.get('taskId');
                var taskUsername = record.get('username');
                var canTerminate = (status === 'running' || status === 'RUNNING' || status === 'pending' || status === 'PENDING');
                var isOwner = (me.currentUser && me.currentUser.username === taskUsername);
                var isAdmin = (me.currentUser && me.currentUser.isAdmin);
                var hasPermission = isOwner || isAdmin;

                if (canTerminate && hasPermission) {
                  return '<button class="terminate-btn" data-taskid="' + Ext.htmlEncode(taskId) +
                    '" style="background:#e74c3c;color:white;border:none;border-radius:3px;padding:3px 10px;cursor:pointer;font-size:12px;">' +
                    _t('sync.queue.table.terminate') + '</button>';
                } else {
                  return '<button disabled style="background:#ccc;color:#999;border:none;border-radius:3px;padding:3px 10px;cursor:not-allowed;font-size:12px;">' +
                    _t('sync.queue.table.terminate') + '</button>';
                }
              }
            }
          ],
          tbar: [
            {
              xtype: 'textfield',
              itemId: 'filterTaskId',
              emptyText: _t('sync.queue.filter.taskId'),
              width: 150,
              listeners: {
                specialkey: function (field, e) {
                  if (e.getKey() === e.ENTER) me.applyFilters();
                }
              }
            },
            {
              xtype: 'combobox',
              itemId: 'filterTaskType',
              emptyText: _t('sync.queue.filter.taskType'),
              width: 100,
              editable: false,
              store: [
                ['', _t('sync.queue.filter.all')],
                ['sync', _t('sync.queue.taskType.sync')],
                ['promotion', _t('sync.queue.taskType.promotion')]
              ],
              listeners: {
                select: function () { me.applyFilters(); }
              }
            },
            {
              xtype: 'textfield',
              itemId: 'filterRepository',
              emptyText: _t('sync.queue.filter.repository'),
              width: 150,
              listeners: {
                specialkey: function (field, e) {
                  if (e.getKey() === e.ENTER) me.applyFilters();
                }
              }
            },
            {
              xtype: 'textfield',
              itemId: 'filterPath',
              emptyText: _t('sync.queue.filter.path'),
              width: 150,
              listeners: {
                specialkey: function (field, e) {
                  if (e.getKey() === e.ENTER) me.applyFilters();
                }
              }
            },
            {
              xtype: 'combobox',
              itemId: 'filterStatus',
              emptyText: _t('sync.queue.filter.status'),
              width: 120,
              editable: false,
              store: [
                ['', _t('sync.queue.filter.all')],
                ['running', _t('sync.queue.status.running')],
                ['completed', _t('sync.queue.status.completed')],
                ['failed', _t('sync.queue.status.failed')],
                ['cancelled', _t('sync.queue.status.cancelled')]
              ],
              listeners: {
                select: function () { me.applyFilters(); }
              }
            },
            {
              xtype: 'textfield',
              itemId: 'filterUsername',
              emptyText: _t('sync.queue.filter.username'),
              width: 120,
              listeners: {
                specialkey: function (field, e) {
                  if (e.getKey() === e.ENTER) me.applyFilters();
                }
              }
            },
            {
              text: _t('sync.queue.filter.search'),
              iconCls: 'x-fa fa-search',
              handler: function () { me.applyFilters(); }
            },
            {
              text: _t('sync.queue.filter.reset'),
              iconCls: 'x-fa fa-undo',
              handler: function () {
                me.down('#filterTaskId').setValue('');
                me.down('#filterRepository').setValue('');
                me.down('#filterPath').setValue('');
                me.down('#filterStatus').setValue('');
                me.down('#filterUsername').setValue('');
                me.applyFilters();
              }
            },
            '-',
            {
              text: _t('sync.queue.table.refresh'),
              iconCls: 'x-fa fa-refresh',
              handler: function () {
                me.allDataStore.load({
                  callback: function () {
                    me.applyFilters();
                    me.checkAllFinished();
                  }
                });
              }
            },
            {
              text: _t('sync.queue.status.running') + ': 0',
              iconCls: 'x-fa fa-circle-o',
              itemId: 'activeCountBtn',
              disabled: true
            }
          ],
          dockedItems: [{
            xtype: 'pagingtoolbar',
            store: me.store,
            dock: 'bottom',
            displayInfo: true,
            displayMsg: '{0} - {1} / {2}',
            emptyMsg: '',
            listeners: {
              beforechange: function (toolbar, page) {
                if (me.filteredData) {
                  me.loadPage(page);
                  return false; // Prevent default load
                }
              }
            }
          }]
        }
      ]
    });

    me.callParent(arguments);

    // Load current user info for permission checks
    me.currentUser = null;
    Ext.Ajax.request({
      url: '/service/rest/v1/sync/queue/currentUser',
      method: 'GET',
      success: function(response) {
        try {
          me.currentUser = Ext.decode(response.responseText);
        } catch(e) {}
      }
    });
  },

  afterRender: function() {
    this.callParent(arguments);
    var me = this;

    // Handle terminate button clicks via event delegation
    // Must be in afterRender because getEl() is only available after rendering
    me.getEl().on('click', function(e, t) {
      var btn = Ext.fly(t).down('.terminate-btn');
      if (!btn) btn = Ext.fly(t);
      if (!btn || !btn.dom) return;
      var taskId = btn.getAttribute('data-taskid');
      if (!taskId) return;

      Ext.Msg.confirm(_t('sync.queue.table.terminate'), _t('sync.queue.table.terminateConfirm'), function(btnId) {
        if (btnId === 'yes') {
          Ext.Ajax.request({
            url: '/service/rest/v1/sync/queue/terminate/' + encodeURIComponent(taskId),
            method: 'POST',
            success: function(response) {
              Ext.Msg.alert(_t('sync.queue.table.terminate'), _t('sync.queue.table.terminateSuccess'));
              me.allDataStore.load({
                callback: function () {
                  me.applyFilters();
                  me.checkAllFinished();
                }
              });
            },
            failure: function(response) {
              var msg = _t('sync.queue.table.terminateFailed');
              try {
                var result = Ext.decode(response.responseText);
                if (result.error === 'Permission denied') {
                  msg = _t('sync.queue.table.terminateNoPermission');
                } else if (result.message) {
                  msg = result.message;
                }
              } catch(e) {}
              Ext.Msg.alert(_t('sync.queue.table.terminate'), msg);
            }
          });
        }
      });
    }, null, { delegate: '.terminate-btn' });

    me._queuePollInterval = setInterval(function () {
      if (!me.destroyed) {
        me.allDataStore.load({
          callback: function () {
            me.applyFilters();
            me.checkAllFinished();
          }
        });
      } else {
        clearInterval(me._queuePollInterval);
      }
    }, 3000);
  },

  applyFilters: function () {
    var me = this;
    var taskIdFilter = (me.down('#filterTaskId') || {}).value || '';
    var taskTypeFilter = (me.down('#filterTaskType') || {}).value || '';
    var repoFilter = (me.down('#filterRepository') || {}).value || '';
    var pathFilter = (me.down('#filterPath') || {}).value || '';
    var statusFilter = (me.down('#filterStatus') || {}).value || '';
    var userFilter = (me.down('#filterUsername') || {}).value || '';

    taskIdFilter = taskIdFilter.toLowerCase();
    repoFilter = repoFilter.toLowerCase();
    pathFilter = pathFilter.toLowerCase();
    statusFilter = statusFilter.toLowerCase();
    userFilter = userFilter.toLowerCase();

    var filtered = [];
    me.allDataStore.each(function (rec) {
      if (taskIdFilter && (rec.get('taskId') || '').toLowerCase().indexOf(taskIdFilter) === -1) return;
      if (taskTypeFilter && (rec.get('taskType') || '').toLowerCase() !== taskTypeFilter.toLowerCase()) return;
      if (repoFilter && (rec.get('sourceRepository') || '').toLowerCase().indexOf(repoFilter) === -1 &&
          (rec.get('targetRepository') || '').toLowerCase().indexOf(repoFilter) === -1) return;
      if (pathFilter && (rec.get('path') || '').toLowerCase().indexOf(pathFilter) === -1) return;
      if (statusFilter) {
        var st = (rec.get('status') || '').toLowerCase();
        if (statusFilter === 'running' && st !== 'running' && st !== 'pending') return;
        if (statusFilter === 'completed' && st !== 'completed' && st !== 'migrated') return;
        if (statusFilter === 'failed' && st !== 'failed') return;
        if (statusFilter === 'cancelled' && st !== 'cancelled') return;
        if (statusFilter !== 'running' && statusFilter !== 'completed' && st !== statusFilter) return;
      }
      if (userFilter && (rec.get('username') || '').toLowerCase().indexOf(userFilter) === -1) return;
      filtered.push(rec.copy());
    });

    // Store all filtered data for client-side pagination
    me.filteredData = filtered;

    // Load first page
    me.store.currentPage = 1;
    me.loadPage(1);
    me.checkAllFinished();
  },

  loadPage: function (page) {
    var me = this;
    var pageSize = me.store.pageSize || 20;
    var total = (me.filteredData || []).length;
    var start = (page - 1) * pageSize;
    var end = Math.min(start + pageSize, total);
    var pageData = (me.filteredData || []).slice(start, end);

    me.store.loadData(pageData);
    me.store.currentPage = page;
    me.store.getTotalCount = function () { return total; };

    // Update paging toolbar
    var toolbar = me.down('pagingtoolbar');
    if (toolbar) {
      toolbar.store.getTotalCount = function () { return total; };
      toolbar.onLoad();
    }
  },

  checkAllFinished: function () {
    var me = this;
    var hasActive = false;
    var activeCount = 0;
    me.allDataStore.each(function (rec) {
      var st = (rec.get('status') || '').toLowerCase();
      if (st === 'running' || st === 'pending') {
        hasActive = true;
        activeCount++;
      }
    });

    var activeBtn = me.down('#activeCountBtn');
    if (activeBtn) {
      activeBtn.setText(_t('sync.queue.status.running') + ': ' + activeCount);
    }

    if (!hasActive && me._queuePollInterval) {
      clearInterval(me._queuePollInterval);
      me._queuePollInterval = null;
    }
  },

  beforeDestroy: function () {
    if (this._queuePollInterval) {
      clearInterval(this._queuePollInterval);
      this._queuePollInterval = null;
    }
    this.callParent(arguments);
  }
});

// ==================== Promotion Controller ====================

Ext.define('NX.artifactsPromotion.controller.Promotion', {
  extend: 'NX.app.Controller',

  refs: [
    { ref: 'featureBrowser', selector: 'nx-coreui-feature-browser' }
  ],

  init: function () {
    var me = this;

    // Register Sync Queue as a Browse feature
    me.getApplication().getFeaturesController().registerFeature({
      mode: 'browse',
      path: '/SyncQueue',
      text: _t('sync.queue.page.title'),
      description: _t('sync.queue.page.title'),
      view: { xtype: 'nx-artifacts-promotion-syncqueue' },
      iconCls: 'x-fa fa-tasks',
      weight: 200,
      authenticationRequired: true,
      visible: function () {
        // Visible to all authenticated users
        try {
          var user = NX.State.getValue('user');
          if (!user) return false;
          // NX.State user may be a string (username) or object
          if (typeof user === 'string') {
            return user !== 'anonymous';
          }
          if (typeof user === 'object') {
            return true;
          }
        } catch (e) { /* ignore */ }
        return false;
      }
    }, me);

    // Listen for ComponentAssetInfo panel 'updated' event (fired by setModel)
    // and 'afterrender' for initial setup
    me.control({
      'nx-coreui-component-componentassetinfo': {
        afterrender: me.onAssetInfoRender,
        updated: me.onAssetInfoUpdated
      },
      'nx-coreui-component-componentfolderinfo': {
        afterrender: me.onFolderInfoRender
      }
    });

    // ComponentFolderInfo does not fire events on setModel,
    // so we override setModel to fire a custom 'folderupdated' event
    // and store the folder model on the panel for later access.
    //
    // Safe check for Nexus 3.45-3.70 compatibility.
    // In some versions, ComponentFolderInfo may not exist at init time
    // or may be under a different namespace, so we also add a fallback
    // that polls for folderModel in onFolderInfoRender.
    var folderProtoOverrideDone = false;
    try {
      if (NX.coreui && NX.coreui.view && NX.coreui.view.component &&
          NX.coreui.view.component.ComponentFolderInfo &&
          NX.coreui.view.component.ComponentFolderInfo.prototype &&
          typeof NX.coreui.view.component.ComponentFolderInfo.prototype.setModel === 'function') {
        var origSetModel = NX.coreui.view.component.ComponentFolderInfo.prototype.setModel;
        NX.coreui.view.component.ComponentFolderInfo.prototype.setModel = function (folder) {
          origSetModel.call(this, folder);
          this.folderModel = folder;
          this.fireEvent('folderupdated', this, folder);
        };
        folderProtoOverrideDone = true;
        console.log('[Promotion] ComponentFolderInfo.setModel overridden successfully');
      }
      else {
        console.warn('[Promotion] ComponentFolderInfo not available at init time, will use fallback');
      }
    } catch (e) {
      console.warn('[Promotion] ComponentFolderInfo override failed:', e);
    }

    // Store flag for fallback detection
    me._folderProtoOverrideDone = folderProtoOverrideDone;

    // Now listen for the custom event
    me.control({
      'nx-coreui-component-componentfolderinfo': {
        folderupdated: me.onFolderInfoUpdated
      }
    });
  },

  onAssetInfoRender: function (panel) {
    var me = this;
    me._currentAssetPanel = panel;
    me.tryAddButtons(panel);
  },

  onAssetInfoUpdated: function (panel, asset, component) {
    var me = this;
    me.tryAddButtons(panel, asset);
  },

  onFolderInfoRender: function (panel) {
    var me = this;
    me._currentFolderPanel = panel;

    // Try immediately first
    if (me.tryAddFolderButtons(panel)) {
      return; // Success, no need to retry
    }

    // If prototype override failed (folderModel not set yet), retry with delay.
    // setModel may be called afterrender in some Nexus versions.
    var retryCount = 0;
    var maxRetries = 8;
    var retryInterval = 250; // ms

    var doRetry = function () {
      retryCount++;
      if (me.tryAddFolderButtons(panel)) {
        return; // Success
      }
      if (retryCount < maxRetries) {
        Ext.defer(doRetry, retryInterval);
      }
      else {
        console.warn('[Promotion] Folder buttons not added after', maxRetries, 'retries');
      }
    };
    Ext.defer(doRetry, retryInterval);
  },

  onFolderInfoUpdated: function (panel, folder) {
    var me = this;
    me._currentFolderPanel = panel;
    if (folder) {
      panel.folderModel = folder;
    }
    me.tryAddFolderButtons(panel);
  },

  /**
   * Try to add promotion/sync buttons to a folder info panel.
   * @param {Object} panel The ComponentFolderInfo panel
   * @return {boolean} true if buttons were added (or already exist), false if folderModel was missing
   */
  tryAddFolderButtons: function (panel) {
    var me = this;
    try {
      var folderModel = panel.folderModel;

      if (!folderModel) return false;

      // folderModel may be an Ext.data.Model or a plain object
      // Nexus 3.60+ changed property names - use robust fallback chain
      var repoName = folderModel.repositoryName
        || folderModel.repository
        || (folderModel.get && folderModel.get('repositoryName'))
        || (folderModel.get && folderModel.get('repository'));

      var path = folderModel.path
        || folderModel.name
        || (folderModel.get && folderModel.get('path'))
        || (folderModel.get && folderModel.get('name'));

      if (!repoName || !path) {
        // Fallback: try to get info from the panel's display fields
        var panelItems = panel.query('displayfield');
        Ext.each(panelItems, function (field) {
          if (!repoName && field.fieldLabel && field.fieldLabel.indexOf('\u4ed3\u5e93') >= 0) {
            repoName = field.getValue();
          }
          if (!path && field.fieldLabel && (field.fieldLabel.indexOf('\u8def\u5f84') >= 0 || field.fieldLabel.indexOf('Path') >= 0)) {
            path = field.getValue();
          }
        });
      }

      if (!repoName || !path) {
        // Fallback: try to get repository name from the tree panel's store
        var treePanel = panel.up('nx-coreui-component-asset-tree');
        if (treePanel) {
          var tree = treePanel.down('treepanel');
          if (tree) {
            var selected = tree.getSelectionModel().getSelection()[0];
            if (selected) {
              if (!repoName) {
                repoName = selected.get('repositoryName') || selected.get('text');
              }
              if (!path) {
                path = selected.get('id') || selected.get('text');
              }
            }
          }
        }
      }

      if (!repoName || !path) return false;

      // Remove existing buttons to avoid duplicates
      var existingBtns = panel.query('button[cls=promotion-btn], button[cls=sync-btn]');
      Ext.each(existingBtns, function (btn) { btn.destroy(); });

      // Get format from repository state
      var format = me.getRepositoryFormat(repoName);
      if (!format) return false;

      // Add promotion button for directory
      me.addPromotionButton(panel, repoName, path, format, true);

      // Add sync button for proxy repositories (async check via API)
      me.addSyncButtonIfProxy(panel, repoName, path, format, true);
      return true;
    } catch (e) {
      // Silently fail
      return false;
    }
  },

  getRepositoryFormat: function (repoName) {
    try {
      // Try to find repository in the Repository store
      var store = Ext.getStore('Repository');
      if (store) {
        var record = store.findRecord('name', repoName, 0, false, true, true);
        if (record) {
          return record.get('format');
        }
      }
    } catch (e) { /* ignore */ }
    try {
      // Fallback: try NX.State
      var repos = NX.State.getValue('repositories');
      if (repos) {
        for (var i = 0; i < repos.length; i++) {
          if (repos[i].name === repoName) {
            return repos[i].format;
          }
        }
      }
    } catch (e) { /* ignore */ }
    try {
      // Fallback: try folderModel format
      if (this._currentFolderPanel && this._currentFolderPanel.folderModel) {
        var fm = this._currentFolderPanel.folderModel;
        var fmt = fm.format || (fm.get && fm.get('format'));
        if (fmt) return fmt;
      }
    } catch (e) { /* ignore */ }
    try {
      // Fallback: try assetModel format
      if (this._currentAssetPanel && this._currentAssetPanel.assetModel) {
        var am = this._currentAssetPanel.assetModel;
        var afmt = am.format || (am.get && am.get('format'));
        if (afmt) return afmt;
      }
    } catch (e) { /* ignore */ }
    try {
      // Fallback: try componentModel format
      if (this._currentAssetPanel && this._currentAssetPanel.componentModel) {
        var cm = this._currentAssetPanel.componentModel;
        var cfmt = cm.format || (cm.get && cm.get('format'));
        if (cfmt) return cfmt;
      }
    } catch (e) { /* ignore */ }
    // Last resort: try to guess format from repository name patterns
    if (repoName) {
      if (repoName.indexOf('maven') >= 0 || repoName.indexOf('Maven') >= 0) return 'maven2';
      if (repoName.indexOf('npm') >= 0 || repoName.indexOf('NPM') >= 0) return 'npm';
      if (repoName.indexOf('nuget') >= 0 || repoName.indexOf('NuGet') >= 0) return 'nuget';
      if (repoName.indexOf('pypi') >= 0 || repoName.indexOf('PyPI') >= 0) return 'pypi';
      if (repoName.indexOf('docker') >= 0 || repoName.indexOf('Docker') >= 0) return 'docker';
      if (repoName.indexOf('raw') >= 0 || repoName.indexOf('Raw') >= 0) return 'raw';
      if (repoName.indexOf('yum') >= 0 || repoName.indexOf('Yum') >= 0) return 'yum';
      if (repoName.indexOf('apt') >= 0 || repoName.indexOf('APT') >= 0) return 'apt';
      if (repoName.indexOf('go') >= 0 || repoName.indexOf('Go') >= 0) return 'go';
      if (repoName.indexOf('conda') >= 0 || repoName.indexOf('Conda') >= 0) return 'conda';
      if (repoName.indexOf('rubygems') >= 0 || repoName.indexOf('RubyGems') >= 0) return 'rubygems';
      if (repoName.indexOf('helm') >= 0 || repoName.indexOf('Helm') >= 0) return 'helm';
      if (repoName.indexOf('gitlfs') >= 0 || repoName.indexOf('GitLFS') >= 0) return 'gitlfs';
      if (repoName.indexOf('r') >= 0 || repoName.indexOf('R') >= 0) return 'r';
      // Default to raw if we can't determine
      return 'raw';
    }
    return null;
  },

  tryAddButtons: function (panel, asset) {
    var me = this;
    try {
      if (!asset && panel.assetModel) {
        asset = panel.assetModel;
      }
      if (!asset) return;

      var repoName = asset.get('repositoryName');
      var path = asset.get('name');
      var format = asset.get('format');

      if (!repoName || !path) return;

      // Remove existing promotion/sync buttons to avoid duplicates
      var existingBtns = panel.query('button[cls=promotion-btn], button[cls=sync-btn]');
      Ext.each(existingBtns, function (btn) { btn.destroy(); });

      // Add promotion button for all repository types
      var isDirectory = path && path.endsWith('/');
      me.addPromotionButton(panel, repoName, path, format, isDirectory);

      // Add sync button for proxy repositories (async check via API)
      me.addSyncButtonIfProxy(panel, repoName, path, format, isDirectory);
    } catch (e) {
      // Silently fail - don't break the UI
    }
  },

  isProxyRepository: function (repoName) {
    // Try to determine from local stores first (synchronous)
    try {
      var storeIds = ['Repository', 'nx-coreui-repository', 'RepositoryList',
                       'nx-coreui-repository-list', 'coreui_Repository'];
      for (var s = 0; s < storeIds.length; s++) {
        var s2 = Ext.getStore(storeIds[s]);
        if (s2) {
          var r = s2.findRecord('name', repoName, 0, false, true, true);
          if (r && r.get('type') === 'proxy') {
            return true;
          }
        }
      }
    } catch (e) { /* ignore */ }
    try {
      var repos = NX.State.getValue('repositories');
      if (repos) {
        for (var i = 0; i < repos.length; i++) {
          if (repos[i].name === repoName && repos[i].type === 'proxy') {
            return true;
          }
        }
      }
    } catch (e) { /* ignore */ }
    return false;
  },

  /**
   * Async check if repository is proxy via API, then add sync button.
   * Falls back to synchronous isProxyRepository if API fails.
   */
  addSyncButtonIfProxy: function (panel, repoName, path, format, isDirectory) {
    var me = this;

    // Quick synchronous check first
    if (me.isProxyRepository(repoName)) {
      // Always show sync button for proxy repos, check permissions via API
      apiRequest('GET', '/sync/permission?repository=' + encodeURIComponent(repoName) + '&format=' + encodeURIComponent(format || ''))
      .then(function (result) {
        if (panel.destroyed || panel.query('button[cls=sync-btn]').length > 0) return;
        var hasPermission = result.hasPermission === true;
        var hasDeletePerm = result.hasDeletePermission === true;
        // Disable if no sync permission; also disable if no delete permission
        var disabled = !hasPermission || !hasDeletePerm;
        var tooltip = '';
        if (!hasPermission) {
          tooltip = _t('sync.permission.denied');
        } else if (!hasDeletePerm) {
          tooltip = _t('sync.permission.disabled.tooltip');
        }
        me.addSyncButton(panel, repoName, path, format, isDirectory, disabled, tooltip);
      })
      .catch(function () {
        // API failed (not logged in or error) - add disabled button
        if (!panel.destroyed && panel.query('button[cls=sync-btn]').length === 0) {
          me.addSyncButton(panel, repoName, path, format, isDirectory, true, _t('sync.permission.denied.admin'));
        }
      });
      return;
    }

    // Async API check as fallback - call /sync/permission directly to get isProxy
    apiRequest('GET', '/sync/permission?repository=' + encodeURIComponent(repoName) + '&format=' + encodeURIComponent(format || ''))
    .then(function (result) {
      var isProxy = result.isProxy === true;
      if (isProxy) {
        // Check panel still exists and no sync button already
        if (!panel.destroyed && panel.query('button[cls=sync-btn]').length === 0) {
          var hasPermission = result.hasPermission === true;
          var hasDeletePerm = result.hasDeletePermission === true;
          var disabled = !hasPermission || !hasDeletePerm;
          var tooltip = '';
          if (!hasPermission) {
            tooltip = _t('sync.permission.denied');
          } else if (!hasDeletePerm) {
            tooltip = _t('sync.permission.disabled.tooltip');
          }
          me.addSyncButton(panel, repoName, path, format, isDirectory, disabled, tooltip);
        }
      }
    })
    .catch(function () {
      // API failed (not logged in or error) - don't add sync button for non-proxy
    });
  },

  addPromotionButton: function (panel, repoName, path, format, isDirectory) {
    var me = this;
    if (typeof isDirectory === 'undefined') {
      isDirectory = path && path.endsWith('/');
    }

    // Docker format: show Docker-specific button text
    var btnText = isDirectory ? _t('promotion.button.text.directory') : _t('promotion.button.text');
    if (format === 'docker') {
      btnText = _t('docker.promote.button');
    }

    // Check promotion permission via API first
    apiRequest('GET', '/promotion/permission?repository=' + encodeURIComponent(repoName) + '&format=' + encodeURIComponent(format || ''))
    .then(function (result) {
      var hasPermission = result.hasPermission === true;
      var btn = Ext.create('Ext.button.Button', {
        text: btnText,
        iconCls: 'x-fa fa-arrow-up',
        cls: 'promotion-btn',
        disabled: !hasPermission,
        tooltip: !hasPermission ? _t('promotion.permission.denied') : '',
        handler: function () {
          me.handlePromotionClick(repoName, path, isDirectory, format);
        }
      });

      if (panel.destroyed) return;

      // Add button to existing nx-actions toolbar (dockedItems)
      var actions = panel.down('nx-actions');
      if (actions) {
        actions.add(btn);
      } else {
        panel.addDocked({
          xtype: 'toolbar',
          dock: 'top',
          items: [btn]
        });
      }
    })
    .catch(function () {
      // API failed (not logged in or error) - add disabled button
      if (panel.destroyed) return;
      var btn = Ext.create('Ext.button.Button', {
        text: btnText,
        iconCls: 'x-fa fa-arrow-up',
        cls: 'promotion-btn',
        disabled: true,
        tooltip: _t('promotion.permission.denied.admin'),
        handler: function () {}
      });

      var actions = panel.down('nx-actions');
      if (actions) {
        actions.add(btn);
      } else {
        panel.addDocked({
          xtype: 'toolbar',
          dock: 'top',
          items: [btn]
        });
      }
    });
  },

  addSyncButton: function (panel, repoName, path, format, isDirectory, disabled, tooltip) {
    var me = this;
    if (typeof isDirectory === 'undefined') {
      isDirectory = path && path.endsWith('/');
    }

    // Docker format: show Docker-specific button text
    var btnText = isDirectory ? _t('sync.button.text.directory') : _t('sync.button.text');
    if (format === 'docker') {
      btnText = _t('docker.sync.button');
    }

    var btnTooltip = tooltip || (disabled ? _t('sync.permission.disabled.tooltip') : '');
    var btn = Ext.create('Ext.button.Button', {
      text: btnText,
      iconCls: 'x-fa fa-sync',
      cls: 'sync-btn',
      disabled: !!disabled,
      tooltip: btnTooltip,
      handler: function () {
        me.handleSyncClick(repoName, path, isDirectory, format);
      }
    });

    var actions = panel.down('nx-actions');
    if (actions) {
      actions.add(btn);
    } else {
      // Try to find any existing toolbar (created by addPromotionButton or native)
      var toolbar = panel.down('toolbar');
      if (toolbar) {
        toolbar.add(btn);
      } else {
        panel.addDocked({
          xtype: 'toolbar',
          dock: 'top',
          items: [btn]
        });
      }
    }
  },

  handlePromotionClick: function (repoName, path, isDirectory, format) {
    var me = this;
    // Docker format: use promotion preview to get file list, then display as image:tag
    if (format === 'docker') {
      me.showDockerPromotionModal(repoName, format, path, isDirectory);
      return;
    }
    // Default: show generic promotion modal
    me.showPromotionModal(repoName, path, isDirectory, format);
  },

  handleSyncClick: function (repoName, path, isDirectory, format) {
    var me = this;
    // Execute sync directly
    me.executeSync(repoName, path, isDirectory, format);
  },

  executeSync: function (repoName, path, isDirectory, format) {
    var me = this;
    // Docker format: use the same sync mechanism as other formats
    if (format === 'docker') {
      checkSyncPermission(repoName, format).then(function (hasPermission) {
        if (!hasPermission) {
          showAlertDialog(_t('common.noPermission'), _t('sync.permission.denied'));
          return;
        }
        apiRequest('POST', '/sync/execute', {
          repositoryName: repoName,
          path: path,
          isDirectory: isDirectory,
          format: format
        })
        .then(function (result) {
          me.showSyncProgressWindow(result, repoName, path, isDirectory);
        })
        .catch(function (err) {
          showAlertDialog(_t('sync.execute.failed'), sanitize(err.message));
        });
      });
      return;
    }
    // Default: generic sync
    checkSyncPermission(repoName, format).then(function (hasPermission) {
      if (!hasPermission) {
        showAlertDialog(_t('common.noPermission'), _t('sync.permission.denied'));
        return;
      }
      apiRequest('POST', '/sync/execute', {
        repositoryName: repoName,
        path: path,
        isDirectory: isDirectory,
        format: format
      })
      .then(function (result) {
        me.showSyncProgressWindow(result, repoName, path, isDirectory);
      })
      .catch(function (err) {
        showAlertDialog(_t('sync.execute.failed'), sanitize(err.message));
      });
    });
  },

  showSyncProgressWindow: function (result, repoName, path, isDirectory) {
    var me = this;
    var taskId = result.taskId || '';
    var fullPath = repoName + '/' + path;

    var win = Ext.create('Ext.window.Window', {
      title: isDirectory ? _t('sync.button.text.directory') : _t('sync.button.text'),
      minWidth: 400,
      maxWidth: 700,
      autoScroll: true,
      modal: true,
      closable: true,
      layout: 'fit',
      items: [{
        xtype: 'panel',
        layout: { type: 'vbox', align: 'stretch' },
        bodyPadding: 15,
        autoScroll: true,
        items: [
          {
            xtype: 'displayfield',
            fieldLabel: _t('sync.queue.created.repository'),
            value: '<b style="word-break:break-all;">' + sanitize(fullPath) + '</b>'
          },
          {
            xtype: 'displayfield',
            fieldLabel: _t('sync.queue.created.queueId'),
            value: '<b style="word-break:break-all;">' + sanitize(taskId) + '</b>'
          },
          {
            xtype: 'displayfield',
            fieldLabel: _t('promotion.result.status'),
            itemId: 'syncStatusField',
            value: '<span style="color:#337ab7;font-weight:bold;">' + _t('sync.progress.processing') + '</span>'
          }
        ]
      }],
      buttons: [
        {
          text: _t('promotion.result.close'),
          handler: function () {
            if (win._syncPollInterval) {
              clearInterval(win._syncPollInterval);
              win._syncPollInterval = null;
            }
            win.close();
          }
        }
      ]
    });
    win.show();

    // Start polling for sync task status
    me.startSyncPolling(win, taskId);
  },

  startSyncPolling: function (win, taskId) {
    var isFinished = false;
    var pollCount = 0;
    var MAX_POLLS = 400;

    var stopPolling = function () {
      if (win._syncPollInterval) {
        clearInterval(win._syncPollInterval);
        win._syncPollInterval = null;
      }
    };

    var doPoll = function () {
      if (isFinished || win.destroyed) {
        stopPolling();
        return;
      }

      pollCount++;
      if (pollCount > MAX_POLLS) {
        isFinished = true;
        stopPolling();
        return;
      }

      var xhr = new XMLHttpRequest();
      xhr.open('GET', '/service/rest/v1/sync/task/' + encodeURIComponent(taskId), true);
      xhr.setRequestHeader('Accept', 'application/json');

      var csrfToken = null;
      var cookies = document.cookie.split(';');
      for (var ci = 0; ci < cookies.length; ci++) {
        var cookie = cookies[ci].trim();
        if (cookie.indexOf('NX-ANTI-CSRF-TOKEN=') === 0) {
          csrfToken = cookie.substring('NX-ANTI-CSRF-TOKEN='.length);
          break;
        }
      }
      if (csrfToken) {
        xhr.setRequestHeader('NX-ANTI-CSRF-TOKEN', csrfToken);
      }

      xhr.onreadystatechange = function () {
        if (xhr.readyState !== 4) return;
        if (isFinished || win.destroyed) {
          stopPolling();
          return;
        }
        if (xhr.status !== 200) return;

        var result = {};
        try {
          result = JSON.parse(xhr.responseText);
        } catch (e) { return; }

        var statusStr = (result.status || '').toLowerCase();
        var statusField = win.down('#syncStatusField');

        // Update status display
        if (statusField) {
          if (statusStr === 'running') {
            statusField.setValue('<span style="color:#337ab7;font-weight:bold;">' + _t('sync.progress.processing') + '</span>');
          } else if (statusStr === 'completed') {
            statusField.setValue('<span style="color:#5cb85c;font-weight:bold;">' + _t('sync.progress.success') + '</span>');
          } else if (statusStr === 'failed') {
            statusField.setValue('<span style="color:#d9534f;font-weight:bold;">' + _t('sync.progress.failed') + '</span>');
          } else if (statusStr === 'cancelled') {
            statusField.setValue('<span style="color:#f0ad4e;font-weight:bold;">' + _t('sync.queue.status.cancelled') + '</span>');
          } else if (statusStr === 'pending') {
            statusField.setValue('<span style="color:#999;">' + _t('sync.progress.pending') + '</span>');
          }
        }

        // Check if task is finished
        if (statusStr === 'completed' || statusStr === 'failed' || statusStr === 'cancelled') {
          isFinished = true;
          stopPolling();

          // Update window title
          if (statusStr === 'cancelled') {
            win.setTitle(_t('sync.queue.status.cancelled'));
          } else if (statusStr === 'failed') {
            win.setTitle(_t('sync.progress.failed'));
          } else {
            win.setTitle(_t('sync.progress.success'));
          }
        }
      };

      xhr.send();
    };

    win._syncPollInterval = setInterval(doPoll, 1500);
    // Initial poll after delay
    setTimeout(doPoll, 1000);
  },

  showPromotionModal: function (repoName, path, isDirectory, format) {
    var me = this;
    apiRequest('GET', '/promotion/targets?sourceRepository=' +
      encodeURIComponent(repoName) + '&format=' + encodeURIComponent(format))
      .then(function (data) {
        if (!data.repositories || data.repositories.length === 0) {
          showAlertDialog(_t('promotion.noTargets.title'), _t('promotion.noTargets.message'));
          return;
        }
        var previewReq = {
          sourceRepository: repoName,
          targetRepository: data.repositories[0].name,
          path: path,
          isDirectory: isDirectory,
          format: format
        };
        apiRequest('POST', '/promotion/preview', previewReq)
          .then(function (preview) {
            me.renderPromotionModal(data.repositories, previewReq, preview);
          })
          .catch(function (err) {
            showAlertDialog(_t('promotion.preview.failed'), sanitize(err.message));
          });
      })
      .catch(function (err) {
        showAlertDialog(_t('common.error'), sanitize(err.message));
      });
  },

  renderPromotionModal: function (targetRepos, request, preview) {
    var me = this;
    var targetOptions = [];
    Ext.each(targetRepos, function (repo) {
      targetOptions.push({ text: repo.name + ' (' + repo.format + ' - ' + repo.type + ')', value: repo.name });
    });

    // Status map to track all file statuses across pages (store.each/findExact only works on current page)
    // Also used for deduplication
    var statusMap = {};
    var seenPaths = {};
    if (preview && preview.files) {
      Ext.each(preview.files, function (f) {
        if (f.path && !seenPaths[f.path]) {
          seenPaths[f.path] = true;
          statusMap[f.path] = 'pending';
        }
      });
    }

    var fileStore = Ext.create('Ext.data.Store', {
      fields: ['path', 'type', 'size', 'status'],
      pageSize: 10,
      proxy: {
        type: 'memory',
        enablePaging: true
      },
      data: preview && preview.files ? (function () {
        var arr = [];
        var seen = {};
        Ext.each(preview.files, function (f) {
          if (f.path && !seen[f.path]) {
            seen[f.path] = true;
            arr.push({ path: f.path, type: f.type, size: f.size, status: 'pending' });
          }
        });
        return arr;
      })() : []
    });

    // Sync statusMap to current page records whenever store loads (handles page navigation)
    fileStore.on('load', function (store) {
      store.each(function (rec) {
        var recPath = rec.get('path');
        if (statusMap[recPath] && statusMap[recPath] !== rec.get('status')) {
          rec.set('status', statusMap[recPath]);
        }
      });
    });

    var totalFiles = Object.keys(statusMap).length || fileStore.getTotalCount() || fileStore.getCount();

    var statusRenderer = function (val) {
      switch (val) {
        case 'processing':
          return '<span style="color:#337ab7;font-weight:bold;">' + _t('promotion.progress.processing') + '</span>';
        case 'success':
          return '<span style="color:#5cb85c;font-weight:bold;">' + _t('promotion.progress.success') + '</span>';
        case 'failed':
          return '<span style="color:#d9534f;font-weight:bold;">' + _t('promotion.progress.failed') + '</span>';
        case 'cancelled':
          return '<span style="color:#f0ad4e;font-weight:bold;">' + _t('promotion.progress.cancelled') + '</span>';
        case 'skipped':
          // Display skipped files (MD5 match) as success in UI, but keep backend status unchanged
          return '<span style="color:#5cb85c;font-weight:bold;">' + _t('promotion.progress.success') + '</span>';
        default:
          return '<span style="color:#999;">' + _t('promotion.progress.pending') + '</span>';
      }
    };

    var win = Ext.create('Ext.window.Window', {
      title: _t('promotion.modal.title'),
      width: 700,
      height: 500,
      modal: true,
      closable: true,
      layout: 'fit',
      items: [{
        xtype: 'panel',
        layout: { type: 'vbox', align: 'stretch' },
        bodyPadding: 10,
        items: [
          // Target repo selector (hidden during progress)
          {
            xtype: 'combobox',
            fieldLabel: _t('promotion.modal.targetRepository'),
            store: Ext.create('Ext.data.Store', { fields: ['text', 'value'], data: targetOptions }),
            displayField: 'text',
            valueField: 'value',
            value: targetOptions.length > 0 ? targetOptions[0].value : null,
            queryMode: 'local',
            editable: false,
            itemId: 'targetCombo'
          },
          // Target repo display (shown during progress, hidden initially)
          {
            xtype: 'displayfield',
            itemId: 'targetDisplay',
            hidden: true,
            value: '',
            labelWidth: 120,
            fieldLabel: _t('promotion.result.targetRepository')
          },
          // Progress bar (hidden initially, shown during progress)
          {
            xtype: 'container',
            itemId: 'progressContainer',
            hidden: true,
            margin: '8 0',
            layout: { type: 'hbox', align: 'middle' },
            items: [
              {
                xtype: 'progressbar',
                itemId: 'overallProgress',
                flex: 1,
                value: 0,
                text: _t('promotion.progress.completed', 0, totalFiles || '?'),
                animate: true
              }
            ]
          },
          // File list grid
          {
            xtype: 'gridpanel',
            title: _t('promotion.modal.filesToPromote'),
            flex: 1,
            itemId: 'fileGrid',
            store: fileStore,
            columns: [
              { text: 'Path', dataIndex: 'path', flex: 2, cellWrap: true },
              { text: 'Type', dataIndex: 'type', width: 80 },
              {
                text: _t('promotion.result.status'), dataIndex: 'status', width: 100,
                renderer: statusRenderer
              }
            ],
            dockedItems: [{
              xtype: 'pagingtoolbar',
              store: fileStore,
              dock: 'bottom',
              displayInfo: true,
              displayMsg: '{0} - {1} / {2}',
              emptyMsg: ''
            }]
          }
        ]
      }],
      buttons: [
        {
          text: _t('promotion.modal.cancel'),
          itemId: 'cancelBtn',
          handler: function () { win.close(); }
        },
        {
          text: _t('promotion.modal.promote'),
          itemId: 'promoteBtn',
          handler: function () {
            var targetRepo = win.down('#targetCombo').getValue();
            if (!targetRepo) return;

            var promoteBtn = win.down('#promoteBtn');
            var cancelBtn = win.down('#cancelBtn');
            promoteBtn.setText(_t('promotion.modal.promoting'));
            promoteBtn.disable();
            cancelBtn.disable();

            // Collect file paths from preview for directory promotion
            var promoFiles = [];
            if (preview && preview.files) {
              Ext.each(preview.files, function (f) {
                if (f.path) { promoFiles.push(f.path); }
              });
            }

            apiRequest('POST', '/promotion/execute', {
              sourceRepository: request.sourceRepository,
              targetRepository: targetRepo,
              path: request.path,
              isDirectory: request.isDirectory,
              format: request.format,
              files: promoFiles
            })
            .then(function (result) {
              // Switch to progress mode in the same window
              win.setTitle(_t('promotion.modal.promoting'));

              // Hide target combo, show target display
              win.down('#targetCombo').hide();
              var targetDisplay = win.down('#targetDisplay');
              targetDisplay.setValue('<b>' + sanitize(targetRepo) + '</b>');
              targetDisplay.show();

              // Show progress bar
              var progressContainer = win.down('#progressContainer');
              progressContainer.show();

              // Remove grid title
              var grid = win.down('#fileGrid');
              if (grid && grid.title) {
                grid.setTitle('');
              }

              // Change buttons: hide promote, change cancel to close
              promoteBtn.hide();
              cancelBtn.setText(_t('promotion.result.close'));
              cancelBtn.enable();
              cancelBtn.setHandler(function () {
                if (win._pollInterval) {
                  clearInterval(win._pollInterval);
                  win._pollInterval = null;
                }
                win.close();
              });

              // Start polling for task status
              me.startPollingInWindow(win, result.taskId, promoFiles, totalFiles, statusMap);
            })
            .catch(function (err) {
              promoteBtn.setText(_t('promotion.modal.promote'));
              promoteBtn.enable();
              cancelBtn.enable();
              // Check if error contains username and repository info for write permission denied
              if (err.username && err.repository) {
                var msg = _t('promotion.permission.write.denied')
                  .replace('{0}', err.username)
                  .replace('{1}', err.repository);
                showAlertDialog(_t('common.noPermission'), msg);
                return;
              }
              showAlertDialog(_t('promotion.execute.failed'), sanitize(err.message));
            });
          }
        }
      ]
    });
    win.show();
  },

  startPollingInWindow: function (win, taskId, fileList, totalFiles, statusMap) {
    var isFinished = false;
    var pollCount = 0;
    var MAX_POLLS = 400; // ~10 minutes at 1.5s interval

    var stopPolling = function () {
      if (win._pollInterval) {
        clearInterval(win._pollInterval);
        win._pollInterval = null;
      }
    };

    var finishProgress = function (result, backendDoneCount, backendTotalCount) {
      if (isFinished) return;
      isFinished = true;
      stopPolling();

      var grid = win.down('#fileGrid');
      var progressBar = win.down('#overallProgress');

      // Ensure all items have final status in the grid
      if (grid && !grid.destroyed) {
        var store = grid.getStore();
        var taskFailed = (result.status === 'FAILED');
        // Check if all items were processed by backend
        var backendProcessedCount = 0;
        if (statusMap) {
          for (var bk in statusMap) {
            if (statusMap[bk] === 'success' || statusMap[bk] === 'failed' || statusMap[bk] === 'skipped') {
              backendProcessedCount++;
            }
          }
        }
        var hasUnprocessed = (backendProcessedCount < totalFiles);
        // Update statusMap for all remaining pending items
        if (statusMap) {
          for (var p in statusMap) {
            if (statusMap[p] !== 'success' && statusMap[p] !== 'failed' && statusMap[p] !== 'skipped') {
              statusMap[p] = (taskFailed || hasUnprocessed) ? 'failed' : 'success';
            }
          }
        }
        // Update current page store records (other pages synced via 'load' event)
        store.each(function (rec) {
          var st = rec.get('status');
          if (st !== 'success' && st !== 'failed' && st !== 'skipped') {
            rec.set('status', (taskFailed || hasUnprocessed) ? 'failed' : 'success');
          }
        });
      }

      // Update progress bar - use backend counts directly for accuracy
      if (progressBar) {
        progressBar.setValue(1);
        var total = backendTotalCount || totalFiles;
        var done = backendDoneCount;
        // Fallback: if no backend counts, count from statusMap
        if (!done && statusMap) {
          done = 0;
          for (var k in statusMap) {
            var sv = statusMap[k];
          if (sv === 'success' || sv === 'failed' || sv === 'skipped' || sv === 'cancelled') done++;
          }
          total = total || Object.keys(statusMap).length;
        }
        progressBar.updateText(_t('promotion.progress.completed', done, total));
      }

      // Update window title
      if (result.status === 'COMPLETED') {
        win.setTitle(_t('promotion.progress.success'));
      } else {
        win.setTitle(_t('promotion.progress.failed'));
      }
    };

    var doPoll = function () {
      if (isFinished || win.destroyed) {
        stopPolling();
        return;
      }

      pollCount++;
      if (pollCount > MAX_POLLS) {
        finishProgress({ status: 'FAILED' }, 0, totalFiles);
        return;
      }

      // Use raw XMLHttpRequest to avoid any framework issues
      var xhr = new XMLHttpRequest();
      xhr.open('GET', '/service/rest/v1/promotion/task/' + encodeURIComponent(taskId), true);
      xhr.setRequestHeader('Accept', 'application/json');
      xhr.onreadystatechange = function () {
        if (xhr.readyState !== 4) return;
        if (isFinished || win.destroyed) {
          stopPolling();
          return;
        }

        if (xhr.status !== 200) {
          // Task not found or error - retry next interval
          return;
        }

        var grid = win.down('#fileGrid');
        var progressBar = win.down('#overallProgress');
        if (!grid || !progressBar || win.destroyed) {
          stopPolling();
          return;
        }

        var store = grid.getStore();
        var result = {};
        try {
          result = JSON.parse(xhr.responseText);
        } catch (e) {
          progressBar.updateText('Error parsing response');
          return;
        }

        var statusStr = (result.status || '').toLowerCase();
        var backendItems = result.items || [];
        var backendItemCount = backendItems.length;
        var currentTotal = totalFiles;

        // Update statusMap from backend results first (works across all pages)
        for (var i = 0; i < backendItems.length; i++) {
          var item = backendItems[i];
          if (!item.path) continue;
          var itemStatus = item.status;
          if (itemStatus !== 'failed' && itemStatus !== 'skipped' && itemStatus !== 'success') {
            itemStatus = 'success';
          }
          statusMap[item.path] = itemStatus;
        }

        // Sync current page store records from statusMap
        // (other pages will be synced via the 'load' event when user navigates)
        store.each(function (rec) {
          var recPath = rec.get('path');
          if (statusMap[recPath] && statusMap[recPath] !== rec.get('status')) {
            rec.set('status', statusMap[recPath]);
          }
        });

        if (statusStr === 'completed' || statusStr === 'failed' || statusStr === 'cancelled') {
            var taskFailed = (statusStr === 'failed' || statusStr === 'cancelled');

            // Mark any remaining pending/processing items in statusMap
            // If backend processed fewer items than total, remaining items were NOT promoted
            var backendProcessedCount = 0;
            for (var bk in statusMap) {
              if (statusMap[bk] === 'success' || statusMap[bk] === 'failed' || statusMap[bk] === 'skipped' || statusMap[bk] === 'cancelled') {
                backendProcessedCount++;
              }
            }
            var hasUnprocessed = (backendProcessedCount < currentTotal);

            for (var p in statusMap) {
              if (statusMap[p] !== 'success' && statusMap[p] !== 'failed' && statusMap[p] !== 'skipped' && statusMap[p] !== 'cancelled') {
                // If task failed/cancelled or there are unprocessed items, mark remaining as cancelled
                // Only mark as success if task completed AND all items were processed
                statusMap[p] = (taskFailed || hasUnprocessed) ? 'cancelled' : 'success';
              }
            }

            // Sync current page store records from statusMap
            // (other pages will be synced via the 'load' event when user navigates)
            store.each(function (rec) {
              var st = rec.get('status');
              if (st !== 'success' && st !== 'failed' && st !== 'skipped' && st !== 'cancelled') {
                rec.set('status', (taskFailed || hasUnprocessed) ? 'cancelled' : 'success');
              }
            });

            // Set progress bar - use backend count
            var doneCount = backendItemCount > 0 ? backendItemCount : Object.keys(statusMap).length;
            progressBar.setValue(1);
            progressBar.updateText(_t('promotion.progress.completed', doneCount, currentTotal));

            // Update window title
            if (statusStr === 'cancelled') {
              win.setTitle(_t('promotion.result.title.cancelled'));
            } else if (taskFailed) {
              win.setTitle(_t('promotion.progress.failed'));
            } else {
              win.setTitle(_t('promotion.progress.success'));
            }

            // Stop polling
            if (!isFinished) {
              isFinished = true;
              stopPolling();
            }
            return;
          }

        // Task is still running - mark first pending item as processing (current page only)
        var hasProcessing = false;
        store.each(function (rec) {
          var rs = rec.get('status');
          if (!hasProcessing && rs !== 'success' && rs !== 'failed' && rs !== 'skipped') {
            rec.set('status', 'processing');
            hasProcessing = true;
          }
        });

        // Count and update progress bar from statusMap (all pages)
        var processedCount = 0;
        for (var k in statusMap) {
          var sv = statusMap[k];
          if (sv === 'success' || sv === 'failed' || sv === 'skipped' || sv === 'cancelled') { processedCount++; }
        }

        var pct = currentTotal > 0 ? (processedCount / currentTotal) : 0;
        progressBar.setValue(pct);
        progressBar.updateText(_t('promotion.progress.completed', processedCount, currentTotal));
      };

      // Add CSRF token if available
      var csrfToken = null;
      var cookies = document.cookie.split(';');
      for (var ci = 0; ci < cookies.length; ci++) {
        var cookie = cookies[ci].trim();
        if (cookie.indexOf('NX-ANTI-CSRF-TOKEN=') === 0) {
          csrfToken = cookie.substring('NX-ANTI-CSRF-TOKEN='.length);
          break;
        }
      }
      if (csrfToken) {
        xhr.setRequestHeader('NX-ANTI-CSRF-TOKEN', csrfToken);
      }

      xhr.send();
    };

    win._pollInterval = setInterval(doPoll, 1500);

    // Initial poll after delay to allow task to be created
    setTimeout(doPoll, 2000);
  },

  showPromotionResult: function (result, targetRepo) {
    var isSuccess = result.status === 'COMPLETED';
    var title = isSuccess ? _t('promotion.result.title.success') : _t('promotion.result.title.failed');
    var items = '';

    // New format: items have path (full repo/path), status
    if (result.items && result.items.length > 0) {
      items = '<ul>';
      Ext.each(result.items, function (item) {
        var statusIcon = item.status === 'failed' ? ' [FAILED]' : '';
        var path = sanitize(item.path || '');
        items += '<li>' + path + statusIcon + '</li>';
      });
      items += '</ul>';
    }

    var win = Ext.create('Ext.window.Window', {
      title: title,
      width: 550,
      modal: true,
      layout: 'fit',
      items: [{
        xtype: 'panel',
        bodyPadding: 15,
        html: '<p><b>' + _t('promotion.result.targetRepository') + '</b> ' + sanitize(targetRepo) + '</p>' +
              '<p><b>' + _t('promotion.result.status') + '</b> ' + sanitize(result.status) + '</p>' +
              (result.errorMessage ? '<p><b>' + _t('promotion.result.error') + '</b> ' + sanitize(result.errorMessage) + '</p>' : '') +
              (items ? '<p><b>' + _t('promotion.result.items') + '</b></p>' + items : '')
      }],
      buttons: [
        {
          text: _t('promotion.result.close'),
          handler: function () { win.close(); }
        }
      ]
    });
    win.show();
  },

  // ==================== Docker-specific UI ====================

  /**
   * Show Docker promotion modal with image/tag selection.
   */
  showDockerPromotionModal: function (repoName, format, path, isDirectory) {
    var me = this;

    // First get target repos for promotion
    apiRequest('GET', '/promotion/targets?sourceRepository=' + encodeURIComponent(repoName) + '&format=' + encodeURIComponent(format))
    .then(function (data) {
      if (!data.repositories || data.repositories.length === 0) {
        showAlertDialog(_t('promotion.noTargets.title'), _t('promotion.noTargets.message'));
        return;
      }

      // Use the existing preview API to get Docker file list
      var previewReq = {
        sourceRepository: repoName,
        targetRepository: data.repositories[0].name,
        path: path,
        isDirectory: isDirectory,
        format: format
      };

      apiRequest('POST', '/promotion/preview', previewReq)
      .then(function (preview) {
        // Transform preview files to image:tag format for Docker display
        var dockerFiles = [];
        if (preview && preview.files) {
          Ext.each(preview.files, function (f) {
            // Parse Docker asset paths to image:tag format
            // Paths like: v2/myapp/backend/manifests/latest, v2/nginx/manifests/v1.0
            var displayPath = f.path;
            var manifestMatch = f.path.match(/^v2\/(.+?)\/manifests\/(.+)$/);
            if (manifestMatch) {
              displayPath = manifestMatch[1] + ':' + manifestMatch[2];
            }
            dockerFiles.push({
              path: displayPath,
              originalPath: f.path,
              type: 'image',
              size: f.size,
              status: 'pending'
            });
          });
        }

        // Reuse the standard promotion modal with Docker-specific display
        me.renderDockerPromotionModal(data.repositories, previewReq, dockerFiles, preview);
      })
      .catch(function (err) {
        showAlertDialog(_t('promotion.preview.failed'), sanitize(err.message));
      });
    })
    .catch(function (err) {
      showAlertDialog(_t('common.error'), sanitize(err.message));
    });
  },

  /**
   * Render Docker promotion modal using existing preview data.
   * Reuses the standard promotion flow (preview → execute) but displays image:tag format.
   */
  renderDockerPromotionModal: function (targetRepos, request, dockerFiles, preview) {
    var me = this;
    var targetOptions = [];
    Ext.each(targetRepos, function (repo) {
      targetOptions.push({ text: repo.name + ' (' + repo.format + ' - ' + repo.type + ')', value: repo.name });
    });

    // Status map to track all file statuses across pages (store.each/findExact only works on current page)
    var dockerStatusMap = {};
    if (dockerFiles) {
      Ext.each(dockerFiles, function (f) {
        var key = f.originalPath || f.path;
        if (key) { dockerStatusMap[key] = 'pending'; }
      });
    }

    var fileStore = Ext.create('Ext.data.Store', {
      fields: ['path', 'originalPath', 'type', 'size', 'status'],
      pageSize: 10,
      proxy: {
        type: 'memory',
        enablePaging: true
      },
      data: dockerFiles || []
    });

    // Sync dockerStatusMap to current page records whenever store loads (handles page navigation)
    fileStore.on('load', function (store) {
      store.each(function (rec) {
        var key = rec.get('originalPath') || rec.get('path');
        if (dockerStatusMap[key] && dockerStatusMap[key] !== rec.get('status')) {
          rec.set('status', dockerStatusMap[key]);
        }
      });
    });

    var totalFiles = Object.keys(dockerStatusMap).length || fileStore.getTotalCount() || fileStore.getCount();

    var statusRenderer = function (val) {
      switch (val) {
        case 'processing':
          return '<span style="color:#337ab7;font-weight:bold;">' + _t('promotion.progress.processing') + '</span>';
        case 'success':
          return '<span style="color:#5cb85c;font-weight:bold;">' + _t('promotion.progress.success') + '</span>';
        case 'failed':
          return '<span style="color:#d9534f;font-weight:bold;">' + _t('promotion.progress.failed') + '</span>';
        case 'cancelled':
          return '<span style="color:#f0ad4e;font-weight:bold;">' + _t('promotion.progress.cancelled') + '</span>';
        case 'skipped':
          // Display skipped files (MD5 match) as success in UI, but keep backend status unchanged
          return '<span style="color:#5cb85c;font-weight:bold;">' + _t('promotion.progress.success') + '</span>';
        default:
          return '<span style="color:#999;">' + _t('promotion.progress.pending') + '</span>';
      }
    };

    var tipRenderer = function (value, meta) {
      meta.tdAttr = 'data-qtip="' + sanitize(value || '') + '"';
      return value;
    };

    var win = Ext.create('Ext.window.Window', {
      title: _t('docker.modal.title.promote'),
      width: 700,
      height: 500,
      modal: true,
      closable: true,
      layout: 'fit',
      items: [{
        xtype: 'panel',
        layout: { type: 'vbox', align: 'stretch' },
        bodyPadding: 10,
        items: [
          {
            xtype: 'combobox',
            fieldLabel: _t('promotion.modal.targetRepository'),
            store: Ext.create('Ext.data.Store', { fields: ['text', 'value'], data: targetOptions }),
            displayField: 'text',
            valueField: 'value',
            value: targetOptions.length > 0 ? targetOptions[0].value : null,
            queryMode: 'local',
            editable: false,
            itemId: 'targetCombo'
          },
          {
            xtype: 'displayfield',
            itemId: 'targetDisplay',
            hidden: true,
            value: '',
            labelWidth: 120,
            fieldLabel: _t('promotion.result.targetRepository')
          },
          {
            xtype: 'container',
            itemId: 'progressContainer',
            hidden: true,
            margin: '8 0',
            layout: { type: 'hbox', align: 'middle' },
            items: [
              {
                xtype: 'progressbar',
                itemId: 'overallProgress',
                flex: 1,
                value: 0,
                text: _t('promotion.progress.completed', 0, totalFiles || '?'),
                animate: true
              }
            ]
          },
          {
            xtype: 'gridpanel',
            title: _t('docker.modal.filesToPromote'),
            flex: 1,
            itemId: 'fileGrid',
            store: fileStore,
            columns: [
              { text: 'Image:Tag', dataIndex: 'path', flex: 2, renderer: tipRenderer, cellWrap: true },
              { text: 'Type', dataIndex: 'type', width: 80 },
              {
                text: _t('promotion.result.status'), dataIndex: 'status', width: 100,
                renderer: statusRenderer
              }
            ],
            dockedItems: [{
              xtype: 'pagingtoolbar',
              store: fileStore,
              dock: 'bottom',
              displayInfo: true,
              displayMsg: '{0} - {1} / {2}',
              emptyMsg: ''
            }]
          }
        ]
      }],
      buttons: [
        {
          text: _t('promotion.modal.cancel'),
          itemId: 'cancelBtn',
          handler: function () { win.close(); }
        },
        {
          text: _t('promotion.modal.promote'),
          itemId: 'promoteBtn',
          handler: function () {
            var targetRepo = win.down('#targetCombo').getValue();
            if (!targetRepo) return;

            var promoteBtn = win.down('#promoteBtn');
            var cancelBtn = win.down('#cancelBtn');
            promoteBtn.setText(_t('promotion.modal.promoting'));
            promoteBtn.disable();
            cancelBtn.disable();

            // Collect original file paths from preview for promotion (use dockerFiles array directly)
            var promoFiles = [];
            Ext.each(dockerFiles, function (f) {
              if (f.originalPath) { promoFiles.push(f.originalPath); }
            });

            apiRequest('POST', '/promotion/execute', {
              sourceRepository: request.sourceRepository,
              targetRepository: targetRepo,
              path: request.path,
              isDirectory: request.isDirectory,
              format: request.format,
              files: promoFiles
            })
            .then(function (result) {
              // Switch to progress mode
              win.setTitle(_t('promotion.modal.promoting'));
              win.down('#targetCombo').hide();
              var targetDisplay = win.down('#targetDisplay');
              targetDisplay.setValue('<b>' + sanitize(targetRepo) + '</b>');
              targetDisplay.show();
              win.down('#progressContainer').show();
              var grid = win.down('#fileGrid');
              if (grid && grid.title) { grid.setTitle(''); }

              // Change buttons: hide promote, change cancel to close
              promoteBtn.hide();
              cancelBtn.setText(_t('promotion.result.close'));
              cancelBtn.enable();
              cancelBtn.setHandler(function () {
                if (win._pollInterval) {
                  clearInterval(win._pollInterval);
                  win._pollInterval = null;
                }
                win.close();
              });

              // Start polling
              me.startPollingInWindow(win, result.taskId, dockerFiles.map(function(f) { return f.originalPath || f.path; }), totalFiles, dockerStatusMap);
            })
            .catch(function (err) {
              promoteBtn.setText(_t('promotion.modal.promote'));
              promoteBtn.enable();
              cancelBtn.enable();
              showAlertDialog(_t('promotion.execute.failed'), sanitize(err.message));
            });
          }
        }
      ]
    });
    win.show();
  },

});

// ==================== Sync Queue Created Dialog ====================

function showSyncQueueCreatedDialog(result) {
  var win = Ext.create('Ext.window.Window', {
    title: _t('sync.queue.created.title'),
    minWidth: 400,
    maxWidth: 700,
    autoScroll: true,
    modal: true,
    layout: 'fit',
    items: [{
      xtype: 'panel',
      bodyPadding: 15,
      autoScroll: true,
      items: [
        { xtype: 'displayfield', fieldLabel: _t('sync.queue.created.queueId'), value: '<span style="word-break:break-all;">' + sanitize(result.taskId || result.queueId || '') + '</span>' },
        { xtype: 'displayfield', fieldLabel: _t('sync.queue.created.repository'), value: '<span style="word-break:break-all;">' + sanitize(result.repository || '') + '</span>' },
        { xtype: 'displayfield', fieldLabel: _t('sync.queue.created.path'), value: '<span style="word-break:break-all;">' + sanitize(result.path || '') + '</span>' },
        { xtype: 'displayfield', value: _t('sync.queue.created.message') }
      ]
    }],
    buttons: [
      {
        text: _t('sync.queue.created.viewQueue'),
        handler: function () {
          win.close();
          // Navigate to sync queue page
          if (NX && NX.Bookmarks && NX.Bookmarks.navigateTo && NX.Bookmarks.fromToken) {
            NX.Bookmarks.navigateTo(NX.Bookmarks.fromToken('browse/SyncQueue'));
          }
        }
      },
      {
        text: _t('sync.queue.created.close'),
        handler: function () { win.close(); }
      }
    ]
  });
  win.show();
}
