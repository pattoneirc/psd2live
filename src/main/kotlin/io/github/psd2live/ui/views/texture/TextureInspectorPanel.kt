package io.github.psd2live.ui.views.texture

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.application.WorkspaceImageFit
import io.github.psd2live.application.WorkspaceLayerTexture
import io.github.psd2live.i18n.tr
import io.github.psd2live.project.LayerCanvasRect
import io.github.psd2live.ui.components.CompactButton
import io.github.psd2live.ui.components.CompactCheckbox
import io.github.psd2live.ui.components.CompactDropdown
import io.github.psd2live.ui.components.CompactSectionHeader
import io.github.psd2live.ui.components.CompactSlider
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.state.TextureDensity
import io.github.psd2live.ui.state.TextureSnapshot
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import io.github.psd2live.ui.utils.NativeFilePicker

/**
 * The texture workspace's layer panel. For the selected layer it shows the three sizes that decide its texture -
 * the rectangle it covers on the canvas, the pixels of its raster, and the pixels its atlas tile holds - and edits
 * them: the canvas rectangle, the density multiplier and lock (also for a multi-selection), the pin, and the
 * pixels themselves (replace image). Every change is one texture command; a refusal shows its reason here.
 */
@Composable
fun TextureInspectorPanel(state: PSD2LiveState, vm: PSD2LiveViewModel, modifier: Modifier = Modifier) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val snapshot = rememberTextureSnapshot(state, vm)
	val texture = state.textureWorkspace
	Column(modifier.fillMaxSize().background(colors.panelBackground).verticalScroll(rememberScrollState()).padding(8.dp),
		verticalArrangement = Arrangement.spacedBy(6.dp)) {
		if (snapshot == null) {
			Text(tr("texture.atlas.empty"), style = typography.body.copy(fontSize = 11.5.sp), color = colors.textMuted)
			return@Column
		}
		val ids = remember(state.selectedLayerIds, state.selectedLayerId, snapshot) {
			(listOfNotNull(state.selectedLayerId) + state.selectedLayerIds).mapNotNull { snapshot.textureLayerId(it, state.previewModel) }.distinct()
		}
		val layers = ids.mapNotNull(snapshot::layer).filter { !it.deleted }
		val busy = texture.busy || state.isAnalyzing || state.isGenerating
		texture.error?.let { message ->
			Text(message, style = typography.caption.copy(fontSize = 11.sp), color = colors.error)
		}
		if (layers.isEmpty()) {
			Text(tr("texture.inspector.none"), style = typography.body.copy(fontSize = 11.5.sp), color = colors.textMuted)
			AtlasSummary(snapshot)
			return@Column
		}
		val primary = layers.first()
		Text(
			if (layers.size == 1) primary.name else tr("texture.inspector.multiple", layers.size),
			style = typography.body.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
			color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
		)
		if (layers.size == 1) {
			CanvasRectSection(vm, snapshot, primary, busy)
			SourceSection(primary)
		}
		AtlasSection(vm, snapshot, layers, busy)
		if (layers.size == 1) ReplaceSection(state, vm, snapshot, primary, busy)
	}
}

