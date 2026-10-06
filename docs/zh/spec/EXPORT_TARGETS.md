# 中立绑定 IR 与导出目标

所有导出都从同一份中立绑定 IR 出发：编辑器把当前已提交的 Rig 编译成 IR，每个导出目标再把 IR 降级成自己的格式，并写出一份损失报告，列出该格式无法保留的内容。

## 模块

| 模块 | 内容 | 许可证 |
| --- | --- | --- |
| `:format-model` | 中立绑定 IR（`RigIR`）：参数、部件、Warp / 旋转变形器、网格、关键形网格、通道、混合形、Glue、绘制树、贴图页与图块、物理组、动作片段、编辑器附带数据 | MIT |
| `:format-compile` | 导出框架：`ExportTarget`、`CapabilityProfile`、`LossEntry`、`ExportReport`、`Compiler`；降级步骤：能力扫描、按参数烘焙与交叉项误差测量（`ParameterBake`）、关键帧精简（`KeyReduction`）、动作片段采样；宿主接口 `FrameRenderer`、`GeometryEvaluator` | MIT |
| `:targets:raster` | `png-sequence`、`sprite-sheet`、`gif`，以及经 ffmpeg 编码的 `mp4`、`webm`、`mov`、`apng`、`webp` | MIT |
| `:targets:spine` | `spine`：Spine 4.2 骨骼 JSON + 图集，由宿主提供的几何求值器烘焙变形 | MIT |
| `:targets:dragonbones` | `dragonbones`：DragonBones 5.5 骨骼 JSON + 图集，由宿主提供的几何求值器烘焙变形 | MIT |
| `:targets:runtime` | `p2lrt`：PSD2Live 运行时模型，见[运行时](RUNTIME.md) | MIT |
| `:format-eval` | Rust 运行时的 JVM 绑定（JNA），可作为几何求值器 | MIT |
| `:targets:gltf` | `gltf`：glTF 2.0 二进制（`.glb`），变形按参数关键点烘焙为变形目标，由宿主提供的几何求值器采样 | MIT |
| `:targets:web` | `web`：网页播放器（运行时的 WebAssembly 构建、WebGL 播放脚本、页面与模型） | MIT |
| `:targets:cubism` | `PuppetModel` 与 IR 的双向转换器（`PuppetIr`）、`moc3`、`cmo3`、`vtube-studio`、motion3 / physics3 写出 | GPL-3 |
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
| `dragonbones` | 结构化绑定 | `_ske.json`、每页 `_tex_<i>.json` 与贴图 | 与 Spine 相同的参数动画与片段烘焙；关键点取整到帧；透明度以整百分比记录；运行时以 16 位偏移寻址每个动画的变形数据，超出时按更大容差精简并报告（骨架模型的整身片段误差较大）；遮罩、屏幕色、物理未写入 |
| `vtube-studio` | 结构化绑定 | `moc3` 的全部文件 + `.vtube.json` | 同 `moc3`；面部跟踪只映射标准参数（头、身体、眼、视线、眉、嘴、呼吸），每个动作片段一个热键；其余设置由 VTube Studio 取默认值 |
| `spine` | 结构化绑定 | 骨骼 JSON、`.atlas`、贴图页 | 每个参数成为一段 1 秒动画（时间即参数归一化值），交叉项近似并报告误差；动作片段按帧采样后精简关键帧；无 Warp、混合形、Glue（已烘焙）；遮罩转为按轮廓多边形裁剪（反相遮罩丢弃）；物理、屏幕色未写入 |
| `p2lrt` | 结构化绑定 | `.p2lrt`（PSD2Live 运行时） | 编辑器附带数据（来源图层、图块、编辑路径）不写入；渲染（遮罩、混合模式）由宿主完成 |
| `web` | 结构化绑定 | `index.html`、`p2l.js`、`p2l_runtime.wasm`、`.p2lrt` | 叠加、乘算以外的混合模式按普通绘制；需通过 http 访问 |
| `gltf` | 结构化绑定 | `.glb`（无光照材质、变形目标、权重动画） | 每个参数关键点一个变形目标，参数组合为叠加近似并报告误差；绘制顺序按静止值以深度分层；遮罩、混合模式、按参数变化的透明度与物理不写入 |
| `psd-pose` | 合成/时间轴 | 每个可见网格一层，按静止绘制顺序 | 变形器和参数不保留；按参数变化的绘制顺序取静止值 |
| `png-sequence` | 光栅 | 编号 PNG 帧 | 结构全部烘焙为像素 |
| `sprite-sheet` | 光栅 | 网格排列的精灵表 PNG + TexturePacker（hash）JSON | 同上 |
| `gif` | 光栅 | 动态 GIF | 256 色、1 位透明 |
| `mp4` | 光栅 | H.264 视频（ffmpeg） | 无透明，合成到背景色（默认白色） |
| `webm` | 光栅 | VP9 视频，带透明（ffmpeg） | 透明保存在 VP9 侧通道，需要 libvpx 解码 |
| `mov` | 光栅 | ProRes 4444，带透明（ffmpeg） | — |
| `apng` | 光栅 | 动态 PNG（ffmpeg） | — |
| `webp` | 光栅 | 动态 WebP（ffmpeg） | 有损压缩 |

