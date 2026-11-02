# DataFusion and Spice support boundaries

This is the shared-library qualification for DFS-012. It covers the pinned profiles below
and the tested consumer fixture. A production IntelliJ plugin still needs its own packaged
integration and driver smoke tests. Publication remains on hold.

## Qualified profiles

- DataFusion 55.2.0, upstream revision `500744d5c25499055e3b2ca46b022bc425989203`,
  DFParser with GenericDialect, identifier normalization enabled. The SQL reference uses
  the pinned feature set in `tools/datafusion-reference`. Flight execution is qualified only
  for the bounded `flight_execution.rs` embedding, with per-handshake contexts, typed direct
  CAST parameter inference, utility schemas and lazy result streams. This is a disposable
  test embedding, not a production DataFusion server.
- Spice v2.4.0+models, checksum-pinned released executable, PostgreSQL parser profile with
  identifier normalization disabled. Its DataFusion fork is 55.2.0 at
  `cf084aa85dfdc27b4736208f5c132828020774ea`. The local Spicepod contains deterministic local
  data and no configured model endpoints. Optional compiled features are partially known.
  Product version `2.4.0` and embedded engine version `55.2.0` are separate facts.
- Arrow Flight SQL JDBC 19.0.0, with the checksum and driver JVM options in the
  [consumer guide](datafusion-spice-consumer.md). The runtime pair matters; syntax alone
  does not qualify a driver or endpoint.

Only these pins are qualified. Other versions receive a labeled bundled metadata fallback,
not an exact-version claim. Known mismatched parser settings or versions refuse Brikk
lowering. The [upgrade workflow](research/datafusion-spice-upgrades.md) requires fresh
evidence before changing a default.

## Reading the matrices

"Fixture" means the checked corpus and selected forms, not every SQL construct in a family.
"Local" means core/static analysis without executing SQL. "Partial" retains unknown types,
signatures, fields or provider behavior. Native evidence distinguishes parser acceptance
from execution. An opaque command, skipped probe or accepted JDBC method is never counted
as semantic support.

Evidence paths below are relative to `vendor/data/datafusion-spice/`. Historical captures
remain unchanged. The RESET follow-up supersedes the historical round11 RESET gap.

## DataFusion matrix

| Feature | Structured parse | Faithful generation | Analysis | Metadata | Native evidence | IDE coverage |
| --- | --- | --- | --- | --- | --- | --- |
| CTEs, joins, sets, grouping, windows, QUALIFY, ordering, limits, arrays and wildcard forms | Fixture | Fixture | Local scope, shape, binds and lineage | Types and keywords | `round3`, `round4`, `round5`; selected execution | Parser, formatting, completion and references |
| Registered functions | Calls retain arguments; aggregate/window kinds retained | Native spellings and source-aware rewrites | Evidence-backed returns; unknowns retained | 320 name/kind entries; overloads only where justified | Registry and targeted execution controls | Context completion, docs and conservative arity/type checks |
| DDL/DML, external tables, COPY and views | Selected inventory | Selected inventory | Partial statement/relations | Provider-dependent | Primarily parser evidence | Structured PSI or core-backed fallback |
| RESET configuration names | Yes, `DatafusionReset` | Quoted components retained | Modifies state | Setting names from live inventory | `reset`: 12 accepted round trips, 13 rejections; no RESET execution | Syntax diagnostics, whole-statement handling |
| SET | Selected structured forms | Selected forms | Setting/state support is partial | Session-specific | `round10` and `round12` bounded embedding only | Stock selection and explicit execution |
| SHOW, PREPARE/EXECUTE and EXPLAIN | Dedicated `Datafusion*` nodes for qualified forms | Ordered options, typed bodies, names and arguments retained | Traversable statements; last-wins ANALYZE classification; server-owned `$n` | Session-specific | `utilities` parser/AST round trips; bounded execution remains separate | Core-backed diagnostics, stock prompting/selection and explicit execution |
| CREATE TABLE CLUSTER BY | `Create` with `ClusterProperty` | Tuple keys, separate keys and expressions stay distinct | Structured key expressions; state modification | Provider-dependent | `utilities` parser/AST controls, not storage execution | Whole-statement diagnostics; GenericDialect backticks accepted |
| JDBC parameters | `$n`; client forms tracked separately | No automatic `?` transport conversion | Exact source spans | Typed CAST fixture only | Typed `$n`, rebinding and nullable values in the bounded fixture | Stock console contracts; see T3 |
| Live catalogs | Native metadata queries | Not applicable | Arrow/namespace facts; open partial schemas | information_schema and SHOW FUNCTIONS | Real Flight reads and production Kotlin harvester | Separate session/shared-cache fallback and refresh |
| Brikk pipes | First-class AST | Ten qualified lowered stages | Stage input/output facts | Copied catalog inputs | All ten generated SQL controls execute | Explicit opt-in, preview, run-to-stage, freshness guards |

