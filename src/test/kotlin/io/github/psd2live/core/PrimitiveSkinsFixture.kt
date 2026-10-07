package io.github.psd2live.core

import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.Glue
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel

/**
 * A stand-in for the side channel the resolved base builds (workstream A): parts taken whole from [model] - for an
 * unchanged generator, the generated original carried onto the parts - with their grid axes as generated axes,
 * owned per [owner] (parameter → generator node).
 */
internal object PrimitiveSkinsFixture {
	fun fromParts(model: PuppetModel, ids: List<DrawableId>, stubs: Set<DrawableId> = emptySet(),
	              owner: Map<ParameterId, String> = emptyMap(), welds: List<Glue> = emptyList()): PrimitiveSkins {
		val drawables = ids.associateWith { id -> model.drawables.single { it.id == id } }
		val axes = drawables.mapValues { (_, drawable) -> drawable.geometryGrid?.axes.orEmpty().mapTo(LinkedHashSet()) { it.parameterId } }
		return PrimitiveSkins(drawables, welds, generatedAxes = axes, stubs = stubs,
			ownership = axes.mapValues { (_, set) -> set.mapNotNull { axis -> owner[axis]?.let { axis to it } }.toMap() })
	}
}
