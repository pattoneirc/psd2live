package io.github.psd2live.core

import io.github.psd2live.project.WorkspaceSourceLayer
import kotlinx.serialization.json.*
import org.umamo.format.art.*
import org.umamo.runtime.model.*

/**
 * The `art_primitive` journal record: drawables the document owns outright, with their whole authored state.
 *
 * A split writes one record. Where it replays, the drawables it [supersedes] are removed and its primitives
 * take their place: in the drawable list and the part tree, as masks, and in every Glue that touched them.
 * Each primitive carries its mesh (parent-space positions, triangles and canvas-unit texture coordinates),
 * material and channels, keyforms, blend shapes, paths and vertex groups. Nothing is derived from the
 * superseded drawable, so replay does not depend on regenerating it bit for bit.
 *
 * Texture coordinates are canvas units, the convention of `canvas_mesh_create` and `canvas_mesh_rebuild`:
 * a later paint re-crops the layer, and the same canvas point still names the same pixel. Replay converts them
 * through the current atlas ([RasterMeshJournal.TextureCoordinates]).
 *
 * Record (version 1):
 * - `op`, `v`, `origin` (`split` or `depth`), `texture_source_id`;
 * - `supersedes` (drawable ids) and `supersedes_layers` (their source layers, removed from the source art);
 * - `replace`: superseded drawable id → primitive ids, for its slot in the part tree and, unless `masks` says
 *   otherwise, as a mask of other drawables;
 * - `primitives`: one object per part (see [encodePrimitive]);
 * - `glues`: `replaced` - per Glue touching a superseded drawable, in model order, its replacements;
 *   `appended` - Glues added after all others.
 */
internal object ArtPrimitiveJournal {
	const val OP = "art_primitive"
	const val VERSION = 1

	fun commands(overlay: RigEditOverlay): List<JsonObject> = overlay.authoringJournal.filter { isRecord(it) }

	fun isRecord(command: JsonObject) = command["op"]?.jsonPrimitive?.contentOrNull == OP

	fun primitives(command: JsonObject): List<JsonObject> = command.getValue("primitives").jsonArray.map { it.jsonObject }

	/** Layers whose pixels only document-owned primitives show; the rig generator never builds them. */
	fun ownedLayers(overlay: RigEditOverlay): Set<String> = commands(overlay).flatMapTo(LinkedHashSet()) { command ->
		primitives(command).map { it.getValue("layer_id").jsonPrimitive.content }
	}

	/** Layers a record removed. They exist only before that record's position in the journal. */
	fun supersededLayers(overlay: RigEditOverlay): Set<String> = commands(overlay).flatMapTo(LinkedHashSet()) { command ->
		command.getValue("supersedes_layers").jsonArray.map { it.jsonPrimitive.content }
	}

	/** Superseded layer → the layers that replaced it, following later splits of those layers too. */
	fun replacementLayers(overlay: RigEditOverlay): Map<String, List<String>> {
		val direct = LinkedHashMap<String, List<String>>()
		for (command in commands(overlay)) {
			val layers = primitives(command).map { it.getValue("layer_id").jsonPrimitive.content }.distinct()
			command.getValue("supersedes_layers").jsonArray.forEach { direct[it.jsonPrimitive.content] = layers }
		}
		fun resolve(id: String, seen: Set<String>): List<String> = direct[id]?.flatMap { next ->
			if (next in seen || next !in direct) listOf(next) else resolve(next, seen + next)
		}?.distinct() ?: listOf(id)
		return direct.keys.associateWith { resolve(it, setOf(it)) }
	}

	/** The same for superseded drawable ids. */
	fun replacementDrawables(overlay: RigEditOverlay): Map<String, List<String>> {
		val direct = LinkedHashMap<String, List<String>>()
		for (command in commands(overlay)) command.getValue("replace").jsonObject.forEach { (id, value) ->
			direct[id] = value.jsonArray.map { it.jsonPrimitive.content }
		}
		fun resolve(id: String, seen: Set<String>): List<String> = direct[id]?.flatMap { next ->
			if (next in seen || next !in direct) listOf(next) else resolve(next, seen + next)
		}?.distinct() ?: listOf(id)
		return direct.keys.associateWith { resolve(it, setOf(it)) }
	}

