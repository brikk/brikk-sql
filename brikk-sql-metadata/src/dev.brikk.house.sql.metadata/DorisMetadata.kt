package dev.brikk.house.sql.metadata

/** One combined snapshot; [FunctionDef.firstDocumentedIn] carries Doris's documentation tiers. */
val DORIS_METADATA: DialectMetadata = singleSnapshot(
    "doris",
    MetadataSnapshot(
        "4.1.5",
        DORIS_FUNCTION_CATALOG,
        DORIS_4_1_5_KEYWORDS,
        tableFunctions = DORIS_TABLE_FUNCTIONS,
        grammarForms = DORIS_GRAMMAR_FORMS,
    ),
    DORIS_DOCS,
)
