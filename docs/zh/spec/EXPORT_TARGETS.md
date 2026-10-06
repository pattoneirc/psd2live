# 中立绑定 IR 与导出目标

所有导出都从同一份中立绑定 IR 出发：编辑器把当前已提交的 Rig 编译成 IR，每个导出目标再把 IR 降级成自己的格式，并写出一份损失报告，列出该格式无法保留的内容。

## 模块

| 模块 | 内容 | 许可证 |
| --- | --- | --- |
| `:format-model` | 中立绑定 IR（`RigIR`）：参数、部件、Warp / 旋转变形器、网格、关键形网格、通道、混合形、Glue、绘制树、贴图页与图块、物理组、动作片段、编辑器附带数据 | MIT |
| `:format-compile` | 导出框架：`ExportTarget`、`CapabilityProfile`、`LossEntry`、`ExportReport`、`Compiler`；降级步骤：能力扫描、按参数烘焙与交叉项误差测量（`ParameterBake`）、关键帧精简（`KeyReduction`）、动作片段采样；宿主接口 `FrameRenderer`、`GeometryEvaluator` | MIT |
| `:targets:raster` | `png-sequence`、`sprite-sheet`、`gif` | MIT |
| `:targets:spine` | `spine`：Spine 4.2 骨骼 JSON + 图集，由宿主提供的几何求值器烘焙变形 | MIT |
| `:targets:cubism` | `PuppetModel` 与 IR 的双向转换器（`PuppetIr`）、`moc3`、`cmo3`、motion3 / physics3 写出 | GPL-3 |
| `:targets:psd` | `psd-pose`：指定姿势的分层 PSD | GPL-3 |
| 根项目 | `RigIrCompiler`（预览模型 → IR）、`IrFrameRenderer`（宿主提供的渲染器）、`ExportService`、CLI 与界面 | GPL-3 |

MIT 模块不依赖任何 GPL 模块，由 Gradle 依赖关系在编译期保证。

## IR 约定

- 网格的静止位置与各变形器的关键形都在父变形器的空间中：根部为画布像素（y 向下），Warp 子对象为其 0–1 格子空间，旋转子对象为其局部像素坐标。
- 所有 ID 稳定，重新生成后不变。
- 动作片段已经从预设编译成参数曲线；Bezier 段的控制点时间限制在段内，按时间线性推进曲线参数求值（与 Cubism 的 `AreBeziersRestricted` 一致）。
- `restPose` 给出需要画布空间基础网格的格式烘焙基础网格时使用的参数值（例如嘴保持张开，使纹理坐标覆盖整幅图）。
- `PuppetIr` 双向无损：`toPuppet(toIr(model))` 与原模型逐字段一致，往返测试见 `RigIrRoundTripTest`。两边新增字段时须在同一改动中更新转换器。

## 导出目标

| ID | 类别 | 输出 | 主要损失 |
| --- | --- | --- | --- |
| `moc3` | 结构化绑定 | `.moc3`、model3、physics3、motion3、cdi3、贴图 | 按目标运行时版本剥离不支持的功能 |
| `cmo3` | 结构化绑定 | Cubism Editor 工程 | 生成器意图不保留；动作片段不写入 |
| `spine` | 结构化绑定 | 骨骼 JSON、`.atlas`、贴图页 | 每个参数成为一段 1 秒动画（时间即参数归一化值），交叉项近似并报告误差；动作片段按帧采样后精简关键帧；无 Warp、混合形、Glue（已烘焙）；物理、遮罩、屏幕色未写入 |
| `psd-pose` | 合成/时间轴 | 每个可见网格一层，按静止绘制顺序 | 变形器和参数不保留；按参数变化的绘制顺序取静止值 |
| `png-sequence` | 光栅 | 编号 PNG 帧 | 结构全部烘焙为像素 |
| `sprite-sheet` | 光栅 | 网格排列的精灵表 PNG + TexturePacker（hash）JSON | 同上 |
| `gif` | 光栅 | 动态 GIF | 256 色、1 位透明 |

