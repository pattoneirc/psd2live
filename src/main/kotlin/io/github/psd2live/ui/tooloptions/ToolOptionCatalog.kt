package io.github.psd2live.ui.tooloptions

import io.github.psd2live.core.SkeletonWeightBrushMode
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.BrushFalloff
import io.github.psd2live.ui.BrushShape
import io.github.psd2live.ui.CanvasEditor
import io.github.psd2live.ui.CanvasTool
import io.github.psd2live.ui.CreatePlacementKind
import io.github.psd2live.ui.CreateRelation
import io.github.psd2live.ui.DEFORM_BRUSH_TOOLS
import io.github.psd2live.ui.EditHierarchyMode
import io.github.psd2live.ui.GLUE_WEIGHT_MODE_LABELS
import io.github.psd2live.ui.GlueSubTool
import io.github.psd2live.ui.GlueWeightMode
import io.github.psd2live.ui.SELECTION_TOOLS
import io.github.psd2live.ui.SkeletonEditSubTool
import io.github.psd2live.ui.SkeletonPoseSubTool
import io.github.psd2live.ui.WeightPaintMode

/*
 * Every tool's options, in the order the bar lays them out. Shared values are single instances, so the deform and
 * weight brushes - which share the editor's radius, hardness and strength - also share one range for each.
 */

private val wholeRange: (Float) -> ClosedFloatingPointRange<Float> = { 0f..1f }

// ─── Brushes ────────────────────────────────────────────────────────────────

internal val BRUSH_RADIUS = SliderOption("brush.radius", "editor.radius", OptionUnit.PX, { 1f..it },
    { it.radius }, { e, v -> e.radius = v }, logarithmic = true, role = OptionRole.SIZE)

/** Never 1: the deform brush's weight divides by one minus the hardness. */
internal val BRUSH_HARDNESS = SliderOption("brush.hardness", "editor.hardness", OptionUnit.PERCENT, { 0f..0.95f },
    { it.hardness }, { e, v -> e.hardness = v }, role = OptionRole.HARDNESS)

internal val BRUSH_STRENGTH = SliderOption("brush.strength", "editor.strength", OptionUnit.PERCENT, { 0.01f..1f },
    { it.strength }, { e, v -> e.strength = v }, role = OptionRole.STRENGTH)

internal val BRUSH_ANGLE = SliderOption("brush.angle", "editor.angle", OptionUnit.DEGREES, { 0f..359f },
    { it.brushAngle }, { e, v -> e.brushAngle = v.mod(360f) }, step = 15f, role = OptionRole.ANGLE)

internal val BRUSH_FALLOFF = ChoiceOption("brush.falloff", "editor.falloff", { BrushFalloff.entries }, { _, f -> tr(f.labelKey) },
    { it.brushFalloff }, { e, f -> e.brushFalloff = f }, inline = false)

internal val BRUSH_CONNECTED = ToggleOption("brush.connected", "editor.connectedOnly", { it.connectedOnly }, { e, on -> e.connectedOnly = on })

internal val INFLATE_DIRECTION = ChoiceOption("brush.inflate", "editor.tool.inflate", { listOf(false, true) },
    { _, shrink -> tr(if (shrink) "editor.inflate.shrink" else "editor.inflate.grow") },
    { it.inflateInvert }, { e, shrink -> e.inflateInvert = shrink })

internal val KNIFE_SNAP = SliderOption("knife.snap", "editor.knifeSnapRadius", OptionUnit.PX, { 3f..30f },
    { it.knifeSnapRadius }, { e, v -> e.knifeSnapRadius = v }, step = 1f, integer = true)

internal val GLUE_DISTANCE = SliderOption("glue.distance", "editor.glueDistance", OptionUnit.PX, { 0.5f..200f },
    { it.glueDistance }, { e, v -> e.glueDistance = v }, logarithmic = true, role = OptionRole.SIZE)

internal val WEIGHT_MODE = ChoiceOption("weight.mode", "editor.weightMode", { WeightPaintMode.entries }, { _, m -> tr(m.labelKey) },
    { it.weightPaintMode }, { e, m -> e.weightPaintMode = m })

// ─── Skeleton weights ───────────────────────────────────────────────────────

