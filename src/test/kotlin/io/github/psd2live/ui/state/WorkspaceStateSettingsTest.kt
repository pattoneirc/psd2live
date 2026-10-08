package io.github.psd2live.ui.state

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.serialization.json.*
import kotlin.test.*

/** The editor's settings text ([WorkspaceStateCodec]) is the document's: the same configuration, keys and order. */
class WorkspaceStateSettingsTest {
    @Test fun documentConfigPreservesDesktopGenerationPolicyAndCustomSettings() {
        val cases = listOf(
            PSD2LiveState(),
            PSD2LiveState(meshOnly = true, generatePhysics = true),
            PSD2LiveState(atlasSize = 512, meshFillAlgorithm = MeshFillAlgorithm.ADAPTIVE_QUADTREE,
                meshSuppressBoundaryDiagonals = true, mouthColor = 0x456789,
                drawOrderOverrides = mapOf("mesh" to 123f),
                rigTuning = RigTuning(turnDegrees = 17f), motionBasic = false, motionSkeleton = false),
        )
        for (state in cases) {
            val document = WorkspaceDocument(WorkspaceSourceArt(32, 32, emptyList(), emptyList()), emptyMap(),
                emptySet(), emptyMap(), emptyMap(), state.rigEdits, WorkspaceStateCodec.settings(state))
            assertEquals(state.buildConfig(), document.config(), "Domain settings must reproduce the desktop configuration")
            // A revision hashes the settings text, so the desktop projection must also keep the canonical key order.
            assertEquals(WorkspaceSettingsCodec.encode(state.buildConfig()).keys.toList(), WorkspaceStateCodec.settings(state).keys.toList())
        }
    }

    @Test fun theDesktopProjectionKeepsTextureFields() {
        val codec = WorkspaceStateCodec
        val plain = PSD2LiveState()
        assertFalse(WorkspaceSettingsCodec.ATLAS in codec.settings(plain), "no budget writes no key, keeping the settings text")
        val budget = AtlasBudget(pageSize = 2048, maxPages = 2, padding = 6)
        val settings = codec.settings(plain.copy(atlasBudget = budget))
        assertEquals(budget, codec.decode(settings, plain).atlasBudget)
        // A settings payload without a budget clears one; a payload that is not settings keeps it.
        assertNull(codec.decode(codec.settings(plain), plain.copy(atlasBudget = budget)).atlasBudget)
        assertEquals(budget, codec.decode(buildJsonObject { put("historyZoom", 1f) }, plain.copy(atlasBudget = budget)).atlasBudget)
    }

    @Test fun theDesktopProjectionKeepsTheMeshWrap() {
        val overrides = mapOf("eye" to MeshSettings(wrap = 12f), "body" to MeshSettings())
        val encoded = WorkspaceSettingsCodec.encode(PipelineConfig(meshWrap = 6f, meshOverrides = overrides))
        val text = WorkspaceStateCodec.settings(PSD2LiveState(meshWrap = 6f, meshOverrides = overrides))
        assertEquals(encoded["meshWrap"], text["meshWrap"])
        val restored = WorkspaceStateCodec.decode(text)
        assertEquals(6f, restored.meshWrap)
        assertEquals(12f, restored.meshOverrides.getValue("eye").wrap)
        assertEquals(0f, WorkspaceStateCodec.decode(WorkspaceStateCodec.settings(PSD2LiveState()), PSD2LiveState(meshWrap = 3f)).meshWrap)
    }
}