	/** The canvas rectangle each primitive layer's texture must cover, so its texture coordinates stay inside its tile. */
	fun coverage(overlay: RigEditOverlay): Map<String, LayerBounds> {
		val result = LinkedHashMap<String, LayerBounds>()
		for (command in commands(overlay)) for (primitive in primitives(command)) {
			val layer = primitive.getValue("layer_id").jsonPrimitive.content
			val bounds = RasterMeshCreation.sourceBounds(primitive)
			result[layer] = result[layer]?.let { union(it, bounds) } ?: bounds
		}
		return result
	}

	/**
	 * A transparent stand-in for a superseded layer's texture. Journal entries before the record still address
	 * the superseded drawable's tile; the stand-in keeps them replayable without packing the old pixels.
	 */
	fun placeholder(id: String, name: String, bounds: LayerBounds): SourceLayer = WorkspaceSourceLayer(LayerId(id), name, "",
		SourceLayerKind.Raster, true, 0, bounds, 1f, false, LayerBlend.Normal, ChannelMask.ALL, LayerRaster(1, 1, ByteArray(4)), null, null, false)

	fun placeholder(layer: SourceLayer, bounds: LayerBounds = layer.bounds): SourceLayer = object : SourceLayer by layer {
		override val bounds = bounds
		override val raster = LayerRaster(1, 1, ByteArray(4))
	}

	/** The generation order a superseded layer keeps: the slot its replacements hold in the current source. */
	fun anchorOrder(overlay: RigEditOverlay, id: String, current: Map<String, SourceLayer>): Int? {
		val replacements = replacementLayers(overlay)[id] ?: return null
		return replacements.mapNotNull { current[it]?.order }.maxOrNull()
	}

	/** The analysis without superseded layers: they keep a transparent tile only while their records replay. */
	fun visibleAnalysis(analysis: PipelineAnalysis, overlay: RigEditOverlay): PipelineAnalysis {
		val superseded = supersededLayers(overlay)
		if (superseded.isEmpty()) return analysis
		val current = analysis.source.layers.mapTo(HashSet()) { it.id.raw }
		// Ribbons generated from a superseded mouth stay: their meshes are not superseded, only a sliced ribbon is.
		return analysis.copy(layers = analysis.layers.filterNot { layer ->
			val id = layer.source.id.raw
			id !in current && (id in superseded || layer.source !is MouthLipLayer && superseded.any { id.startsWith("$it:") })
		})
	}

	/** Drops the atlas tiles and art inventory of superseded layers once no drawable samples them. */
	fun pruneAtlas(model: PuppetModel, overlay: RigEditOverlay): PuppetModel {
		val superseded = supersededLayers(overlay)
		if (superseded.isEmpty()) return model
		val used = model.drawables.mapNotNullTo(HashSet()) { it.atlasTileId }
		fun gone(key: String) = superseded.any { key == it || key.startsWith("$it:") }
		val removed = model.atlas.tiles.filter { tile -> tile.id !in used && tile.source?.layerKey?.let(::gone) == true }
		if (removed.isEmpty()) return model
		val keys = removed.mapNotNullTo(HashSet()) { it.source?.let { source -> source.sourceId to source.layerKey } }
		val remaining = model.atlas.tiles - removed.toSet()
		val kept = remaining.mapNotNullTo(HashSet()) { it.source?.let { source -> source.sourceId to source.layerKey } }
		return model.copy(atlas = model.atlas.copy(tiles = remaining), sources = model.sources.map { source ->
			source.copy(layers = source.layers.filterNot { (source.id to it.key) in keys && (source.id to it.key) !in kept })
		})
	}

