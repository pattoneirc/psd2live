package io.github.psd2live.targets.cubism

import io.github.psd2live.format.model.*
import org.umamo.format.cmo3.caff.CaffArchive
import org.umamo.format.cmo3.caff.CaffCodec
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.*

class Can3Test {
	private val clip = Clip("wave", "Wave", "Idle", "wave", 1f, 30f, true, fadeIn = 0.5f,
		curves = listOf(
			Curve("A", 0f, 0f, listOf(CurveSegment.Bezier(0.1f, 0f, 0.2f, 10f, 0.3f, 10f), CurveSegment.Stepped(0.35f, -5f)), fadeOut = 0.75f),
			Curve("gone", 0f, 0f, emptyList()),
		),
		events = listOf(ClipEvent(0.5f, "wave <&>"), ClipEvent(0f, "start")),
		targetCurves = listOf(
			TargetCurve(CurveTarget.PartOpacity("PartArm"), 0f, 1f, listOf(CurveSegment.Linear(1f, 0f))),
			TargetCurve(CurveTarget.ModelOpacity, 0f, 1f, listOf(CurveSegment.Linear(0.5f, 0.5f))),
			TargetCurve(CurveTarget.EyeBlink, 0f, 1f, emptyList()),
		))
	private val ir = RigIR(Canvas(800f, 600f), listOf(Parameter("A", "Angle", -1f, 1f, 0f)),
		parts = listOf(Part("PartArm", "Arm", emptyList())), clips = listOf(clip, clip.copy(id = "idle", file = "idle", curves = emptyList(), targetCurves = emptyList())))

