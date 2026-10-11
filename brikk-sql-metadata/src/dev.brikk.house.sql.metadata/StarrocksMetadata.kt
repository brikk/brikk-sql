package dev.brikk.house.sql.metadata

val STARROCKS_METADATA: DialectMetadata = singleSnapshot(
    "starrocks",
    MetadataSnapshot("4.1.4", STARROCKS_FUNCTION_CATALOG, grammarForms = STARROCKS_GRAMMAR_FORMS),
)
