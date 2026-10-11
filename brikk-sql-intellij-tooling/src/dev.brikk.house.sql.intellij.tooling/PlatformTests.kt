package dev.brikk.house.sql.intellij.tooling

import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import org.jetbrains.amper.plugins.Classpath
import org.jetbrains.amper.plugins.ExecutionAvoidance
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction

// Bundled plugins whose classes the tests use; later IDEs split some into separate plugins.
private val runtimePlugins = listOf("DatabaseTools", "grid-core-plugin", "platform-execution-serviceView-plugin")
private val testFramework = listOf("test-framework", "test-framework-core", "test-framework-common", "test-framework-team-city")

/**
 * Compiles [testSources] against the 261 API and runs their JUnit tests in a real IDE.
 * BRIKK_INTELLIJ_RUNTIME_SDK runs the same classes on a newer installation.
 */
@TaskAction(executionAvoidance = ExecutionAvoidance.Disabled)
fun testIntellijPlatform(
    @Input apiDir: Path,
    @Input libraries: Classpath,
    @Input testSources: Path,
    @Input toolchain: Classpath,
    @Output outputDir: Path,
) {
    val compileSdk = System.getenv("BRIKK_INTELLIJ_SDK")?.let(Path::of) ?: apiDir.resolve("sdk")
    val runtimeSdk = System.getenv("BRIKK_INTELLIJ_RUNTIME_SDK")?.let(Path::of) ?: compileSdk
    val runtimeBuild = buildNumber(runtimeSdk)
    val run = Files.createDirectories(outputDir.resolve("run-$runtimeBuild"))
    val junit = toolchain.resolvedFiles.filter { jar ->
        listOf("junit-", "hamcrest-", "opentest4j-").any { jar.fileName.toString().startsWith(it) }
    }
    listOf("classes", "config", "system").forEach { run.resolve(it).toFile().deleteRecursively() }
    val classes = run.resolve("classes")
    val sources = Files.walk(testSources).use { paths -> paths.filter { it.toString().endsWith(".kt") }.sorted().toList() }
    val compileClasspath = listOf(apiDir.resolve("intellij-sql-api.jar")) +
        frameworkJars(outputDir, buildNumber(compileSdk)) + junit + libraries.resolvedFiles
    java(toolchain.resolvedFiles, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
        listOf("-no-stdlib", "-no-reflect", "-jvm-target", "21", "-classpath", compileClasspath.joinToString(File.pathSeparator),
            "-d", classes.toString()) + sources.map(Path::toString),
        run.resolve("compile.log"))
    val tests = Files.walk(classes).use { paths ->
        paths.map { classes.relativize(it).toString() }
            .filter { it.endsWith("Test.class") && '$' !in it }
            .map { it.removeSuffix(".class").replace(File.separatorChar, '.') }
            .sorted().toList()
    }
    check(tests.isNotEmpty()) { "No platform tests in $testSources" }
    val runtimeClasspath = sdkJars(runtimeSdk) + frameworkJars(outputDir, runtimeBuild) + junit + libraries.resolvedFiles + listOf(classes)
    val opens = Regex("\"(--add-opens=[^\"]+)\"").findAll(Files.readString(runtimeSdk.resolve("product-info.json")))
        .map { it.groupValues[1] }.distinct().toList()
    val log = run.resolve("test.log")
    java(runtimeClasspath, "org.junit.runner.JUnitCore", tests, log, runtimeSdk.resolve("jbr/bin/java"), opens + listOf(
        "--enable-native-access=ALL-UNNAMED", "-Djava.awt.headless=true",
        "-Djava.system.class.loader=com.intellij.util.lang.PathClassLoader", "-Didea.force.use.core.classloader=true",
        "-Djna.boot.library.path=${runtimeSdk.resolve("lib/jna/amd64")}", "-Djna.nosys=true", "-Djna.noclasspath=true",
        "-Dintellij.platform.runtime.repository.path=${runtimeSdk.resolve("modules/module-descriptors.dat")}",
        "-Didea.home.path=$runtimeSdk", "-Didea.plugins.path=${runtimeSdk.resolve("plugins")}",
        "-Didea.config.path=${run.resolve("config")}", "-Didea.system.path=${run.resolve("system")}",
        "-Didea.load.plugins.id=com.intellij.database",
    ))
    val summary = Regex("OK \\(\\d+ tests?\\)").find(Files.readString(log))?.value ?: error("Missing JUnit summary in $log")
    println("IntelliJ platform tests on $runtimeBuild: $summary")
}

private fun buildNumber(sdk: Path): String =
    Regex("\"buildNumber\"\\s*:\\s*\"(\\d+\\.\\d+\\.\\d+)\"").find(Files.readString(sdk.resolve("product-info.json")))
        ?.groupValues?.get(1) ?: error("Missing IDE build number in $sdk")

private fun sdkJars(sdk: Path): List<Path> =
    (listOf("lib", "lib/modules") + runtimePlugins.flatMap { listOf("plugins/$it/lib", "plugins/$it/lib/modules") })
        .map(sdk::resolve).filter(Files::isDirectory)
        .flatMap { dir -> Files.list(dir).use { files -> files.filter { it.toString().endsWith(".jar") }.sorted().toList() } }

private fun frameworkJars(outputDir: Path, build: String): List<Path> = testFramework.map { name ->
    val jar = outputDir.resolve("$name-$build.jar")
    if (!Files.isRegularFile(jar)) {
        val download = Files.createTempFile(outputDir, ".$name-", ".jar")
        try {
            val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
            val found = listOf("releases", "snapshots").any { repository ->
                val url = URI("https://www.jetbrains.com/intellij-repository/$repository/com/jetbrains/intellij/platform/$name/$build/${jar.fileName}")
                client.send(HttpRequest.newBuilder(url).GET().build(), HttpResponse.BodyHandlers.ofFile(download)).statusCode() == 200
            }
            check(found) { "Test framework download failed: $name $build" }
            ZipFile(download.toFile()).use { check(it.size() > 0) { "Empty test framework jar: $name $build" } }
            Files.move(download, jar, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(download)
        }
    }
    jar
}

private fun java(classpath: List<Path>, main: String, args: List<String>, log: Path,
                 executable: Path = Path.of(System.getProperty("java.home"), "bin", "java"), jvmArgs: List<String> = emptyList()) {
    val process = ProcessBuilder(listOf(executable.toString(), "-Xmx2g") + jvmArgs +
        listOf("-cp", classpath.joinToString(File.pathSeparator), main) + args)
        .redirectErrorStream(true).redirectOutput(log.toFile()).start()
    if (!process.waitFor(15, TimeUnit.MINUTES)) {
        process.destroyForcibly()
        error("Timed out: $log")
    }
    check(process.exitValue() == 0) { "Failed ($log):\n${Files.readString(log).takeLast(12000)}" }
}