	private fun document(bytes: ByteArray): Element {
		val archive = CaffCodec.read(bytes)
		assertEquals(0x42, archive.obfuscateKey)
		val xml = assertNotNull(archive.firstByTag(CaffArchive.TAG_MAIN_XML)).content
		return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.inputStream()).documentElement
	}

	private fun Element.all(): List<Element> = buildList {
		add(this@all)
		val children = childNodes
		for (index in 0 until children.length) (children.item(index) as? Element)?.let { addAll(it.all()) }
	}

	private fun Element.child(name: String): Element = all().drop(1).first { it.getAttribute("xs.n") == name }

	@Test fun everyReferenceResolvesAndTheModelIsLinkedByName() {
		val root = document(assertNotNull(Can3.write(ir, "rig", mapOf("PartArm" to "11111111-2222-4333-8444-555555555555"))))
		val elements = root.all()
		val ids = elements.filter { it.hasAttribute("xs.id") }.associateBy { it.getAttribute("xs.id") }
		assertTrue(elements.filter { it.hasAttribute("xs.ref") }.all { it.getAttribute("xs.ref") in ids })
		assertEquals(ids.size, ids.values.map { it.getAttribute("xs.idx") }.toSet().size)
		assertEquals("rig.cmo3", elements.single { it.getAttribute("xs.n") == "srcFile" }.textContent)
		assertEquals("rig.can3", elements.single { it.tagName == "file" && it.getAttribute("xs.n") == "file" }.textContent)
		// The clip without curves has no scene.
		assertEquals(listOf("rig.wave"), elements.filter { it.getAttribute("xs.n") == "sceneName" }.map { it.textContent })
		val movie = elements.single { it.tagName == "CMvMovieInfo" }
		assertEquals("30", movie.child("duration").textContent)
		assertEquals("500", movie.child("fadeInMSec").textContent)
		assertEquals("true", movie.child("isLoopMotion").textContent)
	}

	@Test fun curvesBecomeKeysOnFramesWithTheirHandles() {
		val root = document(Can3.write(ir, "rig", emptyMap())!!)
		val attrs = root.all().filter { it.tagName == "CMvAttrF" && it.hasAttribute("xs.id") }
		fun attr(idstr: String) = attrs.single { a -> a.all().any { it.tagName == "CAttrId" && it.getAttribute("xs.n") == "id" && it.getAttribute("idstr") == idstr } }

		val angle = attr("live2dParam_A")
		assertEquals("Angle", angle.child("name").textContent)
		assertEquals("0 9 11", angle.child("keyPts2").textContent)
		assertEquals(listOf("BEZIER", "STEP", "LINEAR"), angle.all().filter { it.tagName == "CCurveType" }.map { it.getAttribute("v") })
		val points = angle.all().filter { it.tagName == "CBezierPt" }
		assertEquals("3.0", points[0].child("next").child("posF").textContent)
		assertEquals("6.0", points[1].child("prev").child("posF").textContent)
		assertEquals("10.0", points[1].child("prev").child("doubleValue").textContent)
		// The range widens to the keys; the curve's fade and parameter id are its options.
		assertEquals("-5.0", angle.child("rangeMin").textContent)
		assertEquals("10.0", angle.child("rangeMax").textContent)
		val options = angle.child("optionParam").all().drop(1).associate { it.getAttribute("xs.n") to it.textContent }
		assertEquals(mapOf("KEY_ATTR_FADE_OUT" to "750", "KEY_PARAM_ID" to "live2dParam:A"), options)

		assertEquals("0 15", attr("opacity").child("keyPts2").textContent)
		val part = attr("live2DPartsOpacity_PartArm")
		assertEquals("PartArm", part.child("optionParam").child("KEY_PARTS_VISIBLE_ID").textContent)
		val partsEffect = root.all().single { it.tagName == "CMvEffect_Live2DPartsVisible" && it.hasAttribute("xs.id") }
		assertEquals(listOf("live2DPartsOpacity_PARTS_ARM"), partsEffect.child("attrMap").all().filter { it.tagName == "CAttrId" }.map { it.getAttribute("idstr") })

		val userData = root.all().filter { it.getAttribute("xs.n") == "userData" }.single { it.getAttribute("count") == "2" }
		assertEquals(listOf("0" to "start", "15" to "wave <&>"), userData.all().filter { it.tagName == "entry" }.map { it.child("key").textContent to it.child("value").textContent })
	}

	@Test fun thePartGuidIsTheCmo3s() {
		val root = document(Can3.write(ir, "rig", mapOf("PartArm" to "11111111-2222-4333-8444-555555555555"))!!)
		assertEquals("11111111-2222-4333-8444-555555555555", root.all().single { it.tagName == "CPartGuid" }.getAttribute("uuid"))
	}

	@Test fun lossesNameWhatTheAnimatorCannotHold() {
		assertEquals(listOf("wave" to "Curve on unknown parameter gone is not written", "wave" to "EyeBlink curve has no Animator track and is not written"),
			Can3.losses(ir).map { it.objectId to it.note })
		assertNull(Can3.write(ir.copy(clips = emptyList()), "rig", emptyMap()))
	}

	@Test fun numbersPartKeysAndKeysFollowTheAnimator() {
		assertEquals(listOf("30.0", "-30.0", "0.1", "81.666667", "NaN", "-1.7976931348623157E308"),
			listOf(30.0, -30.0, 0.1f.toDouble(), 245.0 / 3, Double.NaN, -Double.MAX_VALUE).map(Can3::number))
		assertEquals(listOf("PARTS_ARM", "PARTS_ARM_L_2", "HAIR_FRONT", "PARTS_01_HTML_VIEW", "PARTSX"),
			listOf("PartArm", "Part_Arm_L2", "hairFront", "PARTS_01HTMLView", "partsx").map(Can3::partKey))
		// 0.35 s at 30 fps rounds to frame 11 as the motion3 decimal does, and a second key on a frame with the same value goes.
		val keys = Can3.keys(0f, 1f, listOf(CurveSegment.Linear(0.35f, 2f), CurveSegment.Linear(0.36f, 2f)), 30.0)
		assertEquals(listOf(0, 11), keys.map { it.frame })
		assertEquals(listOf(11.0 / 3, 11.0), keys.map { it.nextFrame })
	}
}