	/**
	 * One primitive from [drawable] of [model], whose mesh stores canvas-unit texture coordinates in place of uvs.
	 * [sourceBounds] is the canvas rectangle the texture must cover; [neutralBounds] the drawable's neutral extent.
	 */
	fun encodePrimitive(model: PuppetModel, drawable: Drawable, layerId: String, textureSourceId: String,
	                    sourceBounds: LayerBounds, neutralBounds: Bounds): JsonObject {
		val mesh = requireNotNull(drawable.mesh) { "An art primitive needs a mesh" }
		RasterMeshJournal.validateMesh(mesh)
		val owner = model.parts.singleOrNull { OrgChild.Drawable(drawable.id) in it.children }
		val axes = (drawable.geometryGrid?.axes.orEmpty() + drawable.channelGrids.gridsByChannel.values.flatMap { it.axes })
			.map { it.parameterId } + drawable.blendShapes.flatMap { binding -> listOf(binding.parameterId) + binding.limits.map { it.parameterId } }
		return buildJsonObject {
			put("id", drawable.id.raw); put("layer_id", layerId); put("source_id", textureSourceId)
			put("source_bounds", floats(floatArrayOf(sourceBounds.left.toFloat(), sourceBounds.top.toFloat(),
				(sourceBounds.left + sourceBounds.width).toFloat(), (sourceBounds.top + sourceBounds.height).toFloat())))
			put("neutral_bounds", floats(floatArrayOf(neutralBounds.left, neutralBounds.top, neutralBounds.right, neutralBounds.bottom)))
			put("name", drawable.name); put("parent", drawable.parentDeformerId?.raw?.let(::JsonPrimitive) ?: JsonNull)
			put("part", owner?.id?.raw?.let(::JsonPrimitive) ?: JsonNull)
			put("blend", drawable.blendMode.name); put("opacity", drawable.opacity)
			put("order", drawable.drawOrder); put("visible", drawable.isVisible); put("selectable", drawable.isSelectable)
			put("multiply", color(drawable.multiplyColor)); put("screen", color(drawable.screenColor))
			put("invert_mask", drawable.invertMask); put("alpha_blend", drawable.alphaBlendMode.name); put("culling", drawable.culling)
			put("masks", JsonArray(drawable.maskedBy.map { JsonPrimitive(it.raw) })); put("user_data", drawable.userData)
			put("positions", floats(mesh.positions)); put("triangles", JsonArray(mesh.indices.map(::JsonPrimitive)))
			put("canvas_uvs", floats(mesh.uvs))
			put("parameters", JsonArray(axes.distinct().map { axis ->
				val parameter = model.parameters.single { it.id == axis }
				buildJsonObject {
					put("id", axis.raw); put("name", parameter.name); put("min", parameter.min)
					put("max", parameter.max); put("default", parameter.default); put("kind", parameter.kind.name); put("repeat", parameter.repeat)
				}
			}))
			put("geometry", drawable.geometryGrid?.let { grid -> RasterMeshCreation.grid(grid) { floats(it.positionDeltas) } } ?: JsonNull)
			put("channels", GeneratedRigJournalCodec.channels(drawable.channelGrids))
			put("blends", JsonArray(drawable.blendShapes.map { binding -> buildJsonObject {
				put("parameter", binding.parameterId.raw); put("keys", floats(binding.keys)); put("neutral", binding.neutralIndex)
				put("forms", JsonArray(binding.forms.map { form -> form?.let { buildJsonObject {
					put("deltas", floats(it.positionDeltas)); put("order", it.drawOrder); put("opacity", it.opacity)
					put("multiply", color(it.multiplyColor)); put("screen", color(it.screenColor))
				} } ?: JsonNull }))
				put("limits", JsonArray(binding.limits.map { limit -> buildJsonObject {
					put("parameter", limit.parameterId.raw)
					put("points", JsonArray(limit.points.map { point -> buildJsonArray { add(point.value); add(point.weight) } }))
				} }))
			} }))
			put("paths", JsonArray(model.deformPaths.filter { it.drawableId == drawable.id }.map(DeformPathJournal::encode)))
			put("vertex_groups", JsonArray(model.vertexGroups.filter { it.drawableId == drawable.id }.map { group -> buildJsonObject {
				put("name", group.name); put("kind", group.kind.jsonName); put("weights", floats(group.weights))
			} }))
		}
	}

