画布工具全面整理：新增工具选项栏、数字键选项和统一的网格外观；改了生成设置后不再需要重放整条编辑历史，工程打开更快、更稳；运行时模型升级到 2.0，带高级模式、表情与点击区域；GPU 渲染统一到窗口自身的 OpenGL 上下文。

## ⚠️ 升级须知

- **工程需要 3.1.0 打开**：在本版本中开启头发模拟、调整骨架、拆分图层等改变生成结果的操作后保存的工程，旧版本无法打开。
- **运行时模型格式 2.0**：导出的 `.p2lrt` 默认是新格式，旧版网页播放器和 Godot 节点无法读取；需要给旧播放器使用时，在导出设置中勾选「版本 1」。
- **Windows 界面改用 OpenGL 渲染**。自行构建 Cubism 预览的用户建议重新构建 `native/live2d_renderer`，否则每次修改都会从临时文件重新加载模型。

## 主要更新

### 新增

- **工具选项栏**：画布左下角显示当前工具的半径、强度、硬度等数值，左右拖动即可调节，单击弹出滑块；右键菜单显示同一组数值和该工具的操作。工具面板只保留高级设置。
- **数字键选项**：`1`–`5` 选择当前模式或工具的第 n 项，如网格变形器的级别、点 / 边 / 面、笔刷形状、骨骼子工具等。
- **最近打开**：**文件 → 最近打开** 列出最近的工程与 PSD，不存在的文件不显示，网络盘断开也不卡界面。
- **更新生成结果**：**工具 → 更新生成结果**（MCP `rig_update_generation`）用当前版本的生成器重新生成，并把你的编辑合并上去；升级程序不会自动改变已保存的生成结果。
- **创建工具**：选择模式的工具栏新增创建 Warp、Rotation 与变形路径。
- **变形模式的点 / 边 / 面选择**；**权重笔刷**可选直线和矩形笔尖并旋转；**变换工具**在未选中时可点选或框选。
- **预览后端可选**：在 Cubism SDK、p2lrt · 标准、p2lrt · 扩展之间切换；没有 Cubism SDK 时自动使用 PSD2Live 运行时。
- **运行时高级模式**（播放时开启，默认关闭，关闭时与 Cubism 效果一致）：骨骼沿真实弧线蒙皮、已烘焙的布料与头发实时模拟并可加风、可添加圆或胶囊碰撞体。
- **运行时表情与点击区域**：内置微笑、悲伤、生气、惊讶四种表情和头部、身体点击区域；导出设置可指定部件互斥组，切换时自动淡入淡出；块压缩可选 zstd。网页播放器新增缩放与平移。
- **识别韩语图层名**：앞머리、눈동자、상의 等韩语图层名和左右标记可自动分类。

### 改进

- **统一的网格显示**：各模式下可编辑的点与线采用同一套外观和固定的颜色含义；所有形变笔刷悬停时就标出会受影响的顶点；Warp 控制点改为方点。
- **统一的 GPU 渲染**：编辑画布、纹理集页面和预览都在窗口自己的 OpenGL 上下文里绘制，平移、缩放和播放不再落后一两帧。
- **预览帧率跟随显示器**，也可限制为 30 / 60 / 120；多个预览同时可见时不再限制为 30 FPS。工具栏的帧率改称「物理帧率」。
- **变形级别**改称「顶点」与「贝塞尔」，只在目标为网格变形器时显示；贝塞尔下详细网格变淡。
- **打开工程更快**：每个历史版本编辑后的模型随工程保存，打开、撤销和切换历史时直接读取。
- **运行时模型格式 2.0**：分块、可压缩、可校验，带参数分组与吸附值；运行时、网页播放器和 Godot 节点同时读新旧两种格式。
- 「显示蒙皮权重」移到右下角显示栏；网格统计只在预览画布显示。

### 修复

- **改了生成设置后工程打不开或报错**（如「Art primitive parent is missing」）：现在已有编辑合并到新的生成结果上保存，无法干净迁移的内容在质量报告中列出。
- **模拟与摆动参数**可以像 Cubism 物理输出参数一样被其他网格或 Warp 打键、改名和改范围，此前报「参数不在模型中」或提交后丢失。
- 模拟网格上用变形笔刷、贝塞尔编辑报「参数不在模型中」；胶水改为在静止形上胶合，不再产生拉扯。
- 在摆动、模拟生成的关键形上用贝塞尔或 MCP `rig_deform` 报错；无法保存为覆盖的编辑现在当场说明原因。
- 删除摆动所作用的 Warp 后留下不起作用的摆动；摆动失效时物理面板显示原因。
- Windows 上单按 Alt 后界面卡住、按键被吞。
- 工具栏下方的工具穿透到画布、部分右键菜单为空、胶水工具在条件不满足时消失等画布工具问题。
- 网页播放器和 C 接口播放一段时间后角度等参数漂移到数万。
- Windows 上保存时被短暂占用的工程文件导致保存失败。
- 命令行 `--mesh-pixels` 报未知选项。

