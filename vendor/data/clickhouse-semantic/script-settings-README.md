# CHSEM-014 preceding script SET state

`clickhouseScriptSemanticDiagnostics` now carries explicit preceding SET facts
between statements on exactly 26.10.2.103. Each call starts with unknown connection
state. This is conditional analysis of a script executed sequentially in one
session, assuming preceding statements succeed. It does not execute SET, inspect
user profiles or validate server permissions, settings constraints or parameter values.

The bounded state records Boolean values for `sql_compatible_division_for_integers`
and `decimal_check_overflow`. Integer 0/1, Boolean literals and the corresponding
string spellings are supported. Unresolved/parameterized values become unknown.
The latter setting is retained for CHSEM-015's Decimal rules.

Query-local SETTINGS takes precedence over script state, including unknown or
DEFAULT overrides. Nested query settings keep the existing nearest-scope rules.
Neither local overrides nor inner-query settings persist to the next statement.
The single-statement API deliberately remains independent of preceding text;
call the script API when that context should be used.

SET name=DEFAULT clears that fact instead of assuming a compiled or connection
default. Native changes are applied before resets, so a DEFAULT anywhere in a
multi-assignment statement wins over literal assignments of that same setting.
Ordinary duplicates without DEFAULT use the last assignment. Other ordinary SET
keys do not alter tracked facts, but profile/compatibility changes invalidate them.
Unsupported mutation forms, unknown commands/DDL and parse/boundary failures also
invalidate state conservatively. They are not guessed to be successful resets.
`SETTING_STATE` records these unknown transitions separately from errors.

The state is local to the analysis call and passed read-only into the existing
validator. No AST/source/type slots, caller schema, connection settings or public
function/data-class signatures change. UTF-16 positioning, cancellation and shared
script framing remain in use; inline INSERT data is not interpreted as SET.

`script-settings-sources.json` pins native session application, default/reset order,
parameter parsing and compatibility-sensitive resets. The 18 script fixtures run
as complete scripts in isolated clickhouse-local sessions, not separate processes
per statement. `script-settings-native/` compares original and generated scripts
on both digest pins, including unavailable-setting controls on the older pin.
The scripts cover persistence, overrides, duplicate/reset order, Boolean spellings,
division errors and unresolved parameters. Existing captures remain unchanged.

Validation: 1,860 JVM passes with 61 optional integration assumption aborts,
103 Python passes with one opt-in skip, and 757 IntelliJ passes on 261.25134.95.
No new IntelliJ API use, old public signature changes, publication or consumer edits.

Reproduction:

```sh
./kotlin test -m brikk-sql --include-classes='*ClickhouseScriptSettingsTest' \
  --jvm-args='-Dbrikk.clickhouseScriptSettingsOut=/tmp/opencode/script-settings-brikk.json'
python3 tools/probe_clickhouse_script_settings.py \
  --cases vendor/data/clickhouse-semantic/script-settings-cases.json \
  --brikk /tmp/opencode/script-settings-brikk.json --output /tmp/opencode/script-settings-native
```
