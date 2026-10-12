package dev.brikk.house.sql.intellij

import dev.brikk.house.sql.intellij.catalog.GenericJdbcIntrospectorGate
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import junit.framework.TestCase
import org.jetbrains.org.objectweb.asm.AnnotationVisitor
import org.jetbrains.org.objectweb.asm.ClassReader
import org.jetbrains.org.objectweb.asm.ClassVisitor
import org.jetbrains.org.objectweb.asm.FieldVisitor
import org.jetbrains.org.objectweb.asm.MethodVisitor
import org.jetbrains.org.objectweb.asm.Opcodes
import org.jetbrains.org.objectweb.asm.commons.ClassRemapper
import org.jetbrains.org.objectweb.asm.commons.Remapper

/**
 * Only the catalog kit may use internal platform API (README), so that the kit can later move to
 * its own artifact without touching the rest. Internal means annotated `@ApiStatus.Internal` or
 * `@IntellijInternalApi` on the class, an enclosing class, its package or the referenced member.
 */
class InternalApiContainmentTest : TestCase() {
    fun testOnlyTheCatalogKitUsesInternalApi() {
        val uses = supportClasses().mapValues { internalReferences(it.value) }.filterValues { it.isNotEmpty() }
        val (kit, rest) = uses.entries.partition { it.key.startsWith(CATALOG_KIT) }
        assertTrue(rest.joinToString("\n") { "${it.key} -> ${it.value}" }, rest.isEmpty())
        assertEquals("the internal API the README lists", DOCUMENTED, kit.flatMap { it.value }.toSet())
    }

    private fun supportClasses(): Map<String, ByteArray> {
        val anchor = GenericJdbcIntrospectorGate::class.java
        val url = checkNotNull(anchor.getResource("${anchor.simpleName}.class"))
        val location = when (url.protocol) {
            "jar" -> Path.of(URI(url.path.substringBefore("!/")))
            else -> generateSequence(Path.of(url.toURI())) { it.parent }.elementAt(anchor.name.count { it == '.' } + 1)
        }
        val classes = if (Files.isDirectory(location)) {
            Files.walk(location).use { paths ->
                paths.filter { it.toString().endsWith(".class") }.toList()
                    .associate { location.relativize(it).toString().replace('\\', '/') to Files.readAllBytes(it) }
            }
        } else {
            ZipFile(location.toFile()).use { zip ->
                zip.entries().asSequence().filter { it.name.endsWith(".class") }
                    .associate { it.name to zip.getInputStream(it).readBytes() }
            }
        }
        return classes.filterKeys { it.startsWith("dev/brikk/house/sql/intellij/") }
            .mapKeys { (path, _) -> path.removeSuffix(".class").replace('/', '.') }
            .also { assertTrue("support classes at $location", it.size > 20) }
    }

    private fun internalReferences(bytes: ByteArray): Set<String> {
        val types = HashSet<String>()
        val members = HashSet<Triple<String, String, String>>()
        val recorder = object : Remapper() {
            override fun map(internalName: String): String = internalName.also { types += it }
            override fun mapMethodName(owner: String, name: String, descriptor: String): String = name.also { members += Triple(owner, name, descriptor) }
            override fun mapFieldName(owner: String, name: String, descriptor: String): String = name.also { members += Triple(owner, name, descriptor) }
        }
        ClassReader(bytes).accept(ClassRemapper(object : ClassVisitor(Opcodes.ASM9) {}, recorder), 0)
        val internalTypes = types.filter { it.isPlatform() && it.isInternalType() }
        val internalMembers = members.filter { (owner, name, descriptor) -> owner.isPlatform() && annotations(owner)?.isInternalMember(name, descriptor) == true }
            .map { (owner, name, _) -> "$owner.$name" }
        return (internalTypes + internalMembers).mapTo(sortedSetOf()) { it.replace('/', '.') }
    }

    private fun String.isPlatform() = !startsWith("dev/brikk/") && listOf("java/", "javax/", "kotlin/", "kotlinx/", "[").none(::startsWith)

    private fun String.isInternalType(): Boolean =
        generateSequence(this) { name -> name.substringBeforeLast('$', "").ifEmpty { null } }.any { annotations(it)?.internal == true } ||
            annotations(substringBeforeLast('/') + "/package-info")?.internal == true

    private class Annotations(val internal: Boolean, val internalMembers: Set<String>) {
        fun isInternalMember(name: String, descriptor: String): Boolean {
            val property = name.removePrefix("get").removePrefix("set").removePrefix("is")
            return "$name$descriptor" in internalMembers || listOf("$name\$annotations", "get$property\$annotations").any { synthetic ->
                internalMembers.any { it.startsWith("$synthetic(") }
            }
        }
    }

    private val parsed = HashMap<String, Annotations?>()

    private fun annotations(internalName: String): Annotations? = parsed.getOrPut(internalName) {
        val bytes = ClassLoader.getSystemResourceAsStream("$internalName.class")?.use { it.readBytes() } ?: return@getOrPut null
        var internal = false
        val internalMembers = HashSet<String>()
        ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? {
                if (descriptor in INTERNAL) internal = true
                return null
            }

            override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor =
                object : MethodVisitor(Opcodes.ASM9) {
                    override fun visitAnnotation(annotation: String, visible: Boolean): AnnotationVisitor? {
                        if (annotation in INTERNAL) internalMembers += "$name$descriptor"
                        return null
                    }
                }

            override fun visitField(access: Int, name: String, descriptor: String, signature: String?, value: Any?): FieldVisitor =
                object : FieldVisitor(Opcodes.ASM9) {
                    override fun visitAnnotation(annotation: String, visible: Boolean): AnnotationVisitor? {
                        if (annotation in INTERNAL) internalMembers += "$name$descriptor"
                        return null
                    }
                }
        }, ClassReader.SKIP_CODE)
        Annotations(internal, internalMembers)
    }

    private companion object {
        const val CATALOG_KIT = "dev.brikk.house.sql.intellij.catalog."
        val INTERNAL = setOf("Lorg/jetbrains/annotations/ApiStatus\$Internal;", "Lcom/intellij/openapi/util/IntellijInternalApi;")
        val DOCUMENTED = setOf("com.intellij.database.introspection.DBIntrospector\$Factory")
    }
}
