// PSD2Live runtime bindings (MIT): the Rust runtime (runtime/) through JNA, as a GeometryEvaluator and a
// player. Must not depend on any GPL module.
plugins {
	kotlin("jvm")
}

kotlin {
	jvmToolchain(21)
	explicitApi()
}

dependencies {
	api(project(":format-compile"))
	implementation(project(":targets:runtime"))
	implementation("net.java.dev.jna:jna:5.18.0")
	testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach {
	useJUnitPlatform()
	filter.isFailOnNoMatchingTests = false
	// A runtime built with `cargo build --release` in runtime/; tests that need it skip without it.
	systemProperty("psd2live.runtime.dir", rootProject.file("runtime/target/release").absolutePath)
}

tasks.register("quickTest") {
	group = "verification"
	description = "Runs the runtime binding tests."
	dependsOn(tasks.test)
}
