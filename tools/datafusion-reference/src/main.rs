//! Development-only JSONL parser/execution oracle. Never used to execute editor SQL.
use datafusion_sql::parser::{DFParser, Statement};
use datafusion_sql::sqlparser::dialect::GenericDialect;
use datafusion_sql::sqlparser::tokenizer::Location;
use serde::Deserialize;
use serde_json::{Value, json};
use std::io::{self, BufRead, Write};

#[cfg(not(feature = "spice"))]
mod catalog;
#[cfg(feature = "spice")]
mod spice;

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Request {
    id: String,
    sql: String,
    #[serde(default = "parse_operation")]
    operation: String,
    #[serde(default)]
    setup: Vec<String>,
}

fn parse_operation() -> String {
    "parse".into()
}

fn profile() -> Value {
    assert_eq!(
        cfg!(feature = "spice"),
        env!("CARGO_PKG_NAME") == "brikk-spice-parser-reference",
        "Use the reference package's default feature set; engine profiles cannot be relabeled"
    );
    if cfg!(feature = "spice") {
        json!({"id":"spice-2.4.0-parser", "datafusion":"55.2.0",
            "revision":"cf084aa85dfdc27b4736208f5c132828020774ea",
            "dialect":"PostgreSQL", "identifier_normalization":false,
            "scope":"parser plus adapted Spice CLUSTER BY wrapper; no runtime binding"})
    } else {
        json!({"id":"datafusion-55.2.0", "datafusion":"55.2.0",
            "revision":"500744d5c25499055e3b2ca46b022bc425989203",
            "dialect":"Generic", "identifier_normalization":true,
            "scope":"upstream parser and in-memory engine; optional Spice/JSON/Spark functions absent"})
    }
}

/// sqlparser locations count Unicode scalar values; IDE offsets count UTF-16 units.
fn byte_offset(sql: &str, location: Location) -> Option<usize> {
    let line = usize::try_from(location.line).ok()?.checked_sub(1)?;
    let column = usize::try_from(location.column).ok()?.checked_sub(1)?;
    let mut start = 0;
    for _ in 0..line {
        start += sql.get(start..)?.find('\n')? + 1;
    }
    let rest = sql.get(start..)?;
    let end = rest.find('\n').unwrap_or(rest.len());
    let content = &rest[..end];
    let offset = if column == content.chars().count() {
        content.len()
    } else {
        content.char_indices().nth(column)?.0
    };
    Some(start + offset)
}

fn error_position(sql: &str, message: &str) -> Value {
    // DataFusion wraps sqlparser errors. Keep the original message; coordinates are
    // optional and only extracted from its explicit trailing Line/Column suffix.
    let Some((_, suffix)) = message.rsplit_once("Line: ") else {
        return Value::Null;
    };
    let Some((line, col)) = suffix.split_once(", Column: ") else {
        return Value::Null;
    };
    let column_text = col.trim().strip_suffix("\")").unwrap_or(col.trim());
    let (Ok(line), Ok(column)) = (line.parse::<u64>(), column_text.parse::<u64>()) else {
        return Value::Null;
    };
    let Some(byte) = byte_offset(sql, Location { line, column }) else {
        return Value::Null;
    };
    json!({"line":line,"column":column,"column_units":"unicode-scalars",
        "byte_offset":byte,"utf16_offset":sql[..byte].encode_utf16().count()})
}

fn parse(sql: &str) -> Result<Vec<Statement>, String> {
    #[cfg(feature = "spice")]
    {
        spice::parse_script(sql)
    }
    #[cfg(not(feature = "spice"))]
    {
        DFParser::parse_sql_with_dialect(sql, &GenericDialect {})
            .map(|v| v.into())
            .map_err(|e| e.to_string())
    }
}

fn parse_response(sql: &str) -> Value {
    match parse(sql) {
        Ok(statements) => json!({"status":"accepted","evidence":"parse-only",
            "statements": statements.iter().map(|s| {
                let debug = format!("{s:?}");
                let inner = debug.strip_prefix("Statement(").unwrap_or(&debug);
                let kind = inner.split(|c:char| !c.is_alphanumeric() && c != '_').next().unwrap_or("Unknown");
                json!({"kind":kind,"sql":s.to_string(),"ast_debug":debug})
            }).collect::<Vec<_>>() }),
        Err(message) => json!({"status":"rejected","evidence":"parse-only",
            "position":error_position(sql,&message),"message":message}),
    }
}

