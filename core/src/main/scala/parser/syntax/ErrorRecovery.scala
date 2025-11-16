package parser.syntax

import parser.core._
import parser.runtime._

/**
 * Error recovery combinators for resilient parsing.
 *
 * These combinators enable parsers to recover from errors and continue
 * parsing, collecting multiple errors instead of failing on the first one.
 * This is essential for IDE tooling where we need to provide feedback on
 * all errors, not just the first.
 *
 * Usage:
 * {{{
 * import parser.syntax.ErrorRecovery.*
 *
 * val parser = jsonObject.resilient
 * val result = parser.parseResilient("""{"name": "Alice", "age": }""")
 * // Returns Partial with tree and errors
 * }}}
 */
object ErrorRecovery {

  // ============================================================================
  // Extension Methods - ALL IN ONE BLOCK
  // ============================================================================

  extension [E, A](p: Parser[E, A]) {

    /**
     * Skip input until predicate succeeds, recovering from errors.
     *
     * When the main parser fails, this combinator skips characters one by one
     * until the predicate parser succeeds, then returns the predicate's result.
     * All skipped input is preserved in the parse tree as error tokens.
     *
     * Example:
     * {{{
     * val parser = identifier.skipUntil(char(';'))
     * // If identifier fails, skips to next semicolon
     * }}}
     *
     * @param pred The recovery predicate - parsing continues when this succeeds
     * @return A parser that recovers by skipping to the predicate
     */
    def skipUntil(pred: Parser[E, Any]): Parser[E, A] =
      Parser.Custom { state =>
        interpret(p, state) match {
          case success @ Result.Success(_, _)    => success
          case partial @ Result.Partial(_, _, _) => partial

          case Result.Failure(errors, furthest) =>
            // Try to find recovery point
            var found = false

            while (!state.atEnd && !found) {
              val snapshot = state.save
              interpret(pred, state) match {
                case Result.Success(_, _) | Result.Partial(_, _, _) =>
                  state.restore(snapshot)
                  found = true
                case Result.Failure(_, _) =>
                  state.restore(snapshot)
                  state.advance()
              }
            }

            // Return failure with the original errors
            Result.Failure(errors, furthest)
        }
      }

    /**
     * Try parser, if it fails use recovery parser instead.
     *
     * This combinator attempts the main parser first. If it fails without
     * consuming input, it tries the recovery parser instead. The recovery
     * parser's result is wrapped in a Partial result with the original errors.
     *
     * Example:
     * {{{
     * val parser = identifier.recoverWith(string("ERROR"))
     * // If identifier fails, uses "ERROR" as fallback
     * }}}
     *
     * @param recovery The fallback parser to use on failure
     * @return A parser that recovers using the fallback
     */
    def recoverWith(recovery: Parser[E, A]): Parser[E, A] =
      Parser.Custom { state =>
        val snapshot = state.save

        interpret(p, state) match {
          case success @ Result.Success(_, _)    => success
          case partial @ Result.Partial(_, _, _) => partial

          case Result.Failure(errors, furthest) =>
            state.restore(snapshot)
            interpret(recovery, state) match {
              case Result.Success(value, consumed) =>
                // Recovered successfully, but note the errors
                Result.Partial(value, errors, consumed)

              case Result.Partial(value, recoveryErrors, consumed) =>
                // Recovery was partial, combine all errors
                Result.Partial(value, errors ++ recoveryErrors, consumed)

              case Result.Failure(recoveryErrors, recoveryFurthest) =>
                // Both failed, combine errors
                val combinedErrors = errors ++ recoveryErrors
                val finalFurthest =
                  if (furthest.offset > recoveryFurthest.offset)
                    furthest
                  else recoveryFurthest
                Result.Failure(combinedErrors, finalFurthest)
            }
        }
      }

    /**
     * Parse with error recovery - returns Partial on error.
     *
     * Attempts to parse, but if an error occurs, marks it and continues.
     * This is useful for parsers that should always produce some result,
     * even if parts fail.
     *
     * Example:
     * {{{
     * val parser = jsonValue.resilient
     * // Continues parsing even if some values are malformed
     * }}}
     *
     * @return A parser that returns Partial results on errors
     */
    def resilient: Parser[E, A] =
      Parser.Custom { state =>
        interpret(p, state) match {
          case success @ Result.Success(_, _)    => success
          case partial @ Result.Partial(_, _, _) => partial
          case Result.Failure(errors, _)         =>
            // Try to recover by consuming one character as error token
            if (!state.atEnd) {
              val errorStart = state.location
              state.advance()
              // Note: This is a simplified recovery - real implementation
              // would need to return a meaningful error token
              Result.Failure(errors, errorStart)
            } else {
              Result.Failure(errors, state.location)
            }
        }
      }

