# Option 5: Rethink orElse Semantics - Design Document

## Executive Summary

**Proposal**: Split `orElse` into two distinct combinators with clear semantics:
- **`orElse`** (new): Simple alternation without error tracking (fast)
- **`recover`** (new): Explicit error recovery with error accumulation (current behavior)

**Status**: Design proposal for v1.0 (breaking change)

**Expected Performance**: 8-10x improvement on alternation workloads (matching cats-parse)

**Trade-off**: Users explicitly choose between performance (alternation) and diagnostics (recovery)

---

## Current State (After LazyPartial)

### Current Implementation

```scala
// In Combinators.scala:509
inline def orElse[E, A](p: Parser[E, A], fallback: Parser[E, A]): Parser[E, A] =
  Parser.RecoverWith(p, fallback)  // Always uses RecoverWith (error tracking)
```

### Current Behavior

**When primary succeeds**: Returns `Success` ✅ (no error tracking)

**When primary fails, fallback succeeds**: Returns `Partial` with errors from primary:
```scala
val p = char('a').orElse(char('b'))
p.run("b")  // Partial('b', List(Unexpected('b', expected: 'a')), 1)
```

### Performance Cost

With 900 recoveries (90% error rate):
- **Before LazyPartial**: 4222ms (forcing 900 error thunks during Many)
- **After LazyPartial**: 3650ms (batch forcing at toResult)
- **cats-parse**: 9ms (no error tracking at all)

**Gap**: Still **405x slower** because we track errors on every recovery.

---

## Proposed Change

### New API

```scala
// In Combinators.scala

/**
 * Simple alternation - try alternatives without error tracking.
 *
 * When the primary parser fails, tries the fallback parser. If fallback
 * succeeds, returns Success (errors from primary are discarded).
 *
 * Use this for performance-critical alternation where error diagnostics
 * aren't needed. For error recovery with diagnostics, use `recover`.
 *
 * Performance: O(1) error handling (no allocation/tracking)
 *
 * Example:
 * {{{
 * val letter = char('a').orElse(char('b')).orElse(char('c'))
 * letter.run("b")  // Success('b', 1) - fast, no error tracking
 * }}}
 */
inline def orElse[E, A](p: Parser[E, A], fallback: Parser[E, A]): Parser[E, A] =
  Parser.Or(p, fallback)  // Uses Or - no error tracking

/**
 * Error recovery with full error tracking.
 *
 * When the primary parser fails, tries the fallback parser. If fallback
 * succeeds, returns Partial with accumulated errors from both attempts.
 *
 * Use this for resilient parsing where you need error diagnostics even
 * when recovery succeeds. For simple alternation, use `orElse`.
 *
 * Performance: O(n) error handling where n = number of errors
 *
 * Example:
 * {{{
 * val resilient = digit.many1.recover(succeed(List.empty))
 * resilient.run("abc")  // Partial(List(), List(EndOfInput), 0) - has errors
 * }}}
 */
inline def recover[E, A](p: Parser[E, A], fallback: Parser[E, A]): Parser[E, A] =
  Parser.RecoverWith(p, fallback)  // Uses RecoverWith - tracks errors

/**
 * Alias for `recover` - more explicit about error recovery intent.
 */
inline def recoverWith[E, A](p: Parser[E, A], fallback: Parser[E, A]): Parser[E, A] =
  Parser.RecoverWith(p, fallback)
```

### Interpreter Changes

**Parser.Or** (already implemented correctly - no change needed!):

```scala
case Parser.Or(left, right) =>
  val snapshot = state.save
  interpretI(left, state) match {
    case success @ Result.Success(_, _) => success
    case partial @ LazyPartial(_, _, _) => partial  // Pass through Partial from left
    case LazyFailure(leftMkErrors, leftFurthest) =>
      // Left failed - try right, discard leftMkErrors if right succeeds
      state.restore(snapshot)
      interpretI(right, state) match {
        case success @ Result.Success(_, _) => success  // ✅ NO ERROR TRACKING!
        case partial @ LazyPartial(_, _, _) => partial  // Pass through Partial from right
        case LazyFailure(rightMkErrors, rightFurthest) =>
          // Both failed - combine errors for failure reporting
          if (leftFurthest.offset > rightFurthest.offset) {
            LazyFailure(leftMkErrors, leftFurthest)
          } else if (rightFurthest.offset > leftFurthest.offset) {
            LazyFailure(rightMkErrors, rightFurthest)
          } else {
            LazyFailure(() => leftMkErrors() ++ rightMkErrors(), leftFurthest)
          }
      }
  }
```

