package io.github.psd2live.targets.cubism

import io.github.psd2live.format.model.*
import kotlinx.serialization.json.*
import kotlin.test.*

class Cubism3JsonTest {
	private val clip = Clip("c", "Clip", "Idle", "", 1f, 30f, false,
		curves = listOf(Curve("A", 0f, 0f, listOf(CurveSegment.Linear(1f, 1f)), fadeIn = 0.25f), Curve("gone", 0f, 0f, emptyList())),
		events = listOf(ClipEvent(0.5f, "wave \"hi\""), ClipEvent(0.1f, "start")),
		targetCurves = listOf(
			TargetCurve(CurveTarget.PartOpacity("P"), 0f, 1f, listOf(CurveSegment.Stepped(0.5f, 0f))),
			TargetCurve(CurveTarget.ModelOpacity, 0f, 1f, emptyList()),
			TargetCurve(CurveTarget.EyeBlink, 0f, 1f, emptyList(), fadeOut = 0.5f),
			TargetCurve(CurveTarget.LipSync, 0f, 0f, emptyList()),
		))

	@Test fun motionsCarryTheirCurveTargetsFadesAndUserData() {
		val json = Json.parseToJsonElement(Cubism3Json.motion3(clip, setOf("A"))!!).jsonObject
		val meta = json.getValue("Meta").jsonObject
		val curves = json.getValue("Curves").jsonArray.map { it.jsonObject }
		assertEquals(5, meta.getValue("CurveCount").jsonPrimitive.int)
		assertEquals(listOf("Parameter" to "A", "PartOpacity" to "P", "Model" to "Opacity", "Model" to "EyeBlink", "Model" to "LipSync"),
			curves.map { it.getValue("Target").jsonPrimitive.content to it.getValue("Id").jsonPrimitive.content })
		assertEquals(0.25f, curves[0].getValue("FadeInTime").jsonPrimitive.float)
		assertEquals(0.5f, curves[3].getValue("FadeOutTime").jsonPrimitive.float)
		assertNull(curves[1]["FadeInTime"])
		// Events in time order, their text escaped, and counted in the meta as Cubism reads it.
		val events = json.getValue("UserData").jsonArray.map { it.jsonObject }
		assertEquals(listOf("start", "wave \"hi\""), events.map { it.getValue("Value").jsonPrimitive.content })
		assertEquals(2, meta.getValue("UserDataCount").jsonPrimitive.int)
		assertEquals("start".length + "wave \"hi\"".length, meta.getValue("TotalUserDataSize").jsonPrimitive.int)
		// A part the model lacks keeps its curve out.
		val withoutPart = Json.parseToJsonElement(Cubism3Json.motion3(clip, setOf("A"), availableParts = emptySet())!!).jsonObject
		assertEquals(4, withoutPart.getValue("Curves").jsonArray.size)
	}

	@Test fun aPlainMotionKeepsItsFormerLayout() {
		val plain = Clip("p", "Plain", "Idle", "", 1f, 30f, true, curves = listOf(Curve("A", 0f, 0f, emptyList())))
		val text = Cubism3Json.motion3(plain, setOf("A"))!!
		assertFalse("UserData\"" in text.substringAfter("\"Curves\""))
		assertTrue("\"UserDataCount\": 0" in text)
		assertNull(Cubism3Json.motion3(plain, emptySet()))
	}

	@Test fun verticalPhysicsIsWrittenAsY() {
		val group = PhysicsGroup("g", "G", listOf(PhysicsInput("A", 100f, PhysicsSource.Y, false)),
			listOf(PhysicsOutput("B", 1, 1f, 100f, PhysicsSource.ANGLE, false)), listOf(PhysicsSegment(10f, 0.9f, 0.9f, 1f)),
			PhysicsNormalization(-10f, 0f, 10f, -10f, 0f, 10f))
		val json = Json.parseToJsonElement(Cubism3Json.physics3(listOf(group), 0)!!).jsonObject
		val input = json.getValue("PhysicsSettings").jsonArray[0].jsonObject.getValue("Input").jsonArray[0].jsonObject
		assertEquals("Y", input.getValue("Type").jsonPrimitive.content)
	}
}