internal val SKELETON_WEIGHT_MODE = ChoiceOption("skeleton.weightMode", "skeleton.tool.weights", { SkeletonWeightBrushMode.entries },
    { _, m -> tr("skeleton.weights.mode.${m.name.lowercase()}") }, { it.skeletonWeightBrushMode }, { e, m -> e.skeletonWeightBrushMode = m })

internal val SKELETON_WEIGHT_RADIUS = SliderOption("skeleton.weightRadius", "editor.radius", OptionUnit.PX, { 1f..it },
    { it.skeletonWeightRadius }, { e, v -> e.skeletonWeightRadius = v }, logarithmic = true, role = OptionRole.SIZE)

internal val SKELETON_WEIGHT_STRENGTH = SliderOption("skeleton.weightStrength", "editor.strength", OptionUnit.PERCENT, { 0.01f..1f },
    { it.skeletonWeightStrength }, { e, v -> e.skeletonWeightStrength = v }, role = OptionRole.STRENGTH)

internal val SKELETON_WEIGHT_VALUE = SliderOption("skeleton.weightValue", "skeleton.weights.value", OptionUnit.PERCENT, wholeRange,
    { it.skeletonWeightReplaceValue }, { e, v -> e.skeletonWeightReplaceValue = v })

// ─── Variants: the flavour of the tool in hand, first in the bar ───────────

internal val BRUSH_TIP = ChoiceOption("variant.brushTip", "editor.brushShape", { BrushShape.entries }, { _, s -> tr(s.labelKey) },
    { it.brushShape }, { e, s -> e.brushShape = s }, variant = true)

internal val PAINT_SHAPE_KIND = ChoiceOption("variant.paintShape", "editor.tool.paint_shape", { io.github.psd2live.ui.PaintShape.entries },
    { _, s -> tr(s.labelKey) }, { it.paintShape }, { e, s -> e.selectPaintShape(s) }, variant = true)

internal val GLUE_SUB = ChoiceOption("variant.glue", "editor.tool.glue", { GlueSubTool.entries },
    { _, s -> tr(io.github.psd2live.ui.GLUE_SUB_TOOL_LABELS.first { it.first == s }.second) }, { it.glueSubTool }, { e, s -> e.glueSubTool = s },
    variant = true)

internal val SKELETON_EDIT_SUB = ChoiceOption("variant.skeletonEdit", "editor.tool.skeleton_edit", { SkeletonEditSubTool.entries },
    { _, s -> tr(s.labelKey) }, { it.skeletonEditSubTool }, { e, s -> e.skeletonEditSubTool = s }, variant = true)

internal val SKELETON_POSE_SUB = ChoiceOption("variant.skeletonPose", "editor.tool.skeleton_pose", { SkeletonPoseSubTool.entries },
    { _, s -> tr(s.labelKey) }, { it.skeletonPoseSubTool }, { e, s -> e.skeletonPoseSubTool = s }, variant = true)

internal val WEIGHT_KIND = ChoiceOption("variant.weightKind", "editor.weightKind", { io.github.psd2live.ui.PAINTED_GROUP_KINDS },
    { _, k -> tr("sim.group.${k.jsonName}") }, { it.weightGroupKind }, { e, k -> e.weightGroupKind = k }, variant = true)

// ─── Paint ──────────────────────────────────────────────────────────────────

internal val PAINT_BRUSH_SIZE = SliderOption("paint.size", "editor.paint.size", OptionUnit.PX, { 1f..it },
    { it.paintSize }, { e, v -> e.paintSize = v }, logarithmic = true, role = OptionRole.SIZE)

internal val PAINT_SHAPE_SIZE = SliderOption("paint.shapeWidth", "editor.width", OptionUnit.PX, { 1f..it },
    { it.paintShapeSize }, { e, v -> e.paintShapeSize = v }, logarithmic = true, role = OptionRole.SIZE)

internal val PAINT_HARDNESS = SliderOption("paint.hardness", "editor.hardness", OptionUnit.PERCENT, wholeRange,
    { it.paintHardness }, { e, v -> e.paintHardness = v }, role = OptionRole.HARDNESS)

internal val PAINT_OPACITY = SliderOption("paint.opacity", "editor.opacity", OptionUnit.PERCENT, { 0.01f..1f },
    { it.paintOpacity }, { e, v -> e.paintOpacity = v }, role = OptionRole.OPACITY)