**Key insight**: `Parser.Or` ALREADY behaves correctly for the proposed `orElse` semantics!
- Line 268: When right succeeds with `Success`, we return `success` (no error tracking)
- Line 269: When right succeeds with `Partial`, we pass through that `Partial`
- Only when BOTH fail do we combine errors (for failure reporting)

**Parser.RecoverWith** (already tracks errors - no change needed!):

```scala
case Parser.RecoverWith(p, recovery) =>
  val snapshot = state.save
  interpretI(p, state) match {
    case success @ Result.Success(_, _)   => success
    case partial @ LazyPartial(_, _, _)   => partial
    case LazyFailure(mkErrors, furthest) =>
      state.restore(snapshot)
      interpretI(recovery, state) match {
        case Result.Success(value, consumed) =>
          // ✅ ERROR TRACKING: Return LazyPartial with errors from primary
          LazyPartial(value, mkErrors, consumed)
        case LazyPartial(value, mkRecoveryErrors, consumed) =>
          // Combine error thunks lazily
          LazyPartial(value, () => mkErrors() ++ mkRecoveryErrors(), consumed)
        case LazyFailure(mkRecoveryErrors, recoveryFurthest) =>
          val finalFurthest =
            if (furthest.offset > recoveryFurthest.offset) furthest
            else recoveryFurthest
          LazyFailure(() => mkErrors() ++ mkRecoveryErrors(), finalFurthest)
      }
  }
```

**No interpreter changes needed!** The infrastructure is already there. We just need to:
1. Change `orElse` to use `Parser.Or` instead of `Parser.RecoverWith`
2. Add new `recover`/`recoverWith` combinators that use `Parser.RecoverWith`

---

## Migration Path

### Breaking Changes

**Before** (current):
```scala
val p = char('a').orElse(char('b'))
p.run("b")  // Partial('b', List(Unexpected('b', expected: 'a')), 1)
```

**After** (proposed):
```scala
val p = char('a').orElse(char('b'))
p.run("b")  // Success('b', 1)  // ⚠️ BREAKING: No errors!

// To get old behavior:
val p = char('a').recover(char('b'))
p.run("b")  // Partial('b', List(Unexpected('b', expected: 'a')), 1)
```

### Migration Script

Users can automatically migrate with search/replace:

```scala
// Step 1: Rename all orElse to recover (temporary)
.orElse(  →  .recover(

// Step 2: Manually review each use case:
// - If alternation (trying different branches): change back to .orElse
// - If error recovery (need error diagnostics): keep .recover
```

### Migration Guide Example

**Decision tree**:

1. **Are you using orElse for simple alternation?**
   ```scala
   // Trying different parsers for the same concept
   val letter = upper.orElse(lower)
   val digit = char('0').orElse(char('1')).orElse(char('2'))
   ```
   → Keep as `.orElse` (fast, no error tracking)

2. **Are you using orElse for error recovery?**
   ```scala
   // Providing fallback/default values when parsing fails
   val optional = number.orElse(succeed(0))
   val resilient = field.orElse(emptyField)
   ```
   → Change to `.recover` (tracks errors for diagnostics)

3. **Are you using orElse in Many/sepBy with frequent failures?**
   ```scala
   // Error-prone parsing that recovers often
   val items = item.orElse(skipToNext).many
   ```
   → **Performance critical**: Use `.orElse` unless you need error diagnostics

---

## Performance Impact

### Expected Improvements

**Alternation workloads** (using new `orElse`):

