package parser.runtime

import scala.collection.mutable

import parser.core._

// ============================================================================
// Ref - Controlled Mutation (Eru Pattern)
// ============================================================================

/**
 * Mutable reference cell for controlled mutation.
 *
 * Encapsulates mutation behind a pure interface, allowing functional-style
 * updates while maintaining efficiency.
 *
 * @tparam A The type of value stored
 */
final private class Ref[A](private var value: A) {
  def get: A                  = value
  def set(newValue: A): Unit  = value = newValue
  def update(f: A => A): Unit = value = f(value)
}

// ============================================================================
// Left Recursion Support - Seed-Growth Algorithm (Warth et al.)
// ============================================================================

/**
 * Entry in the memoization table for left recursion handling.
 *
 * @param result The cached parse result (None if currently being evaluated)
 * @param pos The position after parsing (for detecting progress)
 */
final private[runtime] case class MemoEntry(
  result: Option[Result[Any, Any]],
  pos: Int
)

/**
 * Tracks the "head" of a left-recursive rule.
 * Used to detect when we're in a left-recursive cycle.
 *
 * @param rule The parser identity that started the left recursion
 * @param involvedSet Parsers involved in this left-recursive cycle
 * @param evalSet Parsers that need re-evaluation during seed growth
 */
final private[runtime] class LRHead(
  val rule: AnyRef,
  val involvedSet: mutable.Set[AnyRef],
  var evalSet: mutable.Set[AnyRef]
)

/**
 * Left recursion marker used during seed detection.
 *
 * @param seed The current seed result
 * @param rule The parser identity
 * @param head The head of the left-recursive cycle (if known)
 */
final private[runtime] case class LR(
  var seed: Result[Any, Any],
  rule: AnyRef,
  var head: Option[LRHead]
)

// ============================================================================
// ParserState - Controlled Mutation via Refs
// ============================================================================

/**
 * Mutable parser state tracking position in input.
 *
 * Encapsulates all mutation needed for parsing while keeping the parser
 * descriptions themselves pure and immutable.
 *
 * State tracked:
 * - offset: Character position (0-indexed)
 * - line: Line number (1-indexed)
 * - column: Column number (1-indexed)
 *
 * Left recursion support:
 * - memo: Memoization table keyed by (parser-identity, position)
 * - lrStack: Stack tracking left-recursive rule invocations
 * - heads: Map from position to the head of left-recursive cycles
 *
 * Use the `parserState(input)` function to create instances.
 *
 * @param input The input string being parsed
 */
final class ParserState private[runtime] (
  val input: String,
  private val offsetRef: Ref[Int],
  private val lineRef: Ref[Int],
  private val columnRef: Ref[Int],
  // Left recursion support
  private[runtime] val memo: mutable.Map[(AnyRef, Int), Either[LR, MemoEntry]] = mutable.Map.empty,
  private[runtime] val lrStack: mutable.ArrayBuffer[LR] = mutable.ArrayBuffer.empty,
  private[runtime] val heads: mutable.Map[Int, LRHead] = mutable.Map.empty
) {

  def offset: Int = offsetRef.get
  def line: Int   = lineRef.get
  def column: Int = columnRef.get

  def location: Location =
    (line = lineRef.get, column = columnRef.get, offset = offsetRef.get)

  def atEnd: Boolean = offsetRef.get >= input.length

  def current: Option[Char] =
    if (atEnd) {
      None
    } else {
      Some(input(offsetRef.get))
    }

  def peek(n: Int): Option[Char] = {
    val idx = offsetRef.get + n
    if (idx >= input.length) {
      None
    } else {
      Some(input(idx))
    }
  }

  def advance(): Unit =
    if (!atEnd) {
      if (input(offsetRef.get) == '\n') {
        lineRef.update(_ + 1)
        columnRef.set(1)
      } else {
        columnRef.update(_ + 1)
      }
      offsetRef.update(_ + 1)
    }

  def advanceN(n: Int): Unit =
    (0 until n).foreach(_ => advance())

  def save: StateSnapshot =
    (offset = offsetRef.get, line = lineRef.get, column = columnRef.get)

  def restore(snapshot: StateSnapshot): Unit = {
    offsetRef.set(snapshot.offset)
    lineRef.set(snapshot.line)
    columnRef.set(snapshot.column)
  }

  def remaining: String = input.substring(offsetRef.get)

  def slice(start: Int, end: Int): String = input.substring(start, end)
}

/**
 * Snapshot of parser state for backtracking.
 *
 * Used by save/restore methods for implementing choice and lookahead.
 *
 * Fields:
 * - offset: Character offset (0-indexed)
 * - line: Line number (1-indexed)
 * - column: Column number (1-indexed)
 */
type StateSnapshot = (offset: Int, line: Int, column: Int)

/**
 * Creates a new parser state for the given input.
 *
 * Initial state:
 * - offset = 0
 * - line = 1
 * - column = 1
 *
 * @param input The string to parse
 * @return A fresh parser state ready to parse input
 *
 * Example:
 * {{{
 * val state = parserState("hello")
 * state.current  // Some('h')
 * }}}
 */
def parserState(input: String): ParserState =
  new ParserState(input, Ref(0), Ref(1), Ref(1))