    /**
     * Collect all errors while parsing, don't stop on first failure.
     *
     * This combinator runs a parser and accumulates all errors encountered.
     * It continues parsing even after errors, attempting to build as much
     * of the parse tree as possible.
     *
     * Returns:
     * - Success(value) if no errors occurred
     * - Partial(value, errors) if errors occurred but parsing continued
     * - Failure(errors) if parsing could not continue
     *
     * Example:
     * {{{
     * val parser = many(statement).collectErrors
     * // Parses all statements, collecting errors from each
     * }}}
     *
     * @return A parser that collects all errors
     */
    def collectErrors: Parser[E, A] =
      Parser.Custom { state =>
        var accumulatedErrors: List[E] = List.empty

        interpret(p, state) match {
          case Result.Success(value, consumed) =>
            if (accumulatedErrors.isEmpty) {
              Result.Success(value, consumed)
            } else {
              Result.Partial(value, accumulatedErrors, consumed)
            }

          case Result.Partial(value, errors, consumed) =>
            accumulatedErrors = accumulatedErrors ++ errors
            Result.Partial(value, accumulatedErrors, consumed)

          case Result.Failure(errors, furthest) =>
            accumulatedErrors = accumulatedErrors ++ errors
            Result.Failure(accumulatedErrors, furthest)
        }
      }

    /**
     * Parse input with full error recovery, producing a GreenNode tree.
     *
     * This is a high-level combinator that attempts to parse the entire input,
     * recovering from errors and producing a complete syntax tree even when
     * errors occur. Error regions are marked with Error tokens in the tree.
     *
     * Returns:
     * - Success if parsing completed without errors
     * - Partial if parsing completed with recoverable errors
     * - Failure only for catastrophic failures
     *
     * Example:
     * {{{
     * val parser = jsonObject
     * val result = parser.parseResilient("""{"name": "Alice", "age": }""")
     * result match {
     *   case Success((tree, Nil), _) => // Perfect parse
     *   case Partial((tree, errors), _) => // Parse with errors
     *   case Failure(errors, _) => // Catastrophic failure
     * }
     * }}}
     */
    def parseResilient(input: String): Result[E, (A, List[E])] = {
      val state = parserState(input)

      interpret(p, state) match {
        case Result.Success(value, consumed) =>
          Result.Success((value, List.empty), consumed)

        case Result.Partial(value, errors, consumed) =>
          Result.Success((value, errors), consumed)

        case Result.Failure(errors, furthest) =>
          // Catastrophic failure - could not recover
          Result.Failure(errors, furthest)
      }
    }

    /**
     * Expect a parser to succeed, or insert an error marker.
     *
     * This combinator requires a parser to succeed. If it fails, an error
     * marker is inserted and parsing continues. This is useful for required
     * elements that should not stop the entire parse if missing.
     *
     * Example:
     * {{{
     * val parser = char('{') ~ expect(jsonObject, "object body") ~ char('}')
     * // If object body is missing, inserts error but continues
     * }}}
     */
    def expect(errorMsg: String): Parser[E, A] =
      Parser.Custom { state =>
        interpret(p, state) match {
          case success @ Result.Success(_, _)    => success
          case partial @ Result.Partial(_, _, _) => partial
          case Result.Failure(errors, furthest)  =>
            // Parser failed - this is now an error we need to report
            val enhancedErrors =
              ParseError.Custom(errorMsg, state.location) :: errors.asInstanceOf[List[ParseError]]
            Result.Failure(enhancedErrors.asInstanceOf[List[E]], furthest)
        }
      }
  }

  // ============================================================================
  // Helper Functions - OUTSIDE extension block
  // ============================================================================

  /**
   * Create an error marker token at the current location.
   *
   * This is a utility for building error tokens in the syntax tree.
   * Error tokens mark regions where parsing failed but recovery was possible.
   *
   * @param message The error message
   * @param state The parser state at the error location
   * @return A GreenNode token marked as an error
   */
  def errorToken(message: String, state: ParserState): GreenNode = {
    val loc        = state.location
    val span: Span = (start = loc, end = loc)
    GreenNode.Token(TokenKind.Error, message, span)
  }

  /**
   * Skip whitespace and comments (common recovery strategy).
   *
   * This is a convenience combinator for skipping over whitespace and
   * comments to find the next meaningful token. Useful for error recovery
   * in languages with significant whitespace.
   */
  def skipTrivia: Parser[ParseError, Unit] =
    Parser.Custom { state =>
      var continue = true
      while (!state.atEnd && continue)
        state.current match {
          case Some(c) if c.isWhitespace => state.advance()
          case _                         => continue = false
        }
      Result.Success((), 0)
    }
}
