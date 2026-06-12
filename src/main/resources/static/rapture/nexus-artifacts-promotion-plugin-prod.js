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
    "promotion.result.title.success": "Promotion Success",
    "promotion.result.title.failed": "Promotion Failed",
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
    "sync.permission.denied": "You do not have sync permission for this repository.",
    "sync.permission.denied.admin": "You do not have sync permission. Please contact your administrator.",
    "sync.execute.failed": "Sync Failed",
    "sync.queue.created.title": "Sync Queue Created",
    "sync.queue.created.queueId": "Queue ID:",
    "sync.queue.created.repository": "Repository:",
    "sync.queue.created.path": "Path:",
    "sync.queue.created.message": "Sync task has been submitted to the queue.",
    "sync.queue.created.viewQueue": "View Queue",
    "sync.queue.created.close": "Close",

    "sync.queue.page.title": "Sync Queue",
    "sync.queue.page.loginRequired": "Please log in to view sync queue.",
    "sync.queue.page.loading": "Loading sync tasks...",
    "sync.queue.page.noTasks": "No sync tasks found.",
    "sync.queue.page.loadFailed": "Failed to load sync tasks:",
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
    "sync.queue.config.title": "Queue Configuration",
    "sync.queue.config.maxSyncQueueSize": "Max Sync Queue Size",
    "sync.queue.config.syncPoolSize": "Sync Pool Size",
    "sync.queue.config.promotionPoolSize": "Promotion Pool Size",
    "sync.queue.config.save": "Save",
    "sync.queue.config.saved": "Configuration saved",
    "sync.queue.config.saveFailed": "Failed to save configuration",

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
    "promotion.result.title.success": "\u664b\u7ea7\u6210\u529f",
    "promotion.result.title.failed": "\u664b\u7ea7\u5931\u8d25",
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
    "sync.permission.denied": "\u60a8\u6ca1\u6709\u8be5\u4ed3\u5e93\u7684\u540c\u6b65\u6743\u9650\u3002",
    "sync.permission.denied.admin": "\u60a8\u6ca1\u6709\u540c\u6b65\u6743\u9650\uff0c\u8bf7\u8054\u7cfb\u7ba1\u7406\u5458\u3002",
    "sync.execute.failed": "\u540c\u6b65\u5931\u8d25",
    "sync.queue.created.title": "\u540c\u6b65\u961f\u5217\u5df2\u521b\u5efa",
    "sync.queue.created.queueId": "\u961f\u5217ID\uff1a",
    "sync.queue.created.repository": "\u4ed3\u5e93\uff1a",
    "sync.queue.created.path": "\u8def\u5f84\uff1a",
    "sync.queue.created.message": "\u540c\u6b65\u4efb\u52a1\u5df2\u63d0\u4ea4\u5230\u961f\u5217\u3002",
    "sync.queue.created.viewQueue": "\u67e5\u770b\u961f\u5217",
    "sync.queue.created.close": "\u5173\u95ed",

    "sync.queue.page.title": "\u540c\u6b65\u961f\u5217",
    "sync.queue.page.loginRequired": "\u8bf7\u767b\u5f55\u540e\u67e5\u770b\u540c\u6b65\u961f\u5217\u3002",
    "sync.queue.page.loading": "\u52a0\u8f7d\u540c\u6b65\u4efb\u52a1\u4e2d...",
    "sync.queue.page.noTasks": "\u6682\u65e0\u540c\u6b65\u4efb\u52a1\u3002",
    "sync.queue.page.loadFailed": "\u52a0\u8f7d\u540c\u6b65\u4efb\u52a1\u5931\u8d25\uff1a",
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
    "sync.queue.config.title": "\u961f\u5217\u914d\u7f6e",
    "sync.queue.config.maxSyncQueueSize": "\u6700\u5927\u540c\u6b65\u961f\u5217\u6570",
    "sync.queue.config.syncPoolSize": "\u540c\u6b65\u7ebf\u7a0b\u6c60\u5927\u5c0f",
    "sync.queue.config.promotionPoolSize": "\u664b\u7ea7\u7ebf\u7a0b\u6c60\u5927\u5c0f",
    "sync.queue.config.save": "\u4fdd\u5b58",
    "sync.queue.config.saved": "\u914d\u7f6e\u5df2\u4fdd\u5b58",
    "sync.queue.config.saveFailed": "\u4fdd\u5b58\u914d\u7f6e\u5931\u8d25",

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
    if (bundle && bundle.hasOwnProperty(key)) {
      return bundle[key];
    }
    var enBundle = this.bundles['en'];
    if (enBundle && enBundle.hasOwnProperty(key)) {
      return enBundle[key];
    }
    return key;
  }
});