	fun encodeGlue(glue: Glue): JsonObject = buildJsonObject {
		put("id", glue.id?.let(::JsonPrimitive) ?: JsonNull); put("a", glue.meshA.raw); put("b", glue.meshB.raw)
		put("intensity", glue.intensity); put("channels", GeneratedRigJournalCodec.channels(glue.channelGrids))
		put("pairs", JsonArray(glue.pairs.map { pair -> buildJsonArray { add(pair.indexA); add(pair.indexB); add(pair.weightA); add(pair.weightB) } }))
	}

	fun encode(origin: String, textureSourceId: String, supersedes: List<DrawableId>, supersededLayers: List<String>,
	           replace: Map<DrawableId, List<DrawableId>>, primitives: List<JsonObject>,
	           replacedGlues: List<List<Glue>>, appendedGlues: List<Glue>,
	           masks: Map<DrawableId, List<DrawableId>> = replace): JsonObject = buildJsonObject {
		put("op", OP); put("v", VERSION); put("origin", origin); put("texture_source_id", textureSourceId)
		put("supersedes", JsonArray(supersedes.map { JsonPrimitive(it.raw) }))
		put("supersedes_layers", JsonArray(supersededLayers.map(::JsonPrimitive)))
		put("replace", buildJsonObject { replace.forEach { (id, ids) -> put(id.raw, JsonArray(ids.map { JsonPrimitive(it.raw) })) } })
		if (masks != replace) put("masks", buildJsonObject { masks.forEach { (id, ids) -> put(id.raw, JsonArray(ids.map { JsonPrimitive(it.raw) })) } })
		put("primitives", JsonArray(primitives))
		putJsonObject("glues") {
			put("replaced", JsonArray(replacedGlues.map { group -> JsonArray(group.map(::encodeGlue)) }))
			put("appended", JsonArray(appendedGlues.map(::encodeGlue)))
		}
	}

