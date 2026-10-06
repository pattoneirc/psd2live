// glTF target (MIT): meshes with morph targets per parameter key, clips as weight animations.
// Must not depend on any GPL module.
plugins {
	kotlin("jvm")
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
	description = "Runs the glTF target tests."
	dependsOn(tasks.test)
}
