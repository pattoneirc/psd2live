package io.github.psd2live.core

/**
 * The space the atlas packer may use: square pages of [pageSize] texture pixels, at most [maxPages]
 * of them, with [padding] texture pixels kept clear around every tile.
 *
 * Layers are packed at their raster resolution times their texture density; when that does not fit
 * the budget, unlocked layers are scaled down together by one common fit factor (at most 1).
 * Stored as the optional `atlas` project setting (`WorkspaceSettingsCodec.ATLAS`); without it the budget is the
 * legacy `atlasSize`/`texturePadding` with [DEFAULT_MAX_PAGES]. Not used by the packer yet: today the page size
 * and padding still come from [PipelineConfig].
 */
data class AtlasBudget(
	val pageSize: Int = 4096,
	val maxPages: Int = DEFAULT_MAX_PAGES,
	val padding: Int = 2,
) {
	init {
		require(pageSize > 0) { "Atlas page size must be positive" }
		require(maxPages > 0) { "Atlas page count must be positive" }
		require(padding >= 0) { "Atlas padding must not be negative" }
	}

	companion object {
		const val DEFAULT_MAX_PAGES: Int = 8
	}
}
