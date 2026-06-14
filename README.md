# Nexus Artifacts Promotion Plugin

**Version**: 1.0.0-SNAPSHOT  
**Compatibility**: Nexus Repository Manager 3.45.0+ (PRO & OSS)

Nexus 3 插件，提供制品晋级（Promotion）和 Proxy 仓库远程同步（Sync）功能。

---

## 目录

- [功能概览](#功能概览)
- [设计架构](#设计架构)
- [晋级同步流程](#晋级同步流程)
- [安装部署](#安装部署)
- [使用方式](#使用方式)
- [REST API](#rest-api)
- [定时任务](#定时任务)
- [权限说明](#权限说明)
- [构建开发](#构建开发)

---

## 功能概览

| 功能 | 描述 |
|------|------|
| **制品晋级** | 将制品从源仓库复制到目标仓库（HTTP 下载→上传），支持单文件/目录晋级，MD5 增量跳过 |
| **Proxy 仓库同步** | 从远程同步 Proxy 仓库缓存，支持单文件/目录/全库同步，MD5 增量比对 |
| **Docker 镜像晋级** | 解析 Docker Manifest 智能识别依赖的 Blob，仅晋级必需的 Blob 层，支持全量/指定 Tag 晋级 |
| **Docker Proxy 同步** | 解析 Docker Manifest 后按依赖同步 Blob，避免无效传输，支持全量/指定 Tag 同步 |
| **定时同步任务** | 在 Nexus System → Tasks 中创建定时任务，自动同步 Proxy 仓库 |

---

## 设计架构

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                            Nexus Repository Manager 3                       │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │                         Frontend (ExtJS)                            │    │
│  │  ┌──────────────────┐  ┌──────────────────┐  ┌─────────────────┐  │    │
│  │  │  Asset Info Panel │  │ Folder Info Panel │  │  i18n (en/zh)  │  │    │
│  │  │  [Promote] [Sync] │  │  [Promote] [Sync] │  │                 │  │    │
│  │  └────────┬─────────┘  └────────┬──────────┘  └─────────────────┘  │    │
│  │           │                      │                                  │    │
│  │           └──────────┬───────────┘                                  │    │
│  │                      ▼                                              │    │
│  │  ┌───────────────────────────────────────────────────────────────┐  │    │
│  │  │                    PluginConfig.js                            │  │    │
│  │  │  • Button injection (afterrender/updated events)             │  │    │
│  │  │  • API request wrapper (cookie + CSRF auth)                  │  │    │
│  │  │  • Progress window + polling                                 │  │    │
│  │  │  • Permission check before action                            │  │    │
│  │  └───────────────────────────┬───────────────────────────────────┘  │    │
│  └──────────────────────────────┼──────────────────────────────────────┘    │
│                                 │ REST API                                 │
│  ┌──────────────────────────────┼──────────────────────────────────────┐    │
│  │                      REST Resource Layer                            │    │
│  │  ┌───────────────────┐  ┌───────────────────┐  ┌────────────────┐ │    │
│  │  │ PromotionResource │  │   SyncResource    │  │SyncQueueResource│ │    │
│  │  │ /v1/promotion/*   │  │   /v1/sync/*      │  │ /v1/sync/queue │ │    │
│  │  └────────┬──────────┘  └────────┬──────────┘  └───────┬────────┘ │    │
│  │  ┌───────────────────┐                                           │    │
│  │  │  DockerResource   │                                           │    │
│  │  │ /v1/docker/*      │                                           │    │
│  │  └────────┬──────────┘                                           │    │
│  └───────────┼──────────────────────┼──────────────────────┼──────────┘    │
│              │                      │                      │               │
│  ┌───────────┼──────────────────────┼──────────────────────┼──────────┐    │
│  │           │       Service Layer  │                      │          │    │
│  │  ┌────────▼──────────┐  ┌───────▼──────────┐  ┌───────▼────────┐ │    │
│  │  │ PromotionService  │  │   SyncService    │  │TaskExecutorSvc │ │    │
│  │  │ • HTTP download   │  │ • ContentFacet   │  │ • Thread pool  │ │    │
│  │  │ • HTTP upload     │  │ • MD5 compare    │  │ • Queue manage │ │    │
│  │  │ • MD5 compare     │  │ • Cache delete   │  │ • Dedup        │ │    │
│  │  │ • Search assets   │  │ • NegCache inval │  │                │ │    │
│  │  └────────┬──────────┘  └───────┬──────────┘  └───────┬────────┘ │    │
│  │  ┌────────▼──────────┐                                           │    │
│  │  │  DockerService    │                                           │    │
│  │  │ • Manifest parse  │                                           │    │
│  │  │ • Blob reference  │                                           │    │
│  │  │ • Image/tag list  │                                           │    │
│  │  │ • Docker promote  │                                           │    │
│  │  │ • Docker sync     │                                           │    │
│  │  └────────┬──────────┘                                           │    │
│  │           │                     │                      │           │    │
│  │  ┌────────┴─────────────────────┴──────────────────────┘           │    │
│  │  │                     Common Components                         │    │
│  │  │  ┌──────────────────┐  ┌──────────────┐  ┌────────────────┐  │    │
│  │  │  │ PermissionChecker│  │TaskCacheMgr  │  │ SecurityHelper │  │    │
│  │  │  │ • Shiro perms    │  │ • MD5 cache  │  │ • Subject bind │  │    │
│  │  │  └──────────────────┘  └──────────────┘  └────────────────┘  │    │
│  │  └───────────────────────────────────────────────────────────────┘    │
│  └──────────────────────────────────────────────────────────────────────┘    │
│                                                                             │
│  ┌──────────────────────────────────────────────────────────────────────┐    │
│  │                     Scheduled Task Layer                             │    │
│  │  ┌────────────────────────┐  ┌─────────────────────────────────┐   │    │
│  │  │ ProxySyncTaskDescriptor│  │      ProxySyncTask              │   │    │
│  │  │ • Task template reg   │  │ • Read config (repo + path)     │   │    │
│  │  │ • Repo name field     │  │ • Call SyncService.syncScheduled│   │    │
│  │  │ • Sync path field     │  │ • No permission check (admin)   │   │    │
│  │  └────────────────────────┘  └─────────────────────────────────┘   │    │
│  │  ┌────────────────────────────┐  ┌──────────────────────────────┐  │    │
│  │  │ SyncQueueSizeTaskDescriptor│  │   SyncQueueSizeTask         │  │    │
│  │  │ • Monitor template reg    │  │ • Report sync queue size    │  │    │
│  │  └────────────────────────────┘  └──────────────────────────────┘  │    │
│  └──────────────────────────────────────────────────────────────────────┘    │
│                                                                             │
│  ┌──────────────────────────────────────────────────────────────────────┐    │
│  │                     Capability Configuration                        │    │
│  │  ┌────────────────────────────┐  ┌────────────────────────────────┐│    │
│  │  │ PromotionCapability(Desc)  │  │  SyncCapability(Desc)         ││    │
│  │  │ • Thread pool size         │  │  • Thread pool size           ││    │
│  │  │                            │  │  • Admin credentials          ││    │
│  │  └────────────────────────────┘  └────────────────────────────────┘│    │
│  └──────────────────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 晋级同步流程

### 制品晋级流程（Promotion）

```
用户点击 [Promote] 按钮
        │
        ▼
┌───────────────────┐
│ 1. 权限检查        │  PermissionChecker.checkTargetWritePermission()
│    目标仓库写权限   │  → 失败: 显示权限错误提示
└────────┬──────────┘
         │ 通过
         ▼
┌───────────────────┐
│ 2. 文件预览        │  PromotionService.previewPromotion()
│    列出待晋级文件   │  → Search API / Components API
│    选择目标仓库     │  → PromotionService.listTargetRepositories()
└────────┬──────────┘
         │ 确认
         ▼
┌───────────────────────────────────────────────────┐
│ 3. 执行晋级 (同步等待)                             │
│                                                   │
│   ┌─────────────────────────────────────────────┐ │
│   │ For each file:                              │ │
│   │                                             │ │
│   │  ┌──────────┐    ┌──────────┐   ┌────────┐ │ │
│   │  │ MD5 比对  │───→│ 相同？   │──→│ Skip   │ │ │
│   │  │ 源 vs 目标│    │          │   │ 跳过   │ │ │
│   │  └──────────┘    └────┬─────┘   └────────┘ │ │
│   │                       │ 不同                 │ │
│   │                       ▼                      │ │
│   │  ┌──────────────────────────────────────┐   │ │
│   │  │ HTTP GET 下载源文件                   │   │ │
│   │  │   /repository/{source}/{path}        │   │ │
│   │  │         │                            │   │ │
│   │  │         ▼                            │   │ │
│   │  │ HTTP PUT 上传到目标仓库               │   │ │
│   │  │   /repository/{target}/{path}        │   │ │
│   │  │   (使用用户 Cookie + CSRF 认证)      │   │ │
│   │  └──────────────────────────────────────┘   │ │
│   └─────────────────────────────────────────────┘ │
└───────────────────────────────────────────────────┘
         │
         ▼
┌───────────────────────────────────────────────────┐
│ 4. 弹窗显示晋级结果                                │
│    显示晋级文件列表及状态：                         │
│    • 目标仓库名称                                   │
│    • 晋级状态 (COMPLETED / FAILED)                  │
│    • 每个文件的路径及状态 (成功/失败)                │
│    • 错误信息（如有）                               │
└───────────────────────────────────────────────────┘
```

### Proxy 仓库同步流程（Sync）

```
用户点击 [Sync] 按钮 / 定时任务触发
        │
        ▼
┌───────────────────┐
│ 1. 权限检查        │  PermissionChecker.checkSyncPermission()
│    Proxy 仓库删除权 │  (定时任务跳过此步)
│    验证为 proxy 类型│  → 失败: 显示权限错误提示
└────────┬──────────┘
         │ 通过
         ▼
┌───────────────────┐
│ 2. 获取远程文件列表│  SyncService.listRemoteAssets()
│                    │
│   ┌─────────────────────────────────────────┐
│   │ Strategy 1: 远程 URL 指向本 Nexus 实例   │
│   │   → Search Assets API (分页)             │
│   │   → group 过滤 / path 前缀过滤           │
│   ├─────────────────────────────────────────┤
│   │ Strategy 2: 外部 HTTP 远程仓库           │
│   │   → HTML 目录列表解析                    │
│   │   → 递归爬取子目录                       │
│   └─────────────────────────────────────────┘
└────────┬──────────┘
         │
         ▼
┌───────────────────┐
│ 3. 提交异步任务    │  SyncService.sync() / syncScheduled()
│    返回 taskId     │  → TaskExecutorService.submitSyncTask()
└────────┬──────────┘
         │
         ▼
┌───────────────────────────────────────────────────┐
│ 4. 逐文件同步                                      │
│                                                   │
│   ┌─────────────────────────────────────────────┐ │
│   │ For each remote asset:                      │ │
│   │                                             │ │
│   │  ┌──────────┐    ┌──────────┐   ┌────────┐ │ │
│   │  │ MD5 比对  │───→│ 相同？   │──→│ Skip   │ │ │
│   │  │ 远程 vs   │    │          │   │ 跳过   │ │ │
│   │  │ 本地缓存  │    └────┬─────┘   └────────┘ │ │
│   │  └──────────┘         │ 不同               │ │
│   │                       ▼                     │ │
│   │  ┌──────────────────────────────────────┐   │ │
│   │  │ ① 删除本地缓存 Asset                 │   │ │
│   │  │     StorageTx.deleteAsset()          │   │ │
│   │  │                │                     │   │ │
│   │  │                ▼                     │   │ │
│   │  │ ② 清除负缓存 (404 记录)              │   │ │
│   │  │     NegativeCacheFacet.invalidate()  │   │ │
│   │  │                │                     │   │ │
│   │  │                ▼                     │   │ │
│   │  │ ③ ViewFacet.dispatch(GET)            │   │ │
│   │  │     → Nexus 自动从远程下载并缓存      │   │ │
│   │  │     → 保存到 BlobStore               │   │ │
│   │  └──────────────────────────────────────┘   │ │
│   └─────────────────────────────────────────────┘ │
│                                                   │
│   更新 taskInfo → 前端轮询进度                     │
└───────────────────────────────────────────────────┘
         │
         ▼
┌───────────────────┐
│ 5. 前端轮询结果    │  GET /v1/sync/task/{taskId}
│    显示同步结果    │  → 成功/失败/跳过 文件列表
└───────────────────┘
```

### Docker 镜像晋级流程（Docker Promotion）

```
用户点击 [Docker Promote] 按钮（Docker 格式仓库）
        │
        ▼
┌───────────────────────────┐
│ 1. Docker 镜像选择         │  GET /v1/docker/images?repository=xxx
│    选择镜像名称            │  → 下拉列表展示所有镜像及标签
│    选择标签（全部/指定）    │  → Checkbox 网格选择 Tag
│    选择目标仓库            │  → 同格式 Docker 仓库
└────────────┬──────────────┘
             │ 确认
             ▼
┌───────────────────────────┐
│ 2. 执行晋级               │  POST /v1/docker/promote
│    同步等待完成            │  DockerService.promoteDockerImage()
└────────────┬──────────────┘
             │
             ▼
┌──────────────────────────────────────────────────────┐
│ 3. 逐 Tag 晋级 (线程池)                               │
│                                                      │
│   For each tag:                                      │
│   ┌──────────────────────────────────────────────┐   │
│   │ ① 下载 Manifest (源仓库)                     │   │
│   │    GET /repository/{source}/v2/{image}/       │   │
│   │        manifests/{tag}                        │   │
│   │              │                                │   │
│   │              ▼                                │   │
│   │ ② 解析 Manifest 提取 Blob 摘要               │   │
│   │    config.digest + layers[].digest            │   │
│   │    manifests[].digest (胖清单多平台)           │   │
│   │              │                                │   │
│   │              ▼                                │   │
│   │ ③ 逐 Blob 晋级                               │   │
│   │    ┌──────────────┐   ┌──────────┐           │   │
│   │    │ MD5 比对      │──→│ 相同？   │──→ Skip  │   │
│   │    │ 源 vs 目标    │   └────┬─────┘           │   │
│   │    └──────────────┘        │ 不同             │   │
│   │                            ▼                  │   │
│   │    HTTP GET 下载 Blob → HTTP PUT 上传到目标   │   │
│   │              │                                │   │
│   │              ▼                                │   │
│   │ ④ 上传 Manifest 到目标仓库                    │   │
│   │    PUT /repository/{target}/v2/{image}/       │   │
│   │        manifests/{tag}                        │   │
│   └──────────────────────────────────────────────┘   │
│                                                      │
│   更新 taskResult                                   │
└──────────────────────────────────────────────────────┘
             │
             ▼
┌───────────────────────────────────────────────────┐
│ 4. 弹窗显示晋级结果                                │
│    显示晋级文件列表及状态：                         │
│    • 目标仓库名称                                   │
│    • 晋级状态 (COMPLETED / FAILED)                  │
│    • 每个文件的路径及状态 (成功/失败)                │
│    • 错误信息（如有）                               │
└───────────────────────────────────────────────────┘
```

**核心优势**：相比通用目录晋级（盲目传输目录下所有文件），Docker 专用晋级：
- 解析 Manifest 精确识别依赖的 Blob 层，**仅传输必需的 Blob**
- 避免传输未被任何 Tag 引用的孤立 Blob
- 支持指定 Tag 晋级，无需晋级整个镜像的所有版本

### Docker Proxy 仓库同步流程（Docker Sync）

```
用户点击 [Docker Sync] 按钮（Docker Proxy 仓库）
        │
        ▼
┌───────────────────────────┐
│ 1. Docker 镜像选择         │  GET /v1/docker/images?repository=xxx
│    选择镜像名称            │  → 下拉列表展示所有镜像及标签
│    选择标签（全部/指定）    │  → Checkbox 网格选择 Tag
└────────────┬──────────────┘
             │ 确认
             ▼
┌───────────────────────────┐
│ 2. 提交异步任务           │  POST /v1/docker/sync
│    返回 taskId            │  DockerService.syncDockerImage()
└────────────┬──────────────┘
             │
             ▼
┌──────────────────────────────────────────────────────┐
│ 3. 逐 Tag 同步 (线程池)                               │
│                                                      │
│   For each tag:                                      │
│   ┌──────────────────────────────────────────────┐   │
│   │ ① 同步 Manifest (ViewFacet.dispatch)         │   │
│   │    删除缓存 → 清负缓存 → dispatch GET         │   │
│   │              │                                │   │
│   │              ▼                                │   │
│   │ ② 从本地缓存读取 Manifest 内容               │   │
│   │    StorageTx → Blob.getInputStream()          │   │
│   │              │                                │   │
│   │              ▼                                │   │
│   │ ③ 解析 Manifest 提取 Blob 摘要               │   │
│   │    config.digest + layers[].digest            │   │
│   │              │                                │   │
│   │              ▼                                │   │
│   │ ④ 逐 Blob 同步                               │   │
│   │    ┌──────────────┐   ┌──────────┐           │   │
│   │    │ MD5 比对      │──→│ 相同？   │──→ Skip  │   │
│   │    │ 远程 vs 本地  │   └────┬─────┘           │   │
│   │    └──────────────┘        │ 不同             │   │
│   │                            ▼                  │   │
│   │    删除缓存 → 清负缓存 → ViewFacet.dispatch   │   │
│   │    → Nexus 自动从远程下载并缓存到 BlobStore   │   │
│   └──────────────────────────────────────────────┘   │
│                                                      │
│   更新 taskInfo → 前端轮询进度                        │
└──────────────────────────────────────────────────────┘
             │
             ▼
┌───────────────────────────┐
│ 4. 前端轮询结果           │  GET /v1/docker/sync/task/{taskId}
│    显示同步结果            │  → 成功/失败/跳过 文件列表
└───────────────────────────┘
```

**核心优势**：相比通用目录同步（逐文件尝试同步），Docker 专用同步：
- 先同步 Manifest，解析后**精确知道需要同步哪些 Blob**
- 跳过未被引用的 Blob，**大幅减少远程请求次数**
- 通过 Docker Registry V2 API (`/v2/<name>/tags/list`) 获取完整的远程标签列表

### 前置条件

- Nexus Repository Manager 3.45.0+
- Java 8+
- Maven 3.6+

### 构建

```bash
cd NexusArtifactsPromotion
mvn clean package -DskipTests
```

产物位于 `target/nexus-artifacts-promotion-plugin-1.0.0-SNAPSHOT.jar`

### 部署

1. 将 JAR 文件复制到 Nexus 的 `deploy/` 目录：
   ```bash
   cp target/nexus-artifacts-promotion-plugin-1.0.0-SNAPSHOT.jar /path/to/nexus/deploy/
   ```

2. 重启 Nexus：
   ```bash
   /path/to/nexus/bin/nexus restart
   ```

### 验证

1. 登录 Nexus 管理界面
2. 浏览任意仓库的 Asset/Folder 详情页，应出现 **Promote** 和 **Sync** 按钮
3. 进入 **Administration → System → Tasks**，应出现 **Proxy Repository Scheduled Sync** 任务模板

---

## 使用方式

### 制品晋级

1. 在仓库浏览页面，选择一个文件或文件夹
2. 点击详情面板上的 **Promote** 按钮
3. 选择目标仓库（仅显示同格式且有写权限的仓库）
4. 预览待晋级文件列表
5. 确认执行，弹窗显示晋级文件列表状态（每个文件的路径及成功/失败状态）

### Docker 镜像晋级

1. 在 Docker 格式仓库浏览页面，点击详情面板上的 **Docker 晋级** 按钮
2. 在弹出窗口中选择镜像名称（下拉列表自动加载仓库中所有镜像）
3. 选择标签模式：
   - **全部标签**：晋级该镜像的所有 Tag
   - **选择标签**：勾选需要晋级的特定 Tag
4. 选择目标 Docker 仓库
5. 确认执行，弹窗显示晋级文件列表状态（每个文件的路径及成功/失败状态）

### Docker Proxy 仓库同步

1. 在 Docker Proxy 仓库浏览页面，点击详情面板上的 **Docker 同步** 按钮
2. 在弹出窗口中选择镜像名称
3. 选择标签模式：
   - **全部标签**：同步该镜像的所有 Tag（从远程 Docker Registry V2 API 获取完整标签列表）
   - **选择标签**：勾选需要同步的特定 Tag
4. 确认执行，等待进度窗口显示结果

### Proxy 仓库同步

1. 在 Proxy 仓库浏览页面，选择一个文件或文件夹
2. 点击详情面板上的 **Sync** 按钮
3. 确认执行，等待进度窗口显示结果
4. 同步后的文件将从远程重新拉取到本地缓存

---

## REST API

### Promotion API (`/v1/promotion`)

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/permission?repository=&format=` | 检查晋级权限 |
| GET | `/targets?sourceRepository=&format=` | 列出可用目标仓库 |
| POST | `/preview` | 预览待晋级文件 |
| POST | `/execute` | 执行晋级 |
| GET | `/task/{taskId}` | 查询晋级任务状态 |

### Sync API (`/v1/sync`)

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/permission?repository=&format=` | 检查同步权限 |
| POST | `/execute` | 执行同步（异步） |
| GET | `/task/{taskId}` | 查询同步任务状态 |
| GET | `/proxy-repositories` | 列出所有 Proxy 仓库 |

### Sync Queue API (`/v1/sync/queue`)

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/size` | 获取当前同步队列大小 |

### Docker API (`/v1/docker`)

Docker 镜像专用 API，提供比通用晋级/同步更高效的操作方式：

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/images?repository=xxx` | 列出 Docker 仓库中的所有镜像及标签 |
| GET | `/tags?repository=xxx&image=yyy` | 列出指定镜像的所有标签 |
| POST | `/promote` | Docker 镜像晋级（支持全量/指定 Tag），弹窗显示晋级结果 |
| POST | `/sync` | Docker 镜像同步（Proxy 仓库，支持全量/指定 Tag），异步轮询进度 |
| GET | `/promote/task/{taskId}` | 查询 Docker 晋级任务状态 |
| GET | `/sync/task/{taskId}` | 查询 Docker 同步任务状态 |

**Docker 晋级请求示例**（晋级指定 Tag）：
```json
{
  "sourceRepository": "docker-dev",
  "targetRepository": "docker-prod",
  "image": "myapp/backend",
  "tags": ["latest", "v1.0"],
  "format": "docker"
}
```

**Docker 晋级请求示例**（晋级所有 Tag）：
```json
{
  "sourceRepository": "docker-dev",
  "targetRepository": "docker-prod",
  "image": "myapp/backend",
  "tags": [],
  "format": "docker"
}
```

**Docker 同步请求示例**（同步指定 Tag）：
```json
{
  "sourceRepository": "docker-proxy",
  "image": "nginx",
  "tags": ["stable", "alpine"],
  "format": "docker"
}
```

---

## 定时任务

### Proxy Repository Scheduled Sync

在 **Administration → System → Tasks** 中创建：

| 字段 | 说明 |
|------|------|
| **Task Type** | Proxy Repository Scheduled Sync |
| **Repository Name** | Proxy 仓库名称（必填） |
| **Sync Path** | 同步目录路径（选填，留空 = 全库同步） |
| **Schedule** | 手动 / Cron 表达式 / 固定频率 |

定时任务由管理员创建，自动跳过权限检查，同步执行。

---

## 权限说明

| 操作 | 所需权限 | 说明 |
|------|----------|------|
| 制品晋级 | `repository-view:edit` (目标仓库) | 需要目标仓库的编辑权限 |
| Proxy 同步 | `repository-view:delete` (Proxy 仓库) | 需要仓库的删除权限（因为要先删缓存再同步） |
| 定时同步任务 | 无（管理员操作） | 由管理员在 Task 界面创建，跳过权限检查 |

---

## 构建开发

### 项目结构

```
src/main/java/com/nexus/artifacts/promotion/
├── config/                 # Capability 配置
│   ├── PromotionCapability.java
│   ├── PromotionCapabilityDescriptor.java
│   ├── SyncCapability.java
│   └── SyncCapabilityDescriptor.java
├── exception/              # 异常定义
├── model/                  # 数据模型
│   ├── PromotionRequest.java
│   ├── PromotionTaskResult.java
│   ├── SyncRequest.java
│   ├── SyncTaskInfo.java
│   └── ...
├── resource/               # REST API 端点
│   ├── PromotionResource.java
│   ├── SyncResource.java
│   ├── SyncQueueResource.java
│   └── SystemConfigResource.java
├── security/               # 权限检查
│   └── PermissionChecker.java
├── service/                # 业务逻辑
│   ├── PromotionService.java
│   ├── SyncService.java
│   ├── TaskExecutorService.java
│   └── TaskCacheManager.java
└── task/                   # 定时任务
    ├── ProxySyncTask.java
    ├── ProxySyncTaskDescriptor.java
    ├── SyncQueueSizeTask.java
    └── SyncQueueSizeTaskDescriptor.java
```

### 技术栈

- **后端**: Java 8, JAX-RS, Guice, Nexus Plugin API 3.45.0
- **前端**: ExtJS (Nexus Rapture UI Framework)
- **构建**: Maven + OSGi Bundle
- **安全**: Apache Shiro (Nexus 集成)