internal val PAINT_TOLERANCE = SliderOption("paint.tolerance", "editor.tolerance", OptionUnit.COUNT, { 0f..255f },
    { it.paintTolerance.toFloat() }, { e, v -> e.paintTolerance = v.toInt() }, step = 4f, integer = true)

internal val PAINT_FILL = ChoiceOption("paint.fill", "editor.tool.paint_shape", { listOf(false, true) },
    { _, filled -> tr(if (filled) "editor.filled" else "editor.outline") }, { it.paintShapeFilled }, { e, f -> e.paintShapeFilled = f })

// ─── The catalog ────────────────────────────────────────────────────────────

/** The options of the tool in hand on [editor], in bar order. Empty when the tool has nothing to set or do. */
internal fun toolOptions(editor: CanvasEditor): List<ToolOption> = buildList {
    val placement = editor.placement
    if (placement != null) {
        addPlacement(editor, placement.kind)
        return@buildList
    }
    when (editor.tool) {
        CanvasTool.CREATE_WARP, CanvasTool.CREATE_ROTATION, CanvasTool.CREATE_DEFORM_PATH -> {
            // Armed without a part to build on: the tool waits for the pick that starts its placement.
            add(NoteOption("create.wait", { tr("editor.creationSelectFirst") }, warning = { true }))
            if (editor.tool != CanvasTool.CREATE_DEFORM_PATH) add(SEQUENTIAL_CREATE)
            add(ActionOption("create.cancel", "action.cancel", { !it.busy }, { it.cancel() }, OptionIcon.CANCEL))
            return@buildList
        }
        CanvasTool.GLUE -> {
            addGlue(editor)
            return@buildList
        }
        else -> Unit
    }
    when (editor.hierarchyMode) {
        EditHierarchyMode.SELECT -> addObjectMode(editor)
        EditHierarchyMode.DEFORM, EditHierarchyMode.EDIT -> addPointModes(editor)
        EditHierarchyMode.SIMULATE -> addSimulate(editor)
        EditHierarchyMode.SKELETON -> addSkeleton(editor)
        EditHierarchyMode.PAINT -> addPaint(editor)
    }
}

/** The options of [toolOptions] the context menu shows. */
internal fun menuOptions(editor: CanvasEditor): List<ToolOption> = toolOptions(editor).filter { it.place.menu }.trimSections()

/** The options of [toolOptions] the options bar shows. */
internal fun barOptions(editor: CanvasEditor): List<ToolOption> = toolOptions(editor).filter { it.place.bar }.trimSections()

/** The rows the context menu shows before it folds a group into a second level. */
internal const val MENU_ROW_BUDGET = 10

/** A group folds only when it saves rows worth a second level: three entries or more. */
private const val MIN_FOLDED_GROUP = 3

/**
 * The ids of the groups of [options] the context menu folds into a second level. Folding hides what it folds, so a
 * group stays open unless the menu needs the room: while every row fits [MENU_ROW_BUDGET] nothing folds; otherwise
 * the largest groups fold first (the later of two the same size), only until the rest fits. A group is a submenu
 * section with the options up to the next section, or a choice too long to lay out on one row; open, it takes a
 * caption and a row per entry, folded a single row.
 */
internal fun foldedGroups(editor: CanvasEditor, options: List<ToolOption>): Set<String> {
    val groups = mutableListOf<Pair<String, Int>>()
    var rows = 0
    var i = 0
    while (i < options.size) {
        val option = options[i]
        val members = when {
            option is SectionOption && option.submenu -> options.drop(i + 1).takeWhile { it !is SectionOption }.size
            option is ChoiceOption<*> && !option.inline && !option.variant -> option.choices(editor).size
            else -> -1
        }
        if (members >= 0) {
            rows += 1 + members
            if (members >= MIN_FOLDED_GROUP) groups += option.id to members
            i += 1 + if (option is SectionOption) members else 0
        } else {
            rows++
            i++
        }
    }
    val folded = mutableSetOf<String>()
    for ((id, members) in groups.withIndex().sortedWith(compareByDescending<IndexedValue<Pair<String, Int>>> { it.value.second }
        .thenByDescending { it.index }).map { it.value }) {
        if (rows <= MENU_ROW_BUDGET) break
        folded += id
        rows -= members
    }
    return folded
}

/** Drops the section captions left with nothing under them, and a leading one in the bar. */
private fun List<ToolOption>.trimSections(): List<ToolOption> = filterIndexed { i, option ->
    option !is SectionOption || (i + 1 < size && this[i + 1] !is SectionOption)
}