#[cfg(not(feature = "spice"))]
async fn execute(request: &Request) -> Value {
    use datafusion::arrow::util::display::array_value_to_string;
    use datafusion::prelude::{SessionConfig, SessionContext};
    let ctx = SessionContext::new_with_config(SessionConfig::new().with_target_partitions(1));
    for sql in &request.setup {
        let result = match ctx.sql(sql).await {
            Ok(df) => df.collect().await.map(|_| ()),
            Err(e) => Err(e),
        };
        if let Err(e) = result {
            return json!({"status":"setup-error","evidence":"execution","message":e.to_string()});
        }
    }
    let frame = match ctx.sql(&request.sql).await {
        Ok(df) => df,
        Err(e) => return json!({"status":"rejected","evidence":"planning","message":e.to_string()}),
    };
    let fields = frame
        .schema()
        .fields()
        .iter()
        .map(|f| {
            json!({
                "name":f.name(),"type":f.data_type().to_string(),"nullable":f.is_nullable()
            })
        })
        .collect::<Vec<_>>();
    match frame.collect().await {
        Err(e) => json!({"status":"rejected","evidence":"execution","message":e.to_string()}),
        Ok(batches) => {
            use datafusion::arrow::array::Array;
            let mut rows = Vec::new();
            for batch in batches {
                for row in 0..batch.num_rows() {
                    let mut values = Vec::new();
                    for array in batch.columns() {
                        if array.is_null(row) {
                            values.push(Value::Null);
                        } else {
                            match array_value_to_string(array.as_ref(), row) {
                                Ok(v) => values.push(json!(v)),
                                Err(e) => {
                                    return json!({"status":"transport-error","message":e.to_string()});
                                }
                            }
                        }
                    }
                    rows.push(values);
                }
            }
            json!({"status":"accepted","evidence":"execution","fields":fields,"rows":rows,
                "encoding":"Arrow display strings with SQL NULL separate; not a lossless nested/binary codec"})
        }
    }
}

async fn handle(request: &Request) -> Value {
    let mut result = match request.operation.as_str() {
        "parse" if request.setup.is_empty() => parse_response(&request.sql),
        "parse" => json!({"status":"request-error","message":"setup is only valid for execute"}),
        "execute" => {
            #[cfg(not(feature = "spice"))]
            {
                execute(request).await
            }
            #[cfg(feature = "spice")]
            {
                json!({"status":"unavailable","message":"Use the pinned Spice runtime execution adapter"})
            }
        }
        "catalog" if request.sql.is_empty() && request.setup.is_empty() => {
            #[cfg(not(feature = "spice"))]
            {
                catalog::capture().await
            }
            #[cfg(feature = "spice")]
            {
                json!({"status":"unavailable","message":"Capture Spice catalogs from its runtime"})
            }
        }
        _ => {
            json!({"status":"request-error","message":"operation must be parse, execute, or catalog with empty sql/setup"})
        }
    };
    result["id"] = json!(request.id);
    result["protocol_version"] = json!(1);
    result["profile"] = profile();
    result
}

#[tokio::main(flavor = "current_thread")]
async fn main() -> io::Result<()> {
    let mut output = io::BufWriter::new(io::stdout().lock());
    for line in io::stdin().lock().lines() {
        let line = line?;
        let result = match serde_json::from_str::<Request>(&line) {
            Ok(request) => handle(&request).await,
            Err(e) => {
                json!({"protocol_version":1,"status":"request-error","message":e.to_string()})
            }
        };
        serde_json::to_writer(&mut output, &result)?;
        writeln!(output)?;
        output.flush()?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn unicode_offsets_are_not_bytes_or_utf16_columns() {
        let sql = "SELECT '😀';\r\nSELECT é +";
        assert_eq!(
            byte_offset(
                sql,
                Location {
                    line: 2,
                    column: 10
                }
            ),
            Some(26)
        );
        let p = error_position(sql, "Expected expression Line: 2, Column: 10");
        assert_eq!(p["utf16_offset"], json!(23));
        let wrapped = error_position(
            sql,
            "SQL error: ParserError(\"Expected expression at Line: 2, Column: 10\")",
        );
        assert_eq!(wrapped, p);
        assert!(byte_offset(sql, Location { line: 5, column: 1 }).is_none());
    }
    #[test]
    fn scripts_do_not_accept_a_valid_prefix() {
        assert_eq!(parse("SELECT 1; SELECT 2").unwrap().len(), 2);
        assert!(parse("SELECT 1; SELECT (").is_err());
        assert!(parse("SELECT 1; /* unterminated").is_err());
        assert!(parse("-- comment only").unwrap().is_empty());
        assert_eq!(
            parse("SELECT ';', '😀'; /* nested /* x */ y */ SELECT 2")
                .unwrap()
                .len(),
            2
        );
    }
    #[test]
    fn parser_is_not_a_function_binder() {
        assert!(parse("SELECT deliberately_unknown_function(1)").is_ok());
    }

    #[test]
    fn actual_parser_error_maps_back_to_original_unicode_script() {
        let sql = "SELECT '😀';\r\nSELECT é + )";
        let result = parse_response(sql);
        assert_eq!(result["status"], json!("rejected"));
        assert_eq!(result["position"]["utf16_offset"], json!(25));
        assert_eq!(result["position"]["byte_offset"], json!(28));
    }

    #[cfg(not(feature = "spice"))]
    #[tokio::test]
    async fn execution_distinguishes_value_type_null_and_missing_function() {
        let request = Request {
            id: "control".into(),
            operation: "execute".into(),
            setup: vec![],
            sql: "SELECT 1 + 2 AS v, CAST(NULL AS VARCHAR) AS n".into(),
        };
        let result = execute(&request).await;
        assert_eq!(result["status"], json!("accepted"));
        assert_eq!(result["rows"], json!([["3", null]]));
        assert_eq!(result["fields"][0]["type"], json!("Int64"));
        assert_eq!(result["fields"][1]["nullable"], json!(true));
        let missing = Request {
            sql: "SELECT deliberately_unknown_function(1)".into(),
            ..request
        };
        let rejected = execute(&missing).await;
        assert_eq!(rejected["status"], json!("rejected"));
        assert_eq!(rejected["evidence"], json!("planning"));
    }
}
