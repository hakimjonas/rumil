package parser.benchmarks

import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole

import java.util.concurrent.TimeUnit
import scala.compiletime.uninitialized

import parser.core.*

/** JMH benchmarks for the incremental parser.
  *
  * Each scenario applies a single [[TextEdit]] to a pre-built synthetic source file + green tree
  * and measures `IncrementalParser.incrementalParse`. Scenarios are chosen to exercise the distinct
  * paths through the implementation:
  *
  *   - tokenLocalEdit: single-char change inside one token — token-level fast path
  *   - stmtLocalEdit: operator flip inside one statement — block-level reparse
  *   - stmtInsert: append a new line — block-level reparse at SourceFile root
  *   - stmtDelete: delete a whole line — block-level reparse at SourceFile root
  *   - crossStmtEdit: edit spanning two statements — escalates to SourceFile
  *   - largeEdit: replacement larger than half the document — forces full reparse
  *
  * Comparing `fullReparse` against the incremental scenarios gives the speedup attributable to the
  * incremental path; the spread across scenarios tells us which paths pay off on what edits.
  *
  * Run with: `sbt "benchmarks/Jmh/run -i 5 -wi 3 -f 1 IncrementalBenchmarks"`
  */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, jvmArgs = Array("-Xms2G", "-Xmx2G"))
class IncrementalBenchmarks {

  // ~200 statements × 5 operators each ≈ 2.2 KB. Realistic for a small module;
  // large enough that incremental should measurably outperform full reparse.
  @Param(Array("200"))
  var statementCount: Int = uninitialized

  @Param(Array("5"))
  var opsPerStatement: Int = uninitialized

  var source: String = uninitialized
  var tree: GreenNode = uninitialized

  /** Offset of the first character of the statement halfway through the document. Used to target
    * edits so they hit a representative region, not the very start or end.
    */
  var midStmtStart: Int = uninitialized

  /** Offset of the last character (before `\n`) of that same statement. */
  var midStmtEnd: Int = uninitialized

  @Setup(Level.Trial)
  def setup(): Unit = {
    val (src, t) = IncrementalGrammar.synthesize(statementCount, opsPerStatement)
    source = src
    tree = t
    val lineWidth = 1 + opsPerStatement * 2 + 1 // digit + (op digit)* + newline
    val midLine = statementCount / 2
    midStmtStart = midLine * lineWidth
    midStmtEnd = midStmtStart + (lineWidth - 1)
  }

  /** A single character change inside the second operand of the middle statement. Should hit the
    * token-level fast path (a number token in-place).
    */
  @Benchmark
  def tokenLocalEdit(bh: Blackhole): Unit = {
    val target = midStmtStart + 2 // e.g. the `5` in `4+5*6` at line start
    val edit = TextEdit.replace(target, target + 1, "9")
    bh.consume(IncrementalParser.incrementalParse(tree, source, edit, IncrementalGrammar.parsers))
  }

  /** Flip an operator inside the middle statement. Changes token kind, so token-level fast path
    * won't fire; block-level reparse should resolve at SyntaxKind.Statement.
    */
  @Benchmark
  def stmtLocalEdit(bh: Blackhole): Unit = {
    val target = midStmtStart + 1 // the first operator char
    val edit = TextEdit.replace(target, target + 1, "-")
    bh.consume(IncrementalParser.incrementalParse(tree, source, edit, IncrementalGrammar.parsers))
  }

  /** Append a new statement at the end of the file. Block-level reparse at SourceFile root. */
  @Benchmark
  def stmtInsert(bh: Blackhole): Unit = {
    val edit = TextEdit.insert(source.length, "1+2*3\n")
    bh.consume(IncrementalParser.incrementalParse(tree, source, edit, IncrementalGrammar.parsers))
  }

  /** Delete the middle statement in one edit. Block-level reparse. */
  @Benchmark
  def stmtDelete(bh: Blackhole): Unit = {
    val edit = TextEdit.delete(midStmtStart, midStmtEnd + 1) // include trailing newline
    bh.consume(IncrementalParser.incrementalParse(tree, source, edit, IncrementalGrammar.parsers))
  }

  /** Replace the newline between two statements with a space — joins two statements. Edit spans two
    * statement boundaries so reparse region escalates to SourceFile or Block.
    */
  @Benchmark
  def crossStmtEdit(bh: Blackhole): Unit = {
    val newlineOffset = midStmtEnd // the `\n` at the end of the middle statement
    val edit = TextEdit.replace(newlineOffset, newlineOffset + 1, "+")
    bh.consume(IncrementalParser.incrementalParse(tree, source, edit, IncrementalGrammar.parsers))
  }

  /** A replacement larger than half the document — forces the full-reparse fallback via the
    * `adjustedEnd - regionStart >= newSource.length - minReparseSize` guard.
    */
  @Benchmark
  def largeEdit(bh: Blackhole): Unit = {
    val start = source.length / 4
    val end = (source.length * 3) / 4
    val replacement = IncrementalGrammar.synthesize(statementCount / 2, opsPerStatement)._1
    val edit = TextEdit.replace(start, end, replacement)
    bh.consume(IncrementalParser.incrementalParse(tree, source, edit, IncrementalGrammar.parsers))
  }

  /** Baseline: a full reparse with no incremental path. Used to compute the speedup of each
    * incremental scenario — `(full / incremental)` per scenario.
    */
  @Benchmark
  def fullReparseBaseline(bh: Blackhole): Unit = {
    bh.consume(parser.runtime.run(IncrementalGrammar.sourceFile, source))
  }
}