光栅类和 `psd-pose` 通过宿主提供的 `FrameRenderer` 渲染。编辑器的实现（`IrFrameRenderer`）用引擎的 CPU 求值器得到几何，用 `IrColors` 按通道与混合形求出乘算/屏幕色，再由 `:format-compile` 的 `SoftwareRasterizer`（MIT）逐像素绘制：预乘浮点缓冲、双线性采样、按纹理 alpha 的遮罩（含反相）、Cubism 的叠加/乘算（保持目标 alpha）以及 W3C 合成规范的全部可分离与非可分离混合模式；帧之间推进摆锤物理。部件的分组合成与 over 以外的 alpha 合成尚按普通绘制。

### 设置

| 目标 | 设置键 |
| --- | --- |
| `moc3` | `physics`、`user_data`、`display_info`、`hidden_parts`、`hidden_meshes`、`guide_parts`（布尔）、`pixels_per_unit`（正数）；缺省取工程导出设置 |
| `cmo3` | `timestamp`（毫秒，默认 0） |
| `spine` | `clip_fps`（动作采样帧率，默认 15）、`clips`（是否写出动作，默认 true）、`key_tolerance`（关键帧精简容差，像素，默认 0.25）、`sample_pairs` |
| `psd-pose` | `clip` 与 `time`（秒）按动作片段摆姿势，或 `pose`（`ParamAngleX=20,ParamEyeLOpen=0`，覆盖片段）；`scale`（0.25–2，默认 1） |
| 视频类 | 同光栅类，另有 `ffmpeg`（ffmpeg 路径；缺省依次取环境变量 `PSD2LIVE_FFMPEG` 与 PATH） |
| 光栅类 | `clip`（默认第一个片段，无片段时为静止姿势）、`fps`（默认片段帧率）、`size`（长边像素，默认 1024）、`background`（ARGB 十六进制，默认透明）、`physics`（默认 true） |

## Spine

Spine 有骨骼没有参数。导出用一根根骨骼承载全部网格（无权重网格附件，原点为画布底边中点，y 向上），并：

- 每个参数一段动画 `param/<参数 ID>`，长 1 秒，时间为参数归一化值（最小 0、最大 1），deform 键位于参数的采样点；运行时为每个参数开一个轨道、用叠加混合（`MixBlend.add`）并按参数值设置轨道时间。关键形透明度写成槽位 `rgba`，按参数变化的绘制顺序写成 `drawOrder`。
- 每个动作片段一段动画 `clip/<名称>`，按 `clip_fps` 采样，再用 `KeyReduction` 去掉线性插值可重建（容差 `key_tolerance` 像素）的关键帧，偏移保留到千分之一像素。示例工程 13 段动作的 JSON 由 49 MB 降到 13 MB。
- 图集为每页一个覆盖整页的区域 `page<i>`，网格 UV 直接引用该页。
- 遮罩转为裁剪附件：被遮罩网格前插入裁剪槽位（`end` 指向该网格），多边形为单个遮罩网格的外轮廓，多个遮罩或多个轮廓时取凸包并报告；裁剪附件随遮罩网格写变形键，绘制顺序变化时与被遮罩网格一起移动。Spine 按多边形裁剪，不按纹理 alpha；反相遮罩无法表达，丢弃并报告。
- 验证：单元测试按 Spine 运行时的规则（无权重 deform 键为相对设置姿势的增量、按 offset 写入、线性插值；绘制顺序按偏移算法重排）重建姿势并与求值器比较。未使用官方 Spine 运行时（其许可证要求持有 Spine 许可），也未在 Spine Editor 中打开验证。

## DragonBones

与 Spine 相同的烘焙方式：一根根骨骼，每个可见网格为无权重网格显示（原点为画布底边中点，y 向下）；每个参数一段 1 秒的动画 `param/<参数 ID>`，变形（ffd）帧位于参数关键点取整后的帧上，宿主可按参数值定位并分层叠加；每个片段按帧采样后精简。帧间插值显式写 `tweenEasing: 0`（缺省为阶梯）。DragonBones 运行时用 16 位偏移寻址每个动画的变形浮点数据（每帧存满该网格全部顶点），超出约 3.2 万个浮点数时按倍增再二分的容差精简关键帧，并在损失报告中写出所用容差。验证：`DragonBonesFidelityTool`（`PSD2LIVE_TOOLS=1`，需要 node）用 `tools/dragonbones-check`（官方 DragonBones 5.7 运行时核心，无渲染）播放导出：无骨架样例的单参数姿势在关键帧上与编辑器误差小于 0.25 像素；带骨架时受上述上限影响的动画误差不超过报告的容差，片段误差见报告。

## glTF

每个可见网格成为一个无光照（`KHR_materials_unlit`）、双面、半透明混合的平面网格，处于静止姿势，按绘制顺序每层朝观察者前移 0.5 毫米；单位为米（`pixels_per_meter`，默认 1000），y 向上，原点在画布底边中点。每个参数的每个非默认关键点对它移动的网格成为一个变形目标（`extras.targetNames` 为 `参数=关键点`），参数值到权重的映射是关键点之间的线性帽函数，参数及其关键点列在 `extras.psd2live.parameters`。动作片段按 `clip_fps` 采样为权重动画并用 `KeyReduction` 精简。验证：单元测试由权重重建采样姿势；tml 导出经 Khronos glTF Validator 校验零错误零警告，并在 Godot 4.4 中按静止姿势和摇头动作渲染正确。

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
