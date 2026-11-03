# Spice Flight SQL failure evidence

Root-cause investigation of DFS-010-T1 and T2 on the existing Spice v2.4.0+models /
Arrow Flight SQL JDBC 19.0.0 pins. This is evidence of defective paths, not qualification
of those paths as supported.

- `observations.json`: 26 standalone Java probe groups. Includes JDBC and raw Flight
  controls, prepared-update reply bytes, same/other-session named statements, advertised
  versus streamed schemas, parameter nullability, allocation counters and a server-free
  binder failure. Probe source hashes, driver checksum and runtime identity are included.
- `source-evidence.json`: immutable source revisions, hashes and excerpts for the paths
  demonstrated by the probe. Arrow sources match the released Maven source artifacts.

See [the detailed investigation](../../../../docs/research/spice-flight-failures.md) for
causal chains, source links, limits and reproduction commands. The probe runs without
Brikk or IDE dependencies. Its driver-specific escape hatch and reflection are diagnostic
controls only. No production library workaround is introduced.

Validation: the complete standalone probe ran against the pinned release and produced all
26 groups. Six offline investigation checks pass, including the raw protocol reply, session
and schema controls, null behavior and allocation ownership. The full DataFusion Python suite
passes 64 checks, and the separate Spice native-evidence suite passes 3 checks.
