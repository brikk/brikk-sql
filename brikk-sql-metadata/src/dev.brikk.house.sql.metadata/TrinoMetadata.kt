package dev.brikk.house.sql.metadata

val TRINO_METADATA: DialectMetadata =
    singleSnapshot("trino", MetadataSnapshot("484", TRINO_FUNCTION_CATALOG, TRINO_484_KEYWORDS, grammarForms = TRINO_GRAMMAR_FORMS))
