# Debug Session: promotion-401-dir-btn

**Status**: [OPEN]
**Created**: 2026-06-12
**Symptoms**:
1. 点击"晋级工件"按钮 → 返回 401 未认证错误
2. 目录右侧详情面板中，没有"晋级目录"按钮（文件按钮正常显示）

**Environment**: Nexus Repository Manager (version unknown, likely 3.45-3.70)

---

## Hypotheses

### H1: CSRF Token 获取失败
- `Ext.Ajax.defaultHeaders` 中无 `NX-ANTI-CSRF-TOKEN`
- Cookie 中也无该 token
- Nexus 使用其他机制传递 CSRF（如 meta 标签、API 响应头等）

### H2: 认证头未正确继承
- `Ext.applyIf` 只合并不存在的 key
- Nexus 可能使用非标准认证头名
- Session cookie 是 HttpOnly 的，浏览器自动发送但 CSRF 头缺失

### H3: Nexus 版本认证机制变更
- 新版 Nexus (3.60+) 改用 JWT/Bearer token
- 或 CSRF 保护配置不同

### H4: ComponentFolderInfo 选择器不匹配
- `'nx-coreui-component-componentfolderinfo'` 在用户版本中不存在
- 组件类名已改变（如改为 BrowseFolderInfo 等）
- 导致 afterrender 监听器从未触发

### H5: setModel 不被调用或 folderModel 属性丢失
- override 成功但 setModel 未被调用
- 或 setModel 调用后 folderModel 被后续逻辑清除

---

## Evidence Log

| Step | Action | Result |
|------|--------|--------|
| 1 | Instrumentation | Pending |
| 2 | Collect logs | Pending |
| 3 | Analyze | Pending |
| 4 | Fix | Pending |
| 5 | Verify | Pending |

---
