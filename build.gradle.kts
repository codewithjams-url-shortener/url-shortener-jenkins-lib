plugins {
	groovy
	idea
}

group = "io.url-shortener"
version = "0.1.0"

repositories {
	mavenCentral()
	// JenkinsPipelineUnit stopped publishing to Maven Central after 1.1 (2017); newer releases only
	// exist as GitHub tags, consumed here via JitPack.
	maven("https://jitpack.io")
	// JenkinsPipelineUnit depends on Jenkins' own groovy-cps, hosted here rather than Central.
	maven("https://repo.jenkins-ci.org/public/")
}

dependencies {
	// Match the Groovy version JenkinsPipelineUnit v1.20 was actually compiled against (declared as
	// a runtime-only dependency in its own POM) - a newer Groovy causes MOP/dispatch incompatibilities.
	testImplementation("org.codehaus.groovy:groovy-all:2.4.21")
	testImplementation("com.github.jenkinsci:JenkinsPipelineUnit:v1.20")
	testImplementation("junit:junit:4.13.2")
	testImplementation("org.assertj:assertj-core:3.25.3")
}

// vars/*.groovy is Jenkins Shared Library convention, not a Gradle source set - it's not statically
// compiled against a normal classpath (pipeline/stage/sh/etc. only resolve inside a live Jenkins CPS
// interpreter). JenkinsPipelineUnit instead parses and executes it dynamically at test runtime, with
// mocked pipeline steps standing in for the real ones. Declared as an explicit task input so Gradle's
// up-to-date check actually reruns tests when a step's script changes - it's invisible to Gradle
// otherwise, since tests only read it via loadScript(...) at runtime, not through the classpath.
tasks.test {
	useJUnit()
	inputs.dir("vars")
}

// vars/ isn't a Gradle source set (see above - it must not be statically compiled), so IntelliJ's
// Gradle-derived project model doesn't know it's Groovy and won't attach the Groovy SDK there,
// surfacing as "Groovy SDK is not configured for module" when opening a file under it. Declaring it
// here as an extra IDE-only source directory (via the `idea` plugin, read during IntelliJ's Gradle
// sync) fixes that without telling Gradle itself to compile it.
idea {
	module {
		sourceDirs = sourceDirs + file("vars")
	}
}
