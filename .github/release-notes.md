Windows 安装包改用 Inno Setup，覆盖安装不再报「Error writing to file … .rbf」，旧版 EXE / MSI 安装会被自动替换；日志面板的列可调整，层级树可排序，前发模拟更稳定。

## 主要更新

### 改进

- **日志列可调**：日志上方新增列标题行，可拖动调整列的顺序和宽度，右键显示或隐藏时间、来源、标签列；布局会被记住。自动滚动在向上滚动时停止、滚回底部时恢复；等级菜单改为显示所选等级符号的图标按钮；搜索框宽度随内容变化。
- **层级树排序**：层级树工具栏新增排序按钮，默认按高度（画布上从上到下）排列，也可按模型顺序、水平位置、绘制顺序或名称排列，并可倒序；所选排序会被记住。
- **绘制顺序尺**：选中项的刻度与其他刻度重合时，拖动、双击或右键改的是选中项；悬停刻度时显示网格贴图预览，重合处一次列出所有网格。

### 修复

- **Windows 安装包改用 Inno Setup**：直接复制文件，覆盖安装不再经过 MSI 的 `Config.Msi`，避开部分电脑报「Error writing to file … .rbf」、只能删掉安装目录再重试的问题（#14）。升级时装回原来的目录；3.1.x 及更早的 EXE / MSI 安装会被自动移除并沿用原目录，安装目录里你自己的文件保留。安装前若 PSD2Live 正在运行，会提示保存并关闭后重试。启动时可选择为所有用户或只为自己安装（后者无需管理员权限）；安装界面跟随系统语言；卸载时询问是否同时删除 PSD2Live 的数据（默认保留）。不再提供 MSI 安装包。
- **前发模拟不再大幅乱甩**：模型预设生成的前发改用新增的「刘海」材质（回弹更强、阻尼更大），摇头、点头时的摆幅明显减小。已有工程重新应用前发预设即可改用。
- **Alt + 右键调整笔刷不再串轴**：左右拖动调整半径后，下一次上下拖动不会再沿用半径调整；方向要拖出约 12 像素且明显偏向一侧才会锁定。

<details>
<summary>English</summary>

The Windows installer is now built with Inno Setup: upgrading over an installed version no longer fails with "Error writing to file … .rbf", and earlier EXE / MSI installs are replaced automatically. Log columns can be arranged, the hierarchy tree can be sorted, and front hair simulates more steadily.

## Highlights

### Improvements

- **Adjustable log columns**: a header over the log lets you drag columns to reorder and resize them, and right-click to show or hide the time, source and tag columns; the layout is remembered. Auto-scroll stops when you scroll up and resumes at the end; the level menu is an icon button showing the chosen level's sign; the search field grows with its text.
- **Hierarchy sorting**: a new sort button in the hierarchy toolbar orders siblings by height (top to bottom on the canvas) by default, or by model order, horizontal position, draw order or name, optionally reversed; the choice is remembered.
- **Draw-order ruler**: where the selected item's mark overlaps others, dragging, double-clicking or right-clicking changes the selected item; hovering a mark previews its mesh, listing every mesh where marks overlap.

### Fixes

- **Windows installer built with Inno Setup**: files are copied in place instead of going through Windows Installer's `Config.Msi`, avoiding the "Error writing to file … .rbf" failure some machines hit on upgrades, which only deleting the installation folder got past (#14). An upgrade goes back to the installed folder; EXE / MSI installs of 3.1.x and earlier are removed and their folder kept, along with your own files in it. When PSD2Live is running, setup asks you to save, close it and retry. At start you choose to install for all users or only for yourself (no administrator rights needed); the wizard follows the system language; uninstalling asks whether to delete your PSD2Live data too (kept by default). There is no MSI package any more.
- **Front hair no longer swings wildly**: front hair made by the model presets uses the new "Bangs" material (stiffer return, more damping), so head turns and nods swing it much less. Reapply the front hair preset in an existing project to switch.
- **Alt + right-drag brush adjustment no longer mixes axes**: after a horizontal drag adjusts the radius, the next vertical drag no longer keeps adjusting it; a direction locks only after about 12 px clearly along one axis.

</details>
