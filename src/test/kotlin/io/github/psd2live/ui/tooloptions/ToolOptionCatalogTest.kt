package io.github.psd2live.ui.tooloptions

import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.core.PipelineConfig
import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.project.WorkspaceSourceArt
import io.github.psd2live.project.WorkspaceSourceLayer
import io.github.psd2live.ui.BrushShape
import io.github.psd2live.ui.CREATE_GROUP_TOOLS
import io.github.psd2live.ui.CREATION_TOOLS
import io.github.psd2live.ui.CanvasEditor
import io.github.psd2live.ui.CanvasTool
import io.github.psd2live.ui.EditHierarchyMode
import io.github.psd2live.ui.SkeletonEditSubTool
import io.github.psd2live.ui.TOOLBAR_TOOL_ORDER
import io.github.psd2live.ui.toolbarRows
import io.github.psd2live.ui.createGroupOffered
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.toolbarGroups
import io.github.psd2live.ui.views.canvasContextMenuHasContent
import org.umamo.format.art.*
import kotlin.test.*

class ToolOptionCatalogTest {
    private fun preview(): RigPreviewModel {
        val layer = WorkspaceSourceLayer(LayerId("body"), "body", "", SourceLayerKind.Raster, true, 1,
            LayerBounds(0, 0, 8, 8), 1f, false, LayerBlend.Normal, ChannelMask.ALL,
            LayerRaster(8, 8, ByteArray(8 * 8 * 4) { -1 }), null, null, true)
        return PSD2LivePipeline().buildPreview(WorkspaceSourceArt(8, 8, listOf(layer), emptyList()),
            PipelineConfig(meshOnly = true, atlasSize = 256))
    }

    private fun editor(action: (CanvasEditor) -> Unit) {
        PSD2LiveViewModel().use { vm ->
            val model = preview()
            vm.setStateForTest(vm.state.value.copy(previewModel = model))
            val editor = vm.canvasEditorFor(vm.state.value.activeCanvas.id)
            editor.selectLayer(model.rig.layerIdByDrawableId.values.first())
            action(editor)
        }
    }

    /** Every tool in every mode, each variant that changes what the tool offers included. */
    private fun everyState(editor: CanvasEditor, check: (String) -> Unit) {
        for (mode in EditHierarchyMode.entries) for (tool in CanvasTool.entries) {
            editor.hierarchyMode = mode
            editor.tool = tool
            for (shape in BrushShape.entries) {
                editor.brushShape = shape
                for (sub in SkeletonEditSubTool.entries) {
                    editor.skeletonEditSubTool = sub
                    check("$mode/$tool/$shape/$sub")
                }
            }
        }
    }

    @Test fun everyToolsOptionsHaveUniqueIdsAndUsableRanges() = editor { editor ->
        everyState(editor) { where ->
            val options = toolOptions(editor)
            assertEquals(options.size, options.map { it.id }.toSet().size, "duplicate option ids at $where")
            for (slider in options.filterIsInstance<SliderOption>()) {
                for (limit in listOf(512f, 8192f)) {
                    val range = slider.bounds(limit)
                    assertTrue(range.start < range.endInclusive, "${slider.id} has an empty range at $where")
                }
                val value = slider.get(editor)
                val range = slider.range(editor)
                assertTrue(value in range, "${slider.id} starts at $value outside $range")
            }
        }
    }

    @Test fun theBarAndTheMenuShowTheSameDefinitions() = editor { editor ->
        everyState(editor) { where ->
            val all = toolOptions(editor).associateBy { it.id }
            for (option in menuOptions(editor) + barOptions(editor)) {
                val own = all[option.id]
                assertNotNull(own, "${option.id} is not one of the tool's own options at $where")
                // A value is one shared definition, so both places clamp and label it alike.
                if (option is SliderOption) assertSame(own, option, "${option.id} is defined twice at $where")
            }
            for (option in barOptions(editor)) assertTrue(option.place.bar)
            for (option in menuOptions(editor)) assertTrue(option.place.menu)
        }
    }