/**
 * Steps the [role] value of the tool in hand - the brush keys' `[`, `]` and their Shift and Alt variants - and
 * says whether the tool had one. Sizes scale, the rest step.
 */
internal fun CanvasEditor.stepOption(role: OptionRole, up: Boolean): Boolean {
    val option = toolOptions(this).filterIsInstance<SliderOption>().firstOrNull { it.role == role } ?: return false
    option.apply(this, option.stepped(option.get(this), up))
    return true
}

/**
 * Picks choice [n] (1-based) of the mode or tool in hand - the number keys: Deform's levels, Edit's vertex, edge and
 * face; otherwise the variants the toolbar lists under the tool: the brush tips, the glue and skeleton sub-tools, the vertex group kinds and the
 * paint shapes. Says whether there was a choice [n] to pick.
 */
internal fun CanvasEditor.pickVariant(n: Int): Boolean {
    fun <T> pick(choices: List<T>, take: (T) -> Unit): Boolean = choices.getOrNull(n - 1)?.let { take(it); true } ?: false
    return when {
        hierarchyMode == EditHierarchyMode.DEFORM -> pick(listOf(1, 2, 3)) { setEditLevel(it) }
        hierarchyMode == EditHierarchyMode.SIMULATE -> pick(io.github.psd2live.ui.PAINTED_GROUP_KINDS) { weightGroupKind = it }
        // Edit's point tools take Blender's 1 2 3: vertex, edge and face.
        hierarchyMode == EditHierarchyMode.EDIT && (tool in SELECTION_TOOLS || tool == CanvasTool.TRANSFORM) -> pick(listOf(0, 1, 2)) { elementMode = it }
        tool in DEFORM_BRUSH_TOOLS -> pick(BrushShape.entries) { brushShape = it }
        tool == CanvasTool.GLUE -> pick(GlueSubTool.entries) { glueSubTool = it }
        tool == CanvasTool.SKELETON_EDIT -> pick(SkeletonEditSubTool.entries) { skeletonEditSubTool = it }
        tool == CanvasTool.SKELETON_POSE -> pick(SkeletonPoseSubTool.entries) { skeletonPoseSubTool = it }
        tool == CanvasTool.PAINT_SHAPE -> pick(io.github.psd2live.ui.PaintShape.entries) { selectPaintShape(it) }
        else -> false
    }
}

private val SEQUENTIAL_CREATE = ToggleOption("create.sequential", "editor.sequentialCreate", { it.sequentialCreate }, { e, on -> e.sequentialCreate = on })

private fun MutableList<ToolOption>.addPlacement(editor: CanvasEditor, kind: CreatePlacementKind) {
    if (kind == CreatePlacementKind.PATH) {
        add(ActionOption("path.finish", "editor.finishPath", { it.editable && it.draft.size >= 2 }, { it.confirmPlacement() },
            OptionIcon.CONFIRM, primary = true))
        add(ActionOption("path.undoPoint", "editor.undoPoint", { !it.busy && it.draft.isNotEmpty() }, { it.undoDraftPoint() },
            OptionIcon.UNDO, keepsMenu = true))
    } else {
        add(ActionOption("placement.confirm", "editor.placementConfirm", { it.editable }, { it.confirmPlacement() },
            OptionIcon.CONFIRM, primary = true))
    }
    add(ActionOption("placement.cancel", "editor.placementCancel", { true }, { it.cancelPlacement() }, OptionIcon.CANCEL))
    if (kind == CreatePlacementKind.WARP || kind == CreatePlacementKind.ROTATION) add(SEQUENTIAL_CREATE)
}

