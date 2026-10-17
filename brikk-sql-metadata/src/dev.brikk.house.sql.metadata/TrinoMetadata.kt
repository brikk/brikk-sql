package dev.brikk.house.sql.metadata

val TRINO_METADATA: DialectMetadata =
    singleSnapshot("trino", MetadataSnapshot("483", TRINO_FUNCTION_CATALOG, TRINO_483_KEYWORDS, grammarForms = TRINO_GRAMMAR_FORMS))
