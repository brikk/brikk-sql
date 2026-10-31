package dev.brikk.house.sql.metadata

/** Parameter names, not overloads or arity rules. Spice 78a47787, runtime-search and runtime-decide. */
val SPICE_NAMED_ARGUMENTS: Map<String, List<String>> = mapOf(
    "vector_search" to listOf("column", "limit", "include_score", "distance_metric", "rank_weight"),
    "text_search" to listOf("column", "limit", "include_score", "rank_weight"),
    "rrf" to listOf("k", "limit", "join_key", "time_column", "recency_decay", "decay_constant", "decay_scale_secs", "decay_window_secs"),
    "rerank" to listOf("model", "document", "query", "limit", "strategy", "prompt_template"),
    "ai_if" to listOf("model", "on_error"),
    "ai_probability" to listOf("model", "on_error"),
    "ai_classify" to listOf("instructions", "model", "on_error"),
    "ai_score" to listOf("levels", "model", "on_error"),
    "ai_decide" to listOf("model", "on_error"),
)

/** Fixed scalar results proved by runtime-decide/output.rs and the pinned JSON captures, independent of arity. */
val SPICE_SCALAR_RETURN_TYPES: Map<String, String> = mapOf(
    "ai_if" to "BOOLEAN", "ai_probability" to "DOUBLE", "ai_score" to "DOUBLE",
    "ai_classify" to "VARCHAR", "ai_decide" to "STRUCT",
    "json_get_int" to "BIGINT", "json_get_float" to "DOUBLE", "json_get_str" to "VARCHAR", "json_get_bool" to "BOOLEAN",
)
