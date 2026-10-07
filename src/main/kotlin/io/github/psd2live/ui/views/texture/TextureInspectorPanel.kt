package io.github.psd2live.ui.views.texture

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.application.WorkspaceImageFit
import io.github.psd2live.application.WorkspaceLayerTexture
import io.github.psd2live.i18n.tr
import io.github.psd2live.project.LayerCanvasRect
import io.github.psd2live.ui.components.CompactButton
import io.github.psd2live.ui.components.CompactCheckbox
import io.github.psd2live.ui.components.CompactSectionHeader
import io.github.psd2live.ui.components.CompactSlider
import io.github.psd2live.ui.components.CompactToggleChip
import io.github.psd2live.ui.components.IconChevron
import io.github.psd2live.ui.components.IconLock
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.state.TextureDensity
import io.github.psd2live.ui.state.TextureSnapshot
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import io.github.psd2live.ui.utils.NativeFilePicker

/**
 * The texture workspace's layer panel, the companion of the atlas page. Editing happens on the page - drag a
 * tile to pin it, drag its corner to change its density - and this panel shows what decides the selection's
 * texture: how large it is on the canvas, in its own pixels and on the atlas, and where its effective density
 * lies on the heat scale. It also holds what the page cannot show: the lock, the pixels themselves (replace,
 * upscale) and, folded away, the exact canvas rectangle. Every change is one texture command; a refusal shows
 * its reason on the page.
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
		val ids = remember(state.selectedLayerIds, state.selectedLayerId, snapshot) { textureSelection(state, snapshot) }
		val layers = ids.mapNotNull(snapshot::layer)
		val busy = texture.busy || state.isAnalyzing || state.isGenerating
		if (layers.isEmpty()) {
			Text(tr("texture.inspector.none"), style = typography.body.copy(fontSize = 11.5.sp), color = colors.textMuted)
			AtlasSummary(vm, snapshot, busy)
			return@Column
		}
		val primary = layers.first()
		Text(
			if (layers.size == 1) primary.name else tr("texture.inspector.multiple", layers.size),
			style = typography.body.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
			color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
		)
		if (layers.size == 1) SizeChain(snapshot, primary, texture.densityPreview[primary.layerId])
		DensitySection(vm, snapshot, layers, busy)
		if (layers.size == 1) {
			PixelsSection(state, vm, snapshot, primary, busy)
			PreciseSection(vm, snapshot, primary, busy)
		}
	}
}

/**
 * The three sizes that decide a layer's texture, left to right: its rectangle on the canvas, its raster and its
 * atlas tile, with the effective density marked on the heat scale under them. While the density slider is dragged,
 * [preview] scales the tile to the size it would get.
 */
@Composable
private fun SizeChain(snapshot: TextureSnapshot, layer: WorkspaceLayerTexture, preview: Float? = null) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val factor = preview ?: 1f
	val tile = layer.tile?.let { it.copy(width = Math.round(it.width * factor), height = Math.round(it.height * factor),
		scaleX = it.scaleX * factor, scaleY = it.scaleY * factor) }
	Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
		SizeCard(tr("texture.inspector.canvas"), "%.0f × %.0f".format(layer.canvasRect.width, layer.canvasRect.height), tr("texture.inspector.units"), Modifier.weight(1f))
		Text("→", style = typography.caption, color = colors.textMuted)
		SizeCard(tr("texture.inspector.source"), "${layer.rasterWidth} × ${layer.rasterHeight}", "px", Modifier.weight(1f))
		Text("→", style = typography.caption, color = colors.textMuted)
		SizeCard(tr("texture.inspector.atlas"), tile?.let { "${it.width} × ${it.height}" } ?: "—",
			tile?.let { tr("texture.inspector.pageShort", it.page + 1) } ?: tr("texture.inspector.noTile"), Modifier.weight(1f),
			highlight = tile?.let { heatColor(TextureDensity.heat(snapshot.texelsPerCanvasUnit(it))) })
	}
	if (tile != null) {
		val perUnit = snapshot.texelsPerCanvasUnit(tile)
		Text(tr("texture.inspector.effectiveLine", TextureDensity.format(perUnit), TextureDensity.format(tile.scaleX)),
			style = typography.caption.copy(fontSize = 11.sp), color = colors.textPrimary)
		HeatLegend(Modifier.fillMaxWidth(), marker = TextureDensity.heat(perUnit), showUnit = false)
	}
}

