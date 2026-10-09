package io.github.psd2live.core

import io.github.psd2live.core.SkeletonCharacterFixture.layer
import io.github.psd2live.format.compile.ExportOptions
import io.github.psd2live.format.compile.OutputSink
import io.github.psd2live.format.model.CurveTarget
import io.github.psd2live.format.model.TargetCurve
import io.github.psd2live.format.model.CurveSegment
import io.github.psd2live.targets.cubism.Cmo3Target
import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.caff.CaffArchive
import org.umamo.format.cmo3.caff.CaffCodec
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.interop.cmo3.Cmo3GraphIndex
import org.umamo.interop.cmo3.Cmo3Import
import kotlin.test.*

class Can3ExportTest {
	@Test fun theCmo3TargetWritesTheClipsToAnAnimatorProjectOnItsParts() {
		val source = SkeletonCharacterFixture.source(layer("face", 2, intArrayOf(170, 30, 250, 110)), layer("top", 1, intArrayOf(160, 115, 260, 240)))
		val config = SkeletonCharacterFixture.config(mapOf(
			"face" to LayerClassificationOverride(tag = SemanticTag.FACE), "top" to LayerClassificationOverride(tag = SemanticTag.TOPWEAR)))
		val compiled = RigIrCompiler.compile(PSD2LivePipeline().buildPreview(source, config), tileArt = true)
		assertTrue(compiled.clips.isNotEmpty())
		// One clip fades a part out, which the can3 keys by the cmo3's part GUID.
		val part = compiled.parts.first().id
		val ir = compiled.copy(clips = compiled.clips.mapIndexed { index, clip ->
			if (index == 0) clip.copy(targetCurves = listOf(TargetCurve(CurveTarget.PartOpacity(part), 0f, 1f, listOf(CurveSegment.Linear(clip.duration, 0f))))) else clip
		})
		val files = LinkedHashMap<String, ByteArray>()
		Cmo3Target().plan(ir, ExportOptions("rig")).write(OutputSink { path, bytes -> files[path] = bytes })
		assertEquals(listOf("rig.cmo3", "rig.can3"), files.keys.toList())

		val animation = CaffCodec.read(files.getValue("rig.can3")).firstByTag(CaffArchive.TAG_MAIN_XML)!!.content.decodeToString()
		assertTrue("<file xs.n=\"srcFile\">rig.cmo3</file>" in animation)
		for (clip in ir.clips) assertTrue("<s xs.n=\"sceneName\">rig.${clip.file}</s>" in animation, clip.file)
		val guid = Cmo3Import.uuidOf(Cmo3GraphIndex(Cmo3.read(files.getValue("rig.cmo3")).root as CModelSource).partByIdStr.getValue(part).guid)
		assertTrue("<CPartGuid xs.n=\"guid\" note=\"$part CPartGuid\" uuid=\"$guid\"/>" in animation)

		// Without clips there is no can3.
		val plain = LinkedHashMap<String, ByteArray>()
		Cmo3Target().plan(ir, ExportOptions("rig", settings = mapOf("clips" to "false"))).write(OutputSink { path, bytes -> plain[path] = bytes })
		assertEquals(listOf("rig.cmo3"), plain.keys.toList())
	}
}