private fun MutableList<ToolOption>.addGlue(editor: CanvasEditor) {
    val pair = editor.glueMeshPair()
    add(GLUE_SUB)
    if (editor.glueSubTool == GlueSubTool.WEIGHT) {
        add(ChoiceOption("glue.weightSide", "editor.glueWeight", { GLUE_WEIGHT_MODE_LABELS.map { it.first } },
            { _, mode -> GLUE_WEIGHT_MODE_LABELS.first { it.first == mode }.second }, { it.glueWeightMode },
            { e, mode: GlueWeightMode -> e.glueWeightMode = mode }))
    }
    add(GLUE_DISTANCE)
    add(NoteOption("glue.pair", { e ->
        val p = e.glueMeshPair()
        if (p == null) tr("editor.glueNeedTwo", e.glueMeshCount())
        else "${e.meshLabel(p.first)} ↔ ${e.meshLabel(p.second)}"
    }, warning = { it.glueMeshPair() == null }))
    add(ActionOption("glue.swap", "editor.glueSwap", { it.glueMeshPair() != null && it.editable }, { it.swapGlueEnds() }, OptionIcon.SWAP, keepsMenu = true))
    add(ActionOption("glue.apply", if (pair != null && editor.glueAlreadyBound()) "editor.glueReplace" else "editor.glueCreate",
        { it.glueMeshPair() != null && it.editable && it.gluePreviewPoints().isNotEmpty() }, { it.applyGlue() }, OptionIcon.CONFIRM, primary = true))
    add(ActionOption("glue.remergeAll", "editor.glueRemergeAll", { it.glueMeshPair() != null && it.editable }, { it.remergeGlue() },
        OptionIcon.REMERGE, place = OptionPlace.MENU))
}

/** Object mode: the selection, and what can be built on the part in hand. */
private fun MutableList<ToolOption>.addObjectMode(editor: CanvasEditor) {
    addSelectionActions(objectMode = true)
    val target = editor.target()
    val split = target?.takeIf { it.kind == "mesh" }?.let { mesh ->
        editor.model.drawables.firstOrNull { it.id.raw == mesh.id }?.takeIf { editor.viewModel.depthSplitMiddleIds(mesh.id).isNotEmpty() }
    }
    if (split != null) {
        add(ActionOption("object.depthSplit", "editor.depthSplit.quick", { it.editable }, { it.viewModel.requestDepthSplit(split.id.raw) },
            OptionIcon.DEPTH_SPLIT, labelArgs = { listOf(split.name) }, place = OptionPlace.MENU))
    }
    if (target?.kind == "mesh") {
        add(SectionOption("object.create", "editor.toolbar.create", OptionPlace.MENU, submenu = true, icon = OptionIcon.CREATE))
        add(createAction("object.createWarp", "editor.createWarp", OptionIcon.WARP, CreatePlacementKind.WARP, CreateRelation.AS_PARENT))
        add(createAction("object.createRotation", "editor.createRotation", OptionIcon.ROTATION, CreatePlacementKind.ROTATION, CreateRelation.AS_PARENT))
        add(createAction("object.createPath", "editor.treeAddPath", OptionIcon.PATH, CreatePlacementKind.PATH, CreateRelation.AS_CHILD))
        add(ActionOption("object.skeleton", if (editor.committedSkeleton == null) "skeleton.tree.create" else "skeleton.tree.edit",
            { it.editable }, { if (it.committedSkeleton == null) it.createSkeleton() else it.beginSkeletonEdit() },
            OptionIcon.SKELETON, place = OptionPlace.MENU))
    }
    if (target?.kind == "mesh" || target?.kind == "warp") {
        add(ActionOption("object.swing", "swing.menu", { it.editable }, { it.viewModel.beginSwing(it.swingTargets()) },
            OptionIcon.SWING, place = OptionPlace.MENU))
    }
}

/** The tree's place-then-confirm create on the mesh in hand, the same entry as the hierarchy tree's Add menu. */
private fun createAction(id: String, labelKey: String, icon: OptionIcon, kind: CreatePlacementKind, relation: CreateRelation) =
    ActionOption(id, labelKey, { it.editable }, { e ->
        e.target()?.id?.let { meshId -> e.beginTreeCreate(kind, relation, false, meshId) }
    }, icon, place = OptionPlace.MENU)

private fun MutableList<ToolOption>.addSelectionActions(objectMode: Boolean) {
    add(SectionOption("selection", "editor.selectionMode", OptionPlace.MENU, submenu = true, icon = OptionIcon.SELECTION))
    add(ActionOption("selection.all", "shortcut.selectAll", { it.editable }, { it.selectAll(invert = false) }, OptionIcon.SELECT_ALL,
        place = OptionPlace.MENU))
    add(ActionOption("selection.invert", "help.shortcuts.invertSelection", { it.editable }, { it.selectAll(invert = true) }, OptionIcon.INVERT,
        place = OptionPlace.MENU))
    if (!objectMode) {
        add(ActionOption("selection.linked", "shortcut.selectLinked", { it.editable && it.vertices.isNotEmpty() }, { it.selectLinked() },
            OptionIcon.LINKED, place = OptionPlace.MENU))
    }
    add(ActionOption("selection.none", "editor.deselect",
        { if (objectMode) it.objects.isNotEmpty() else it.vertices.isNotEmpty() },
        { e ->
            if (objectMode) e.objects = emptySet()
            else { e.vertices = emptySet(); e.selectedEdges = emptySet(); e.selectedFaces = emptySet() }
        }, OptionIcon.DESELECT, place = OptionPlace.MENU))
}