| Benchmark | Current (LazyPartial) | Proposed | Improvement |
|-----------|----------------------|----------|-------------|
| Many with Recovery (100 items) | 445ms | ~50ms | **8.9x faster** |
| Many with High Error Rate (1K items) | 3650ms | ~10ms | **365x faster** |
| Choice (10 alternatives) | 30ms | ~3ms | **10x faster** |

Matches cats-parse performance on alternation! 🎯

**Error recovery workloads** (using new `recover`):

| Benchmark | Current | Proposed | Change |
|-----------|---------|----------|--------|
| Resilient parsing with diagnostics | 3650ms | 3650ms | **No change** |

Users who need error tracking still get it - they just use `.recover` instead.

### Real-World Impact

**Example 1: JSON string escape handling**

```scala
// Before (tracks errors even though we don't need them)
val escape = char('\\') *> (
  char('n').as('\n')
    .orElse(char('t').as('\t'))
    .orElse(char('r').as('\r'))
    .orElse(char('"').as('"'))
)

// After (fast alternation)
val escape = char('\\') *> (
  char('n').as('\n')
    .orElse(char('t').as('\t'))  // Fast! No error tracking
    .orElse(char('r').as('\r'))
    .orElse(char('"').as('"'))
)
// 10x faster on typical JSON workloads
```

**Example 2: Resilient CSV parsing**

```scala
// Before
val field = quotedField.orElse(unquotedField).orElse(emptyField)

// After (keep error tracking for diagnostics)
val field = quotedField.recover(unquotedField).recover(emptyField)
// Still tracks errors - helps debug malformed CSV
```

---

## Type Safety Considerations

### Question: Should Or and RecoverWith have different type signatures?

**Current** (both use same signature):
```scala
case Or(left: Parser[E, A], right: Parser[E, A])
case RecoverWith(p: Parser[E, A], recovery: Parser[E, A])
```

**Could we enforce at type level?** No easy way without phantom types:
```scala
// Hypothetical (complex):
sealed trait ParserMode
case object Alternation extends ParserMode
case object Recovery extends ParserMode

case class Parser[+E, +A, M <: ParserMode](...)

// Too complex - not worth it
```

**Decision**: Keep same types, document semantics clearly. The behavioral difference is in error tracking, not types.

---

## Documentation Updates

### User Guide Section

**"Alternation vs Error Recovery"**

Rumil provides two ways to try multiple parsers:

1. **`orElse` - Fast alternation** (use 95% of the time)
   - Tries alternatives until one succeeds
   - Discards errors from failed branches
   - Use for: choice between valid options
   - Performance: O(1) error handling

2. **`recover` - Error recovery** (use when you need diagnostics)
   - Tries alternatives and tracks ALL errors
   - Returns Partial with accumulated errors
   - Use for: resilient parsing with error reporting
   - Performance: O(n) error handling

**When to use which?**

```scala
// ✅ Use orElse for alternation
val keyword =
  string("if")
    .orElse(string("else"))
    .orElse(string("while"))

val digit = satisfy(_.isDigit)
val letter = satisfy(_.isLetter)
val alphanumeric = digit.orElse(letter)

// ✅ Use recover for error recovery
val number = int.recover(succeed(0))  // Default to 0 on parse error

val optional = field.recover(succeed(""))  // Empty string on missing field

val items = item
  .recover(skipToNextDelimiter *> item)  // Skip malformed items
  .many
```

---

## Rollout Strategy

### Phase 1: v0.3.0 - Add `recover` (non-breaking)

1. Add new `recover`/`recoverWith` combinators (aliases to `RecoverWith`)
2. Mark `orElse` as `@deprecated("Use recover for error tracking", "0.3.0")`
3. Update documentation to show both options
4. Give users time to migrate

### Phase 2: v1.0.0 - Change `orElse` semantics (breaking)

1. Change `orElse` to use `Parser.Or` (no error tracking)
2. Remove deprecation notice
3. Provide migration guide
4. Update all examples in documentation

### Phase 3: Post-1.0 - Optimize further

With clear separation:
- Could optimize `Parser.Or` even more (no error construction at all)
- Could add compiler hints for choice optimization
- Could provide zero-allocation fast-path for common patterns