// Shorthand
function _t(key) {
  return NX.artifactsPromotion.I18n.t(key);
}

// ==================== Utility ====================

function sanitize(str) {
  if (str === null || str === undefined) return '';
  var div = document.createElement('div');
  div.appendChild(document.createTextNode(String(str)));
  return div.innerHTML;
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
          resolve(Ext.decode(response.responseText));
        }
        catch (e) {
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
          reject(new Error('Request failed: ' + status));
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
  layout: 'border',

  initComponent: function () {
    var me = this;

    me.store = Ext.create('Ext.data.Store', {
      fields: ['queueId', 'sourceRepository', 'targetRepository', 'path', 'fileDetails',
        'status', 'startTime', 'endTime', 'username', 'result'],
      proxy: {
        type: 'ajax',
        url: '/service/rest/v1/sync/queue',
        reader: { type: 'json', rootProperty: 'tasks' }
      },
      autoLoad: true
    });

    // Config form
    me.configForm = Ext.create('Ext.form.Panel', {
      region: 'east',
      width: 300,
      split: true,
      title: _t('sync.queue.config.title'),
      bodyPadding: 10,
      collapsible: true,
      collapsed: true,
      defaults: {
        anchor: '100%',
        labelWidth: 120,
        allowBlank: false
      },
      items: [
        {
          xtype: 'numberfield',
          name: 'maxSyncQueueSize',
          fieldLabel: _t('sync.queue.config.maxSyncQueueSize'),
          minValue: 1,
          maxValue: 100,
          value: 20
        },
        {
          xtype: 'numberfield',
          name: 'syncPoolSize',
          fieldLabel: _t('sync.queue.config.syncPoolSize'),
          minValue: 1,
          maxValue: 50,
          value: 4
        },
        {
          xtype: 'numberfield',
          name: 'promotionPoolSize',
          fieldLabel: _t('sync.queue.config.promotionPoolSize'),
          minValue: 1,
          maxValue: 50,
          value: 4
        }
      ],
      buttons: [
        {
          text: _t('sync.queue.config.save'),
          handler: function () {
            var form = me.configForm.getForm();
            if (!form.isValid()) return;
            var values = form.getValues();
            var config = {
              maxSyncQueueSize: parseInt(values.maxSyncQueueSize, 10),
              syncPoolSize: parseInt(values.syncPoolSize, 10),
              promotionPoolSize: parseInt(values.promotionPoolSize, 10)
            };
            apiRequest('PUT', '/sync/queue/config', config)
              .then(function () {
                NX.Messages.info(_t('sync.queue.config.saved'));
              })
              .catch(function (err) {
                showAlertDialog(_t('common.error'), _t('sync.queue.config.saveFailed') + ' ' + err.message);
              });
          }
        }
      ],
      listeners: {
        expand: function () {
          // Load current config when panel is expanded
          apiRequest('GET', '/sync/queue/config')
            .then(function (config) {
              me.configForm.getForm().setValues({
                maxSyncQueueSize: config.maxSyncQueueSize || 20,
                syncPoolSize: config.syncPoolSize || 4,
                promotionPoolSize: config.promotionPoolSize || 4
              });
            })
            .catch(function () { /* ignore */ });
        }
      }
    });

    Ext.apply(me, {
      items: [
        me.configForm,
        {
          region: 'center',
          xtype: 'gridpanel',
          store: me.store,
          columns: [
            { text: _t('sync.queue.table.queueId'), dataIndex: 'queueId', width: 120 },
            { text: _t('sync.queue.table.sourceRepository'), dataIndex: 'sourceRepository', flex: 1 },
            { text: _t('sync.queue.table.path'), dataIndex: 'path', flex: 1 },
            { text: _t('sync.queue.table.status'), dataIndex: 'status', width: 100 },
            { text: _t('sync.queue.table.startTime'), dataIndex: 'startTime', width: 150 },
            { text: _t('sync.queue.table.endTime'), dataIndex: 'endTime', width: 150 },
            { text: _t('sync.queue.table.username'), dataIndex: 'username', width: 100 },
            { text: _t('sync.queue.table.result'), dataIndex: 'result', flex: 1 }
          ],
          tbar: [
            {
              text: _t('sync.queue.table.refresh'),
              iconCls: 'x-fa fa-refresh',
              handler: function () { me.store.reload(); }
            }
          ]
        }
      ]
    });

    me.callParent(arguments);
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
        return NX.State.getValue('user') !== undefined;
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

      // Add sync button for proxy repositories
      var isProxy = me.isProxyRepository(repoName);
      if (isProxy) {
        me.addSyncButton(panel, repoName, path, format, true);
      }
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

      // Add sync button for proxy repositories
      var isProxy = me.isProxyRepository(repoName);
      if (isProxy) {
        me.addSyncButton(panel, repoName, path, format, isDirectory);
      }
    } catch (e) {
      // Silently fail - don't break the UI
    }
  },

  isProxyRepository: function (repoName) {
    try {
      var store = Ext.getStore('Repository');
      if (store) {
        var record = store.findRecord('name', repoName, 0, false, true, true);
        if (record) {
          return record.get('type') === 'proxy';
        }
      }
    } catch (e) { /* ignore */ }
    try {
      var repos = NX.State.getValue('repositories');
      if (repos) {
        for (var i = 0; i < repos.length; i++) {
          if (repos[i].name === repoName) {
            return repos[i].type === 'proxy';
          }
        }
      }
    } catch (e) { /* ignore */ }
    return false;
  },

  addPromotionButton: function (panel, repoName, path, format, isDirectory) {
    var me = this;
    if (typeof isDirectory === 'undefined') {
      isDirectory = path && path.endsWith('/');
    }

    var btn = Ext.create('Ext.button.Button', {
      text: isDirectory ? _t('promotion.button.text.directory') : _t('promotion.button.text'),
      iconCls: 'x-fa fa-arrow-up',
      cls: 'promotion-btn',
      handler: function () {
        me.handlePromotionClick(repoName, path, isDirectory, format);
      }
    });

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
  },

  addSyncButton: function (panel, repoName, path, format, isDirectory) {
    var me = this;
    if (typeof isDirectory === 'undefined') {
      isDirectory = path && path.endsWith('/');
    }

    var btn = Ext.create('Ext.button.Button', {
      text: isDirectory ? _t('sync.button.text.directory') : _t('sync.button.text'),
      iconCls: 'x-fa fa-sync',
      cls: 'sync-btn',
      handler: function () {
        me.handleSyncClick(repoName, path, isDirectory, format);
      }
    });

    var actions = panel.down('nx-actions');
    if (actions) {
      actions.add(btn);
    }
  },

  handlePromotionClick: function (repoName, path, isDirectory, format) {
    var me = this;
    // Directly show promotion modal - target repo list is filtered by write permission
    me.showPromotionModal(repoName, path, isDirectory, format);
  },

  handleSyncClick: function (repoName, path, isDirectory, format) {
    checkSyncPermission(repoName, format).then(function (hasPermission) {
      if (!hasPermission) {
        showAlertDialog(_t('common.noPermission'), _t('sync.permission.denied'));
        return;
      }
      apiRequest('POST', '/sync/execute', {
        repository: repoName,
        path: path,
        isDirectory: isDirectory,
        format: format
      })
      .then(function (result) {
        showSyncQueueCreatedDialog(result);
      })
      .catch(function (err) {
        showAlertDialog(_t('sync.execute.failed'), sanitize(err.message));
      });
    });
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

    var fileStore = Ext.create('Ext.data.Store', {
      fields: ['path', 'type', 'size', 'existsInTarget'],
      data: preview && preview.files ? preview.files : []
    });

    var win = Ext.create('Ext.window.Window', {
      title: _t('promotion.modal.title'),
      width: 600,
      height: 500,
      modal: true,
      layout: 'fit',
      items: [{
        xtype: 'panel',
        layout: {
          type: 'vbox',
          align: 'stretch'
        },
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
            xtype: 'gridpanel',
            title: _t('promotion.modal.filesToPromote'),
            flex: 1,
            store: fileStore,
            columns: [
              { text: 'Path', dataIndex: 'path', flex: 2 },
              { text: 'Type', dataIndex: 'type', width: 80 },
              {
                text: 'Action', dataIndex: 'existsInTarget', width: 80,
                renderer: function (val) {
                  return val
                    ? '<span style="color:#f0ad4e;font-weight:bold;">' + _t('promotion.modal.action.update') + '</span>'
                    : '<span style="color:#5cb85c;font-weight:bold;">' + _t('promotion.modal.action.new') + '</span>';
                }
              }
            ]
          }
        ]
      }],
      buttons: [
        {
          text: _t('promotion.modal.cancel'),
          handler: function () { win.close(); }
        },
        {
          text: _t('promotion.modal.promote'),
          itemId: 'promoteBtn',
          handler: function () {
            var targetRepo = win.down('#targetCombo').getValue();
            if (!targetRepo) return;

            var btn = win.down('#promoteBtn');
            btn.setText(_t('promotion.modal.promoting'));
            btn.disable();

            apiRequest('POST', '/promotion/execute', {
              sourceRepository: request.sourceRepository,
              targetRepository: targetRepo,
              path: request.path,
              isDirectory: request.isDirectory,
              format: request.format
            })
            .then(function (result) {
              win.close();
              me.pollPromotionTask(result.taskId, targetRepo);
            })
            .catch(function (err) {
              btn.setText(_t('promotion.modal.promote'));
              btn.enable();
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

  pollPromotionTask: function (taskId, targetRepo) {
    var me = this;

    // Create a progress window that stays open during promotion
    var progressWin = Ext.create('Ext.window.Window', {
      title: _t('promotion.modal.promoting'),
      width: 650,
      height: 450,
      modal: true,
      closable: false,
      layout: 'fit',
      items: [{
        xtype: 'panel',
        layout: {
          type: 'vbox',
          align: 'stretch'
        },
        items: [
          {
            xtype: 'displayfield',
            value: '<b>' + sanitize(targetRepo) + '</b>',
            labelWidth: 120,
            fieldLabel: _t('promotion.result.targetRepository')
          },
          {
            xtype: 'gridpanel',
            flex: 1,
            itemId: 'progressGrid',
            store: {
              fields: ['path', 'action', 'status'],
              data: []
            },
            columns: [
              { text: 'File Path', dataIndex: 'path', flex: 2, cellWrap: true },
              {
                text: 'Action', dataIndex: 'action', width: 80,
                renderer: function (val) {
                  if (!val) return '';
                  return val === 'CREATED'
                    ? '<span style="color:#5cb85c;font-weight:bold;">' + _t('promotion.modal.action.new') + '</span>'
                    : '<span style="color:#f0ad4e;font-weight:bold;">' + _t('promotion.modal.action.update') + '</span>';
                }
              },
              { text: 'Status', dataIndex: 'status', width: 100 }
            ]
          }
        ]
      }]
    });
    progressWin.show();

    // Poll for status updates
    var pollCount = 0;
    var maxPolls = 300; // 10 minutes at 2s intervals

    var doPoll = function () {
      pollCount++;
      if (pollCount > maxPolls) {
        clearInterval(pollInterval);
        progressWin.close();
        me.showPromotionResult({status: 'FAILED', error: 'Timeout waiting for promotion'}, targetRepo);
        return;
      }

      apiRequest('GET', '/promotion/task/' + encodeURIComponent(taskId))
        .then(function (result) {
          var grid = progressWin.down('#progressGrid');
          if (grid && result.items && result.items.length > 0) {
            grid.getStore().loadData(result.items);
          }

          if (result.status === 'COMPLETED' || result.status === 'FAILED') {
            clearInterval(pollInterval);
            progressWin.close();
            me.showPromotionResult(result, targetRepo);
          }
        })
        .catch(function () {
          clearInterval(pollInterval);
          progressWin.close();
        });
    };

    var pollInterval = setInterval(doPoll, 2000);

    // Initial poll after short delay
    setTimeout(doPoll, 500);
  },

  showPromotionResult: function (result, targetRepo) {
    var isSuccess = result.status === 'COMPLETED';
    var title = isSuccess ? _t('promotion.result.title.success') : _t('promotion.result.title.failed');
    var items = '';

    // New format: items have path (full repo/path), action (CREATED/UPDATED), status
    if (result.items && result.items.length > 0) {
      items = '<ul>';
      Ext.each(result.items, function (item) {
        var action = item.action === 'CREATED'
          ? _t('promotion.modal.action.new')
          : _t('promotion.modal.action.update');
        var statusIcon = item.status === 'failed' ? ' [FAILED]' : '';
        var path = sanitize(item.path || '');
        items += '<li>' + path + ' - ' + action + statusIcon + '</li>';
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
  }
});

// ==================== Sync Queue Created Dialog ====================

function showSyncQueueCreatedDialog(result) {
  var win = Ext.create('Ext.window.Window', {
    title: _t('sync.queue.created.title'),
    width: 400,
    modal: true,
    layout: 'fit',
    items: [{
      xtype: 'panel',
      bodyPadding: 15,
      items: [
        { xtype: 'displayfield', fieldLabel: _t('sync.queue.created.queueId'), value: sanitize(result.taskId || result.queueId || '') },
        { xtype: 'displayfield', fieldLabel: _t('sync.queue.created.repository'), value: sanitize(result.repository || '') },
        { xtype: 'displayfield', fieldLabel: _t('sync.queue.created.path'), value: sanitize(result.path || '') },
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
