# Brikk SQL IntelliJ test support

Test fixtures for IntelliJ database dialect plugins. Use it in test scope only; never
bundle it in a plugin. It is versioned and released with `brikk-sql-intellij-support`.

## RecordingConsole

A real `JdbcConsole` whose session records submitted requests instead of executing them.
Each request also becomes finished session work, which the IDE 263 gutter renderer needs.

```kotlin
RecordingConsole(project, file, "my-driver-id").use { recording ->
    executeThroughThePlugin(recording.console)
    val sql = (recording.requests.single() as DataRequest.QueryRequest).query
}
```

- `requests`: every request submitted through any of the fixture's sessions, in order.
  An empty list means nothing was sent.
- `onSubmit`: runs after a request is recorded; throw from it to fail or cancel a submission.
- `replaceSession()` / `replaceDataSource()`: dispose the console and attach a new one to
  the same file, as a session or data source switch does. Change the URL through
  `console.dataSource.url`; detach clients through `console.session.detach(console)`.
- `close()` disposes the console, removes its data sources, and fails if the platform
  called a session method the recording does not model.

Set the file's SQL dialect before constructing the fixture and clear it after closing.

## Platform tests

Contracts for this module and `brikk-sql-intellij-support` live in `platform-test` and run
in a real IDE: `./kotlin check intellijPlatform -m brikk-sql-intellij-support`.