@Composable
private fun CanvasRectSection(vm: PSD2LiveViewModel, snapshot: TextureSnapshot, layer: WorkspaceLayerTexture, busy: Boolean) {
	CompactSectionHeader(tr("texture.inspector.canvas"))
	val rect = layer.canvasRect
	fun commit(next: LayerCanvasRect) { if (next != rect) vm.setLayerCanvasRect(snapshot, layer.layerId, next) }
	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
		FieldLabel(tr("texture.inspector.position"))
		CommitNumberField(rect.left.toDouble(), { commit(rect.copy(left = it.toFloat())) }, Modifier.width(78.dp), decimals = 1, unit = "x", enabled = !busy)
		CommitNumberField(rect.top.toDouble(), { commit(rect.copy(top = it.toFloat())) }, Modifier.width(78.dp), decimals = 1, unit = "y", enabled = !busy)
	}
	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
		FieldLabel(tr("texture.inspector.size"))
		CommitNumberField(rect.width.toDouble(), { commit(rect.copy(width = it.toFloat())) }, Modifier.width(78.dp), min = 0.1, decimals = 1, unit = "w", enabled = !busy)
		CommitNumberField(rect.height.toDouble(), { commit(rect.copy(height = it.toFloat())) }, Modifier.width(78.dp), min = 0.1, decimals = 1, unit = "h", enabled = !busy)
	}
	Text(tr("texture.inspector.canvasHint"), style = LocalToolTypography.current.caption.copy(fontSize = 10.sp), color = LocalToolColors.current.textMuted)
}

@Composable
private fun SourceSection(layer: WorkspaceLayerTexture) {
	CompactSectionHeader(tr("texture.inspector.source"))
	ValueRow(tr("texture.inspector.pixels"), "${layer.rasterWidth} × ${layer.rasterHeight} px")
	ValueRow(tr("texture.inspector.nativeDensity"), tr("texture.inspector.perUnit",
		TextureDensity.format((layer.nativeDensityX + layer.nativeDensityY) / 2f)))
}

@Composable
private fun AtlasSection(vm: PSD2LiveViewModel, snapshot: TextureSnapshot, layers: List<WorkspaceLayerTexture>, busy: Boolean) {
	val colors = LocalToolColors.current
	CompactSectionHeader(tr("texture.inspector.atlas"))
	val primary = layers.first()
	val tile = primary.tile
	if (layers.size == 1) {
		if (tile == null) {
			ValueRow(tr("texture.inspector.tile"), tr("texture.inspector.noTile"))
		} else {
			ValueRow(tr("texture.inspector.tile"), "${tile.width} × ${tile.height} px")
			ValueRow(tr("texture.inspector.placement"), tr("texture.inspector.placementValue", tile.page + 1, tile.x, tile.y))
			val perUnit = snapshot.texelsPerCanvasUnit(tile)
			Row(verticalAlignment = Alignment.CenterVertically) {
				FieldLabel(tr("texture.inspector.effective"))
				Swatch(heatColor(TextureDensity.heat(perUnit)), Modifier.padding(end = 4.dp))
				Text(tr("texture.inspector.perUnit", TextureDensity.format(perUnit)) + "  ·  " +
					tr("texture.inspector.scale", TextureDensity.format(tile.scaleX)),
					style = LocalToolTypography.current.mono.copy(fontSize = 11.sp), color = colors.textPrimary)
			}
		}
	}
	// Density: one value for the selection; a mixed selection shows the first and sets all.
	val density = primary.override.density ?: 1f
	val mixed = layers.any { (it.override.density ?: 1f) != density }
	var draft by remember(density, layers.map { it.layerId }) { mutableStateOf(TextureDensity.log2(density)) }
	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
		FieldLabel(tr("texture.inspector.density"))
		CompactSlider(
			value = draft,
			onValueChange = { draft = (Math.round(it * 4f) / 4f) },
			onValueChangeFinished = {
				val next = TextureDensity.pow2(draft).coerceIn(TextureDensity.MIN, TextureDensity.MAX)
				if (mixed || next != density) vm.setTextureDensity(snapshot, layers.map { it.layerId }, next.takeUnless { it == 1f })
			},
			valueRange = TextureDensity.log2(TextureDensity.MIN)..TextureDensity.log2(TextureDensity.MAX),
			enabled = !busy,
			modifier = Modifier.weight(1f),
		)
		CommitNumberField(density.toDouble(), { value ->
			vm.setTextureDensity(snapshot, layers.map { it.layerId }, value.toFloat().takeUnless { it == 1f })
		}, Modifier.width(76.dp), min = TextureDensity.MIN.toDouble(), max = TextureDensity.MAX.toDouble(), step = 0.25, decimals = 3,
			unit = "×", enabled = !busy)
	}
	if (mixed) Text(tr("texture.inspector.mixedDensity"), style = LocalToolTypography.current.caption.copy(fontSize = 10.sp), color = colors.warning)
	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
		CompactButton(tr("texture.inspector.resetDensity"), onClick = { vm.setTextureDensity(snapshot, layers.map { it.layerId }, null) },
			enabled = !busy && layers.any { it.override.density != null }, height = 22.dp)
		val locked = layers.all { it.override.lock }
		CompactCheckbox(locked, { vm.setTextureLock(snapshot, layers.map { it.layerId }, it) }, enabled = !busy, label = tr("texture.inspector.lock"))
	}
	Text(tr("texture.inspector.densityHint"), style = LocalToolTypography.current.caption.copy(fontSize = 10.sp), color = colors.textMuted)
	if (layers.size == 1 && tile?.pinned == true) {
		Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
			Text(tr("texture.inspector.pinned"), style = LocalToolTypography.current.caption.copy(fontSize = 11.sp), color = colors.warning)
			CompactButton(tr("texture.inspector.unpin"), onClick = { vm.releaseTexturePin(snapshot, primary.layerId) }, enabled = !busy, height = 22.dp)
		}
	} else if (layers.size == 1 && tile != null) {
		Text(tr("texture.inspector.pinHint"), style = LocalToolTypography.current.caption.copy(fontSize = 10.sp), color = colors.textMuted)
	}
}

