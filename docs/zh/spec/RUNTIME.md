# PSD2Live 运行时

`runtime/` 是一个独立的 Rust 库（MIT），播放 PSD2Live 编译出的 `.p2lrt` 模型：参数驱动的变形、物理和动作片段，提供 C ABI 给其他语言和引擎调用。编辑器通过 `:format-eval`（JNA）调用同一个库。

## 组成

| 部分 | 位置 | 许可证 |
| --- | --- | --- |
| `.p2lrt` 写出与导出目标 `p2lrt` | `:targets:runtime`（`P2lrt`、`P2lrtTarget`） | MIT |
| 读取、求值、物理、动作片段、C ABI | `runtime/`（crate `p2l-runtime`，cdylib + staticlib） | MIT |
| C 头文件 | `runtime/include/p2l_runtime.h` | MIT |
| JVM 绑定 | `:format-eval`（`P2lRuntime`、`NativeGeometryEvaluator`） | MIT |
| 网页播放器 | `:targets:web`（导出目标 `web`：页面、`p2l.js`、运行时的 WebAssembly 构建） | MIT |
| Godot 4 节点 `P2LCharacter` | `runtime/godot/`（GDExtension，godot-rust，Godot 4.3+），演示工程 `runtime/godot/demo/` | MIT |

构建：在 `runtime/` 运行 `cargo build --release`，库位于 `runtime/target/release/`（Windows 为 `p2l_runtime.dll`）。Gradle 的 `buildRuntime` 在有 cargo 时自动构建：`./gradlew run` 使用它，打包时随应用资源分发；没有 cargo 时跳过，应用回退到编辑器求值器。`P2lRuntime.locate()` 依次查找系统属性 `psd2live.runtime.library`、环境变量 `PSD2LIVE_RUNTIME`（文件或目录）、系统属性 `psd2live.runtime.dir`、打包应用的资源目录和 `java.library.path`。

编辑器的导出烘焙（Spine、DragonBones、glTF）在运行时库可用时经 `NativeGeometryEvaluator` 求值，否则使用引擎求值器；两者逐姿势一致。编辑器软件预览（`RigCanvasSupport.evaluate`）同样经 `NativePreview` 使用运行时：首次遇到某个预览模型时由引擎作答并在后台编译，之后的帧走运行时；拖动中的临时模型不等待编译，失败或 `-Dpsd2live.preview.runtime=false` 时保持引擎。在带骨架的 tml 上单次求值约 0.5 毫秒，与引擎相当（JNA 调用与结果转换抵消了原生速度）。

## `.p2lrt` 格式

版本 1，小端二进制，是编译后 IR 的紧凑表示：画布、参数、变形器（父级在前）、部件、网格、Glue、绘制树、贴图页 PNG、物理组、动作片段和参数角色（如 EyeBlink、LipSync）。引用一律为索引。逐字段布局见 `P2lrt.kt` 的文档注释，读取器（`runtime/src/rig.rs`）与之逐字段对应，并校验引用、网格索引、关键形尺寸和版本号，拒绝其他版本。2.0 格式（分块容器、核心层与扩展层）的草案见 [P2LRT_V2.md](P2LRT_V2.md)，尚未实现。

## 求值规则

求值输出每个网格在画布像素坐标（y 向下）中的顶点、透明度、乘算/屏幕色和绘制顺序。规则全部通过黑盒探测编辑器求值器得到（`WarpProbeTool`、`RuntimeConformanceTool`），未参考其实现：

- **参数**：钳制到范围；循环参数只回绕范围之外的值，两端保持原值。
- **关键形网格**：多线性插值。值与某个关键点相差小于 0.001 时取该关键点。稀疏网格中缺失的格子按零形状计入（网格偏移为零，变形器的绝对形状为原点），权重不重新分配。标志通道（翻转）取不大于当前值的关键点。
- **Warp**：单位正方形内按格子双线性或沿 (1,0)–(0,1) 对角线分成两个三角形插值；外部在 [-2, 3]² 内用一层较粗的虚拟格子延伸，额外格点取格子四角的平均仿射框架，按三角形插值；更远处只用仿射框架。
- **旋转变形器**：角度为关键形角度加基准角。父级为旋转时，原点经父级变换，角度和缩放与父级叠加；父级垂直翻转或父级缩放为负时，子框架额外旋转 180°；父级水平翻转不影响子框架方向；自身翻转只作用于子坐标。父级为 Warp 时，框架保持刚性：原点经格子映射，角度随格子在原点上方 0.1 处沿 v 方向的走向旋转，缩放继承格子上方最近旋转的缩放。
- **混合形**：每个绑定按参数在相邻关键点之间线性混合（不取整到关键点），中性关键点和没有形状的关键点不贡献；权重乘以各限制参数的分段线性系数。变形器和通道的形状是目标值，叠加的是“目标 − 默认姿势下的值”；网格形状是目标偏移，叠加的是“目标偏移 − 默认姿势下的偏移”。
- **透明度**：每一级（变形器、网格）先钳制到 [0, 1] 再与父级相乘；部件透明度不计入网格透明度。
- **Glue**：在全部变形之后，每对顶点各自按自己的权重乘强度向对方移动。
- **绘制顺序**：每个绘制组按子项的绘制顺序排序，子组以其部件按通道与混合形求出的绘制顺序为键（无部件时取静态值）；键加千分之一后取整，相同时保持树中顺序；隐藏网格不绘制。规则取自引擎渲染顺序接口的文档说明，未与原生渲染逐帧比对。

