package dev.brikk.house.sql.metadata

/** Native parser policy for unquoted identifier spelling, not lookup case sensitivity. */
enum class DuckdbIdentifierCase(val settingValue: String) {
    PRESERVE_CASE("preserve_case"), LOWERCASE("lowercase"), UPPERCASE("uppercase");

    /** DuckDB folds ASCII only; quoted identifiers and string/path literals are not inputs. */
    fun foldUnquoted(name: String): String = when (this) {
        PRESERVE_CASE -> name
        LOWERCASE -> name.map { if (it in 'A'..'Z') it.lowercaseChar() else it }.joinToString("")
        UPPERCASE -> name.map { if (it in 'a'..'z') it.uppercaseChar() else it }.joinToString("")
    }

    companion object {
        /** Missing/unknown facts stay unknown. Legacy boolean spellings are native aliases. */
        fun fromSetting(value: String?): DuckdbIdentifierCase? = when (value?.lowercase()) {
            "preserve_case", "true", "1", "t", "y", "yes" -> PRESERVE_CASE
            "lowercase", "false", "0", "f", "n", "no" -> LOWERCASE
            "uppercase" -> UPPERCASE
            else -> null
        }
    }
}
