预览播放和界面绘制明显更流畅；日志面板支持按等级筛选，记录编辑器修改与 AI（MCP）调用，样式重新整理。

## 主要更新

### 新增

- **日志等级筛选**：日志面板新增「等级」菜单，可只看调试及以上、信息及以上、警告及以上或仅错误，每项旁显示条数；默认隐藏调试级。复制日志时带上等级。
- **日志覆盖更全**：编辑器里的每次修改、撤销 / 重做 / 切换历史、打开与保存工程、窗口弹出的错误都会记入日志；AI 接口的启动、停止以及 AI 客户端的连接与断开也会记录。
- **日志样式**：来源改称「系统 / 用户 / AI」；每行左侧以色条标出等级；请求参数等详情默认折叠；连续重复的行合并显示「×N」；点击渲染图卡片查看大图。

### 改进

- **预览播放更流畅**：在 240 Hz 显示器上，预览工作区播放约 230 FPS，编辑工作区从 70–110 FPS 提升到约 215 FPS，动画与物理工作区也明显提高。窗口把界面画面缓存在 GPU 上，播放时只重画预览本身。若界面显示异常，可用 `-Dpsd2live.compositor=false` 关闭画面缓存。
- **界面绘制更省 CPU**：窗口经由 4× 多重采样绘制，抗锯齿交给显卡，高刷新率下编辑工作区的帧率提高约两成。

### 修复

- **MCP 操作与渲染重新出现在日志里**：AI 的每次调用都会记一行，按写操作、渲染（附带渲染图）、查询与失败分级标出，并显示耗时和请求参数。

<details>
<summary>English</summary>

Preview playback and interface drawing are noticeably smoother; the log panel gains level filtering, records editor changes and AI (MCP) calls, and has a cleaner layout.

## Highlights

### New

- **Log level filter**: the log panel has a new "Level" menu to show Debug and above, Info and above, Warning and above, or only Error, with a count beside each; Debug is hidden by default. Copied log lines include their level.
- **More in the log**: every change made in the editor, undo / redo / history switches, opening and saving projects, and errors shown by the window are logged, as are the AI interface starting and stopping and AI clients connecting and disconnecting.
- **Log layout**: sources are now "System / User / AI"; a colour bar on the left of each line marks its level; details such as request arguments are collapsed by default; consecutive repeated lines merge with "×N"; click a render card to see the full image.

### Improvements

- **Smoother preview playback**: on a 240 Hz display, the Preview workspace plays at about 230 FPS and the Edit workspace rises from 70–110 FPS to about 215 FPS, with clear gains in the Animation and Physics workspaces too. The window caches the interface on the GPU and redraws only the preview while it plays. If the interface looks wrong, `-Dpsd2live.compositor=false` turns the cache off.
- **Less CPU for drawing the interface**: the window draws through a 4× multisampled target, leaving antialiasing to the GPU; the Edit workspace frame rate at high refresh rates rises by about a fifth.

### Fixes

- **MCP operations and renders appear in the log again**: every AI call is logged as a write, a render (with its images), a query or a failure, with its duration and request arguments.

</details>
