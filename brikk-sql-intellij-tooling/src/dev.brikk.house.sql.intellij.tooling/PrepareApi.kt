package dev.brikk.house.sql.intellij.tooling

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.jetbrains.amper.plugins.ExecutionAvoidance
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.TaskAction

/** SDK is a build input, never a published dependency or part of the library JAR. */
@TaskAction(executionAvoidance = ExecutionAvoidance.Disabled)
fun prepareSqlIdeApi(@Output outputDir: Path, @Output revisionDir: Path,
                     @Input license: Path, @Output resourceDir: Path) {
    Files.createDirectories(outputDir)
    val sdk = System.getenv("BRIKK_INTELLIJ_SDK")?.let(Path::of) ?: provisionSdk(outputDir)
    val product = Files.readString(sdk.resolve("product-info.json"))
    require(Regex("\"buildNumber\"\\s*:\\s*\"261\\.").containsMatchIn(product)) {
        "Compile on 261; newer SDKs belong in consumer runtime tests"
    }
    val inputs = listOf(sdk.resolve("lib"), sdk.resolve("plugins/DatabaseTools/lib")).flatMap { dir ->
        require(Files.isDirectory(dir)) { "Missing SDK directory: $dir" }
        Files.walk(dir).use { paths -> paths.filter { it.toString().endsWith(".jar") }.sorted().toList() }
    }
    fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    val fingerprint = sha(("v3\n" + inputs.joinToString("\n") { "${sdk.relativize(it)}:${fileSha(it)}" }).toByteArray())
    Files.createDirectories(outputDir)
    val jar = outputDir.resolve("intellij-sql-api.jar")
    val stamp = outputDir.resolve("revision")
    if (!Files.exists(jar) || !Files.exists(stamp) || Files.readString(stamp) != fingerprint) {
        val seen = HashSet<String>()
        ZipOutputStream(Files.newOutputStream(jar)).use { out ->
            for (input in inputs) ZipFile(input.toFile()).use { zip ->
                for (entry in zip.entries().asSequence()) {
                    if (entry.isDirectory || entry.name.startsWith("META-INF/versions/") || entry.name == "module-info.class") continue
                    if (!entry.name.endsWith(".class") && !entry.name.endsWith(".kotlin_module") && !entry.name.endsWith(".kotlin_builtins")) continue
                    if (!seen.add(entry.name)) continue
                    out.putNextEntry(ZipEntry(entry.name).also { it.time = 0 })
                    zip.getInputStream(entry).use { it.copyTo(out) }
                    out.closeEntry()
                }
            }
        }
        Files.writeString(stamp, fingerprint)
    }
    Files.createDirectories(revisionDir)
    Files.writeString(revisionDir.resolve("SqlIdeApiRevision.kt"),
        "package dev.brikk.house.sql.intellij.internal\ninternal const val API_REVISION = \"$fingerprint\"\n")
    val meta = Files.createDirectories(resourceDir.resolve("META-INF"))
    Files.copy(license, meta.resolve("LICENSE"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    Files.writeString(meta.resolve("NOTICE"), "Brikk SQL IntelliJ Support\nCopyright 2026 Sortdev SRL\n" +
        "Cooperative action and console mechanics adapted from Sortdev's Apache-2.0 Doris and DuckDB IntelliJ plugins.\n")
}

private fun fileSha(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(65536)
        while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun provisionSdk(output: Path): Path {
    require(System.getProperty("os.name") == "Linux" && System.getProperty("os.arch") in listOf("amd64", "x86_64")) {
        "Set BRIKK_INTELLIJ_SDK to a 261 SDK on this host"
    }
    val expected = "a6f049716da1d09d9e0ec1500c60bf01a5ff8a0fe2419178dd1ff2fdb2b77563"
    val sdk = output.resolve("sdk")
    val ready = sdk.resolve(".verified")
    if (Files.isRegularFile(ready) && Files.readString(ready) == expected) return sdk
    val archive = output.resolve("ideaIU-2026.1.3.tar.gz")
    if (!Files.isRegularFile(archive)) {
        val temp = Files.createTempFile(output, ".sdk-", ".tar.gz")
        try {
            val response = java.net.http.HttpClient.newBuilder().followRedirects(java.net.http.HttpClient.Redirect.NORMAL).build()
                .send(java.net.http.HttpRequest.newBuilder(java.net.URI("https://download.jetbrains.com/idea/ideaIU-2026.1.3.tar.gz")).GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.ofFile(temp))
            check(response.statusCode() == 200 && fileSha(temp) == expected) { "IDEA SDK download/checksum failed" }
            Files.move(temp, archive, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temp) }
    }
    check(fileSha(archive) == expected) { "IDEA SDK checksum mismatch" }
    Files.createDirectories(sdk)
    val process = ProcessBuilder("tar", "-xzf", archive.toAbsolutePath().toString(), "--strip-components=1", "--no-same-owner", "-C", sdk.toAbsolutePath().toString())
        .redirectErrorStream(true).redirectOutput(output.resolve("extract.log").toFile()).start()
    check(process.waitFor(10, java.util.concurrent.TimeUnit.MINUTES) && process.exitValue() == 0) { "IDEA SDK extraction failed" }
    Files.writeString(ready, expected)
    return sdk
}
