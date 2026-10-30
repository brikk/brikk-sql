//! Registry constraints are separate from information_schema's representative signatures.
use datafusion::arrow::json::ArrayWriter;
use datafusion::logical_expr::TypeSignature;
use datafusion::prelude::{SessionConfig, SessionContext};
use serde_json::{Value, json};

fn signature(sig: &TypeSignature) -> Value {
    let mut result = json!({"native":format!("{sig:?}")});
    let kind = match sig {
        TypeSignature::Exact(types) => {
            result["types"] = json!(types.iter().map(ToString::to_string).collect::<Vec<_>>());
            "Exact"
        }
        TypeSignature::Uniform(n, types) => {
            result["arity"] = json!(n);
            result["types"] = json!(types.iter().map(ToString::to_string).collect::<Vec<_>>());
            "Uniform"
        }
        TypeSignature::Nullary => "Nullary",
        TypeSignature::Any(n) => {
            result["arity"] = json!(n);
            "Any"
        }
        TypeSignature::Numeric(n) => {
            result["arity"] = json!(n);
            "Numeric"
        }
        TypeSignature::String(n) => {
            result["arity"] = json!(n);
            "String"
        }
        TypeSignature::Comparable(n) => {
            result["arity"] = json!(n);
            "Comparable"
        }
        TypeSignature::OneOf(variants) => {
            result["variants"] = json!(variants.iter().map(signature).collect::<Vec<_>>());
            "OneOf"
        }
        TypeSignature::Variadic(types) => {
            result["types"] = json!(types.iter().map(ToString::to_string).collect::<Vec<_>>());
            "Variadic"
        }
        TypeSignature::VariadicAny => "VariadicAny",
        TypeSignature::UserDefined => "UserDefined",
        TypeSignature::ArraySignature(_) => "ArraySignature",
        TypeSignature::Coercible(_) => "Coercible",
    };
    result["kind"] = json!(kind);
    result
}

async fn query(ctx: &SessionContext, sql: &str) -> Value {
    let frame = match ctx.sql(sql).await {
        Ok(frame) => frame,
        Err(e) => return json!({"status":"unavailable", "message":e.to_string()}),
    };
    match frame.collect().await {
        Err(e) => json!({"status":"error","message":e.to_string()}),
        Ok(batches) => {
            let mut writer = ArrayWriter::new(Vec::new());
            if let Err(e) = writer
                .write_batches(&batches.iter().collect::<Vec<_>>())
                .and_then(|_| writer.finish())
            {
                return json!({"status":"error","message":e.to_string()});
            }
            match serde_json::from_slice::<Value>(&writer.into_inner()) {
                Ok(rows) => json!({"status":"accepted","rows":rows}),
                Err(e) => json!({"status":"error","message":e.to_string()}),
            }
        }
    }
}

pub async fn capture() -> Value {
    let ctx = SessionContext::new_with_config(
        SessionConfig::new()
            .with_information_schema(true)
            .with_target_partitions(1),
    );
    let state = ctx.state();
    let mut registry = Vec::new();
    macro_rules! functions {
        ($registry:expr, $kind:expr) => {
            for (name, function) in $registry {
                registry.push(json!({"name":name,"canonical_name":function.name(),"kind":$kind,
                    "aliases":function.aliases(),"signature":signature(&function.signature().type_signature),
                    "volatility":format!("{:?}",function.signature().volatility)}));
            }
        };
    }
    functions!(state.scalar_functions(), "SCALAR");
    functions!(state.aggregate_functions(), "AGGREGATE");
    functions!(state.window_functions(), "WINDOW");
    for name in state.table_functions().keys() {
        registry.push(
            json!({"name":name,"canonical_name":name,"kind":"TABLE","aliases":[],
            "signature":null,"volatility":null}),
        );
    }
    registry.sort_by_key(|r| {
        (
            r["name"].as_str().unwrap().to_owned(),
            r["kind"].as_str().unwrap().to_owned(),
        )
    });
    let mut queries = serde_json::Map::new();
    for (name, sql) in [
        ("show_functions", "SHOW FUNCTIONS"),
        (
            "routines",
            "SELECT * FROM information_schema.routines ORDER BY routine_name, function_type, data_type",
        ),
        (
            "parameters",
            "SELECT * FROM information_schema.parameters ORDER BY specific_name, rid, ordinal_position, parameter_mode",
        ),
        (
            "settings",
            "SELECT * FROM information_schema.df_settings ORDER BY name",
        ),
        ("list_udfs", "SELECT * FROM list_udfs() ORDER BY name"),
    ] {
        queries.insert(name.into(), query(&ctx, sql).await);
    }
    json!({"status":"accepted","evidence":"registry and runtime catalog capture; signature samples are not constraints",
        "registry":registry,"queries":queries,"keywords":datafusion_sql::sqlparser::keywords::ALL_KEYWORDS})
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn catalog_keeps_registry_constraints_separate_from_sql_examples() {
        let result = capture().await;
        assert_eq!(result["status"], "accepted");
        for name in ["routines", "parameters", "settings", "show_functions"] {
            assert_eq!(result["queries"][name]["status"], "accepted");
        }
        assert_eq!(result["queries"]["list_udfs"]["status"], "unavailable");
        let registry = result["registry"].as_array().unwrap();
        assert!(
            registry
                .iter()
                .any(|r| r["name"] == "generate_series" && r["kind"] == "TABLE")
        );
        let random = registry.iter().find(|r| r["name"] == "random").unwrap();
        assert_eq!(random["signature"]["kind"], "Nullary");
        assert_eq!(random["volatility"], "Volatile");
    }
}