光栅类和 `psd-pose` 通过宿主提供的 `FrameRenderer` 渲染。编辑器的实现（`IrFrameRenderer`）使用引擎的 CPU 求值器和编辑器的软件绘制，并在帧之间推进摆锤物理；混合模式按普通绘制，乘算/屏幕色不生效，遮罩按几何裁剪。

### 设置

| 目标 | 设置键 |
| --- | --- |
| `moc3` | `physics`、`user_data`、`display_info`、`hidden_parts`、`hidden_meshes`、`guide_parts`（布尔）、`pixels_per_unit`（正数）；缺省取工程导出设置 |
| `cmo3` | `timestamp`（毫秒，默认 0） |
| `spine` | `clip_fps`（动作采样帧率，默认 15）、`clips`（是否写出动作，默认 true）、`key_tolerance`（关键帧精简容差，像素，默认 0.25）、`sample_pairs` |
| `psd-pose` | `clip` 与 `time`（秒）按动作片段摆姿势，或 `pose`（`ParamAngleX=20,ParamEyeLOpen=0`，覆盖片段）；`scale`（0.25–2，默认 1） |
| 光栅类 | `clip`（默认第一个片段，无片段时为静止姿势）、`fps`（默认片段帧率）、`size`（长边像素，默认 1024）、`background`（ARGB 十六进制，默认透明）、`physics`（默认 true） |

## Spine

Spine 有骨骼没有参数。导出用一根根骨骼承载全部网格（无权重网格附件，原点为画布底边中点，y 向上），并：

- 每个参数一段动画 `param/<参数 ID>`，长 1 秒，时间为参数归一化值（最小 0、最大 1），deform 键位于参数的采样点；运行时为每个参数开一个轨道、用叠加混合（`MixBlend.add`）并按参数值设置轨道时间。关键形透明度写成槽位 `rgba`，按参数变化的绘制顺序写成 `drawOrder`。
- 每个动作片段一段动画 `clip/<名称>`，按 `clip_fps` 采样，再用 `KeyReduction` 去掉线性插值可重建（容差 `key_tolerance` 像素）的关键帧，偏移保留到千分之一像素。示例工程 13 段动作的 JSON 由 49 MB 降到 13 MB。
- 图集为每页一个覆盖整页的区域 `page<i>`，网格 UV 直接引用该页。
- 验证：单元测试按 Spine 运行时的规则（无权重 deform 键为相对设置姿势的增量、按 offset 写入、线性插值；绘制顺序按偏移算法重排）重建姿势并与求值器比较。未使用官方 Spine 运行时（其许可证要求持有 Spine 许可），也未在 Spine Editor 中打开验证。

## 损失报告

每次导出在文件旁写出 `<名称>.<目标>.report.json`：

```json
{"target":"gif","compiler":"2.0.4","files":["model.gif"],
 "losses":[{"object":"*","feature":"structure","handling":"baked","note":"..."}]}
```

`handling` 为 `baked`（保留视觉、增大体积）、`approximated`（可接受的误差）或 `dropped`（丢弃）；`object` 为稳定 ID，`*` 表示整个 Rig；测得误差时附 `error`。报告记录编译器版本，用于追查两次导出的差异。

## 确定性

同一份 IR 与同一版本编译器产生逐字节相同的输出，导出不引入时间戳和随机数。例外是 `cmo3`：Cubism Editor 要求每个对象有唯一 GUID，因此文件字节每次不同，内容相同。导出路径重构用 `ExportGoldenTool`（`PSD2LIVE_TOOLS=1`）对比前后提交的文件摘要；cmo3 比较其读回后再降级为 moc3 的摘要。

## 使用

- 界面：导出对话框的“其他格式”分区，导出当前已提交状态，完成后列出损失。
- 命令行：见[开发与命令行](../guide/DEVELOPMENT.md)中的 `export` 命令。
- 编辑器自身的 Cubism 预览包与 `moc3` 导出是同一次编译。
