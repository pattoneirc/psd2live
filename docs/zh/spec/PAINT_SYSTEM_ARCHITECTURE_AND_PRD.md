# 绘画系统

[文档目录](../../README.md) · [画布编辑](../guide/CANVAS_EDITOR.md) · [运行时与导出边界](RUNTIME_EXPORT_ARCHITECTURE_AND_GAPS.md)

本页说明绘画模式的用户流程、组件分工、坐标与提交方式，以及扩展时需要验证的场景。

## 用户流程

选择图层 → 绘画模式 → 画笔 / 铅笔 / 橡皮 / 填充 / 吸管 / 形状 → 会话内撤销重做 → 应用或放弃 → 检查模型并保存工程。

像素绘制在图层自身的栅格上（`PaintSpace`，见[逐层尺寸](DOCUMENT_LAYER.md#逐层尺寸)），按同一密度向画布边缘延伸，不受旧网格边界限制；但保留旧网格时，超出网格覆盖的新像素不会自然变成可渲染区域。应用时可选择保留或重建网格。

## 组件分工

| 组件 | 职责 |
| --- | --- |
| `core/RasterPaintEngine` | 画笔、橡皮、填充、形状等栅格操作；`ui/LayerPaintEngine` 只做 Compose 颜色与形状枚举的适配 |
| `core/PaintRasterSession` | 草稿栅格与独立笔画历史；`PaintPixelPatch` 记录受影响的 64×64 像素瓦片，恢复或交换以撤销 / 重做 |
| `core/PaintSpace` | 草稿像素与画布单位的仿射映射、裁剪与提交像素（`PaintPixels`） |
| `application/WorkspacePaintSessions` | 进程内私有会话（`WorkspacePaintSession`）：捕获起始 `state`，GUI 与 MCP 共用的手势、撤销、跳转、取色和提交 |
| `ui/PaintSession` | 包装一个 `WorkspacePaintSession` 句柄，向 Compose 与 GPU 预览投影草稿和变化区域 |
| `core/RasterPaintCommit` | 提交时的源图、纹理集与网格准备，经 `WorkspaceRasterCommands` 进入文档候选、重建与 CAS |
| `ui/CanvasEditor` | 会话启动、工具交互与提交入口 |

会话所有像素修改应走 `edit` / `willWrite` 等记录入口；绕过它会破坏撤销和增量预览。单笔的覆盖取经过区域的最大值，再乘不透明度，避免同一笔反复经过时不断加深。橡皮使用覆盖去除 Alpha。

MCP 有两种入口：`source_paint_*` 六种单次手势各为一个后台任务与历史节点；`paint_session_begin / paint_session_list / paint_session_control / paint_session_commit` 使用与 GUI 相同的私有会话，`control` 执行手势、撤销 / 重做 / 跳转、取色与 PNG 渲染，`commit` 作为后台任务一次提交（见 [MCP 接口](../agent/MCP_AUTHORING.md#工程与导出任务)）。

## 坐标与提交

指针、手势点、半径与线宽都是画布单位，经 `PaintSpace` 映射到草稿像素；采样类工具再按像素读取。预览按受影响区域发布，不必每次复制整张画布。

应用时只读取图层原区域与笔触写过的区域，裁剪到非透明像素，栅格保持原密度、不缩回画布分辨率，并更新纹理集。保留网格时按画布位置重新寻址纹理坐标；重建时新网格经实际父级的中性变换放回纹素所在位置，避免绘画后包围盒改变导致父级下的形状被重新缩放。完全擦空的图层使用空透明栅格处理。

成功应用进入工程保存 / 历史链路。独立绘画撤销和工程分支撤销是两个层次，界面与文档应明确当前操作的对象。

## 需要验证的场景

- 半透明笔画往返、自交和橡皮：实时显示与松手结果一致。
- 多次撤销重做：像素和预览同步恢复。
- 修色且保留网格：纹理不漂移。
- 扩大轮廓后重建：父级变形器和已有姿态关系没有意外变化。
- 高密度图层：笔触按图层栅格落笔，提交后密度不变。
- 保存重开、历史恢复和导出：源像素与 UV 仍对应。

压感、防抖、绘画专用选区、草稿图层和对称绘制等方向应单独实现与验收，不能因为模型支持混合模式就声称绘画工具已具备完整图像编辑器能力。

源码：[RasterPaintEngine](../../../src/main/kotlin/io/github/psd2live/core/RasterPaintEngine.kt) · [PaintRasterSession](../../../src/main/kotlin/io/github/psd2live/core/PaintRasterSession.kt) · [PaintSpace](../../../src/main/kotlin/io/github/psd2live/core/PaintSpace.kt) · [RasterPaintCommit](../../../src/main/kotlin/io/github/psd2live/core/RasterPaintCommit.kt) · [WorkspacePaintSessions](../../../src/main/kotlin/io/github/psd2live/application/WorkspacePaintSessions.kt) · [PaintSession](../../../src/main/kotlin/io/github/psd2live/ui/PaintSession.kt) · [CanvasEditor](../../../src/main/kotlin/io/github/psd2live/ui/CanvasEditor.kt)。
