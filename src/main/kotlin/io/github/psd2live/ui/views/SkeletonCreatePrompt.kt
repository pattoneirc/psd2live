package io.github.psd2live.ui.views

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.CanvasEditor
import io.github.psd2live.ui.components.CompactButton
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import io.github.psd2live.ui.theme.frostedGlass

/** Skeleton mode on a project without an armature: the canvas offers to create one rather than proposing it unasked. */
@Composable
internal fun SkeletonCreatePrompt(editor: CanvasEditor, modifier: Modifier = Modifier) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	Column(
		modifier = modifier
			.widthIn(max = 280.dp)
			.frostedGlass(shape = RoundedCornerShape(8.dp), isHovered = false, elevation = 4.dp, alpha = 0.86f)
			.padding(horizontal = 16.dp, vertical = 12.dp),
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.spacedBy(10.dp),
	) {
		Text(tr("skeleton.tree.emptyHint"), style = typography.caption.copy(fontSize = 12.sp),
			color = colors.textMuted, textAlign = TextAlign.Center)
		CompactButton(
			text = tr("skeleton.tree.create"),
			onClick = { editor.createSkeleton() },
			enabled = !editor.busy && !editor.skeletonCreating,
			isPrimary = true,
		)
	}
}
