---
name: capcut-theme-video
description: CapCut (剪映) one-tap video creation on Android. Always clear A_latest first (copy_images_to_album clear-only), then retrieve by time (list_gallery_images) or content (image-memories). Copy to A_latest (clear_album_first), CapCut 照片视频 → A_latest → 全部, multi_tap, N=K.
metadata: { "xomniclaw": { "always": false, "emoji": "🎬" } }
---

# 剪映（CapCut）一键成片与相册选图

本 Skill 指导你如何获取目标照片、统一存入 `A_latest` 相册，并在剪映中完成「一键成片」。

## 何时使用

用户要：**打开剪映**、**一键成片**、**从相册选多张图（按时间或按主题）**、**去成片 / 导出视频** 等多步 UI 操作时加载本 Skill。

包名：`com.lemon.lv`

## 相册名 `A_latest`（硬规则）

- **本流程使用的相册名有且只有一个：`A_latest`**（与 `Pictures/A_latest/` 及剪映「照片视频」下拉显示一致）。  
- **禁止**变体：`A_latest_风景`、`主题_A_latest`、`latest`、带空格等；主题/时间只体现在检索，**不要**写进相册名。  
- **入库前必须先清相册**：最终写入时 `copy_images_to_album` 须 **`clear_album_first: true`**。  
- **任意检索前也须先清空 `A_latest`**（路径 A、路径 B **相同**）：见下「步骤 0」。避免旧副本混入按时间的 `list_gallery_images` 结果，也避免按内容选图时相册里仍残留上一轮素材、与本次拷贝混淆。

---

## 核心策略：如何获取照片与目标张数 K

在进入剪映前，必须先确定要选哪些照片以及**目标张数 K**。不论是按时间还是按主题，**所有选出的照片都必须先复制到 `A_latest` 相册中**，然后在剪映里统一从 `A_latest` 相册中选取。

### 步骤 0：先清空 `A_latest`（路径 A 与路径 B 必做，先于一切检索）

在**任何** `list_gallery_images`（路径 A）或 `memory_search` / `memory_get`（路径 B）**之前**，先调用 **`copy_images_to_album`**：

- `album_name: "A_latest"`
- `clear_album_first: true`
- **不要传** `content_uris` / `media_ids` → 工具**只清空**该相册（空相册则删除 0 条）。

**原因简述**：

- **路径 A**：`A_latest` 里旧副本仍在 MediaStore 时，`date_preset` 等**未按相册排除**的检索会把它们和真实目标照片混在一起。  
- **路径 B**：先清空可保证后续写入 `A_latest` 时相册状态干净，不与上一轮成片残留混淆。

**可选探针**（与直接先清空等价）：先 `list_gallery_images`，`album_filter: "A_latest"`，`limit: 1`；仅当 **`count` ≥ 1** 时再执行上述仅清空调用。

### 步骤 1：获取目标照片的 content_uris

完成步骤 0 后，按用户需求二选一：

#### 路径 A：基于时间或相册名（非主题语义）
**适用场景**：用户明确要求「今天拍的照片」「昨天的照片」「最近 24 小时」，或者指定了具体的系统相册名。
**操作步骤**：
1. 调用 `list_gallery_images`，使用 `date_preset` (`today` | `yesterday` | `last_24h`) 或 `album_filter`（必要时提高 **`limit`**，最大 100）。
2. 从返回的图片列表中提取所有的 `content_uri`。

#### 路径 B：基于特定主题、事件或图像内容
**适用场景**：用户要求按内容找图，如「风景」「海边」「猫」「去北京旅行」。
**操作步骤**：
1. **提取主题词**：从用户话里抽出检索用词。
2. **检索 image-memories**（按内容找图时不要用 `list_gallery_images` 的 `name_filter` 当主题检索：文件名多为 `IMG…`，不含「风景」「猫」等语义，易得到 0 条）：
   - 调用 `memory_search`，`query` 填主题词；可适当增大 `maxResults`（建议 ≥ 10）、略降 `minScore`（建议 ≤ 0.4）以多召回。
   - 遍历返回的 `citations` 列表，**逐条 `memory_get`**，提取 `content_uri` 或 `media_id`；**不要只读一段就停**，避免漏图。

### 步骤 2：写入固定相册 `A_latest`（硬规则）

无论走的是路径 A 还是路径 B，拿到 `content_uris` 或 `media_ids` 后：
1. 调用 `copy_images_to_album`：`album_name: "A_latest"`，传入收集到的 `content_uris` 或 `media_ids`，**`clear_album_first: true`**（再次清空后拷贝；与步骤 0 衔接，避免遗漏或并发下残留）。
2. 拷贝后调用 `list_gallery_images`，`album_filter: "A_latest"`，记下返回的 `count`，这就是**目标张数 K**。
3. 在剪映中，必须**先点「照片视频」下拉选择 `A_latest` 相册**，再进行选图。

