# CHSEM-013 positioned integer division diagnostics

The offline semantic checker reports `DIVISION_BY_ZERO` and
`INTEGER_DIVISION_OVERFLOW` with native category 153 on exactly 26.10.2.103.
The existing source capture `division-sources.json` contains the implementation's
zero test, magnitude quotient, signed bounds and nullable masking guards.

The checker shares CHSEM-012's resolved function, setting and operand/result type
facts. It evaluates only non-NULL integer constants formed from exact UInt64-range
numeric literals, unary signs, parentheses and lossless integer casts. Each cast
is checked against its own bounds. Quotient bounds use the first operand's width
and signedness from either operand, including Int/UInt128 and 256 declarations.
The decimal magnitude calculation is shared Kotlin, with no JVM big-integer library.
Input traversal and arithmetic are bounded and cancellation remains cooperative.

String conversions, wrapping casts, larger numeric literal spellings that native
parsing could round, arithmetic/alias evaluation and data-dependent operands are
not evaluated. A zero divisor with an unknown or nullable column numerator alone
does not prove an error. Constant NULL branches and floating delegates do not become
integer errors. Unproved checks retain explicit unknowns. A reported intrinsic
expression failure is not a verdict that a surrounding query executes that expression.
Conditional result types are retained even when the expression has a proven error.

Findings use the existing UTF-16 script positioning, parser separation and native
parser combination APIs. Existing public signatures are unchanged. No SQL, type
slots, caller schema or source metadata are mutated during diagnostics.

`division-diagnostics-cases.json` and `division-diagnostics-native/` contain 19
original/generated witnesses on each digest pin. Every reported error corresponds
to native `ILLEGAL_DIVISION`; controls cover NULL masking, floating infinity,
wrapping casts and nonoverflowing quotients. The older pin's unavailable setting
is an explicit error control, not an execution skip.

Historical CHSEM-012 captures are unchanged. `division-diagnostics-rules.json` and
`division-diagnostics-refinement-brikk.json` record the stronger current findings
for E13–E15 while retaining their conditional types.

CHSEM-014 owns preceding script SET state. CHSEM-015 owns Decimal division typing.
Neither is treated as a reason to reopen earlier completed work.

Validation: 1,856 JVM passes, 61 optional integration assumption aborts, 101 Python
passes and one opt-in skip, and 757 IntelliJ passes on 261.25134.95. The initial
concurrent IntelliJ build failed to load SqlScriptSnapshot; an isolated rerun passed
without production changes. Subsequent build gates run sequentially.
