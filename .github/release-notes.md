模拟烘焙改为逐个输入驱动，导出的摆动更贴近模拟、烘焙更快，并新增视觉检查与参考对比；导出图像、网页播放器、Godot 节点与运行时预览完整支持全部混合模式和隔离部件组；运行时 C 接口升级到 1.4，新增软件渲染、C#（Unity）绑定与 Android 构建；拆分或开启头发模拟之后创建的骨架重新能让手脚动起来。

## 主要更新

### 新增

- **运行时 C 接口 1.1–1.4**：同一角色多份实例共用模型数据；按层推进动作、表情、行为与物理，并在动作之上叠加自己的参数（例如面捕）；动作分层播放并带优先级、事件与部件/整体透明度曲线，多个表情同时生效；物理支持风力、稳定化和纵向输入输出；口型可由音频样本驱动；可只更新每帧变化的网格，接收错误日志，使用宿主自己的内存分配器。只用到旧接口的程序不受影响。
- **运行时软件渲染**：新增 `p2l_render`，不写渲染代码也能把模型画成图像，与编辑器导出图像逐像素一致。
- **C#（Unity）绑定与 Android 构建**：由头文件生成的 C# 声明、Unity 示例组件、C/C++ 示例程序，以及 Android（arm64-v8a、armeabi-v7a、x86_64）运行时库构建脚本。

### 改进

- **模拟烘焙逐个输入驱动**：烘焙先读出每个输入带动物体多远，几乎不带动的输入不再参与，上下移动的输入自动归入上下参数；每个输入单独做阶跃和扫频，跟随鼠标等场景下导出的摆幅与模拟相当（以前可能只有两成）。后发、前发预设在没见过的动作上普遍更准，耗时约减半。已有烘焙不会过期，重新烘焙即可使用新方法。
- **烘焙视觉检查与对比**：烘焙节先显示导出与模拟的摆幅比、滞后、回落和抖动；新增「对比」节，可把参考模拟代替导出、叠影或并排显示，选一个动作从静止播放两者并给出检验。「高级」里可让模型自己的动作参与训练。
- **MCP**：`simulation_put` 新增 `force_inputs` 与 `training_clips`，烘焙摘要返回 `inputs` 与 `visual`，新增只读任务 `simulation_compare`。
- **混合模式全面支持**：导出的 PNG、GIF、视频帧和逐姿势 PSD，网页播放器，Godot 节点 `P2LCharacter`，以及「PSD2Live 运行时（p2lrt）」预览，均按编辑器规则绘制全部颜色与透明度混合模式和隔离部件组（含组的不透明度、乘算/屏幕色与遮罩），开启剔除的网格只画正面。

### 修复

- **拆分或开启头发模拟之后创建的骨架能让手脚动起来**：此前不会生成手臂和腿的参数，挥手、欢呼等动作里手脚不动。受影响的已保存工程，打开后执行「工具 → 更新生成结果」即可补上。
- **骨骼自动胶水恢复**（v3.1.0 起失效）：肢体拆成多段后启用骨架，接缝的自动胶水和后拆分部件的蒙皮重新生效。已在 v3.1.0–v3.1.2 中启用骨架的工程，撤销到启用骨架之前再重新启用即可。
- **快速切换动作不再跳变**、**多个角色不再同时眨眼**；导出帧中的「加算（发光）」按编辑器的方式绘制。
- **运行时出错不再让编辑器闪退**：出错的模型只是停止响应，预览改用编辑器求值器并报告原因；接口不匹配的旧运行时库不再被加载。

<details>
<summary>English</summary>

Simulation bakes now drive each input on its own, so the exported swing follows the simulation more closely and bakes run faster, with a visual check and a side-by-side comparison against the reference. Exported images, the web player, the Godot node and the runtime preview draw every blend mode and isolated part group. The runtime C ABI reaches 1.4, with software rendering, C# (Unity) bindings and Android builds. Skeletons created after a split or a hair simulation preset move the limbs again.

## Highlights

### New

- **Runtime C ABI 1.1–1.4**: several instances of a character share one model; motions, expressions, behaviors and physics update by stage, with your own parameters (such as face tracking) layered over motions; motions play in layers with priorities, events and part / model opacity curves, and several expressions play at once; physics gains wind, stabilization and vertical input and output; lip sync can be driven by audio samples; you can update only the meshes that changed each frame, receive the runtime's log and hand it your own allocator. Programs using only the earlier functions are unaffected.
- **Runtime software rendering**: `p2l_render` draws a model into an image without any rendering code of your own, pixel for pixel as the editor's image export.
- **C# (Unity) bindings and Android builds**: C# declarations generated from the header, a Unity sample component, a C/C++ example, and a script building the runtime library for Android (arm64-v8a, armeabi-v7a, x86_64).

### Improvements

- **Simulation bakes drive each input on its own**: the bake first reads how far each input moves the body, leaves out inputs that barely move it and sends up-and-down movers to the vertical parameter; each input then gets its own steps and sweeps, so the exported swing matches the simulation when following the pointer (it could be a fifth of it before). Back and front hair presets are generally more accurate on unseen motions, in about half the time. Existing bakes are not made stale; bake again to use the new method.
- **Visual check and comparison**: the bake section shows the swing ratio against the simulation, lag, settling and jitter first; a new Compare section shows the reference simulation in place of the export, faded over it or side by side, and plays a motion through both from rest with a check. Under Advanced, the model's own motions can join the training.
- **MCP**: `simulation_put` adds `force_inputs` and `training_clips`, bake summaries return `inputs` and `visual`, and the read-only job `simulation_compare` is new.
- **Every blend mode, everywhere**: exported PNG, GIF, video frames and per-pose PSD, the web player, the Godot node `P2LCharacter` and the "PSD2Live runtime (p2lrt)" preview draw every color and alpha blend mode and isolated part groups (with the group's opacity, multiply / screen colors and masks) as the editor does; meshes with culling draw front faces only.

### Fixes

- **Skeletons created after a split or a hair simulation preset move the limbs again**: no arm or leg parameters were generated, so Wave, Cheer and other motions left the limbs still. For affected saved projects, run Tools → Update generated results after opening.
- **Automatic skeleton glue restored** (broken since v3.1.0): limbs split into several pieces get their seam glue and skinning back when the skeleton is enabled. For projects that enabled a skeleton in v3.1.0–v3.1.2, undo to before enabling it and enable it again.
- **Switching motions quickly no longer jumps**, **several characters no longer blink in sync**, and add (glow) in exported frames draws as in the editor.
- **A runtime error no longer closes the editor**: the failing model just stops responding, the preview falls back to the editor's evaluator and reports why, and runtime libraries with a mismatched interface are no longer loaded.

</details>