	/**
	 * Replaces the superseded drawables of [command] in [input] by its primitives. A primitive in [skins] - one the
	 * skeleton skinned with the base rig - is placed as skinned, with the welds the bake gave it.
	 */
	fun replay(input: PuppetModel, command: JsonObject, skins: PrimitiveSkins = PrimitiveSkins.None): PuppetModel {
		require(command["v"]?.jsonPrimitive?.intOrNull == VERSION) { "Unsupported art primitive record version" }
		val superseded = command.getValue("supersedes").jsonArray.mapTo(LinkedHashSet()) { DrawableId(it.jsonPrimitive.content) }
		val records = primitives(command)
		val ids = records.map { DrawableId(it.text("id")) }
		require(ids.isNotEmpty() && ids.distinct().size == ids.size && ids.none { it in superseded } &&
			input.drawables.none { it.id in ids }) { "Art primitive IDs must be new and unique" }
		val replace = command.getValue("replace").jsonObject.entries.associate { (id, value) ->
			DrawableId(id) to value.jsonArray.map { DrawableId(it.jsonPrimitive.content) }
		}
		require(replace.keys.all { it in superseded } && replace.values.flatten().all { it in ids }) { "Invalid art primitive replacement" }
		// What masks by a superseded drawable now; its slot in the tree by default.
		val masks = (command["masks"] as? JsonObject)?.entries?.associate { (id, value) ->
			DrawableId(id) to value.jsonArray.map { DrawableId(it.jsonPrimitive.content) }
		} ?: replace
		require(masks.keys.all { it in superseded } && masks.values.flatten().all { it in ids }) { "Invalid art primitive mask replacement" }
		var model = input
		for (record in records) for (element in record.getValue("parameters").jsonArray) {
			val p = element.jsonObject
			if (model.parameters.any { it.id.raw == p.text("id") }) continue
			model = RigStructureEdits.replay(model, listOf(buildJsonObject {
				put("action", "create"); put("kind", "parameter"); put("id", p.getValue("id")); put("name", p.getValue("name"))
				put("min", p.getValue("min")); put("max", p.getValue("max")); put("default", p.getValue("default"))
				put("parameter_kind", p.getValue("kind")); put("repeat", p.getValue("repeat"))
			}))
		}
		val textureSource = command.text("texture_source_id")
		val built = records.map { record ->
			skins.drawables[DrawableId(record.text("id"))]?.let { adopt(model, it, record, textureSource) } ?: decodePrimitive(model, record, textureSource)
		}
		val byId = built.associateBy { it.id }
		fun replacing(id: DrawableId) = replace[id].orEmpty()
		val placed = HashSet<DrawableId>()
		val drawables = model.drawables.flatMap { drawable ->
			if (drawable.id in superseded) replacing(drawable.id).map { byId.getValue(it).also { placed += it.id } }
			else if (drawable.maskedBy.none { it in superseded }) listOf(drawable)
			else listOf(drawable.copy(maskedBy = drawable.maskedBy.flatMap { if (it in superseded) masks[it].orEmpty() else listOf(it) }.distinct()))
		} + built.filterNot { it.id in placed }
		val drawableIds = drawables.mapTo(HashSet()) { it.id }
		built.forEach { drawable -> require(drawable.maskedBy.all { it in drawableIds }) { "Art primitive mask is missing" } }
		val inTree = HashSet<DrawableId>()
		fun children(old: List<OrgChild>) = old.flatMap { child ->
			if (child is OrgChild.Drawable && child.id in superseded) replacing(child.id).map { OrgChild.Drawable(it).also { inTree += it.id } }
			else listOf(child)
		}
		var parts = model.parts.map { it.copy(children = children(it.children)) }
		var roots = children(model.rootChildren)
		for ((index, drawable) in built.withIndex()) {
			if (drawable.id in inTree) continue
			// The superseded drawable was gone already: the primitive joins its recorded part, else the root.
			val part = records[index]["part"]?.jsonPrimitive?.contentOrNull?.let(::PartId)?.takeIf { id -> parts.any { it.id == id } }
			if (part == null) roots = roots + OrgChild.Drawable(drawable.id)
			else parts = parts.map { if (it.id == part) it.copy(children = it.children + OrgChild.Drawable(drawable.id)) else it }
		}
		val glues = command.getValue("glues").jsonObject
		val replaced = glues.getValue("replaced").jsonArray.map { group -> group.jsonArray.map { decodeGlue(model, it.jsonObject) } }
		val appended = glues.getValue("appended").jsonArray.map { decodeGlue(model, it.jsonObject) }
		val touching = model.glues.count { it.meshA in superseded || it.meshB in superseded }
		var next = 0
		val rewired = if (touching == replaced.size) model.glues.flatMap { glue ->
			if (glue.meshA in superseded || glue.meshB in superseded) replaced[next++] else listOf(glue)
		} else model.glues.filterNot { it.meshA in superseded || it.meshB in superseded } + replaced.flatten()
		val counts = drawables.associate { it.id to (it.mesh?.vertexCount ?: 0) }
		// A skinned primitive's welds join it once both its meshes are in place: with the later of the two records.
		val welds = skins.glues.filter { glue -> (glue.meshA in ids || glue.meshB in ids) && glue.meshA in counts && glue.meshB in counts }
		var result = model.copy(drawables = drawables, parts = parts, rootChildren = roots, glues = rewired + appended + welds,
			deformPaths = model.deformPaths.filterNot { it.drawableId in superseded },
			vertexGroups = model.vertexGroups.filterNot { it.drawableId in superseded })
		result.glues.forEach { glue ->
			require(glue.meshA in counts && glue.meshB in counts && glue.pairs.all { it.indexA in 0 until counts.getValue(glue.meshA) &&
				it.indexB in 0 until counts.getValue(glue.meshB) }) { "Art primitive Glue does not match its meshes" }
		}
		for ((index, record) in records.withIndex()) {
			val id = ids[index]
			for (path in record.getValue("paths").jsonArray) {
				val encoded = path.jsonObject
				require(encoded["target"]?.jsonPrimitive?.contentOrNull == "mesh:${id.raw}") { "Art primitive path targets another mesh" }
				result = DeformPathJournal.apply(result, encoded)
			}
			val groups = record.getValue("vertex_groups").jsonArray.map { element ->
				val group = element.jsonObject
				val weights = group.floats("weights")
				require(weights.size == counts.getValue(id)) { "Art primitive vertex group does not match its mesh" }
				VertexGroup(group.text("name"), id, VertexGroupKind.parse(group.text("kind")), weights)
			}
			require(groups.map { it.name }.distinct().size == groups.size) { "Duplicate art primitive vertex group" }
			result = result.copy(vertexGroups = result.vertexGroups + groups)
		}
		return result.withDerivedRenderRoot()
	}

