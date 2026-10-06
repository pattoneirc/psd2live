// Layered PSD target (GPL-3, uses the engine's PSD writer): one posed layer per mesh, for returning
// a pose to the art workflow.
plugins {
	kotlin("jvm")
}

kotlin {
	jvmToolchain(21)
	compilerOptions {
		freeCompilerArgs.add("-Xno-optimize")
	}
}

dependencies {
	api(project(":format-compile"))
	api(project(":umamo"))
	testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach {
	useJUnitPlatform()
	maxHeapSize = "2g"
	filter.isFailOnNoMatchingTests = false
	testLogging {
		events(org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED)
		exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
	}
}

tasks.register("quickTest") {
	group = "verification"
	description = "Runs the PSD target tests."
	dependsOn(tasks.test)
}