@Composable
private fun SizeCard(label: String, value: String, unit: String, modifier: Modifier, highlight: androidx.compose.ui.graphics.Color? = null) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	Column(
		modifier.border(BorderStroke(1.dp, highlight ?: colors.border), RoundedCornerShape(4.dp))
			.background(colors.controlBackground.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
			.padding(horizontal = 4.dp, vertical = 3.dp),
		horizontalAlignment = Alignment.CenterHorizontally,
	) {
		Text(label, style = typography.caption.copy(fontSize = 9.5.sp), color = colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
		Text(value, style = typography.mono.copy(fontSize = 11.sp), color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
			textAlign = TextAlign.Center)
		Text(unit, style = typography.caption.copy(fontSize = 9.5.sp), color = colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
	}
}

/** The density multiplier as the page's corner handles set it, the lock and the pin. */
@Composable
private fun DensitySection(vm: PSD2LiveViewModel, snapshot: TextureSnapshot, layers: List<WorkspaceLayerTexture>, busy: Boolean) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	CompactSectionHeader(tr("texture.inspector.density"))
	val ids = layers.map { it.layerId }
	val density = layers.first().override.density ?: 1f
	val mixed = layers.any { (it.override.density ?: 1f) != density }
	var draft by remember(density, ids) { mutableStateOf(TextureDensity.log2(density)) }
	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
		CompactSlider(
			value = draft,
			onValueChange = {
				draft = Math.round(it * 4f) / 4f
				// The atlas shows every selected tile at the size the slider's value gives it, before it is committed.
				val next = TextureDensity.snap(TextureDensity.pow2(draft))
				vm.previewTextureDensity(layers.associate { it.layerId to next / (it.override.density ?: 1f) })
			},
			onValueChangeFinished = {
				val next = TextureDensity.snap(TextureDensity.pow2(draft))
				if (mixed || next != density) vm.setTextureDensity(snapshot, ids, next.takeUnless { it == 1f })
				else vm.previewTextureDensity(emptyMap())
			},
			valueRange = TextureDensity.log2(TextureDensity.MIN)..TextureDensity.log2(TextureDensity.MAX),
			enabled = !busy,
			modifier = Modifier.weight(1f),
		)
		Text(if (mixed) tr("texture.inspector.mixed") else multiplier(TextureDensity.pow2(draft)),
			style = typography.mono.copy(fontSize = 11.sp), color = if (mixed) colors.warning else colors.textPrimary,
			modifier = Modifier.width(52.dp), textAlign = TextAlign.End)
	}
	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
		CompactButton("½×", onClick = { vm.scaleTextureDensity(snapshot, ids, 0.5f) }, enabled = !busy, height = 22.dp)
		CompactButton("2×", onClick = { vm.scaleTextureDensity(snapshot, ids, 2f) }, enabled = !busy, height = 22.dp)
		CompactButton(tr("texture.inspector.resetDensity"), onClick = { vm.setTextureDensity(snapshot, ids, null) },
			enabled = !busy && layers.any { it.override.density != null }, height = 22.dp)
		val locked = layers.all { it.override.lock }
		CompactToggleChip(tr("texture.inspector.lock"), locked, { vm.setTextureLock(snapshot, ids, !locked) }, enabled = !busy,
			leadingIcon = { IconLock(locked = locked, tint = if (locked) colors.accent else colors.textMuted) }, showCheckWhenSelected = false,
			tooltip = tr("texture.inspector.lockHint"))
	}
	Text(tr("texture.inspector.pageHint"), style = typography.caption.copy(fontSize = 10.sp), color = colors.textMuted)
}

