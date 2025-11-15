package parser.runtime

import parser.core.*

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
private final class Ref[A](private var value: A) {
  def get: A = value
  def set(newValue: A): Unit = { value = newValue }
  def update(f: A => A): Unit = { value = f(value) }
}

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
 * Use the `parserState(input)` function to create instances.
 *
 * @param input The input string being parsed
 */
final class ParserState private[runtime] (
  val input: String,
  private val offsetRef: Ref[Int],
  private val lineRef: Ref[Int],
  private val columnRef: Ref[Int]
) {

  def offset: Int = offsetRef.get
  def line: Int = lineRef.get
  def column: Int = columnRef.get

  def location: Location = {
    (line = lineRef.get, column = columnRef.get, offset = offsetRef.get)
  }

  def atEnd: Boolean = offsetRef.get >= input.length

  def current: Option[Char] = {
    if (atEnd) {
      None
    } else {
      Some(input(offsetRef.get))
    }
  }

  def peek(n: Int): Option[Char] = {
    val idx = offsetRef.get + n
    if (idx >= input.length) {
      None
    } else {
      Some(input(idx))
    }
  }

  def advance(): Unit = {
    if (!atEnd) {
      if (input(offsetRef.get) == '\n') {
        lineRef.update(_ + 1)
        columnRef.set(1)
      } else {
        columnRef.update(_ + 1)
      }
      offsetRef.update(_ + 1)
    }
  }

  def advanceN(n: Int): Unit = {
    (0 until n).foreach(_ => advance())
  }

  def save: StateSnapshot = {
    (offset = offsetRef.get, line = lineRef.get, column = columnRef.get)
  }

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
def parserState(input: String): ParserState = {
  new ParserState(input, Ref(0), Ref(1), Ref(1))
}
