# Error Path Performance Analysis

## Problem Statement

ErrorPathBenchmarks revealed significant performance gaps on error-heavy workloads:

| Benchmark | Rumil | cats-parse | Gap |
|-----------|-------|------------|-----|
| Many with 50% errors (100 items) | 19ms | 5ms | **3.8x slower** |
| Many with 90% errors (1K items) | 60ms | 7ms | **8.6x slower** |
| sepBy with errors (10 numbers) | 14ms | 3ms | **4.7x slower** |

The gap **increases** with error rate:
- 50% errors: 3.8x slower
- 90% errors: 8.6x slower

This suggests the bottleneck is **proportional to error frequency**.

---

## Root Cause Analysis

### What the Benchmark Does

```scala
val input = ("x" * 9 + "a") * 100  // 900 'x', 100 'a'

val rumilParser = {
  val errorProne = char('a').orElse(char('x'))  // Recovery pattern
  parser.core.many(errorProne)
}
```

When parsing 'x':
1. `char('a')` fails → creates LazyFailure thunk
2. `orElse` tries fallback → `char('x')` succeeds
3. `RecoverWith` produces **Partial result with error**

### The Bottleneck: Forced Error Evaluation

**File**: `Interpreter.scala:404-408`

```scala
case Parser.RecoverWith(p, recovery) =>
  val snapshot = state.save
  interpretI(p, state) match {
    case LazyFailure(mkErrors, furthest) =>
      state.restore(snapshot)
      // BOTTLENECK: Force errors here!
      val errors = mkErrors()  // Line 404
      interpretI(recovery, state) match {
        case Result.Success(value, consumed) =>
          // Recovered successfully, but note the original errors
          Result.Partial(value, errors, consumed)  // Line 408
```

**What happens**:
- **EVERY** time RecoverWith's fallback succeeds, we force the error thunk
- For 900 recoveries, we evaluate 900 error thunks
- Each evaluation constructs a `List(ParseError.Unexpected(...))`
- These errors are accumulated in Many's ListBuffer

**Why this is slow**:
1. **Error construction**: Creating ParseError objects (location, expected set, found string)
2. **List allocation**: Each `mkErrors()` returns `List(error)`
3. **String operations**: Capturing `found.toString` for error messages
4. **No benefit**: These errors are often ignored (user just wants parsed result)

### Why cats-parse is Faster

cats-parse `orElse` likely:
- Does **not** treat fallback success as an error condition
- Returns simple `Success` when either branch succeeds
- Only tracks errors on **complete failure**

This is a fundamental design difference:
- **Rumil**: `orElse` is error recovery → produces Partial with accumulated errors
- **cats-parse**: `orElse` is simple alternation → produces Success when any branch works

---

## Design Trade-off

Rumil's approach has advantages:
- **Error transparency**: Users see which branches failed
- **Debugging**: Full error history available for diagnosis
- **Resilient parsing**: Can continue after errors

But it has a **performance cost** when errors accumulate heavily.

---

## Potential Solutions

### Option 1: Lazy Partial Results (Low-Hanging Fruit)

Instead of forcing errors immediately in RecoverWith, keep them lazy:

```scala
case Parser.RecoverWith(p, recovery) =>
  interpretI(p, state) match {
    case LazyFailure(mkErrors, furthest) =>
      state.restore(snapshot)
      // DON'T force errors yet!
      interpretI(recovery, state) match {
        case Result.Success(value, consumed) =>
          // Keep errors lazy - wrap in thunk
          LazyPartial(value, mkErrors, consumed)  // New case!
```

**Pros**:
- Defers error construction until actually needed
- Preserves error information for debugging
- No semantic change

**Cons**:
- Requires new `LazyPartial` internal result type
- Errors still forced when result is converted to public API
- Doesn't help if user inspects Partial.errors