Native DataFusion pipe syntax differs from Brikk lowering. Seven SELECT-headed native pipe
controls execute in the recorded embedding; DISTINCT, OFFSET and WINDOW controls do not.
This does not certify every native pipe grammar form or another embedding's enabled features.

## Spice matrix

| Feature | Structured parse | Faithful generation | Analysis | Metadata | Native evidence | IDE coverage |
| --- | --- | --- | --- | --- | --- | --- |
| Common query forms | Fixture, product parser profile | Fixture, case-preserving | Local scope, shape, binds and lineage | Product types and keywords | `round4`, `round5`; selected execution | Parser, formatting, completion and references |
| Registered functions and aliases | Lossless calls with kinds | Native names; no borrowed semantics | Evidence-backed types; partial signatures | 447 name/kind entries | Pinned runtime registry and selected scalar controls | Version/provenance labels, docs and conservative inspections |
| JSON operators and shredding | Product-specific AST/roles | Native operators retained | Fixed schemas where proved; dynamic fields open | Source/runtime evidence | Pinned parser and targeted values/types | Parameters distinguished from JSON `?` |
| Search, RRF, rerank, AI and embed | Native positional/named forms | All supplied arguments retained | Dataset/column roles, partial scores and literal AI structs | Optional availability; scalar stubs distinct from table functions | Parser/source evidence; configured search/model execution unqualified | Named options, dataset references, docs and partial result columns |
| CLUSTER BY, DDL and connector DML | Selected forms | Product-specific forms | Partial statement/relations | Connector/accelerator-dependent | Parser evidence, not universal write capability | Native syntax fallback and diagnostics |
| SHOW, RESET and EXPLAIN | Dedicated utility nodes | Native forms | State/planning/execution classification | Live settings/inventories | Selected native controls; RESET-all semantics unqualified | Whole statements, explicit EXPLAIN and ANALYZE |
| SQL PREPARE/EXECUTE and SET | Structured syntax | Native text | Server-owned binds | Session-specific | Transport execution excluded by T1 | Syntax support does not promise execution |
| JDBC parameters | `$n` and `?` apart from JSON containment | No unsafe literal substitution | Source spans and occurrence identity | Driver-reported nullability | Non-null measured bindings; null binding excluded by T2 | Stock client prompting contracts |
| Live catalogs | Native metadata queries | Not applicable | Quoted/dotted paths, Arrow fields, views | Product and engine versions distinct | Real Flight reads and production Kotlin harvester | Per-console inventories, reconnect and manual refresh |
| Brikk pipes | First-class AST | Ten qualified lowered stages | Stage facts with partial schemas | Copied catalogs | All ten lowered controls execute; all ten native controls reject | Off by default; explicit preview/run actions |

## Explicit exclusions and known limits

- DataFusion utility support covers the [qualified forms](../vendor/data/datafusion-spice/utilities/README.md),
  not every syntax variant accepted by GenericDialect. Other SHOW variants such as SHOW CREATE,
  dynamic EXECUTE IMMEDIATE and EXECUTE USING/INTO/OUTPUT/DEFAULT remain unqualified and are
  refused rather than misrepresented as settings or arguments. Parser support is not a promise
  that an embedding executes a utility statement or implements cluster-key storage behavior.
