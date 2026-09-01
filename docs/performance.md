# Performance

Performance characteristics, in brief:

- **Failure paths are cheap**: error construction is deferred (`LazyFailure`/`LazyPartial`),
  so backtracking branches that fail allocate no error objects until an error surfaces.
- **Alternation dispatches once**: orElse chains flatten into a single choice with radix
  string matching, instead of walking nested alternatives.
- **Stack safety is total**: sequential chains, deep repetition, and structural recursion run
  on a heap trampoline — a 7,000,000-parser `~` chain and 200,000-deep structural nesting
  pass in the test suite (`TrampolineStackSafetyTest`, `StructuralNestingStackSafety`).
- **Comparison**: competitive with cats-parse and zio-parser on most workloads, with wins on
  number parsing and first-match choice; repetition and sequential shapes are where
  cats-parse is ahead.

The benchmark sources live under `benchmarks/`, with a CI smoke step (fat jar runnable, no
measurement) keeping them executable.