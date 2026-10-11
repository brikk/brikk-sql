package dev.brikk.house.sql.metadata

import kotlinx.serialization.Serializable

/** Where a keyword may stand as an identifier; [UNKNOWN] when the source does not say. */
enum class KeywordCategory {
    RESERVED,
    UNRESERVED,

    /** Usable as a function or type name, not as a column name (PostgreSQL `type_func_name_keyword`). */
    TYPE_FUNCTION,

    /** Usable as a column name, not as a function or type name (PostgreSQL `col_name_keyword`). */
    COLUMN_NAME,
    UNKNOWN,
}

/**
 * [word] is spelled as the engine lists it (ClickHouse lists phrases such as `ADD COLUMN`).
 * [token] is the grammar's token symbol for ANTLR-derived keywords (Doris `CONVERT_LSC` for
 * `CONVERT_LIGHT_SCHEMA_CHANGE_PROCESS`).
 */
@Serializable
data class KeywordDef(
    val word: String,
    val category: KeywordCategory = KeywordCategory.UNKNOWN,
    val token: String? = null,
)

/**
 * A dialect's keywords. Every bundled dialect matches keywords ignoring case (ClickHouse's
 * case-sensitive function names do not extend to keywords), so lookups ignore case.
 */
class KeywordCatalog(val keywords: List<KeywordDef>) {

    private val byWord: Map<String, KeywordDef> = keywords.associateBy { it.word.uppercase() }

    /** The [KeywordCategory.RESERVED] words as the engine spells them; test membership with [isReserved]. */
    val reserved: Set<String> = keywords.filter { it.category == KeywordCategory.RESERVED }.mapTo(LinkedHashSet()) { it.word }

    operator fun get(word: String): KeywordDef? = byWord[word.uppercase()]

    operator fun contains(word: String): Boolean = word.uppercase() in byWord

    fun isReserved(word: String): Boolean = get(word)?.category == KeywordCategory.RESERVED
}
