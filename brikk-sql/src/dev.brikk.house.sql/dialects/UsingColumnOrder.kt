package dev.brikk.house.sql.dialects

/** sqlglot: UsingColumnOrder — ordering of merged keys in unqualified star output. */
enum class UsingColumnOrder {
    /** Keys first, following each USING list (the last join moves its keys to the front). */
    USING_LIST,
    /** Keys first, in their order on the left side (MySQL). */
    LEFT_TABLE,
    /** Keys retain their left-side position (DuckDB). */
    IN_PLACE,
}