---

## 剪映操作执行步骤

### 1. Deep Link 直达一键成片相关页（优先）
无需在剪映首页再点「一键成片」时，用 `device` `action=open` 传 `uri` + `package_name`：
```text
device(action="open", package_name="com.lemon.lv", uri="videocut://template/smart_recommend?enter_from=intelligent_edit&tab_name=edit")
```
随后 `device(action="snapshot")` 确认是否已进入选素材界面。若出现「是否允许打开剪映」等系统权限弹窗（如 `com.oplus.securitypermission`），先点允许再 snapshot。若 Deep Link 失败，请在首页用 ref 点击 `text '一键成片'`。

### 2. 选素材页：相册与分类切换

> **snapshot vs screenshot 硬规则（违反必失败）**
> 全程只能用 `device(action="snapshot")` 返回带 refs 的无障碍树并用 `tap ref` 点击。**禁止**用 `screenshot` 盲猜坐标。

1. **先点相册下拉（硬规则）**：在树里找到 `link '照片视频'` 这一行，`tap` 这一行的 `ref`（带上 `snapshot_id`）。**禁止**用同区域附近的无文案 `button` 代替。
2. **选择 `A_latest` 相册**：展开列表后再 `snapshot`，在列表里找 `text 'A_latest'`（名称精确为 A_latest，无后缀）。
   - **正确**：`tap` 紧挨在 `text 'A_latest'` 的「上一节点」那个 `button` 的 ref。
   - **错误**：误点后面的 `button`（那是下一行相册）。  
3. **切换到「全部」Tab（重要）**：
   - 选中 `A_latest` 后，剪映默认可能停留在「照片」或「视频」Tab。
   - **必须**在顶部 Tab 栏找到 **`text '全部'`**（或 `button '全部'`），点击其对应的 **ref**。
   - **目的**：确保能看到 `A_latest` 相册下的所有素材（无论图片还是视频）。
   - `snapshot` 确认 `全部` 已被选中（`selected`）。   

### 3. 勾选素材（点「圈」不点「大图」）（批量点击）
1. **勾选注意事项**
   在相册网格中，每个格子常出现一对相邻节点（先大块 `button` 再小 `link` 圈）：
   - **只点勾选小圈**：多为 `link`（未选中时无文案，选中后常带序号如 `link '1' (selected)`）。
   - **禁止点大块无文案 `button`**：那是整张缩略图区域，点击会进入大图预览。
2. **使用 `multi_tap` 勾选全部**：
   - 在当前 Tab 网格中，只列各格**勾选小圈**的 ref（多为 `link`），**勿混入**同格大块无文案缩略图 `button`。
   - **`multi_tap` 单次最多 15 个 ref**（与单屏可见格数一致）；超出则：**multi_tap 一批 → `snapshot` → 滚动 → 再 `multi_tap` 下一批**。重复 ref 会去重（保序）。
   - 每次 `act`（含 `multi_tap`）须带**当前最近一次** `snapshot` 返回的 **`snapshot_id`**（严格模式下 ref 会过期）。
3. **校验**：
   - **核对张数**：每次点击或 `multi_tap` 后**必须** `snapshot`，核对底部 **`下一步 (N)`** 的 N 是否达到目标张数 **K**。
   - **易错**：界面常出现「选择3个以上素材，成片效果更佳」，这只是推荐，**绝不**表示选 3 张就可以点下一步。必须选到 **N = K**。


### 4. 等待与下一步
- `下一步 (N)` 的 N = K 后，点击「下一步」。
- 后续生成/导出环节，必须使用 `act` + `wait` 进行等待：
  ```text
  device(action="act", kind="wait", timeMs=10000)
  ```
  **错误**：`device(action="wait")`。
- 导出很慢时应用多次短 wait + snapshot，不要假设一次等 10 分钟已生效。

---

## 简要检查清单

1. **步骤 0（两路径相同）**：`copy_images_to_album`（`A_latest` + `clear_album_first: true`，无 URIs）仅清空。
2. **检索**：路径 A → `list_gallery_images`；路径 B → `memory_search` / `memory_get` → 得到 `content_uri` 列表。
3. **统一入库**：`copy_images_to_album`（`A_latest` + **`clear_album_first: true`** + 本次 URIs）→ `list_gallery_images` `album_filter: "A_latest"` 得 **K**。
4. **进入剪映**：优先 Deep Link，失败则首页 ref 点击。
5. **选相册集**：下拉选择 `A_latest`。
6. **切全部 Tab**：**必须点击「全部」Tab**，确保素材可见。
7. **选图**：只点勾选小圈（`link`）；`multi_tap`（≤15/次）配合 `snapshot`。
8. **核对与等待**：`下一步 (N)` 直到 N = K；`act` + `wait` 再 `snapshot`。

