package io.github.psd2live.i18n

import io.github.psd2live.ui.CanvasTool
import io.github.psd2live.ui.tooloptions.ActionOption
import io.github.psd2live.ui.tooloptions.ChoiceOption
import io.github.psd2live.ui.tooloptions.SectionOption
import io.github.psd2live.ui.tooloptions.SliderOption
import io.github.psd2live.ui.tooloptions.ToggleOption
import io.github.psd2live.ui.tooloptions.BRUSH_ANGLE
import io.github.psd2live.ui.tooloptions.BRUSH_CONNECTED
import io.github.psd2live.ui.tooloptions.BRUSH_FALLOFF
import io.github.psd2live.ui.tooloptions.BRUSH_HARDNESS
import io.github.psd2live.ui.tooloptions.BRUSH_RADIUS
import io.github.psd2live.ui.tooloptions.BRUSH_STRENGTH
import io.github.psd2live.ui.tooloptions.ELEMENT_MODE
import io.github.psd2live.ui.tooloptions.GLUE_DISTANCE
import io.github.psd2live.ui.tooloptions.INFLATE_DIRECTION
import io.github.psd2live.ui.tooloptions.KNIFE_SNAP
import io.github.psd2live.ui.tooloptions.PAINT_BRUSH_SIZE
import io.github.psd2live.ui.tooloptions.PAINT_FILL
import io.github.psd2live.ui.tooloptions.PAINT_HARDNESS
import io.github.psd2live.ui.tooloptions.PAINT_OPACITY
import io.github.psd2live.ui.tooloptions.PAINT_SHAPE_SIZE
import io.github.psd2live.ui.tooloptions.PAINT_TOLERANCE
import io.github.psd2live.ui.tooloptions.BRUSH_TIP
import io.github.psd2live.ui.tooloptions.GLUE_SUB
import io.github.psd2live.ui.tooloptions.PAINT_SHAPE_KIND
import io.github.psd2live.ui.tooloptions.SKELETON_EDIT_SUB
import io.github.psd2live.ui.tooloptions.SKELETON_POSE_SUB
import io.github.psd2live.ui.tooloptions.WEIGHT_KIND
import io.github.psd2live.ui.tooloptions.SKELETON_WEIGHT_MODE
import io.github.psd2live.ui.tooloptions.SKELETON_WEIGHT_RADIUS
import io.github.psd2live.ui.tooloptions.SKELETON_WEIGHT_STRENGTH
import io.github.psd2live.ui.tooloptions.SKELETON_WEIGHT_VALUE
import io.github.psd2live.ui.tooloptions.WEIGHT_MODE
import io.github.psd2live.ui.state.ShortcutAction
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MessageBundleParityTest {
    private val bundles = listOf("Messages", "Messages_zh_CN", "Messages_ja", "Messages_ko").associateWith { name ->
        Properties().also { props ->
            javaClass.getResourceAsStream("/i18n/$name.properties")!!.reader(Charsets.UTF_8).use(props::load)
        }
    }

    @Test fun everyLocaleHasEveryKeyWithAValue() {
        val reference = bundles.getValue("Messages").stringPropertyNames()
        for ((name, props) in bundles) {
            val keys = props.stringPropertyNames()
            assertEquals(emptySet(), reference - keys, "$name is missing keys")
            assertEquals(emptySet(), keys - reference, "$name has keys the English bundle lacks")
            val blank = keys.filter { props.getProperty(it).isBlank() }
            assertTrue(blank.isEmpty(), "$name has blank values: $blank")
        }
    }

    @Test fun toolsShortcutsAndToolOptionsAreNamed() {
        val keys = mutableListOf<String>()
        keys += CanvasTool.entries.map { "editor.tool.${it.name.lowercase()}" }
        keys += ShortcutAction.entries.map { it.labelKey }
        for (option in listOf(BRUSH_RADIUS, BRUSH_HARDNESS, BRUSH_STRENGTH, BRUSH_ANGLE, BRUSH_FALLOFF, BRUSH_CONNECTED, INFLATE_DIRECTION,
            KNIFE_SNAP, GLUE_DISTANCE, WEIGHT_MODE, ELEMENT_MODE, SKELETON_WEIGHT_MODE, SKELETON_WEIGHT_RADIUS, SKELETON_WEIGHT_STRENGTH,
            SKELETON_WEIGHT_VALUE, BRUSH_TIP, PAINT_SHAPE_KIND, GLUE_SUB, SKELETON_EDIT_SUB, SKELETON_POSE_SUB, WEIGHT_KIND, PAINT_BRUSH_SIZE, PAINT_SHAPE_SIZE, PAINT_HARDNESS, PAINT_OPACITY, PAINT_TOLERANCE,
            PAINT_FILL)) {
            keys += when (option) {
                is SliderOption -> option.labelKey
                is ChoiceOption<*> -> option.labelKey
                is ToggleOption -> option.labelKey
                is ActionOption -> option.labelKey
                is SectionOption -> option.labelKey
                else -> continue
            }
        }
        for ((name, props) in bundles) {
            val missing = keys.filter { props.getProperty(it).isNullOrBlank() }
            assertTrue(missing.isEmpty(), "$name lacks $missing")
        }
    }
}
