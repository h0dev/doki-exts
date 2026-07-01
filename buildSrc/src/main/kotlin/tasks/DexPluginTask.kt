package tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.*
import java.io.File

abstract class DexPluginTask : DefaultTask() {

	@get:InputFile
	abstract val inputJar: RegularFileProperty

	@get:OutputFile
	abstract val outputJar: RegularFileProperty

	@get:Input
	val d8Path: String = project.findProperty("d8Path") as? String
		?: findD8()

	private fun findD8(): String {
		val androidHome = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
		requireNotNull(androidHome) {
			"Android SDK not found. Please install Android SDK and set ANDROID_HOME, or provide d8Path Gradle property."
		}
		val buildToolsDir = File(androidHome, "build-tools")
		require(buildToolsDir.exists()) {
			"Android build-tools not found at $buildToolsDir"
		}
		val versionDirs = buildToolsDir.listFiles()?.filter { it.isDirectory }.orEmpty()
		require(versionDirs.isNotEmpty()) {
			"No Android build-tools versions installed at $buildToolsDir"
		}
		val targetDir = versionDirs.maxBy { it.name }
		val d8 = File(targetDir, "d8")
		require(d8.exists()) {
			"d8 compiler not found at ${d8.absolutePath}"
		}
		return d8.absolutePath
	}

	@TaskAction
	fun dexJar() {
		project.exec { spec ->
			spec.executable = d8Path
			spec.args = listOf(
				"--release",
				"--min-api", "21",
				"--output", outputJar.get().asFile.absolutePath,
				inputJar.get().asFile.absolutePath,
			)
		}
	}
}
