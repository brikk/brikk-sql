# Agent Guide

## Commit identity (Buzz agents)

When you (a Buzz agent) commit on someone's behalf, attribute the commit to the
**human who issued the request**, not to the machine's global git identity.

1. The requester is the signed author pubkey of the triggering Buzz event.
2. Look that pubkey up in a configured `.buzz/authors.toml` to get the
   git `name` / `email`.
3. Commit as that identity, crediting the model as a co-author:

   ```bash
   GIT_AUTHOR_NAME="<name>" GIT_AUTHOR_EMAIL="<email>" \
   git commit -m "<subject>

   Co-Authored-By: Claude <model> <noreply@anthropic.com>"
   ```

4. **Pubkey not in `.buzz/authors.toml`?** Stop and ask — never silently fall
   back to the machine's global identity. Add the person (reviewed) first.

This matters for a **shared** Buzz agent that acts for several people. A person's
own standalone agent that only ever commits as its owner can ignore it — that
agent's global git identity is already correct.

## Binary compatibility of public data classes

Other artifacts (the plugins, the Brikk Kotlin helper) are compiled separately against published
brikk-sql and support jars, so a changed JVM signature breaks them at link time
(`NoSuchMethodError`) even when their sources still compile. When you add a defaulted field to a
public data class they use (`RelationFacts`, `ColumnFact`, `ScopeColumn`, `SqlScriptParameters`,
`TablePositions`, ...), keep the earlier shape available:

```kotlin
// Binary compatibility: the constructor and copy from before newField.
@Deprecated("Binary compatibility only", level = DeprecationLevel.HIDDEN)
constructor(a: A = ..., b: B = ...) : this(a, b, newFieldDefault)

@Deprecated("Binary compatibility only", level = DeprecationLevel.HIDDEN)
fun copy(a: A = this.a, b: B = this.b): T = copy(a, b, newField)
```

The hidden constructor with the old defaults keeps both the exact constructor and Kotlin's synthetic
defaults constructor of the earlier shape, and is invisible to new code (no ambiguity, serialization
uses the primary constructor). Add the old shape to `BinaryCompatibilityTest` (core) or the
module's platform test. Removing or retyping a field is a breaking change: say so when publishing.
