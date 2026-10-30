package dev.brikk.house.sql

import dev.brikk.house.sql.dialects.ClickhouseCallableInputRule
import dev.brikk.house.sql.dialects.ClickhouseCallableResultRule
import dev.brikk.house.sql.dialects.ClickhouseCallableExecutionRestriction
import dev.brikk.house.sql.dialects.ClickhouseDesignatorArity
import dev.brikk.house.sql.dialects.ClickhouseExplicitCallArity
import dev.brikk.house.sql.dialects.clickhouseCallableFacts
import dev.brikk.house.sql.metadata.CLICKHOUSE_METADATA
import kotlin.test.*

class ClickhouseCallableFactsTest {
    private val versions = listOf("26.8.34.13", "26.10.2.103")
    private val names = listOf("lower", "upper", "domainWithoutWWW", "firstSignificantSubdomain",
        "trim", "multiply", "toString", "concat")

    @Test
    fun onlyTheExactProvenVersionsAndResearchedBuiltinsHaveFacts() {
        for (version in versions) for (name in names) assertNotNull(clickhouseCallableFacts(name, version))
        for (version in listOf("", "unknown", "26.5.1.1", "26.8", "26.8.34.14", "26.10", "26.10.2.104",
            "v26.10.2.103", "26.10.2.103-beta", "27.1.1.1")) {
            for (name in names) assertNull(clickhouseCallableFacts(name, version), "$version/$name")
        }
        for (version in versions) {
            for (name in listOf("abs", "arrayMap", "brikkMissingSemanticFunction", "db.lower", "`lower`"))
                assertNull(clickhouseCallableFacts(name, version), name)
        }
    }

    @Test
    fun lookupPreservesRegisteredAliasesAndPerSpellingCasePolicy() {
        for (version in versions) {
            for (name in listOf("lower", "LOWER", "Lower", "lcase", "LCASE"))
                assertEquals(clickhouseCallableFacts("lower", version), clickhouseCallableFacts(name, version))
            for (name in listOf("upper", "UPPER", "Upper", "ucase", "UCASE"))
                assertEquals(clickhouseCallableFacts("upper", version), clickhouseCallableFacts(name, version))
            assertEquals(clickhouseCallableFacts("concat", version), clickhouseCallableFacts("CONCAT", version))
            assertEquals(clickhouseCallableFacts("trimBoth", version), clickhouseCallableFacts("trim", version))
            assertEquals("trimBoth", clickhouseCallableFacts("trim", version)?.canonicalName)
            for (name in listOf("TRIM", "TrimBoth", "DOMAINWITHOUTWWW", "domainwithoutwww", "MULTIPLY", "TOSTRING"))
                assertNull(clickhouseCallableFacts(name, version), name)
            // TRIM(...) is also SQL grammar. Parser normalization of that special form
            // does not make raw registered function designators case-insensitive.
        }
    }

    @Test
    fun variadicDesignatorsDoNotInheritAnExplicitCallsMinimumArity() {
        for (version in versions) {
            for (name in listOf("lower", "upper", "domainWithoutWWW", "firstSignificantSubdomain")) {
                val fact = clickhouseCallableFacts(name, version)!!
                assertEquals(ClickhouseDesignatorArity.Fixed(1), fact.designatorArity)
                assertEquals(ClickhouseExplicitCallArity(1, 1), fact.explicitCallArity)
            }
            assertEquals(ClickhouseDesignatorArity.Fixed(2), clickhouseCallableFacts("multiply", version)?.designatorArity)
            assertEquals(ClickhouseExplicitCallArity(2, 2), clickhouseCallableFacts("multiply", version)?.explicitCallArity)
            for (name in listOf("trim", "toString", "concat"))
                assertEquals(ClickhouseDesignatorArity.Variadic, clickhouseCallableFacts(name, version)?.designatorArity)
            assertEquals(ClickhouseExplicitCallArity(1, 2), clickhouseCallableFacts("trim", version)?.explicitCallArity)
            assertEquals(ClickhouseExplicitCallArity(1, 2, secondArgumentRequiresDateTime = true),
                clickhouseCallableFacts("toString", version)?.explicitCallArity)
            assertEquals(ClickhouseExplicitCallArity(0, null), clickhouseCallableFacts("concat", version)?.explicitCallArity)
        }
    }

    @Test
    fun resultRulesAreBoundToTheirSupportedInputSubsets() {
        for (version in versions) {
            for (name in listOf("lower", "upper")) {
                val fact = clickhouseCallableFacts(name, version)!!
                assertEquals(ClickhouseCallableInputRule.STRING_OR_FIXED_STRING, fact.inputRule)
                assertEquals(ClickhouseCallableResultRule.PRESERVE_STRING_INPUT, fact.resultRule)
                assertNull(fact.executionRestriction)
            }
            for (name in listOf("domainWithoutWWW", "firstSignificantSubdomain")) {
                val fact = clickhouseCallableFacts(name, version)!!
                assertEquals(ClickhouseCallableInputRule.STRING_OR_FIXED_STRING, fact.inputRule)
                assertEquals(ClickhouseCallableResultRule.PRESERVE_STRING_INPUT, fact.resultRule)
                assertEquals(ClickhouseCallableExecutionRestriction.FIXED_STRING_UNSUPPORTED, fact.executionRestriction)
                assertTrue("CHSEM-002-S14" in fact.sourceIds) // FixedString execution refusal
            }
            assertEquals(ClickhouseCallableInputRule.TRIM_WITH_OPTIONAL_CONSTANT_STRING,
                clickhouseCallableFacts("trim", version)?.inputRule)
            assertEquals(ClickhouseCallableInputRule.SCALAR_TO_STRING,
                clickhouseCallableFacts("toString", version)?.inputRule)
            assertEquals(ClickhouseCallableInputRule.SCALAR_CONCAT,
                clickhouseCallableFacts("concat", version)?.inputRule)
            assertEquals(ClickhouseCallableInputRule.PRIMITIVE_NUMERIC_PAIR,
                clickhouseCallableFacts("multiply", version)?.inputRule)
            assertEquals(ClickhouseCallableResultRule.PRIMITIVE_MULTIPLICATION_PROMOTION,
                clickhouseCallableFacts("multiply", version)?.resultRule)
            for (name in listOf("lower", "upper", "domainWithoutWWW", "firstSignificantSubdomain", "trim"))
                assertTrue(clickhouseCallableFacts(name, version)!!.inputRule.exhaustiveForPlainTypes, name)
            for (name in listOf("multiply", "toString", "concat"))
                assertFalse(clickhouseCallableFacts(name, version)!!.inputRule.exhaustiveForPlainTypes, name)
        }
    }

    @Test
    fun researchedFactsDoNotPopulateFabricatedRegistryOverloads() {
        for (snapshot in CLICKHOUSE_METADATA.snapshots) for (name in names) {
            assertTrue(snapshot.functions[name]!!.overloads.isEmpty(), "${snapshot.engineVersion}/$name")
        }
        for (name in names) {
            val old = clickhouseCallableFacts(name, versions[0])!!
            val stable = clickhouseCallableFacts(name, versions[1])!!
            assertEquals(old, stable) // Same declarations proved independently on both pins.
            assertTrue(old.sourceIds.isNotEmpty())
        }
    }
}