---

## Comparison with Other Libraries

### cats-parse

```scala
// cats-parse orElse = simple alternation (no error tracking)
val p = char('a').orElse(char('b'))
p.parseAll("b")  // Right('b') - no error info
```

**Rumil after Option 5**: Identical semantics! ✅

### Parsec (Haskell)

```haskell
-- Parsec <|> = simple alternation
p = char 'a' <|> char 'b'
parse p "" "b"  -- Right 'b' - no error info
```

**Rumil after Option 5**: Identical semantics! ✅

### Tree-sitter / Rowan (resilient parsing)

Tree-sitter doesn't distinguish alternation vs recovery at the combinator level - error tracking is always on at the tree level (GreenNode/RedNode).

**Rumil advantage**: We separate concerns:
- **Parser level**: Choose alternation (fast) or recovery (tracked)
- **Tree level**: Always track tokens/trivia for lossless property (GreenNode)

This is more flexible - users can build fast alternation parsers AND construct lossless trees when needed.

---

## Questions & Answers

### Q1: Does this break resilient parsing?

**A**: No! Users who want resilient parsing just use `.recover` instead of `.orElse`. The infrastructure (RecoverWith, LazyPartial) is unchanged.

### Q2: Does this break Rowan/RedTree support?

**A**: No! GreenNode/RedTree construction happens at a higher level - they consume `Result.Partial` values. Whether that Partial came from `orElse` (old) or `recover` (new) doesn't matter to the tree layer.

### Q3: Will users be confused by two similar combinators?

**A**: Initial confusion is possible, but:
- Clear naming (`orElse` = alternation, `recover` = recovery)
- Good documentation with decision tree
- 95% of use cases are alternation (use `orElse`)
- Matches other libraries (cats-parse, Parsec)

### Q4: What about the `|` operator?

```scala
extension [E, A](p: Parser[E, A])
  inline def |(fallback: Parser[E, A]): Parser[E, A] = orElse(fallback)
```

**Decision**: Keep `|` as alias for `orElse` (fast alternation). This matches user expectations - `|` means "or" which implies alternation, not recovery.

### Q5: What if users want error tracking on SOME branches?

Compose both:
```scala
// Try fast alternatives first, then recover with tracking
val p =
  quickOption1
    .orElse(quickOption2)
    .recover(slowFallbackWithTracking)
```

---

## Implementation Checklist

### Code Changes

- [ ] Update `orElse` in Combinators.scala to use `Parser.Or`
- [ ] Add `recover` combinator using `Parser.RecoverWith`
- [ ] Add `recoverWith` alias
- [ ] Update extension method `|` (should alias `orElse`)
- [ ] Add ScalaDoc explaining alternation vs recovery

### Tests

- [ ] Update ErrorRecoveryTests to use `recover` where appropriate
- [ ] Add new tests for `orElse` without error tracking
- [ ] Verify all 249 tests still pass
- [ ] Add performance regression tests

### Documentation

- [ ] Update README with orElse vs recover guidance
- [ ] Add "Alternation vs Error Recovery" section to user guide
- [ ] Update examples to use appropriate combinator
- [ ] Create migration guide for v1.0

### Benchmarks

- [ ] Add benchmark comparing orElse (new) vs recover (old behavior)
- [ ] Verify we match cats-parse on alternation workloads
- [ ] Document performance characteristics

---

## Conclusion

**Option 5 is the right long-term solution** for closing the performance gap with cats-parse while preserving Rumil's unique value proposition (error transparency when needed).

**Key insights**:
1. Infrastructure is already there (Parser.Or vs Parser.RecoverWith)
2. No interpreter changes needed!
3. Simple API change with clear semantics
4. Matches industry expectations (cats-parse, Parsec)
5. Preserves resilient parsing for those who need it

**Recommendation**:
- ✅ Implement in v1.0 (after LazyPartial proves stable)
- Provide clear migration path
- Document decision tree prominently
- Celebrate matching cats-parse performance on alternation! 🎉