/** Deform and Edit: the pose a deformation keys, the brush or edit tool in hand, then topology and the selection. */
private fun MutableList<ToolOption>.addPointModes(editor: CanvasEditor) {
    val editing = editor.hierarchyMode == EditHierarchyMode.EDIT
    val target = editor.target()
    val mesh = target?.kind == "mesh"
    if (editing && mesh && (editor.tool in SELECTION_TOOLS || editor.tool == CanvasTool.TRANSFORM)) add(ELEMENT_MODE)
    when (editor.tool) {
        CanvasTool.BRUSH_SELECT -> add(BRUSH_RADIUS)
        CanvasTool.BRUSH, CanvasTool.SMOOTH, CanvasTool.INFLATE -> {
            add(BRUSH_TIP)
            add(BRUSH_RADIUS)
            add(BRUSH_STRENGTH)
            add(BRUSH_HARDNESS)
            if (editor.brushShape != BrushShape.CIRCLE) add(BRUSH_ANGLE)
            add(BRUSH_FALLOFF)
            add(BRUSH_CONNECTED)
            if (editor.tool == CanvasTool.INFLATE) add(INFLATE_DIRECTION)
        }
        CanvasTool.SUBDIVIDE -> {
            add(BRUSH_RADIUS)
            add(ActionOption("subdivide.run", "editor.subdivide", { it.editable && it.vertices.isNotEmpty() }, { it.topology("subdivide") },
                OptionIcon.SUBDIVIDE, primary = true))
        }
        CanvasTool.KNIFE -> {
            add(KNIFE_SNAP)
            add(ActionOption("knife.finish", "editor.finishCut", { it.editable && it.knifeDraft.size >= 2 }, { it.finishKnife() },
                OptionIcon.CONFIRM, primary = true))
            add(ActionOption("knife.undoPoint", "editor.undoPoint", { !it.busy && it.knifeDraft.isNotEmpty() }, { it.undoDraftPoint() },
                OptionIcon.UNDO, keepsMenu = true))
            add(ActionOption("knife.cancel", "action.cancel", { !it.busy && it.knifeDraft.isNotEmpty() }, { it.cancel() }, OptionIcon.CANCEL))
        }
        else -> Unit
    }
    if (editing && mesh && editor.tool != CanvasTool.KNIFE) {
        add(SectionOption("topology", "editor.topology", OptionPlace.MENU, submenu = true, icon = OptionIcon.TOPOLOGY))
        for (action in TOPOLOGY_ACTIONS) {
            if (editor.tool == CanvasTool.SUBDIVIDE && action.first == "subdivide") continue
            add(ActionOption("topology.${action.first}", "editor.${action.first}", { it.editable && it.vertices.isNotEmpty() },
                { it.topology(action.first) }, action.second, danger = action.first == "delete", place = OptionPlace.MENU))
        }
    }
    if (editor.tool in SELECTION_TOOLS || editor.tool == CanvasTool.TRANSFORM) addSelectionActions(objectMode = false)
}

private val TOPOLOGY_ACTIONS = listOf(
    "split" to OptionIcon.SPLIT, "subdivide" to OptionIcon.SUBDIVIDE, "connect" to OptionIcon.CONNECT,
    "merge" to OptionIcon.MERGE, "delete" to OptionIcon.DELETE, "duplicate" to OptionIcon.DUPLICATE,
)

internal val ELEMENT_MODE = ChoiceOption("edit.element", "editor.elementMode", { listOf(0, 1, 2) },
    { _, i -> tr("editor.${listOf("vertex", "edge", "face")[i]}") }, { it.elementMode }, { e, i -> e.elementMode = i })

