修复 Windows 卸载/升级会清空安装目录的问题，新增韩语界面，网格可以贴合纹理生成，骨架编辑、保存和打开明显加快。

## ⚠️ 升级须知

- **从 3.0.0 升级前，请先把安装目录里的工程移到别处。** 3.0.0 及更早版本卸载或升级时会删除整个安装目录；本版本已修复，但旧版本的卸载由旧安装包执行，升级时仍会清理旧目录。
- 带 `-ffmpeg` 与不带的 Windows 安装包可以互相替换，不会同时装上。

## 主要更新

### 新增

- **韩语界面**：可在设置中选择，命令行用 `--lang ko`。
- **语言切换**：语言菜单改为标题栏右侧、主题切换旁的地球图标；切换后整个窗口立即换成新语言。
- **网格贴合纹理**：新工程默认「按贴图像素描出轮廓」，比画布像素还细的笔画也会留在网格内。旧工程保持原有方式和网格不变。
- **网格包裹**：新增网格设置「包裹」（可全局设置，也可按图层覆盖，命令行 `--mesh-wrap`），把睫毛、发梢、手指等细小突起包进同一个轮廓，顶点明显减少。默认关闭。
- **卸载时可删除用户数据**：在 Windows 的「卸载或更改程序」中选择删除后，可勾选「同时删除我的 PSD2Live 数据」，默认不勾选。

### 改进

- **图标统一**：所有图标按同一套网格重绘，高分屏上线条不再变细。
- **骨架编辑更快**：只重新烘焙受影响的肢体，并行计算；大尺寸工程上移动手臂关节从约 2–3 秒降到 1 秒以内。
- **保存与打开更快**：保存时不再重新编码图像，也不再解包整个归档做校验；打开大工程时图像并行解码。
- **大尺寸工程更流畅**：纹理集的填充、分类和合成，以及指定姿势的 PSD 导出明显加快。
- **安装与升级**：升级或重装默认装回原来的目录；同版本重新构建的安装包会替换已安装的版本，而不是并存。

### 修复

- Windows 卸载或升级会清空安装目录。现在只删除安装的文件，新工程的默认位置改为「文档\PSD2Live」。
- 另存为不再默认指向当前文件，覆盖确认改在窗口内弹出，排队中的另存为不会被 Ctrl+S 变成普通保存，文件选择框不会再跑到主窗口后面。
- 切换编辑 / 预览模式时视角和画布大小保持不变。
- 启用骨架后，拆分在手臂悬挂变形器下的部件重放时报错。
- 绑定到骨骼、又被手动放到其他变形器下的网格，烘焙后位置错乱。
- 修改已绑定网格的拓扑后，重新打开工程时骨架结果与编辑时不一致。
- 生成后新加入的图层在重绘后网格被悄悄重新生成，之后重建报错。
- Cubism 预览偶尔丢失姿势。

<details>
<summary>English</summary>

Fixes Windows uninstall/upgrade wiping the installation folder, adds a Korean interface, lets meshes follow the texture, and makes skeleton editing, saving and opening noticeably faster.

## ⚠️ Upgrade notes

- **Before upgrading from 3.0.0, move any projects out of the installation folder.** 3.0.0 and earlier delete the whole installation folder on uninstall or upgrade. This release fixes that, but the old version's uninstall is run by the old installer, so upgrading still cleans out the old folder.
- The Windows installers with and without `-ffmpeg` replace each other; they are never installed side by side.

## Highlights

### New

- **Korean interface**: selectable in Settings; on the command line use `--lang ko`.
- **Language switching**: the Language menu is now a globe icon at the right of the title bar, next to the theme toggle; switching redraws the whole window in the new language immediately.
- **Meshes follow the texture**: new projects default to "Trace outlines from texture pixels", so strokes finer than a canvas pixel still stay inside the mesh. Existing projects keep their previous method and meshes.
- **Mesh wrap**: a new mesh setting, "Wrap" (global or per-layer override; `--mesh-wrap` on the command line), wraps fine protrusions such as lashes, strand tips and fingers into a single outline, with far fewer vertices. Off by default.
- **Delete user data on uninstall**: after choosing Remove in Windows "Uninstall or change a program", you can tick "Also delete my PSD2Live data" (unticked by default).

### Improvements

- **Unified icons**: every icon is redrawn on one shared grid, so lines no longer thin out on high-DPI screens.
- **Faster skeleton editing**: only the affected limb is rebaked, in parallel; moving an arm joint on a large project drops from about 2–3 seconds to under 1 second.
- **Faster saving and opening**: saving no longer re-encodes images or unpacks the whole archive to verify it; opening a large project decodes images in parallel.
- **Smoother large projects**: texture atlas packing, classification and compositing, as well as posed PSD export, are noticeably faster.
- **Installing and upgrading**: upgrades and reinstalls go back to the original folder by default; a rebuilt installer of the same version replaces the installed one instead of installing alongside it.

### Fixes

- Uninstalling or upgrading on Windows wiped the installation folder. Now only the installed files are removed, and the default location for new projects is "Documents\PSD2Live".
- "Save as" no longer defaults to the current file, the overwrite confirmation appears inside the window, a queued "Save as" is no longer turned into a plain save by Ctrl+S, and the file chooser no longer ends up behind the main window.
- Switching between edit and preview mode keeps the view and canvas size.
- With the skeleton enabled, replaying a part split under an arm hanging deformer failed.
- A mesh bound to a bone and then manually placed under another deformer ended up in the wrong place after baking.
- After changing the topology of a bound mesh, the skeleton result on reopening the project differed from the one while editing.
- Layers added after generation had their meshes silently regenerated after a repaint, and later rebuilds failed.
- The Cubism preview occasionally lost the pose.

</details>