**Complexity**: Medium
**Expected gain**: 2-3x (defers work but doesn't eliminate it)

---

### Option 2: Silent Recovery Mode (Flag-Based)

Add an optional flag to control error tracking:

```scala
// New combinator
inline def orElseSilent[E, A](p: Parser[E, A], fallback: Parser[E, A]): Parser[E, A] =
  Parser.Or(p, fallback)  // Use Or instead of RecoverWith

// Or: add mode flag to RecoverWith
case Parser.RecoverWith(p, recovery, trackErrors: Boolean)
```

**Pros**:
- Users choose performance vs error tracking trade-off
- Backward compatible (keep existing orElse)
- Simple implementation

**Cons**:
- API complexity (two orElse variants)
- Users must know when to use which
- Doesn't help existing code

**Complexity**: Low
**Expected gain**: 8-10x (eliminates error tracking entirely when silent)

---

### Option 3: Discard Errors Below Threshold (Heuristic)

Only keep errors that reached "far enough" in the input:

```scala
case Parser.RecoverWith(p, recovery) =>
  interpretI(p, state) match {
    case LazyFailure(mkErrors, furthest) =>
      // Only force errors if they're "interesting"
      val shouldTrack = furthest.offset > state.offset - THRESHOLD
      if (shouldTrack) {
        val errors = mkErrors()
        // ... create Partial
      } else {
        // Discard uninteresting error silently
        // ... create Success
      }
```

**Pros**:
- Automatic (no API changes)
- Keeps "interesting" errors, discards noise
- Users don't need to think about it

**Cons**:
- Heuristic-based (magic threshold value)
- Non-deterministic error reporting
- Breaks error transparency guarantee

**Complexity**: Low
**Expected gain**: Varies (2-5x depending on threshold)

---

### Option 4: Error Sampling (Probabilistic)

Only track a sample of errors:

```scala
case Parser.RecoverWith(p, recovery) =>
  interpretI(p, state) match {
    case LazyFailure(mkErrors, furthest) =>
      // Sample errors with probability P (e.g., 10%)
      if (state.rng.nextDouble() < ERROR_SAMPLING_RATE) {
        val errors = mkErrors()
        // ... create Partial
      } else {
        // ... create Success (error discarded)
      }
```

**Pros**:
- Bounded error accumulation
- Still provides error diagnostics (sample)
- Automatic

**Cons**:
- Non-deterministic
- Requires RNG state
- Sampling rate is a magic constant

**Complexity**: Medium
**Expected gain**: ~10x (at 10% sampling rate)

---

### Option 5: Rethink orElse Semantics (Breaking Change)

Change `orElse` to behave like cats-parse `orElse`:
- Success on either branch → `Success` (no error tracking)
- Keep `RecoverWith` as a separate combinator for explicit error recovery

```scala
// Simple alternation (no error tracking)
inline def orElse[E, A](p: Parser[E, A], fallback: Parser[E, A]): Parser[E, A] =
  Parser.Or(p, fallback)

// Explicit error recovery (tracks errors)
inline def recover[E, A](p: Parser[E, A], fallback: Parser[E, A]): Parser[E, A] =
  Parser.RecoverWith(p, fallback)
```

**Pros**:
- Semantic clarity (alternation vs recovery are different)
- Performance matches cats-parse on alternation workloads
- Users explicitly choose error tracking

**Cons**:
- **BREAKING CHANGE**: Existing `orElse` behavior changes
- May confuse users (why two combinators?)
- Requires migration

**Complexity**: Low (implementation), High (migration)
**Expected gain**: 8-10x (for alternation use cases)

---

## Recommendation

### For v0.2.0: **Option 1 (Lazy Partial Results)**

**Rationale**:
1. **Non-breaking**: Preserves existing semantics and API
2. **Type-safe**: Uses same techniques as LazyFailure
3. **Principled**: Defers work until needed, like lazy error evaluation
4. **Measurable**: Expected 2-3x improvement on error-heavy workloads

**Implementation**:
```scala
// Add new internal result type
final private[runtime] case class LazyPartial[+E, +A](
  value: A,
  mkErrors: () => List[E],  // Lazy error thunk
  consumed: Int
)

private[runtime] type IResult[+E, +A] =
  Result.Success[E, A] | LazyPartial[E, A] | LazyFailure[E]

// Update toResult to force LazyPartial
private[runtime] def toResult[E, A](ir: IResult[E, A]): Result[E, A] = ir match {
  case s: Result.Success[?, ?]  => s.asInstanceOf[Result[E, A]]
  case LazyPartial(v, mkErrs, c) => Result.Partial(v, mkErrs(), c)
  case LazyFailure(mkErrs, loc) => Result.Failure(mkErrs(), loc)
}
```

**Benefits**:
- Many accumulates LazyPartial results without forcing errors
- Errors only forced once at the very end (toResult call)
- 900 error thunk evaluations → 1 batch evaluation

### For v1.0: **Option 5 (Rethink orElse Semantics)**

After v0.2.0 release, consider breaking change:
- Rename current `orElse` to `recover` or `recoverWith`
- Create new `orElse` that uses `Or` (no error tracking)
- Provide migration guide

This aligns with user expectations:
- `orElse` = "try alternatives" (simple, fast)
- `recover` = "handle errors" (tracks errors, slower)

---

## Testing Strategy

1. **Implement LazyPartial**
2. **Run all tests** - verify semantics unchanged
3. **Run ErrorPathBenchmarks** - measure improvement
4. **Compare with cats-parse** - verify we close the gap
5. **Document trade-offs** in user guide

Expected results:
- Many with 90% errors: 60ms → ~20ms (3x improvement)
- sepBy with errors: 14ms → ~5ms (3x improvement)
- Still slower than cats-parse (they don't track errors at all)

---

## Implementation Plan

### Phase 1: LazyPartial (This Iteration)
1. Add `LazyPartial` case to IResult
2. Update RecoverWith interpreter to return LazyPartial
3. Update Many/Many1 to handle LazyPartial
4. Run tests and benchmarks

### Phase 2: Propagate LazyPartial (If Needed)
1. Review all interpreters for error forcing
2. Ensure LazyPartial propagates through combinators
3. Only force at final toResult boundary

### Phase 3: Document (After Benchmarking)
1. Update PERFORMANCE_ANALYSIS.md with findings
2. Document error handling design in user guide
3. Consider API evolution for v1.0

---

## Alternative: Just Document the Trade-off

If optimization doesn't yield sufficient gains:

**Accept** that error tracking has a cost:
- Document that `orElse` tracks errors for recovery
- Recommend `Or` combinator (`|`) for simple alternation without error tracking
- Educate users on performance implications

**Rumil's value proposition**:
- Error transparency and resilient parsing
- Users willing to pay 3-5x performance cost for better error messages
- Different trade-off than cats-parse (not strictly worse)

---

## Conclusion

The 8.6x error-path slowdown is **not a bug**, it's a **design trade-off**:
- Rumil tracks all error paths for transparency
- cats-parse discards errors for performance

**Best path forward**:
1. Implement LazyPartial to defer error construction (non-breaking)
2. Measure improvement (expected 2-3x)
3. Decide if remaining gap is acceptable
4. Consider API evolution for v1.0

The scientific methodology is working - we're understanding the real costs of our design decisions.
