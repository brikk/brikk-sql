package dev.brikk.house.sql.dialects

/** Native qualified-star treatment of keys named by USING/NATURAL joins. */
enum class QualifiedStarUsing { RAW, OMIT, MERGED, MERGED_FIRST }
