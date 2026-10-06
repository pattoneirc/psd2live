// Neutral rig IR shared by every export target. MIT: it must not depend on any GPL module,
// nor contain code copied or adapted from Umamo.
plugins {
	kotlin("jvm")
}

kotlin {
	jvmToolchain(21)
	explicitApi()
}

dependencies {
	testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach {
	useJUnitPlatform()
	filter.isFailOnNoMatchingTests = false
}

tasks.register("quickTest") {
	group = "verification"
	description = "Runs the IR tests."
	dependsOn(tasks.test)
}
