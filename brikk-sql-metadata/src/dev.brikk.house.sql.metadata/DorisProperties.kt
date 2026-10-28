package dev.brikk.house.sql.metadata

/*
 * Source-backed additions to the documentation-derived GeneratedDorisProperties.kt.
 * Apache Doris tag 4.1.5-release, 917b20be83878e98f94e3ebf7efccc80346e3e50:
 * CreateRoutineLoadInfo, MTMVPropertyUtil, InvertedIndexUtil, IndexPolicy, AIProperties.
 * Keep these separate from the older docs capture so regeneration cannot discard them.
 * Only newly introduced keys get a 4.1.5 since value; older keys without evidence stay unset.
 */
private val BOOLEAN_PROPERTY_VALUES = listOf("true", "false")
private val AI_PROVIDERS = listOf(
    "OPENAI", "LOCAL", "GEMINI", "DEEPSEEK", "ANTHROPIC", "MOONSHOT", "QWEN",
    "MINIMAX", "ZHIPU", "BAICHUAN", "VOYAGEAI", "JINA",
)

internal val DORIS_PROPERTIES: List<PropertyList> = DORIS_PROPERTY_LISTS.map { list ->
    when (list.title) {
        "CREATE MATERIALIZED VIEW properties", "CREATE ROUTINE LOAD job properties" -> list.copy(
            keys = list.keys + PropertyDef(
                "compute_group", summary = "Binds the job to the named compute group.", since = "4.1.5",
            ),
        )
        "CREATE RESOURCE properties" -> list.copy(keys = list.keys.map { key ->
            if (key.name == "type") key.copy(values = key.values + "ai") else key
        })
        else -> list
    }
} + listOf(
    PropertyList(
        title = "CREATE INDEX properties", context = "CREATE [OR REPLACE] INDEX", opener = "PROPERTIES",
        path = "sql-manual/sql-statements/table-and-view/index/CREATE-INDEX/",
        keys = listOf(
            PropertyDef("parser", listOf("none", "standard", "unicode", "english", "chinese", "icu", "basic", "ik", "kuromoji")),
            PropertyDef("built_in_analyzer"), PropertyDef("analyzer"), PropertyDef("normalizer"),
            PropertyDef("parser_mode", listOf("fine_grained", "coarse_grained")),
            PropertyDef("support_phrase", BOOLEAN_PROPERTY_VALUES),
            PropertyDef("norms", BOOLEAN_PROPERTY_VALUES, "Stores field-length norms for scoring.", "4.1.5"),
        ),
    ),
    PropertyList(
        title = "CREATE INVERTED INDEX ANALYZER properties", context = "CREATE INVERTED INDEX ANALYZER",
        path = "sql-manual/sql-statements/table-and-view/index/CREATE-INDEX/",
        keys = listOf(
            PropertyDef("tokenizer", listOf(
                "empty", "ngram", "edge_ngram", "keyword", "standard", "char_group", "basic", "icu", "pinyin",
                "ik_smart", "ik_max_word",
            )),
            PropertyDef("token_filter"), PropertyDef("char_filter"),
        ),
    ),
    PropertyList(
        title = "CREATE RESOURCE properties (ai)", context = "CREATE [EXTERNAL] RESOURCE",
        path = "sql-manual/sql-statements/cluster-management/compute-management/CREATE-RESOURCE/",
        variant = "type" to "ai",
        keys = listOf(
            PropertyDef("ai.endpoint"), PropertyDef("ai.provider_type", AI_PROVIDERS),
            PropertyDef("ai.model_name"), PropertyDef("ai.api_key"),
            PropertyDef("ai.temperature"), PropertyDef("ai.max_token"), PropertyDef("ai.max_retries"),
            PropertyDef("ai.retry_delay_second"), PropertyDef("ai.anthropic_version"),
            PropertyDef("ai.dimensions"), PropertyDef("ai.validity_check", BOOLEAN_PROPERTY_VALUES),
            PropertyDef("ai.effort", listOf("none", "minimal", "low", "medium", "high", "xhigh", "max")),
            PropertyDef("ai.embed.endpoint", since = "4.1.5"),
            PropertyDef("ai.embed.provider_type", AI_PROVIDERS, since = "4.1.5"),
            PropertyDef("ai.embed.model_name", since = "4.1.5"), PropertyDef("ai.embed.api_key", since = "4.1.5"),
            PropertyDef("ai.embed.mm.endpoint", since = "4.1.5"),
            PropertyDef("ai.embed.mm.provider_type", AI_PROVIDERS, since = "4.1.5"),
            PropertyDef("ai.embed.mm.model_name", since = "4.1.5"), PropertyDef("ai.embed.mm.api_key", since = "4.1.5"),
        ),
    ),
)
