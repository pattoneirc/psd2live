package io.github.psd2live.core

import io.github.psd2live.targets.cubism.Cmo3Physics
import org.umamo.format.cmo3.model.custom.CModelSource

/** Writes editable Cubism physics settings into a fresh CMO3 graph; see [Cmo3Physics]. */
internal object Cmo3PhysicsInjector {
	fun inject(root: CModelSource, rules: List<RigPhysicsEdit>, fps: Int = RigEditOverlay.DEFAULT_PHYSICS_FPS): Int =
		Cmo3Physics.inject(root, rules.map(Physics3Json::group), fps)
}