@Composable
private fun ReplaceSection(state: PSD2LiveState, vm: PSD2LiveViewModel, snapshot: TextureSnapshot, layer: WorkspaceLayerTexture, busy: Boolean) {
	val texture = state.textureWorkspace
	CompactSectionHeader(tr("texture.inspector.pixelsSection"))
	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
		FieldLabel(tr("texture.inspector.fit"))
		CompactDropdown(
			items = WorkspaceImageFit.entries,
			selectedItem = texture.replaceFit,
			onItemSelected = { vm.setTextureReplaceOptions(it, texture.replaceRebuildMesh) },
			itemLabel = { tr("texture.inspector.fit.${it.name.lowercase()}") },
			modifier = Modifier.width(120.dp),
			height = 22.dp,
		)
	}
	CompactCheckbox(texture.replaceRebuildMesh, { vm.setTextureReplaceOptions(texture.replaceFit, it) }, label = tr("texture.inspector.rebuildMesh"))
	Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
		CompactButton(tr("texture.inspector.replace"), onClick = {
			NativeFilePicker.chooseTransparentImages().firstOrNull()?.let { vm.replaceLayerImage(snapshot, layer.layerId, it) }
		}, enabled = !busy, height = 22.dp)
		CompactButton(tr("texture.inspector.upscale"), onClick = vm::openTextureUpscaleDialog, enabled = !busy, height = 22.dp)
	}
	Text(tr("texture.inspector.replaceHint"), style = LocalToolTypography.current.caption.copy(fontSize = 10.sp), color = LocalToolColors.current.textMuted)
}

/** With nothing selected: the atlas at a glance. */
@Composable
private fun AtlasSummary(snapshot: TextureSnapshot) {
	val atlas = snapshot.atlas
	CompactSectionHeader(tr("texture.inspector.atlas"))
	ValueRow(tr("texture.inspector.pages"), "${atlas.pages.size} / ${atlas.budget.maxPages}  ·  ${atlas.budget.pageSize} px")
	ValueRow(tr("texture.inspector.tiles"), atlas.tiles.size.toString())
	ValueRow(tr("texture.inspector.fitLabel"), "%.0f%%".format(atlas.fit * 100f))
	ValueRow(tr("texture.inspector.lockedPinned"), "${atlas.tiles.count { it.locked }} / ${atlas.tiles.count { it.pinned }}")
}
