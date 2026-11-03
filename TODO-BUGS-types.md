# TODO - type-inference parity (in scope)

> The annotated corpus gates compare Brikk's full annotated ASTs with the
> pinned SQLGlot oracle. Their committed
> `brikk-sql/testResources/annotate-corpus/known-failures-*.json` ledgers are
> authoritative.

---

**0 actionable items.**

The Trino annotation ledger has 7 protected entries: the original 3
`ALTER TABLE ... SET PROPERTIES` parser-AST differences, 3 structured
`SET AUTHORIZATION` differences, and JSON_VALUE's explicit return type instead
of SQLGlot UNKNOWN. These are native-verifier-backed extensions, not defects
to regress toward the Python oracle. See `docs/brikk-extensions.md` sections
8 and 58. Other dialects' committed ledgers remain authoritative.

Run the authoritative gate with:

`./kotlin test -m brikk-sql --include-classes='dev.brikk.house.sql.AnnotateTypesCorpusTest'`