	/**
	 * The primitives of [command] that the skeleton bake of [model] - a base rig, before the journal - can skin
	 * among [ids]: their mesh with canvas-unit texture coordinates and no tile, on their recorded parent. A
	 * primitive whose parent or keyform parameters the base lacks (they come from earlier journal entries), or that
	 * carries paths or vertex groups (the bake would not carry them onto new vertices), is left to its record.
	 */
	fun skinnable(model: PuppetModel, command: JsonObject, ids: Set<String>): List<Drawable> {
		if (command["v"]?.jsonPrimitive?.intOrNull != VERSION) return emptyList()
		return primitives(command).filter { it.text("id") in ids && model.drawables.none { d -> d.id.raw == it.text("id") } &&
			it.getValue("paths").jsonArray.isEmpty() && it.getValue("vertex_groups").jsonArray.isEmpty() }
			.mapNotNull { record ->
				try {
					// Masks name drawables of the replayed rig; the record gives them back when it places the part.
					decodePrimitive(model, record, command.text("texture_source_id"), textured = false).copy(maskedBy = emptyList())
				} catch (_: IllegalArgumentException) {
					null
				}
			}
	}

	/** [skinned], decoded from [record] and skinned with the base rig, textured from [model]'s atlas like a decoded one. */
	private fun adopt(model: PuppetModel, skinned: Drawable, record: JsonObject, textureSource: String): Drawable {
		val layer = record.text("layer_id")
		require(skinned.parentDeformerId == null || model.deformers.any { it.id == skinned.parentDeformerId }) {
			"Art primitive parent is missing: ${skinned.id.raw}"
		}
		val tile = model.atlas.tiles.singleOrNull { it.source?.let { source ->
			source.sourceId.raw == textureSource && source.layerKey == layer } == true }
			?: throw IllegalArgumentException("Art primitive artwork is missing: $layer")
		val placed = skinned.copy(atlasTileId = tile.id, texturePage = tile.placement?.pageIndex ?: -1,
			maskedBy = record.getValue("masks").jsonArray.map { DrawableId(it.jsonPrimitive.content) })
		val mesh = requireNotNull(skinned.mesh)
		return placed.copy(mesh = DrawableMesh(mesh.positions, RasterMeshJournal.TextureCoordinates(model, placed).toUvs(mesh.uvs), mesh.indices))
	}