- **DFS-010-T1:** Reliable Spice SQL PREPARE/EXECUTE session execution and SQL SET are excluded
  on this transport. Generic `Statement.execute` returns a misleading zero update count for
  EXECUTE and a missing-column SELECT; SQL SET rejects with `SetVariable`. Do not treat these
  outcomes as successful validation or result retrieval.
- **DFS-010-T2:** Spice JDBC null binding is excluded. The measured bind is reported as
  non-nullable and fails. A failed null bind after a string bind also reports an Arrow
  allocator leak when closing the separate failure connection. Non-null binds do not prove
  nullable-parameter support or successful cleanup after this failure.
- **DFS-010-T3:** JDBC `?` is excluded for the bounded DataFusion execution fixture. Typed `$n`
  controls pass. Another embedding must qualify its own inference and placeholder handling.
- **DFS-010-T4:** Guaranteed interruption of arbitrary server workloads is excluded. Bounded
  cancellation and subsequent SELECT pass on the final fixtures; earlier cross-join stress
  controls hung or needed a watchdog. Client cancellation is not proof remote computation stopped.
- Background native verification is unavailable for both products. Flight preparation and
  EXPLAIN may plan or execute functions. Local diagnostics remain available and no clean native
  verdict is manufactured. EXPLAIN ANALYZE is an explicit execution action.
- Search/model-backed execution, paid model calls, remote connectors, storage permissions,
  accelerator-specific writes and arbitrary UDF behavior are not qualified by these fixtures.
- Representative introspection signatures are not exhaustive overloads. Missing constraints,
  remarks, table-function output fields and types remain unknown. Partial inventories never
  replace complete cached inventories after a failed refresh.
- Native-only utility/operator/search semantics cannot be translated to another engine just
  because the function name exists there. Unsupported translations and lowerings remain refusals.
- Other GenericDialect RESET quote forms and RESET-all execution semantics are unqualified.
  `DFS-GAP-612e0f186332398a` itself is resolved, not an open blocker.
- No PostgreSQL wire compatibility, universal Flight product identity, unrestricted SQL
  execution guarantee or production plugin support follows from the shared-library tests.

## Evidence and qualification

The fresh DFS-012 captures and gate record are in
[`vendor/data/datafusion-spice/round12/`](../vendor/data/datafusion-spice/round12/README.md).
Earlier evidence remains useful for specific contracts:

- `round3` and `round4`: parser/generator native checks and negative controls.
- `round5`: analysis values, types and schemas from DFS-006.
- `round8`: editor semantic controls and source-backed named arguments.
- `round9`: live catalog, namespace and driver/harvester evidence.
- `round10`: execution, result rows, binding, cancellation and Brikk-stage evidence.
- `flight-investigation`: [root causes of T1 and T2](research/spice-flight-failures.md), isolated
  with raw Flight, JDBC and server-free controls. These findings do not remove the exclusions.
- `round11`: immutable refresh and controlled-delta tracking; historical RESET gap retained.
- `reset`: the subsequent DataFusion RESET correction and native round trips.
- `utilities`: structured DataFusion SHOW, EXPLAIN, PREPARE/EXECUTE and CLUSTER BY. This resolves
  `DFS-012-S1`; the round12 exclusion remains historical evidence. All ten former opaque controls
  now have structured nodes. DataFusion and Spice retain separate utility classes and quote profiles.

Core and metadata remain Java 17 compatible. Shared IDE support targets Java 21 and compiles
against IDE 261; runtime contracts also run on 262 and 263. ABI bridges preserve earlier public
data-class shapes. No new production API signatures or internal-API allowances are needed for
this handoff. See the [consumer guide](datafusion-spice-consumer.md) for candidate coordinates,
extension registration, transport configuration and downstream adoption gates.
