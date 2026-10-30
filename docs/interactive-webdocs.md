# 交互式 EPUB / HTML 阅读与手写批注

目标：在文石（Onyx Boox）上打开 **EPUB 3 交互式电子书** 和 **完整的离线 HTML5 交互文档**，文档内嵌的 JavaScript 正常运行，同时可以用笔在上面做笔记。

## 入口

- 首页右上角 📖 图标 →「交互式 EPUB / HTML」：导入文件（`.epub` / `.html` / `.htm` / `.xhtml` / 含 `index.html` 的 `.zip`）或导入整个 HTML 文件夹（连同 JS、CSS、字体、图片）。
- 系统文件管理器中选择「用 Notate 打开」`.epub` / `.html`。
- 导入后会复制到应用私有目录 `files/webdocs/<id>/`；同一文件再次导入会打开原副本和原有笔记（id 是内容哈希）。

## 架构

```
WebDocActivity
 ├─ 工具栏（目录 / 上一节 / 下一节 / 笔：书写↔交互 / 钢笔 / 荧光笔 / 橡皮 / 撤销 / 重做 / 刷新屏幕 / ⋯）
 └─ FrameLayout
     ├─ WebView            ← 文档本身，运行 JS
     └─ WebInkOverlayView  ← 透明墨迹层
```

| 模块 | 作用 |
|---|---|
| `EpubParser` | 读 `container.xml` → OPF：标题、spine（跳过 `linear="no"`）、`rendition:layout`（固定版式）、EPUB 3 nav / EPUB 2 NCX 目录名 |
| `WebDocLibrary` | 导入（EPUB / 单 HTML / ZIP / 文件夹）、ZIP Slip 防护、1 GB 上限、meta.json 与 annotations.json 原子写 |
| `WebDocResources` | 把私有源上的请求映射到文件，拒绝 `..`、编码斜杠等越界路径；正确的 MIME（`.mjs` → `text/javascript`，否则 ES Modules 不执行） |
| `WebInkPage` | 某一页的笔画、按线段命中的笔画橡皮、撤销/重做 |
| `WebInkOverlayView` | 输入分流与墨迹绘制 |
| `WebInkRawInput` | 文石原生笔迹通道（`TouchHelper` 原始绘制） |
| `WebDocExportPlan` | 导出时把长页面切成屏幕高度的切片 |
| `WebDocEpd` | E Ink 刷新策略 |

### 每文档独立源

每个文档通过 `https://<id前16位>.webdoc.notate/` 提供，由 `shouldInterceptRequest` 直接读本地文件，不走网络：

- 同源规则下 `fetch()`、XHR、ES Modules、`<iframe>`、Web Worker 都能正常加载离线资源（`file://` 下这些大多会被跨域限制拦住）。
- `localStorage` / `IndexedDB` 按源隔离：一本书的练习答案和进度只属于这本书，删除文档时一并清掉（`WebStorage.deleteOrigin`）。

### 墨迹锚定

笔画以 **文档 CSS 像素** 存储：`doc = (view + webView.scroll) / webView.scale`。页面滚动、双指缩放时墨迹跟着内容走。每个页面（EPUB 的每个 spine 项，HTML 的每个页面路径）单独一组笔画，并记住阅读位置。

### 输入分流（避免笔和网页交互冲突）

- 手指：永远交给网页（点击、拖动、滚动、双指缩放）。
- 笔（书写模式）：写字 / 擦除，网页收不到；笔尾橡皮或侧键按下时临时擦除。
- 笔（交互模式）：交给网页，可以用笔点按钮、拖滑块、填表单。
- 笔落下时丢弃手指事件（掌托防误触），避免页面在写字时滚动。

### 文石原生笔迹通道

与主画布相同，使用 Onyx `TouchHelper` 原始绘制：笔移动时由 EPD 驱动直接把笔迹画到屏幕上（最低延迟），同时把点传给墨迹层保存为文档坐标。抬笔约 350 ms 后关闭一次原始绘制，对笔迹区域做一次局部刷新，用软件绘制的笔迹替换驱动的临时笔迹。