    @Test fun writesAreClampedToTheRangeTheBarShows() = editor { editor ->
        BRUSH_STRENGTH.apply(editor, 5f)
        assertEquals(1f, editor.strength)
        BRUSH_STRENGTH.apply(editor, 0f)
        assertEquals(0.01f, editor.strength)
        BRUSH_HARDNESS.apply(editor, 1f)
        assertEquals(0.95f, editor.hardness)
        BRUSH_RADIUS.apply(editor, 0f)
        assertEquals(1f, editor.radius)
        BRUSH_RADIUS.apply(editor, 1e9f)
        assertEquals(editor.brushSizeLimit, editor.radius)
        KNIFE_SNAP.apply(editor, 12.6f)
        assertEquals(13f, editor.knifeSnapRadius)
        PAINT_TOLERANCE.apply(editor, 999f)
        assertEquals(255, editor.paintTolerance)
        SKELETON_WEIGHT_STRENGTH.apply(editor, -1f)
        assertEquals(0.01f, editor.skeletonWeightStrength)
    }

    @Test fun brushKeysStepTheSizeOfTheToolInHand() = editor { editor ->
        editor.hierarchyMode = EditHierarchyMode.DEFORM
        editor.tool = CanvasTool.BRUSH
        val radius = editor.radius
        assertTrue(editor.stepOption(OptionRole.SIZE, up = true))
        assertEquals(radius * SliderOption.SIZE_KEY_FACTOR, editor.radius, 1e-3f)

        // The shape tool keeps a line width of its own: stepping it leaves the brush tip alone.
        editor.hierarchyMode = EditHierarchyMode.PAINT
        editor.tool = CanvasTool.PAINT_SHAPE
        val brush = editor.paintBrushSize
        val shape = editor.paintShapeSize
        assertTrue(editor.stepOption(OptionRole.SIZE, up = false))
        assertEquals(shape / SliderOption.SIZE_KEY_FACTOR, editor.paintShapeSize, 1e-3f)
        assertEquals(brush, editor.paintBrushSize)
        assertTrue(editor.stepOption(OptionRole.OPACITY, up = false))

        // Object mode has no brush, so the keys are left to whatever else wants them.
        editor.hierarchyMode = EditHierarchyMode.SELECT
        editor.tool = CanvasTool.SELECT
        assertFalse(editor.stepOption(OptionRole.SIZE, up = true))
    }

    @Test fun numberKeysPickTheChoicesOfTheModeOrToolInHand() = editor { editor ->
        // Deform keys levels only on a warp; on a mesh the keys reach the brush tip.
        editor.hierarchyMode = EditHierarchyMode.DEFORM
        editor.tool = CanvasTool.BRUSH
        assertFalse(editor.deformLevelsShown())
        val level = editor.editLevel
        assertTrue(editor.pickVariant(3))
        assertEquals(BrushShape.RECTANGLE, editor.brushShape)
        assertEquals(level, editor.editLevel)
        assertFalse(editor.pickVariant(4))

        // Simulate's keys pick the group kind; the weight brush shares the deform brush's tip.
        editor.hierarchyMode = EditHierarchyMode.SIMULATE
        editor.tool = CanvasTool.WEIGHT_PAINT
        assertTrue(editor.pickVariant(2))
        assertEquals(io.github.psd2live.ui.PAINTED_GROUP_KINDS[1], editor.weightGroupKind)
        editor.brushShape = BrushShape.LINE
        assertTrue(toolOptions(editor).any { it === BRUSH_ANGLE }, "a turned tip has its angle")

        editor.hierarchyMode = EditHierarchyMode.EDIT
        assertTrue(editor.pickVariant(2))
        assertEquals(BrushShape.LINE, editor.brushShape)
        editor.tool = CanvasTool.SELECT
        assertTrue(editor.pickVariant(3))
        assertEquals(2, editor.elementMode)

        editor.hierarchyMode = EditHierarchyMode.SKELETON
        editor.tool = CanvasTool.SKELETON_EDIT
        assertTrue(editor.pickVariant(5))
        assertEquals(SkeletonEditSubTool.WEIGHTS, editor.skeletonEditSubTool)

        editor.hierarchyMode = EditHierarchyMode.SELECT
        editor.tool = CanvasTool.SELECT
        assertFalse(editor.pickVariant(1))
    }

