package io.github.psd2live.core

import kotlinx.serialization.json.*
import org.umamo.runtime.model.*

/**
 * Generator parameters the user takes into their own edits.
 *
 * A swing's or simulation's parameters exist only once it runs, after the journal replays, so a journal entry cannot
 * name one as it stands. An edit that keys another object on one, renames it or changes its range is recorded after a
 * `structure` create of that parameter as the generator defines it now: from then on the parameter is the document's,
 * the journal replays with it, and the generator takes it as it finds it, as it does any parameter that already
 * exists. Removing the generator leaves the parameter and the user's keys on it - in Cubism terms, a physics output
 * the user keyed is an ordinary parameter.
 *
 * Which parameters the generators add is read off the two rigs themselves: those the shown rig has and the authored
 * rig (the journal's stage) has not.
 */
internal object GeneratedParameterAdoption {
	/** The parameters [shown] has and [authored] lacks, by id: the ones the generators add after the journal. */
	fun generated(shown: PuppetModel, authored: PuppetModel): Map<String, Parameter> {
		val present = authored.parameters.mapTo(HashSet()) { it.id.raw }
		return shown.parameters.filter { it.id.raw !in present }.associateBy { it.id.raw }
	}

	/** [journal] after a create of each generated parameter it names, so it replays on [authored] as it was made on [shown]. */
	fun adopted(shown: PuppetModel, authored: PuppetModel, journal: List<JsonObject>): List<JsonObject> {
		val generated = generated(shown, authored)
		if (generated.isEmpty()) return journal
		val named = journal.flatMapTo(LinkedHashSet(), ::references).filter { it in generated }
		if (named.isEmpty()) return journal
		return listOf(create(shown, authored, named.map(generated::getValue))) + journal
	}

	/** The parameters [command] names where the journal stage must have them; panel moves and links replay after the generators. */
	fun references(command: JsonObject): Set<String> {
		fun keys(field: String, source: JsonObject = command) = (source[field] as? JsonObject)?.keys.orEmpty()
		fun text(field: String) = command[field]?.jsonPrimitive?.contentOrNull
		return when (command["op"]?.jsonPrimitive?.contentOrNull) {
			"canvas_geometry" -> keys("key") + keys("pose")
			RigBezierJournal.OP -> keys("key") + keys("pose") +
				((command["geometry"] as? JsonObject)?.let { keys("key", it) + keys("pose", it) }).orEmpty()
			"set" -> keys("key")
			"copy" -> keys("from") + keys("key")
			"delete", "parameter_keys" -> setOfNotNull(text("parameter"))
			"structure" -> (command["edits"] as? JsonArray).orEmpty().mapNotNullTo(LinkedHashSet()) { element ->
				val edit = element as? JsonObject ?: return@mapNotNullTo null
				edit["id"]?.jsonPrimitive?.contentOrNull?.takeIf {
					edit["kind"]?.jsonPrimitive?.contentOrNull == "parameter" &&
						edit["action"]?.jsonPrimitive?.contentOrNull in setOf("rename", "update", "delete")
				}
			}
			else -> emptySet()
		}
	}

	/** One `structure` entry creating [parameters] as [shown] defines them, each in its folder when [authored] has that folder. */
	private fun create(shown: PuppetModel, authored: PuppetModel, parameters: List<Parameter>): JsonObject = buildJsonObject {
		put("op", "structure")
		putJsonArray("edits") {
			for (parameter in parameters) add(buildJsonObject {
				put("action", "create"); put("kind", "parameter"); put("id", parameter.id.raw)
				put("name", parameter.name); put("min", parameter.min); put("max", parameter.max); put("default", parameter.default)
				put("parameter_kind", parameter.kind.name); put("repeat", parameter.repeat)
				folder(shown.parameterTree, parameter.id)?.takeIf { group(authored.parameterTree, it) }?.let { put("parent_id", it.raw) }
			})
		}
	}

	/** The folder holding [id] in [nodes]; null at the root or when the tree does not hold it. */
	private fun folder(nodes: List<ParameterNode>, id: ParameterId, parent: ParameterGroupId? = null): ParameterGroupId? {
		for (node in nodes) when (node) {
			is ParameterNode.Param -> if (node.id == id) return parent
			is ParameterNode.Group -> folder(node.children, id, node.id)?.let { return it }
		}
		return null
	}

	private fun group(nodes: List<ParameterNode>, id: ParameterGroupId): Boolean =
		nodes.any { it is ParameterNode.Group && (it.id == id || group(it.children, id)) }
}