private fun MutableList<ToolOption>.addSimulate(editor: CanvasEditor) {
    add(WEIGHT_KIND)
    when (editor.tool) {
        CanvasTool.WEIGHT_PAINT -> {
            add(WEIGHT_MODE)
            add(BRUSH_RADIUS)
            add(BRUSH_STRENGTH)
            add(BRUSH_HARDNESS)
        }
        CanvasTool.WEIGHT_GRADIENT -> {
            add(WEIGHT_MODE)
            add(BRUSH_STRENGTH)
        }
        else -> Unit
    }
    add(SectionOption("weight.group", "editor.weightGroup", OptionPlace.MENU, submenu = true, icon = OptionIcon.GROUP))
    add(ActionOption("weight.fill", "editor.weightFill", { it.editable }, { it.fillVertexGroup(1f) }, OptionIcon.FILL, place = OptionPlace.MENU))
    add(ActionOption("weight.clear", "editor.weightClear", { it.editable }, { it.fillVertexGroup(0f) }, OptionIcon.ERASE, place = OptionPlace.MENU))
    add(ActionOption("weight.invert", "editor.weightInvert", { it.editable }, { it.invertVertexGroup() }, OptionIcon.INVERT, place = OptionPlace.MENU))
    add(ActionOption("weight.delete", "editor.weightDelete", { it.editable }, { it.deleteVertexGroup() }, OptionIcon.DELETE,
        danger = true, place = OptionPlace.MENU))
}

private fun MutableList<ToolOption>.addSkeleton(editor: CanvasEditor) {
    when (editor.tool) {
        CanvasTool.SKELETON_POSE -> {
            add(SKELETON_POSE_SUB)
            add(ActionOption("skeleton.resetPose", "animation.resetPose", { it.bakedSkeleton != null }, { it.resetSkeletonPose() },
                OptionIcon.RESET, place = OptionPlace.MENU))
        }
        CanvasTool.SKELETON_EDIT -> {
            add(SKELETON_EDIT_SUB)
            if (editor.skeletonEditSubTool == SkeletonEditSubTool.WEIGHTS) {
                add(SKELETON_WEIGHT_MODE)
                add(SKELETON_WEIGHT_RADIUS)
                add(SKELETON_WEIGHT_STRENGTH)
                if (editor.skeletonWeightBrushMode == SkeletonWeightBrushMode.REPLACE) add(SKELETON_WEIGHT_VALUE)
            }
            add(NoteOption("skeleton.hint", { tr(it.skeletonEditSubTool.hintKey) }, place = OptionPlace.BAR))
            add(ActionOption("skeleton.cancel", "skeleton.panel.cancel", { it.skeletonDraft != null }, { it.cancelSkeletonEdit() },
                OptionIcon.CANCEL, place = OptionPlace.MENU))
            add(ActionOption("skeleton.done", "skeleton.panel.done", { it.skeletonDraft != null }, { it.finishSkeletonEdit() },
                OptionIcon.CONFIRM, primary = true, place = OptionPlace.MENU))
        }
        else -> Unit
    }
}

private fun MutableList<ToolOption>.addPaint(editor: CanvasEditor) {
    // Paint mode cannot start without a layer, so until one is picked that is all the bar has to say.
    add(NoteOption("paint.layer", { if (it.paintSession == null) tr("editor.paintSelectLayerHint") else null }, warning = { true }))
    when (editor.tool) {
        CanvasTool.PAINT_BRUSH, CanvasTool.PAINT_ERASER -> {
            add(PAINT_BRUSH_SIZE)
            add(PAINT_HARDNESS)
            add(PAINT_OPACITY)
        }
        CanvasTool.PAINT_PENCIL -> {
            add(PAINT_BRUSH_SIZE)
            add(PAINT_OPACITY)
        }
        CanvasTool.PAINT_BUCKET -> add(PAINT_TOLERANCE)
        CanvasTool.PAINT_SHAPE -> {
            add(PAINT_SHAPE_KIND)
            add(PAINT_SHAPE_SIZE)
            add(PAINT_OPACITY)
            if (editor.paintShape.canFill) add(PAINT_FILL)
        }
        else -> Unit
    }
    add(SectionOption("paint.layerActions", "editor.paintTargetLayer", OptionPlace.MENU))
    add(ActionOption("paint.clear", "editor.paintClear", { it.paintSession != null }, { it.clearCurrentLayerPaint() }, OptionIcon.CLEAR,
        danger = true, place = OptionPlace.MENU))
}