    @Test fun everyEditingToolHasAContextMenu() = editor { editor ->
        for ((mode, tool) in listOf(
            EditHierarchyMode.SELECT to CanvasTool.TRANSFORM,
            EditHierarchyMode.DEFORM to CanvasTool.TRANSFORM,
            EditHierarchyMode.SIMULATE to CanvasTool.WEIGHT_GRADIENT,
            EditHierarchyMode.SKELETON to CanvasTool.SKELETON_POSE,
            EditHierarchyMode.SKELETON to CanvasTool.SKELETON_EDIT,
            EditHierarchyMode.PAINT to CanvasTool.PAINT_EYEDROPPER,
        )) {
            editor.hierarchyMode = mode
            editor.tool = tool
            assertTrue(canvasContextMenuHasContent(editor), "$mode/$tool has no context menu")
        }
    }

    @Test fun toolbarRowsListEachToolOnceAndCreateOnlyInObjectMode() = editor { editor ->
        for (mode in EditHierarchyMode.entries) {
            val palette = toolbarGroups(mode).flatten()
            assertEquals(palette.size, palette.toSet().size, "$mode lists a tool twice")
            assertTrue(palette.none { it in CREATE_GROUP_TOOLS }, "$mode lists a create tool in its palette")
            val rows = toolbarRows(mode).flatten()
            assertTrue(rows.all { it in TOOLBAR_TOOL_ORDER }, "$mode shows a tool the toolbar does not draw")
            assertEquals(mode == EditHierarchyMode.SELECT, CREATE_GROUP_TOOLS.all { it in rows }, "$mode create tools")
        }
        // Every tool that is not a create tool is reached from the palette of the mode it switches to.
        for (tool in CanvasTool.entries - CREATION_TOOLS) {
            assertTrue(tool in toolbarGroups(editor.modeForTool(tool)).flatten(), "$tool is in no palette of its mode")
        }
        assertEquals(setOf(EditHierarchyMode.SELECT), EditHierarchyMode.entries.filter(::createGroupOffered).toSet())
    }

    @Test fun theMenuFoldsGroupsOnlyWhenItRunsLong() = editor { editor ->
        // Deform's selection tools hold one short group: it stays open.
        editor.hierarchyMode = EditHierarchyMode.DEFORM
        editor.tool = CanvasTool.SELECT
        assertEquals(emptySet(), foldedGroups(editor, menuOptions(editor)))
        // Edit's add topology: the larger group folds and the rest fits.
        editor.hierarchyMode = EditHierarchyMode.EDIT
        assertEquals(setOf("topology"), foldedGroups(editor, menuOptions(editor)))
        // A brush's eight falloffs fold rather than lengthen the menu.
        editor.hierarchyMode = EditHierarchyMode.DEFORM
        editor.tool = CanvasTool.BRUSH
        assertEquals(setOf(BRUSH_FALLOFF.id), foldedGroups(editor, menuOptions(editor)))
    }

    @Test fun everyActionHasAnIconAndVariantsStayOnTheToolbar() = editor { editor ->
        everyState(editor) { where ->
            for (action in toolOptions(editor).filterIsInstance<ActionOption>()) assertNotNull(action.icon, "${action.id} has no icon at $where")
            // A tool's variants are rows under it on the toolbar; the bar keeps to the values that tune it.
            assertTrue(barOptions(editor).none { it is ChoiceOption<*> && it.variant }, "a variant in the bar at $where")
        }
        editor.hierarchyMode = EditHierarchyMode.SKELETON
        editor.tool = CanvasTool.SKELETON_POSE
        assertTrue(toolOptions(editor).none { it is ToggleOption }, "skin weights are a display toggle, not a tool option")
    }
}
