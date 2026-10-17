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
import org.jetbrains.org.objectweb.asm.ClassWriter
import org.jetbrains.org.objectweb.asm.FieldVisitor
import org.jetbrains.org.objectweb.asm.MethodVisitor
import org.jetbrains.org.objectweb.asm.Opcodes
import org.jetbrains.org.objectweb.asm.commons.ClassRemapper
import org.jetbrains.org.objectweb.asm.commons.Remapper

/**
 * Only the catalog kit may use internal platform API (support README), so that the kit can later
 * move to its own artifact without touching the rest. Internal means annotated `@ApiStatus.Internal`
 * or `@IntellijInternalApi` on the class, an enclosing class or its package, or on the referenced
 * member or the class declaring it, found through the owner's supertypes. Every reference counts:
 * signatures, method bodies (class literals, casts, calls, field accesses), lambdas and method
 * handles, and annotation values. [DOCUMENTED] is the source of the README's table.
 */
class InternalApiContainmentTest : TestCase() {
    fun testOnlyTheCatalogKitUsesInternalApi() {
        val uses = supportClasses().mapValues { internalReferences(it.value) }.filterValues { it.isNotEmpty() }
        val (kit, rest) = uses.entries.partition { it.key.startsWith(CATALOG_KIT) }
        assertTrue(rest.joinToString("\n") { "${it.key} -> ${it.value}" }, rest.isEmpty())
        assertEquals("the internal API the README lists", DOCUMENTED, kit.flatMap { it.value }.toSet())
    }

    /** Each kind of reference, from classes outside the kit, is found. */
    fun testEveryKindOfReferenceIsCaught() {
        val launcher = "com.intellij.database.console.ConnectionTimerLauncher"
        val expected = mapOf(
            BodyClassLiteral::class.java to launcher,
            BodyCast::class.java to launcher,
            BodyCall::class.java to "com.intellij.openapi.extensions.ExtensionsArea.getNameToPointMap",
            BodyFieldAccess::class.java to "com.intellij.ide.projectView.TreeStructureProvider.EP",
            LambdaBody::class.java to launcher,
            MethodReference::class.java to launcher,
            AnnotationValue::class.java to launcher,
            CallThroughSubclass::class.java to "com.intellij.openapi.actionSystem.AnAction.isDefaultIcon",
        )
        val missed = expected.filter { (control, reference) ->
            val bytes = checkNotNull(control.getResourceAsStream("${control.simpleName}.class")).use { it.readBytes() }
            reference !in internalReferences(bytes)
        }
        assertTrue("missed: ${missed.keys.map { it.simpleName }}", missed.isEmpty())
    }

    /**
     * Support needs no Kotlin IDE plugin (support README): no class of it or of its analysis API
     * (`org.jetbrains.kotlin.*`; the standard library is `kotlin.*`) is referenced, not even by name.
     */
    fun testNoKotlinPluginClassIsReferenced() {
        val uses = supportClasses().mapValues { kotlinPluginReferences(it.value) }.filterValues { it.isNotEmpty() }
        assertTrue(uses.entries.joinToString("\n") { "${it.key} -> ${it.value}" }, uses.isEmpty())
    }

