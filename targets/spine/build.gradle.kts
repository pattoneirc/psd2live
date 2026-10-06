// Spine 4.2 export target (MIT): skeleton JSON or binary + atlas with deformation baked from the host's evaluator.
// Must not depend on any GPL module.
plugins {
	kotlin("jvm")
	// Spine pose and binary readers, shared with the root project's fidelity test.
	`java-test-fixtures`
}

kotlin {
	jvmToolchain(21)
	explicitApi()
}

dependencies {
	api(project(":format-compile"))
	testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach {
	useJUnitPlatform()
	filter.isFailOnNoMatchingTests = false
}

tasks.register("quickTest") {
	group = "verification"
	description = "Runs the Spine target tests."
	dependsOn(tasks.test)
}
