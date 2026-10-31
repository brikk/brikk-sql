package dev.brikk.house.sql.metadata

import kotlin.test.*

class DuckdbIdentifierCaseTest {
    @Test fun nativeModesAndBooleanAliasesDoNotTreatAnUnknownValueAsFalse() {
        for(value in listOf("preserve_case","TRUE","1","t","y","yes"))assertEquals(DuckdbIdentifierCase.PRESERVE_CASE,DuckdbIdentifierCase.fromSetting(value))
        for(value in listOf("lowercase","FALSE","0","f","n","no"))assertEquals(DuckdbIdentifierCase.LOWERCASE,DuckdbIdentifierCase.fromSetting(value))
        assertEquals(DuckdbIdentifierCase.UPPERCASE,DuckdbIdentifierCase.fromSetting("UPPERCASE"))
        for(value in listOf(null,"","bogus","yesnt"))assertNull(DuckdbIdentifierCase.fromSetting(value))
        assertEquals("aÄiİ",DuckdbIdentifierCase.LOWERCASE.foldUnquoted("AÄIİ"))
        assertEquals("AäIı",DuckdbIdentifierCase.UPPERCASE.foldUnquoted("aäiı"))
    }
    @Test fun versionedSettingsMetadataKeepsTwoPointZeroVarcharSeparateFromOldBoolean() {
        val old=DUCKDB_DUMPS.single { it.engineVersion=="1.5.6" }.settings!!.single { it.name=="preserve_identifier_case" }
        val current=DUCKDB_DUMPS.single { it.engineVersion=="2.0.0" }.settings!!.single { it.name=="preserve_identifier_case" }
        assertEquals("BOOLEAN",old.inputType);assertEquals("VARCHAR",current.inputType)
        assertEquals("GLOBAL",current.scope)
        for(mode in DuckdbIdentifierCase.entries)assertTrue(mode.settingValue in current.description.orEmpty())
    }
}