/** The layer's pixels: replace them at any resolution, or upscale them. */
@Composable
private fun PixelsSection(state: PSD2LiveState, vm: PSD2LiveViewModel, snapshot: TextureSnapshot, layer: WorkspaceLayerTexture, busy: Boolean) {
	val texture = state.textureWorkspace
	CompactSectionHeader(tr("texture.inspector.pixelsSection"))
	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
		for (fit in WorkspaceImageFit.entries) {
			CompactToggleChip(tr("texture.inspector.fit.${fit.name.lowercase()}"), texture.replaceFit == fit,
				{ vm.setTextureReplaceOptions(fit, texture.replaceRebuildMesh) }, showCheckWhenSelected = false)
		}
		CompactCheckbox(texture.replaceRebuildMesh, { vm.setTextureReplaceOptions(texture.replaceFit, it) }, label = tr("texture.inspector.rebuildMesh"))
	}
	Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
		CompactButton(tr("texture.inspector.replace"), onClick = {
			NativeFilePicker.chooseTransparentImages().firstOrNull()?.let { vm.replaceLayerImage(snapshot, layer.layerId, it) }
		}, enabled = !busy, height = 22.dp)
		CompactButton(tr("texture.inspector.upscale"), onClick = vm::openTextureUpscaleDialog, enabled = !busy, height = 22.dp)
	}
	Text(tr("texture.inspector.replaceHint"), style = LocalToolTypography.current.caption.copy(fontSize = 10.sp), color = LocalToolColors.current.textMuted)
}

/** The exact canvas rectangle, folded away: the page and the canvas are where layers are edited. */
@Composable
private fun PreciseSection(vm: PSD2LiveViewModel, snapshot: TextureSnapshot, layer: WorkspaceLayerTexture, busy: Boolean) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	var open by remember { mutableStateOf(false) }
	Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(4.dp)) {
		IconChevron(expanded = open, tint = colors.textMuted)
		Text(tr("texture.inspector.precise"), style = typography.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium), color = colors.textMuted)
	}
	if (!open) return
	val rect = layer.canvasRect
	fun commit(next: LayerCanvasRect) { if (next != rect) vm.setLayerCanvasRect(snapshot, layer.layerId, next) }
	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
		FieldLabel(tr("texture.inspector.position"), 64.dp)
		CommitNumberField(rect.left.toDouble(), { commit(rect.copy(left = it.toFloat())) }, Modifier.width(78.dp), decimals = 1, unit = "x", enabled = !busy)
		CommitNumberField(rect.top.toDouble(), { commit(rect.copy(top = it.toFloat())) }, Modifier.width(78.dp), decimals = 1, unit = "y", enabled = !busy)
	}
	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
		FieldLabel(tr("texture.inspector.size"), 64.dp)
		CommitNumberField(rect.width.toDouble(), { commit(rect.copy(width = it.toFloat())) }, Modifier.width(78.dp), min = 0.1, decimals = 1, unit = "w", enabled = !busy)
		CommitNumberField(rect.height.toDouble(), { commit(rect.copy(height = it.toFloat())) }, Modifier.width(78.dp), min = 0.1, decimals = 1, unit = "h", enabled = !busy)
	}
	Text(tr("texture.inspector.canvasHint"), style = typography.caption.copy(fontSize = 10.sp), color = colors.textMuted)
}

/** With nothing selected: the atlas at a glance and its budget. */
@Composable
private fun AtlasSummary(vm: PSD2LiveViewModel, snapshot: TextureSnapshot, busy: Boolean) {
	val atlas = snapshot.atlas
	val shown = atlas.pages.indices.flatMap(snapshot::tiles)
	CompactSectionHeader(tr("texture.inspector.atlas"))
	ValueRow(tr("texture.inspector.pages"), "${atlas.pages.size} / ${atlas.budget.maxPages}  ·  ${atlas.budget.pageSize} px")
	ValueRow(tr("texture.inspector.tiles"), shown.size.toString())
	ValueRow(tr("texture.inspector.fitLabel"), "%.0f%%".format(atlas.fit * 100f))
	ValueRow(tr("texture.inspector.locked"), shown.count { it.locked }.toString())
	ValueRow(tr("texture.inspector.arrangement"), tr(if (atlas.auto) "texture.inspector.arrangement.auto" else "texture.inspector.arrangement.kept"))
	CompactSectionHeader(tr("texture.atlas.budget"))
	AtlasBudgetControls(vm, snapshot, !busy)
}
