package tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.*

abstract class DexPluginTask : DefaultTask() {

    @get:InputFile
    abstract val inputJar: RegularFileProperty

    @get:OutputFile
    abstract val outputJar: RegularFileProperty

    @get:Input
    val d8Path: String = project.findProperty("d8Path") as? String
        ?: "${System.getenv("ANDROID_HOME")}/build-tools/34.0.0/d8"

    @TaskAction
    fun dexJar() {
        project.exec {
            commandLine(
                d8Path,
                "--release",
                "--output", outputJar.get().asFile.absolutePath,
                inputJar.get().asFile.absolutePath
            )
        }
    }
}
