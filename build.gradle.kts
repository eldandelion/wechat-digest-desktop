import java.security.MessageDigest
import java.util.concurrent.TimeUnit

plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.3"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    jacoco
}

group = "com.wechatdigest"
version = "0.1.0"

repositories {
    maven("https://maven.aliyun.com/repository/public")
    maven("https://maven.aliyun.com/repository/google")
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

val ktorVersion = "2.3.12"

dependencies {
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")
    implementation("com.github.luben:zstd-jni:1.5.6-8")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    implementation("org.apache.poi:poi:5.3.0")
    implementation("org.apache.poi:poi-ooxml:5.3.0")
    implementation("org.apache.pdfbox:pdfbox:3.0.3")

    // Compose Desktop & Material 3
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    
    // Ktor Client & Serialization
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testImplementation("io.ktor:ktor-client-mock:$ktorVersion")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

compose.desktop {
    application {
        mainClass = "com.wechatdigest.app.MainKt"
        nativeDistributions {
            targetFormats(org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg)
            packageName = "WeChatDigest"
            packageVersion = "2.0.0"
            description = "Local AI-powered WeChat group announcement & event digest for macOS"
            includeAllModules = true
        }
    }
}

kotlin {
    jvmToolchain(21)
}

val generatedVisionResourcesDir = layout.buildDirectory.dir("generated-vision-resources")
val visionOcrSourceFile = layout.projectDirectory.file("src/main/native/vision-ocr/main.swift")
val generatedScriptResourcesDir = layout.buildDirectory.dir("generated-script-resources")
val keyExtractorScriptFile = layout.projectDirectory.file("src/main/resources/scripts/wcdb_key_extractor.py")

val compileVisionOcrHelper by tasks.registering {
    description = "Compiles the bundled macOS Swift Apple Vision CLI helper."
    group = "build"
    onlyIf {
        System.getProperty("os.name").lowercase().contains("mac")
    }
    inputs.file(visionOcrSourceFile)
    outputs.dir(generatedVisionResourcesDir)
    doLast {
        val outDir = generatedVisionResourcesDir.get().file("native/darwin").asFile
        outDir.mkdirs()
        val binaryFile = File(outDir, "vision-ocr")
        val xcrunFile = File("/usr/bin/xcrun")
        if (!xcrunFile.canExecute()) {
            throw GradleException("Missing pinned macOS toolchain /usr/bin/xcrun required to compile vision-ocr.")
        }
        val proc = ProcessBuilder(
            xcrunFile.absolutePath,
            "swiftc",
            "-O",
            "-framework", "Vision",
            "-framework", "ImageIO",
            "-framework", "CoreGraphics",
            visionOcrSourceFile.asFile.absolutePath,
            "-o", binaryFile.absolutePath
        ).redirectErrorStream(true).start()
        proc.outputStream.close()
        val output = proc.inputStream.bufferedReader().use { it.readText() }
        val finished = proc.waitFor(120, TimeUnit.SECONDS)
        if (!finished) {
            proc.destroyForcibly()
            throw GradleException("Timed out compiling vision-ocr Swift helper after 120s.")
        }
        val exitCode = proc.exitValue()
        if (exitCode != 0) {
            throw GradleException("Failed to compile vision-ocr Swift helper (exit $exitCode):\n$output")
        }
        binaryFile.setExecutable(true)
        val sha256 = MessageDigest.getInstance("SHA-256")
            .digest(binaryFile.readBytes())
            .joinToString("") { "%02x".format(it) }
        File(outDir, "vision-ocr.sha256").writeText("$sha256\n", Charsets.UTF_8)
    }
}

val generateExtractorScriptSha256 by tasks.registering {
    description = "Computes SHA-256 verification metadata for the bundled macOS key extractor script."
    group = "build"
    inputs.file(keyExtractorScriptFile)
    outputs.dir(generatedScriptResourcesDir)
    doLast {
        val outDir = generatedScriptResourcesDir.get().file("scripts").asFile
        outDir.mkdirs()
        val sha256 = MessageDigest.getInstance("SHA-256")
            .digest(keyExtractorScriptFile.asFile.readBytes())
            .joinToString("") { "%02x".format(it) }
        File(outDir, "wcdb_key_extractor.py.sha256").writeText("$sha256\n", Charsets.UTF_8)
    }
}

sourceSets {
    main {
        resources.srcDir(generatedVisionResourcesDir)
        resources.srcDir(generatedScriptResourcesDir)
    }
    create("eval") {
        kotlin.setSrcDirs(listOf("src/eval/kotlin"))
        resources.setSrcDirs(listOf("src/eval/resources"))
        compileClasspath += sourceSets["main"].output
        runtimeClasspath += sourceSets["main"].output
    }
}

kotlin.target.compilations.getByName("eval").associateWith(kotlin.target.compilations.getByName("main"))

configurations["evalImplementation"].extendsFrom(configurations["testImplementation"])
configurations["evalRuntimeOnly"].extendsFrom(configurations["testRuntimeOnly"])

jacoco {
    toolVersion = "0.8.12"
}

tasks.processResources {
    dependsOn(compileVisionOcrHelper, generateExtractorScriptSha256)
    exclude("**/__pycache__/**", "**/*.pyc")
}

tasks.test {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        csv.required.set(true)
        html.required.set(true)
    }
    classDirectories.setFrom(
        files(classDirectories.files.map {
            fileTree(it) {
                exclude(
                    "**/ui/**",
                    "**/MainKt*",
                    "**/*ComposableSingletons*"
                )
            }
        })
    )
}

val evalTest by tasks.registering(Test::class) {
    description = "Runs the offline deterministic AI pipeline eval & regression suite using recorded cassettes."
    group = "verification"
    testClassesDirs = sourceSets["eval"].output.classesDirs
    classpath = sourceSets["eval"].runtimeClasspath
    useJUnitPlatform()
    shouldRunAfter(tasks.test)
}

tasks.named("check") {
    dependsOn(evalTest)
}

fun JavaExec.configureEvalArgs(mode: String) {
    group = "verification"
    classpath = sourceSets["eval"].runtimeClasspath
    mainClass.set("com.wechatdigest.eval.PipelineTestbedCli")
    systemProperty("eval.mode", mode)
    val keys = listOf(
        "provider", "endpoint", "model", "runs", "scenario", "targetLang", "track",
        "wechatDir", "room", "limit", "allowSenders", "rules", "exportOut",
        "baseline", "cassettesDir", "scenariosDir"
    )
    keys.forEach { key ->
        val propVal = project.findProperty("eval.$key")?.toString() ?: System.getProperty("eval.$key")
        if (!propVal.isNullOrBlank()) {
            systemProperty("eval.$key", propVal)
        }
    }
}

tasks.register<JavaExec>("liveEval") {
    description = "Runs pipeline eval scenarios against a live Ollama or OpenAI-compatible model."
    configureEvalArgs("live")
}

tasks.register<JavaExec>("recordCassettes") {
    description = "Records live LLM responses into content-addressed VCR cassettes under src/eval/resources/cassettes/."
    configureEvalArgs("record")
}

tasks.register<JavaExec>("replaySnapshot") {
    description = "Replays messages from a decrypted WeChat directory read-only with full pipeline stage traces."
    configureEvalArgs("snapshot")
}