	/** One primitive of [record]: textured from [model]'s atlas, or with its canvas texture coordinates and no tile. */
	private fun decodePrimitive(model: PuppetModel, record: JsonObject, textureSource: String, textured: Boolean = true): Drawable {
		val id = DrawableId(record.text("id"))
		val layer = record.text("layer_id")
		require(layer.isNotBlank()) { "Art primitive layer is missing" }
		RasterMeshCreation.sourceBounds(record)
		val parent = record["parent"]?.jsonPrimitive?.contentOrNull?.let(::DeformerId)
		require(parent == null || model.deformers.any { it.id == parent }) { "Art primitive parent is missing: ${id.raw}" }
		val tile = if (!textured) null else model.atlas.tiles.singleOrNull { it.source?.let { source ->
			source.sourceId.raw == textureSource && source.layerKey == layer } == true }
			?: throw IllegalArgumentException("Art primitive artwork is missing: $layer")
		val shell = Drawable(id, record.text("name"), parent, BlendMode.valueOf(record.text("blend")),
			record.getValue("masks").jsonArray.map { DrawableId(it.jsonPrimitive.content) }, null, null, atlasTileId = tile?.id,
			texturePage = tile?.placement?.pageIndex ?: -1)
		val positions = record.floats("positions")
		val canvasUvs = record.floats("canvas_uvs")
		require(canvasUvs.size == positions.size && canvasUvs.size % 2 == 0) { "Invalid art primitive texture coordinates" }
		val uvs = if (tile == null) canvasUvs else RasterMeshJournal.TextureCoordinates(model, shell).toUvs(canvasUvs)
		val mesh = DrawableMesh(positions, uvs, record.getValue("triangles").jsonArray.map { it.jsonPrimitive.int }.toIntArray())
		RasterMeshJournal.validateMesh(mesh)
		val geometry = record["geometry"]?.takeIf { it != JsonNull }?.jsonObject?.let { data ->
			RasterMeshCreation.decodeGrid(data, model) { value -> MeshDeltaForm(value.jsonArray.map { it.jsonPrimitive.float }.toFloatArray().also {
				require(it.size == positions.size && it.all(Float::isFinite)) { "Invalid art primitive keyform" }
			}) }
		}
		val blends = record.getValue("blends").jsonArray.map { element ->
			val b = element.jsonObject
			val parameter = ParameterId(b.text("parameter"))
			require(model.parameters.any { it.id == parameter }) { "Art primitive blend parameter is missing" }
			val keys = b.floats("keys"); val neutral = b.getValue("neutral").jsonPrimitive.int
			val forms = b.getValue("forms").jsonArray.map { form -> if (form == JsonNull) null else form.jsonObject.let { f ->
				val deltas = f.floats("deltas")
				require(deltas.size == positions.size) { "Invalid art primitive blend shape" }
				MeshForm(deltas, f.number("order"), f.number("opacity"), decodeColor(f.getValue("multiply")), decodeColor(f.getValue("screen")))
			} }
			require(keys.isNotEmpty() && keys.indices.drop(1).all { keys[it] > keys[it - 1] } && neutral in keys.indices && forms.size == keys.size) {
				"Invalid art primitive blend shape"
			}
			val limits = b.getValue("limits").jsonArray.map { item ->
				val limit = item.jsonObject
				val limitParameter = ParameterId(limit.text("parameter"))
				require(model.parameters.any { it.id == limitParameter }) { "Art primitive blend limit parameter is missing" }
				BlendWeightLimit(limitParameter, limit.getValue("points").jsonArray.map { point ->
					val pair = point.jsonArray.map { it.jsonPrimitive.float }
					require(pair.size == 2 && pair.all(Float::isFinite) && pair[1] in 0f..1f) { "Invalid art primitive blend limit" }
					BlendWeightLimitPoint(pair[0], pair[1])
				})
			}
			BlendShapeBinding(parameter, keys, neutral, forms, limits)
		}
		return shell.copy(mesh = mesh, geometryGrid = geometry,
			channelGrids = GeneratedRigJournalCodec.channels(record.getValue("channels").jsonObject, model), blendShapes = blends,
			drawOrder = record.number("order"), opacity = record.number("opacity"),
			multiplyColor = decodeColor(record.getValue("multiply")), screenColor = decodeColor(record.getValue("screen")),
			isVisible = record.getValue("visible").jsonPrimitive.boolean, isSelectable = record.getValue("selectable").jsonPrimitive.boolean,
			invertMask = record.getValue("invert_mask").jsonPrimitive.boolean, alphaBlendMode = AlphaBlendMode.valueOf(record.text("alpha_blend")),
			culling = record.getValue("culling").jsonPrimitive.boolean, userData = record.text("user_data"))
	}