    /** A Kotlin plugin type in a support class's copy, a class name used reflectively and an analysis API type are each found. */
    fun testKotlinPluginReferencesAreCaught() {
        val annotator = supportClasses().getValue("dev.brikk.house.sql.intellij.diagnostics.NativeErrorAnnotator")
        assertEquals(emptySet<String>(), kotlinPluginReferences(annotator))
        val renamed = ClassWriter(0).also { writer ->
            val rename = object : Remapper() {
                override fun map(internalName: String) = if (internalName == "com/intellij/psi/PsiFile") "org/jetbrains/kotlin/psi/KtFile" else internalName
            }
            ClassReader(annotator).accept(ClassRemapper(writer, rename), 0)
        }.toByteArray()
        assertEquals(setOf("org.jetbrains.kotlin.psi.KtFile"), kotlinPluginReferences(renamed))
        val generated = ClassWriter(ClassWriter.COMPUTE_MAXS).apply {
            visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "dev/brikk/house/sql/intellij/Scratch", null, "java/lang/Object", null)
            visitField(Opcodes.ACC_PRIVATE, "session", "Lorg/jetbrains/kotlin/analysis/api/KaSession;", null, null).visitEnd()
            visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "language", "()Ljava/lang/Class;", null, null).apply {
                visitCode()
                visitLdcInsn("org.jetbrains.kotlin.idea.KotlinLanguage")
                visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Class", "forName", "(Ljava/lang/String;)Ljava/lang/Class;", false)
                visitInsn(Opcodes.ARETURN)
                visitMaxs(0, 0)
                visitEnd()
            }
            visitEnd()
        }.toByteArray()
        assertEquals(setOf("org.jetbrains.kotlin.analysis.api.KaSession", "org.jetbrains.kotlin.idea.KotlinLanguage"), kotlinPluginReferences(generated))
    }

    /** The Kotlin plugin classes [bytes] references, as types anywhere or as class names in its constants. */
    private fun kotlinPluginReferences(bytes: ByteArray): Set<String> {
        val found = sortedSetOf<String>()
        val recorder = object : Remapper() {
            override fun map(internalName: String): String = internalName.also { if (it.startsWith(KOTLIN_PLUGIN)) found += it.replace('/', '.') }
            override fun mapValue(value: Any?): Any? = super.mapValue(value).also {
                if (value is String && value.replace('/', '.').startsWith(KOTLIN_PLUGIN.replace('/', '.'))) found += value.replace('/', '.')
            }
        }
        ClassReader(bytes).accept(ClassRemapper(ClassWriter(0), recorder), 0)
        return found
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
        // A writer as the sink makes the remapper visit method bodies and annotation values too.
        ClassReader(bytes).accept(ClassRemapper(ClassWriter(0), recorder), 0)
        val internalTypes = types.filter { it.isPlatform() && it.isInternalType() }
        val internalMembers = members.mapNotNull { (owner, name, descriptor) ->
            if (owner.startsWith("[")) return@mapNotNull null
            val declaring = (declaringClass(owner, name, descriptor) ?: owner).takeIf { it.isPlatform() } ?: return@mapNotNull null
            when {
                declaring.isInternalType() -> declaring
                classInfo(declaring)?.isInternalMember(name, descriptor) == true -> "$declaring.$name"
                else -> null
            }
        }
        return (internalTypes + internalMembers).mapTo(sortedSetOf()) { it.replace('/', '.') }
    }

    private fun String.isPlatform() = !startsWith("dev/brikk/") && listOf("java/", "javax/", "kotlin/", "kotlinx/", "[").none(::startsWith)

    private fun String.isInternalType(): Boolean =
        generateSequence(this) { name -> name.substringBeforeLast('$', "").ifEmpty { null } }.any { classInfo(it)?.internal == true } ||
            classInfo(substringBeforeLast('/') + "/package-info")?.internal == true

    /** The first of [owner] and its supertypes, breadth first, that declares the member. */
    private fun declaringClass(owner: String, name: String, descriptor: String): String? {
        val seen = HashSet<String>()
        val pending = ArrayDeque(listOf(owner))
        while (pending.isNotEmpty()) {
            val type = pending.removeFirst()
            if (!seen.add(type)) continue
            val info = classInfo(type) ?: continue
            if ("$name$descriptor" in info.members) return type
            pending += info.supertypes
        }
        return null
    }

    private class ClassInfo(val internal: Boolean, val members: Set<String>, val internalMembers: Set<String>, val supertypes: List<String>) {
        fun isInternalMember(name: String, descriptor: String): Boolean {
            val property = name.removePrefix("get").removePrefix("set").removePrefix("is")
            return "$name$descriptor" in internalMembers || listOf("$name\$annotations", "get$property\$annotations").any { synthetic ->
                internalMembers.any { it.startsWith("$synthetic(") }
            }
        }
    }

    private val parsed = HashMap<String, ClassInfo?>()

    private fun classInfo(internalName: String): ClassInfo? = parsed.getOrPut(internalName) {
        val bytes = ClassLoader.getSystemResourceAsStream("$internalName.class")?.use { it.readBytes() } ?: return@getOrPut null
        var internal = false
        val members = HashSet<String>()
        val internalMembers = HashSet<String>()
        val supertypes = ArrayList<String>()
        fun memberAnnotation(member: String, annotation: String): AnnotationVisitor? {
            if (annotation in INTERNAL) internalMembers += member
            return null
        }
        ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visit(version: Int, access: Int, name: String, signature: String?, superName: String?, interfaces: Array<out String>?) {
                supertypes += listOfNotNull(superName) + interfaces.orEmpty()
            }

            override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? {
                if (descriptor in INTERNAL) internal = true
                return null
            }

            override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor {
                members += "$name$descriptor"
                return object : MethodVisitor(Opcodes.ASM9) {
                    override fun visitAnnotation(annotation: String, visible: Boolean) = memberAnnotation("$name$descriptor", annotation)
                }
            }

            override fun visitField(access: Int, name: String, descriptor: String, signature: String?, value: Any?): FieldVisitor {
                members += "$name$descriptor"
                return object : FieldVisitor(Opcodes.ASM9) {
                    override fun visitAnnotation(annotation: String, visible: Boolean) = memberAnnotation("$name$descriptor", annotation)
                }
            }
        }, ClassReader.SKIP_CODE)
        ClassInfo(internal, members, internalMembers, supertypes)
    }

    private companion object {
        const val CATALOG_KIT = "dev.brikk.house.sql.intellij.catalog."
        const val KOTLIN_PLUGIN = "org/jetbrains/kotlin/"
        val INTERNAL = setOf("Lorg/jetbrains/annotations/ApiStatus\$Internal;", "Lcom/intellij/openapi/util/IntellijInternalApi;")
        val DOCUMENTED = setOf("com.intellij.database.introspection.DBIntrospector\$Factory")
    }
}
