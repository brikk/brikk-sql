package dev.brikk.house.sql.dialects

/** Diagnostics are not an authorized channel for S3 keys or signed URL credentials.
 * Never use this detection to parse, strip, decode or rewrite an executable URL.
 */
internal fun mayContainDuckdbS3UrlData(text: String): Boolean =
    listOf("s3://", "s3a://", "s3n://", "X-Amz-Signature", "X-Amz-Credential", "AWSAccessKeyId",
        "s3_secret_access_key", "s3_access_key_id", "s3_session_token").any { text.contains(it, ignoreCase = true) }

internal const val REDACTED_S3_DIAGNOSTIC = "DuckDB S3 diagnostic omitted because source or error may contain sensitive URL data"