	private fun decodeGlue(model: PuppetModel, value: JsonObject): Glue = Glue(DrawableId(value.text("a")), DrawableId(value.text("b")),
		value.getValue("pairs").jsonArray.map { element ->
			val row = element.jsonArray
			require(row.size == 4) { "Invalid art primitive Glue pair" }
			GluePair(row[0].jsonPrimitive.int, row[1].jsonPrimitive.int, row[2].jsonPrimitive.float, row[3].jsonPrimitive.float).also {
				require(it.weightA.isFinite() && it.weightB.isFinite()) { "Invalid art primitive Glue weight" }
			}
		}, GeneratedRigJournalCodec.channels(value.getValue("channels").jsonObject, model), value.number("intensity"),
		value["id"]?.jsonPrimitive?.contentOrNull)

	/** The neutral bounds a primitive layer's canvas texture coordinates span. */
	fun canvasBounds(canvas: FloatArray): Bounds {
		var left = Float.POSITIVE_INFINITY; var top = Float.POSITIVE_INFINITY
		var right = Float.NEGATIVE_INFINITY; var bottom = Float.NEGATIVE_INFINITY
		for (index in 0 until canvas.size - 1 step 2) {
			left = minOf(left, canvas[index]); right = maxOf(right, canvas[index])
			top = minOf(top, canvas[index + 1]); bottom = maxOf(bottom, canvas[index + 1])
		}
		return Bounds(left, top, right, bottom)
	}

	/** The integer rectangle covering [layer]'s raster and every canvas texture coordinate in [canvas]. */
	fun coverage(layer: LayerBounds, canvas: FloatArray): LayerBounds {
		val bounds = canvasBounds(canvas)
		val left = kotlin.math.floor(bounds.left.toDouble()).toInt(); val top = kotlin.math.floor(bounds.top.toDouble()).toInt()
		val right = maxOf(left + 1, kotlin.math.ceil(bounds.right.toDouble()).toInt())
		val bottom = maxOf(top + 1, kotlin.math.ceil(bounds.bottom.toDouble()).toInt())
		return union(layer, LayerBounds(left, top, right - left, bottom - top))
	}

	private fun union(a: LayerBounds, b: LayerBounds): LayerBounds {
		val left = minOf(a.left, b.left); val top = minOf(a.top, b.top)
		val right = maxOf(a.left + a.width, b.left + b.width); val bottom = maxOf(a.top + a.height, b.top + b.height)
		return LayerBounds(left, top, right - left, bottom - top)
	}

	private fun floats(values: FloatArray) = JsonArray(values.map(::JsonPrimitive))
	private fun color(value: ColorRgb) = floats(floatArrayOf(value.red, value.green, value.blue))
	private fun decodeColor(value: JsonElement): ColorRgb {
		val numbers = value.jsonArray.map { it.jsonPrimitive.float }
		require(numbers.size == 3 && numbers.all(Float::isFinite)) { "Invalid art primitive color" }
		return ColorRgb(numbers[0], numbers[1], numbers[2])
	}
	private fun JsonObject.text(name: String) = getValue(name).jsonPrimitive.content
	private fun JsonObject.number(name: String) = getValue(name).jsonPrimitive.float.also { require(it.isFinite()) }
	private fun JsonObject.floats(name: String) = getValue(name).jsonArray.map { it.jsonPrimitive.float }.toFloatArray().also { require(it.all(Float::isFinite)) }
}
