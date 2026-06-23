import tasks.ReportGenerateTask
import tasks.DexPluginTask

plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

group = "org.usagi"
version = "1.0.0"

ksp {
    arg("summaryOutputDir", "${project.projectDir}/.github")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
        freeCompilerArgs.addAll(listOf(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=kotlin.contracts.ExperimentalContracts",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=org.koitharu.kotatsu.parsers.InternalParsersApi",
        ))
    }
}

kotlin {
    jvmToolchain(11)
}

tasks.withType<Test> {
    useJUnitPlatform()
}

dependencies {
    api(libs.jsoup)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.okio)
    implementation(libs.json)
    implementation(libs.gson)
    implementation(libs.androidx.collection)

    implementation(libs.core.parsers)

    ksp(project(":plugins-ksp"))

    testImplementation(libs.bundles.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.quickjs)
}

tasks.register<ReportGenerateTask>("generateTestsReport")

tasks.register<DexPluginTask>("dexJar") {
    inputJar.set(tasks.jar.flatMap { it.archiveFile })
    outputJar.set(layout.buildDirectory.file("libs/plugins.jar"))
}

tasks.register("buildJar") {
    dependsOn("jar", "dexJar")
}