<details>
<summary>English</summary>

A thorough pass over the canvas tools: a tool options bar, number-key choices and one look for editable geometry. Changing generation settings no longer replays the whole edit history, so projects open faster and more reliably. The runtime model moves to format 2.0 with an advanced mode, expressions and hit areas, and GPU rendering now happens in the window's own OpenGL context.

## ⚠️ Upgrade notes

- **Projects need 3.1.0 to open**: a project saved after an operation that changes the generated result in this release (turning on hair simulation, adjusting the skeleton, splitting layers) cannot be opened by older versions.
- **Runtime model format 2.0**: exported `.p2lrt` files use the new format by default, which older web players and Godot nodes cannot read. Tick "Version 1" in the export settings when targeting an older player.
- **The interface now renders with OpenGL on Windows.** If you build the Cubism preview yourself, rebuild `native/live2d_renderer`; otherwise every change reloads the model from a temporary file.

## Highlights

### New

- **Tool options bar**: the bottom left of the canvas shows the current tool's values (radius, strength, hardness and so on); drag left or right to adjust, click for a slider. The context menu shows the same values and the tool's actions. The tool panel keeps only advanced settings.
- **Number-key choices**: `1`–`5` pick the n-th choice of the current mode or tool, such as warp deformer levels, vertex / edge / face, brush tips or skeleton sub-tools.
- **Open Recent**: **File → Open Recent** lists recent projects and PSDs; missing files are hidden, and a disconnected network drive no longer stalls the interface.
- **Update generated rig**: **Tools → Update Generated Rig** (MCP `rig_update_generation`) regenerates with the current generators and merges your edits onto the result; upgrading the app never changes a saved generated rig by itself.
- **Create tools**: the Select mode toolbar can create Warps, Rotations and deform paths.
- **Vertex / edge / face picks in Deform**; the **weight brush** takes line and rectangle tips that can rotate; the **transform tool** clicks or box-selects when nothing is selected.
- **Choose the preview backend**: Cubism SDK, p2lrt · standard or p2lrt · extended; without the Cubism SDK the preview uses the PSD2Live runtime.
- **Runtime advanced mode** (switched on at playback, off by default and identical to Cubism when off): skinning along true bone arcs, live baked cloth and hair with wind, and circle or capsule colliders.
- **Runtime expressions and hit areas**: smile, sad, angry and surprised expressions plus head and body hit areas; export settings can define part pose groups that cross-fade when switched; chunks can be zstd-compressed. The web player gains zoom and pan.
- **Korean layer names**: names such as 앞머리, 눈동자 and 상의, with their side markers, are classified automatically.

### Improvements

- **One look for editable geometry**: editable points and wires share one appearance with fixed colour meanings in every mode; every deform brush marks the vertices it will move while hovering; warp control points are now squares.
- **Unified GPU rendering**: the edit canvas, the texture atlas page and the previews draw in the window's own OpenGL context, so panning, zooming and playback no longer lag a frame or two behind.
- **Preview frame rate follows the display**, or can be capped at 30 / 60 / 120; several visible previews are no longer limited to 30 FPS. The toolbar rate is now called "Physics FPS".
- **Deform levels** are named "Vertices" and "Bezier" and only show for a warp target; the detailed mesh fades under Bezier.
- **Faster project opening**: each revision's edited model is saved with the project and read directly when opening, undoing or switching history.
- **Runtime model format 2.0**: chunked, compressible and checksummed, with parameter groups and snap values; the runtime, web player and Godot node read both formats.
- "Show skin weights" moves to the display rail at the bottom right; mesh statistics show only on the preview canvas.

### Fixes

- **Projects failing to open or erroring after generation settings changed** (e.g. "Art primitive parent is missing"): existing edits are now merged onto the new generated result and saved, and anything that cannot be carried over cleanly is listed in the quality report.
- **Simulation and swing parameters** can be keyed by other meshes or Warps, renamed and re-ranged like Cubism physics outputs; before, this reported "parameter not in model" or was lost on commit.
- Deform brush and Bezier edits on a simulated mesh reported "parameter not in model"; glue now welds at the rest shape and no longer pulls.
- Bezier or MCP `rig_deform` on keyforms generated by swings or simulations failed; edits that cannot be kept as overrides now say why on the spot.
- Deleting a Warp a swing moves left a swing that did nothing; a swing that cannot work shows why in the physics panel.
- A lone Alt on Windows stalled the window and swallowed keys.
- Canvas tool issues: tools below a long toolbar clicked through to the canvas, some context menus were empty, and the glue tool disappeared when it could not be used.
- In the web player and C API, angles and other parameters drifted into the tens of thousands after playing for a while.
- Saving on Windows failed when the replaced project was briefly locked.
- `--mesh-pixels` on the command line was rejected as unknown.

</details>