- 钢笔：PENCIL 样式，宽度随缩放换算成屏幕像素。
- 荧光笔：屏幕不能做透明混合，书写时用 MARKER 样式浅灰预览，刷新后换成半透明荧光。
- 橡皮：关闭驱动绘制，抬笔后刷新显示擦除结果。
- 以下情况暂停原生通道，让网页能正常刷新：手指滚动/拖动（抬起后 0.7 s 恢复）、弹出菜单或对话框（否则笔会在菜单上画线而不是点选）、导出过程中、「笔：交互」模式。
- 「⋯ → 文石原生笔迹（低延迟）」可关闭，回退到普通 View 绘制；非文石设备自动回退。

### 导出

「⋯ → 导出批注…」：

- **PDF：当前节/页**、**PDF：所有有批注的节/页**：按屏幕所见渲染（脚本、Canvas、MathML 都是运行后的样子）并叠加笔迹，长页面按屏幕高度切片，每片一页 PDF，最后一片不重复内容。导出完成后回到原来的位置。
- **笔记数据（JSON）**：标题、目录和全部笔画坐标（文档 CSS 像素），用于备份或在其他工具中处理。

### E Ink 优化

- 手指拖动 / 写字期间进入快速刷新（`EpdFastModeController`，动画波形，无闪烁）。
- 停止操作 700 ms 后退出快速模式，恢复正常高画质。
- 「刷新屏幕」按钮做一次 GC 全刷清残影。
- 「⋯ → 减少动画（E Ink）」注入 CSS，把动画/过渡缩短到 0.001s（而不是 `none`，这样页面脚本等待的 `transitionend` 仍会触发）。

### 安全隔离（按文档设置，存在 meta.json）

| 选项 | 默认 | 说明 |
|---|---|---|
| JavaScript | 开 | 可按文档关闭 |
| 联网 | **关** | 关闭时所有外部请求返回 403，外链被拦截；开启需二次确认 |
| 文件访问 | 禁止 | `allowFileAccess` / `allowContentAccess` / file URL 跨域全部关闭 |
| 权限 | 拒绝 | 摄像头、麦克风、定位请求一律拒绝；不允许弹窗 / 新窗口 |
| 原生接口 | 无 | 不注入任何 `addJavascriptInterface` |
| 渲染进程崩溃 | 隔离 | `onRenderProcessGone` 保存笔记后关闭阅读器，不拖垮整个应用 |

### 兼容性检查

「⋯ → 兼容性检查」显示系统 WebView 包名和版本，并在页面内实测：ES Modules、ES2020 语法、MathML、Canvas 2D、WebGL、SVG、localStorage、IndexedDB、WebAssembly、Pointer Events。WebView 主版本低于 109（MathML Core 起始版本）时打开文档会提示。

## 已知限制（第一版）

- 墨迹锚定在整页滚动上。若页面内容在内部的 `overflow: auto` 容器里滚动（部分单页应用），墨迹不会跟着那个容器移动。
- 页面重排（横竖屏切换导致宽度变化）后，流式文档中的墨迹可能与文字错位。固定版式 EPUB 不受影响。
- 每次只显示一个 spine 项，竖向滚动阅读，没有左右翻页分栏。
- 音视频没有实现 HTTP Range，长音频/视频可能无法拖动进度。
- 原生笔迹的坐标假定 `TouchPoint` 是相对墨迹层视图的坐标（与主画布一致），需要实机确认没有偏移。
- PDF 导出是位图（不可选中文字）；单页最多 300 屏。

## 实机测试清单（Note X）

1. **交互式 EPUB 3**：带 `<script>` 的章节（测验、可点击图表）、固定版式书、含 MathML 的章节；检查目录、上一节/下一节、练习答案在退出重进后仍在（localStorage / IndexedDB）。
2. **离线 HTML5**：整个文件夹导入（ES Modules + KaTeX/MathJax 本地包 + Canvas 2D 模拟，例如单摆/电路模拟）；同样内容打成 ZIP 再导入一次；断网下全部资源都能加载。
3. **手写批注与交互共存**：书写模式下手指滚动、点按钮、双指缩放；切到「笔：交互」用笔拖滑块、填表单；写字时手掌放在屏幕上页面不动；缩放和滚动后墨迹仍在原处；退出重进墨迹和阅读位置恢复。
4. **原生笔迹与导出**：书写延迟与主画布一致；抬笔后笔迹不偏移、不残留；书写时网页动画是否仍会刷新；弹出菜单时用笔能正常点选；导出 PDF 的笔迹与内容对齐。
