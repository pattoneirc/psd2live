// Engine ported from Umamo (GPL-3): model, keyform evaluation, edit primitives, PSD/PNG/CMO3/MOC3
// formats and their interop. It must not depend on the PSD2Live product code.
plugins {
	kotlin("jvm")
	kotlin("plugin.serialization")
}

kotlin {
	jvmToolchain(21)
	compilerOptions {
		freeCompilerArgs.add("-Xno-optimize")
	}
}

dependencies {
	implementation(kotlin("reflect"))
	implementation("org.jdom:jdom:1.1.3")
	implementation("com.squareup.okio:okio:3.17.0")
	implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
	api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
	api(platform("org.lwjgl:lwjgl-bom:3.4.2"))
	api("org.lwjgl:lwjgl")
	api("org.lwjgl:lwjgl-opengl")
	testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach {
	useJUnitPlatform()
	maxHeapSize = "2g"
	// `--tests` names one class from either module; the other module simply has nothing to run.
	filter.isFailOnNoMatchingTests = false
	testLogging {
		events(org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED)
		exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
		showStackTraces = true
	}
}

// Every engine test is quick.
tasks.register("quickTest") {
	group = "verification"
	description = "Runs the engine tests."
	dependsOn(tasks.test)
}
