package io.github.psd2live.ui.state

import org.umamo.runtime.model.ParameterId
import kotlin.test.Test
import kotlin.test.assertEquals

/** The parameter a canvas deformation binds to follows the parameter panel. */
class ParameterPanelSelectionTest {
    @Test fun panelSelectionIncludesUnchangedValuesAndLinkedInputs() {
        val fresh = ParameterId("NewParameter")
        val old = ParameterId("OldParameter")
        PSD2LiveViewModel().use { vm ->
            vm.setParameterValueFromPanel(fresh, 0f)
            assertEquals(fresh.raw, vm.canvasEditor.parameter)
            vm.setParameterValuesFromPanel(linkedMapOf(old to 1f, fresh to 1f))
            assertEquals(old.raw, vm.canvasEditor.parameter)
            // Explicit picker selection still overrides the panel selection.
            vm.canvasEditor.parameter = fresh.raw
            assertEquals(fresh.raw, vm.canvasEditor.parameter)
        }
    }
}
