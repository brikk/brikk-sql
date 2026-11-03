// Adapted from Spice v2.4.0, crates/runtime/src/datafusion/planner/mod.rs,
// commit 78a47787ef74dec52d705bf9e26a0f9b7a26ca94.
// Copyright 2024-2026 The Spice.ai OSS Authors. Apache License 2.0.
// Changes: operate directly on DFParser; split scripts before the single-statement
// wrapper; preserve absolute error locations by blanking the preceding text.
use super::*;
use datafusion_sql::sqlparser::ast::Statement as SQLStatement;
use datafusion_sql::sqlparser::dialect::{Dialect, PostgreSqlDialect};
use datafusion_sql::sqlparser::keywords::Keyword;
use datafusion_sql::sqlparser::parser::Parser;
use datafusion_sql::sqlparser::tokenizer::{Token, Tokenizer};

fn single(sql: &str, dialect: &dyn Dialect) -> Result<Statement, String> {
    let mut statements =
        DFParser::parse_sql_with_dialect(sql, dialect).map_err(|e| e.to_string())?;
    if statements.len() != 1 {
        return Err("Expected exactly one statement".into());
    }
    Ok(statements.pop_front().unwrap())
}

fn cluster_range(sql: &str) -> Option<(usize, usize)> {
    let dialect = GenericDialect {};
    let tokens = Tokenizer::new(&dialect, sql)
        .tokenize_with_location()
        .ok()?;
    let significant: Vec<_> = tokens
        .iter()
        .enumerate()
        .filter(|(_, t)| !matches!(t.token, Token::Whitespace(_)))
        .collect();
    let mut depth = 0;
    for pair in significant.windows(2) {
        let [(_, token), (by_index, by)] = pair else {
            continue;
        };
        match &token.token {
            Token::LParen => depth += 1,
            Token::RParen => depth -= 1,
            Token::Word(word)
                if depth == 0
                    && word.keyword == Keyword::CLUSTER
                    && matches!(&by.token,Token::Word(w) if w.keyword==Keyword::BY) =>
            {
                let mut parser = Parser::new(&dialect)
                    .with_tokens_with_locations(tokens.get(by_index + 1..)?.to_vec());
                parser.parse_comma_separated(Parser::parse_expr).ok()?;
                return Some((
                    byte_offset(sql, token.span.start)?,
                    byte_offset(sql, parser.get_current_token().span.end)?,
                ));
            }
            _ => {}
        }
    }
    None
}

fn parse_statement(sql: &str) -> Result<Statement, String> {
    let original_error = match single(sql, &PostgreSqlDialect {}) {
        Ok(s) => return Ok(s),
        Err(e) => e,
    };
    let Ok(Statement::Statement(generic)) = single(sql, &GenericDialect {}) else {
        return Err(original_error);
    };
    let SQLStatement::CreateTable(table) = *generic else {
        return Err(original_error);
    };
    let Some(cluster_by) = table.cluster_by else {
        return Err(original_error);
    };
    let Some((start, end)) = cluster_range(sql) else {
        return Err(original_error);
    };
    let blank = sql[start..end]
        .chars()
        .map(|c| if c == '\n' { '\n' } else { ' ' })
        .collect::<String>();
    let replaced = format!("{}{}{}", &sql[..start], blank, &sql[end..]);
    let mut statement = single(&replaced, &PostgreSqlDialect {})?;
    let Statement::Statement(inner) = &mut statement else {
        return Err(original_error);
    };
    let SQLStatement::CreateTable(table) = inner.as_mut() else {
        return Err(original_error);
    };
    if table.cluster_by.is_some() {
        return Err(original_error);
    };
    table.cluster_by = Some(cluster_by);
    Ok(statement)
}

pub fn parse_script(sql: &str) -> Result<Vec<Statement>, String> {
    let tokens = Tokenizer::new(&PostgreSqlDialect {}, sql)
        .tokenize_with_location()
        .map_err(|e| e.to_string())?;
    let mut boundaries = Vec::new();
    for token in &tokens {
        if token.token == Token::SemiColon {
            boundaries.push(byte_offset(sql, token.span.end).ok_or("Invalid tokenizer offset")?);
        }
    }
    boundaries.push(sql.len());
    let mut start = 0;
    let mut result = Vec::new();
    for end in boundaries {
        let prefix = sql[..start]
            .chars()
            .map(|c| if c == '\n' { '\n' } else { ' ' })
            .collect::<String>();
        let statement = format!("{prefix}{}", &sql[start..end]);
        start = end;
        if DFParser::parse_sql_with_dialect(&statement, &PostgreSqlDialect {})
            .is_ok_and(|s| s.is_empty())
        {
            continue;
        }
        result.push(parse_statement(&statement)?);
    }
    Ok(result)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn cluster_extension_does_not_enable_other_generic_syntax() {
        assert!(parse_script("CREATE TABLE t (id INT) CLUSTER BY (id)").is_ok());
        assert!(parse_script("CREATE TABLE `t` (id INT) CLUSTER BY (id)").is_err());
        assert!(
            parse_script("SELECT 1; CREATE TABLE t (id INT) CLUSTER BY (id); SELECT 2").is_ok()
        );
    }
}
