package dev.brikk.house.sql

import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.dialects.Dialect
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.optimizer.Schema
import dev.brikk.house.sql.optimizer.Scope
import kotlin.test.Test
import kotlin.test.assertEquals

/** Engine's published compiler-plugin JAR links these descriptors, including Kotlin defaults. */
class EngineAnalysisAbiContractTest {
    @Test
    fun parameterizedQualificationDescriptorsAndDefaultBridgesRemainBinaryCompatible() {
        val bool = Boolean::class.javaPrimitiveType!!
        val int = Int::class.javaPrimitiveType!!
        val boxedBool = Boolean::class.javaObjectType
        val signature = arrayOf(
            Expression::class.java, Dialect::class.java,
            Any::class.java, Any::class.java, Any::class.java,
            bool, bool, boxedBool, bool, bool, bool, bool, bool, bool, bool,
            Function1::class.java, String::class.java,
        )
        val owner = Class.forName("dev.brikk.house.sql.optimizer.QualifyKt")
        assertEquals(Expression::class.java, owner.getMethod("qualify", *signature).returnType)
        val upstreamSignature = signature.filterIndexed { index, _ -> index != 7 }.toTypedArray()
        assertEquals(Expression::class.java, owner.getMethod("qualify", *upstreamSignature).returnType)
        val defaults = owner.getMethod("qualify\$default", *signature, int, Any::class.java)
        // Same old bridge used by Engine's named-argument call: the absent-schema
        // control deliberately disables inference and validation, rather than guessing.
        val explicit = mapOf(0 to Dialects.POSTGRES.parseOne("SELECT amount FROM mystery AS m"),
            1 to Dialects.POSTGRES, 7 to false, 11 to false)
        val args = signature.mapIndexed { index, type ->
            explicit[index] ?: if (type == bool) false else null
        }
        val mask = signature.indices.filter { it !in explicit }.fold(0) { bits, index -> bits or (1 shl index) }
        val qualified = defaults.invoke(null, *(args + listOf(mask, null)).toTypedArray()) as Expression
        assertEquals("", qualified.findAll<dev.brikk.house.sql.ast.Column>().single().table)

        val columnsSignature = arrayOf(Expression::class.java, Any::class.java, bool, bool, boxedBool, bool, Dialect::class.java)
        val columnsOwner = Class.forName("dev.brikk.house.sql.optimizer.QualifyColumnsKt")
        assertEquals(Expression::class.java, columnsOwner.getMethod("qualifyColumns", *columnsSignature).returnType)
        val upstreamColumnsSignature = columnsSignature.filterIndexed { index, _ -> index != 4 }.toTypedArray()
        assertEquals(Expression::class.java,
            columnsOwner.getMethod("qualifyColumns", *upstreamColumnsSignature).returnType)
        assertEquals(Expression::class.java,
            columnsOwner.getMethod("qualifyColumns\$default", *columnsSignature, int, Any::class.java).returnType)
        val resolver = Class.forName("dev.brikk.house.sql.optimizer.Resolver")
        resolver.getConstructor(Scope::class.java, Schema::class.java, bool)
    }
}