## 物理

按 Cubism 运行时读取 physics3.json 的方式实现，包括其特有行为：输入按参数范围中点归一化；固定步长（`Fps`）时在帧间插值输入、输出在最后两步之间插值；不限步长时输出滞后一帧；平移旋转时复用已旋转的 x；只有角度输出带缩放，平移输出的缩放为零；超过 5 秒的积累时间清零。与编辑器 `PhysicsEngine` 在随机摆锤组和样例模型上逐帧比较，误差小于 2e-5。

## 动作片段

曲线段与编辑器相同（线性、Bezier（时间控制点限制在段内）、阶梯、反阶梯）；循环片段按时长回绕，单次片段停在末尾。`Player` 一次播放一个片段，切换时按片段的淡入淡出时间（缺省 1 秒，余弦缓动）交叉过渡。

## 程序化行为

宿主可开启：眨眼（`EyeBlink` 角色，每 2–6 秒一次，闭合 0.1 秒、保持 0.05 秒、睁开 0.15 秒，乘到当前值上）、呼吸（`Breath` 设置为范围内的正弦，`AngleX/Y/Z`、`BodyAngleX` 叠加小幅摆动）、视线（`look_at(x, y)` 经临界阻尼跟随，驱动 `AngleX/Y`、`BodyAngleX`、`EyeBallX/Y`，`AngleZ` 随 x·y 倾斜）和口型（`lip_sync(level)`，`LipSync` 参数取较大值）。角色由编辑器编译时按存在的标准参数写入；moc3 的 model3.json 仍只写 EyeBlink 与 LipSync 两组。更新顺序：动作片段 → 行为 → 物理 → 变形。

## C ABI

一个句柄对应一个已加载模型，持有参数、动作播放器和物理状态。典型流程：`p2l_rig_load` → 设置参数（`p2l_parameter_values` / `p2l_set_parameter`）→ `p2l_update(dt)`（动作、物理、变形）或 `p2l_evaluate` → 读取 `p2l_mesh_vertices`、`p2l_mesh_opacity`、`p2l_mesh_colors`，按 `p2l_render_order` 由后往前绘制，贴图由 `p2l_texture_png` 提供。所有函数接受空句柄；返回的指针在句柄释放（姿势数据在下次求值）前有效。

## 验证

- `cargo test`：每条求值规则一个小模型单元测试（数值来自探测结果），以及 Warp、动作片段测试。
- `RuntimeConformanceTool`（`PSD2LIVE_TOOLS=1`）：生成 80 个随机模型（Warp、旋转、嵌套、稀疏网格、混合形、Glue、通道、部件、混合）、样例和本地工程的参考姿势，以及物理轨迹；`cargo run --release --bin p2lrt-conformance -- ../build/tools/runtime-conformance`（物理为 `runtime-physics`）逐例比较。当前除两个在放大极端的镜像格子中出现 0.02–0.1 像素单精度误差的随机模型外全部一致。

## 尚未完成

- 网格渲染（遮罩、混合模式）由宿主完成，运行时只提供几何与属性。
- Godot 节点中反相遮罩按无遮罩绘制（Godot 的画布组无法移除覆盖）。

## 网页播放器

导出目标 `web` 写出可直接部署的文件夹：`index.html`、`p2l.js`（ES 模块 `P2LPlayer`）、`p2l_runtime.wasm` 与模型。播放器用 WebGL 绘制：遮罩经模板缓冲（按纹理 alpha 0.5 裁剪，支持反相），叠加与乘算用混合函数，乘算/屏幕色在着色器中计算；页面提供动作选择、口型滑块，视线跟随指针。WebAssembly 构建作为资源随仓库提交，修改运行时后用 `./gradlew :targets:web:updateWasm` 刷新（需要 `rustup target add wasm32-unknown-unknown`）；单元测试核对播放器调用的每个函数都由该构建导出。tml 样例在 Edge（无界面）中显示正确。

## Godot

`runtime/godot/` 用 `cargo build --release` 构建，`P2LCharacter` 的属性与方法见其 README。每个网格绘制在自己的画布项中，每帧按绘制顺序重排；Cubism 的加算/乘算用 CanvasItemMaterial，屏幕色与扩展混合模式用着色器（后者读取下方屏幕纹理，绘制前复制后缓冲），遮罩用仅裁剪的画布组。`RuntimeSamplesTool`（`PSD2LIVE_TOOLS=1`）生成覆盖全部混合模式、乘算/屏幕色与遮罩的合成模型及软件光栅器参考图，Godot 渲染除反相遮罩外与参考图一致（差值 ≤ 8/255）。验证：演示工程的 `smoke_test.gd` 在无界面模式加载模型并推进一秒；`--shot` 在窗口中渲染一帧，tml 样例显示正确（含眼部遮罩）。
