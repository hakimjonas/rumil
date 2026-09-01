package parser.core

/** Creates a parser that always succeeds with the given value.
  *
  * Does not consume any input.
  *
  * @param value
  *   The value to return
  * @return
  *   A parser that always succeeds with value
  *
  * Example:
  * {{{
  * succeed(42).run("anything")  // Success(42, 0)
  * }}}
  */
inline def succeed[A](value: A): Parser[Nothing, A] =
  Parser.Succeed(value)

/** Creates a parser that always fails with the given error.
  *
  * Does not consume any input.
  *
  * @param error
  *   The error to report
  * @return
  *   A parser that always fails with error
  *
  * Example:
  * {{{
  * fail("oops").run("anything")  // Failure(List("oops"), ...)
  * }}}
  */
inline def fail[E](error: E): Parser[E, Nothing] =
  Parser.Fail(error)

/** Fails with a positioned [[ParseError.Custom]]: the error's location is where the failure happens
  * in the input, unlike [[fail]], whose error is built before any input is seen.
  *
  * Example:
  * {{{
  * failWith("mismatched end tag").run("ab")  // Failure(List(Custom(..., line 1, column 3)))
  * }}}
  */
inline def failWith(message: String): Parser[ParseError, Nothing] =
  Parser.FailWith(message)

/** The current input offset, without consuming anything. Use it to attach source positions to
  * parsed values when a validation error must point at where a structure started.
  */
def offset[E]: Parser[E, Int] = Parser.GetOffset()

/** Transforms the result of a parser using a function.
  *
  * Functor map operation.
  *
  * @param p
  *   The parser to transform
  * @param f
  *   The transformation function
  * @return
  *   A parser that applies f to the result of p
  *
  * Example:
  * {{{
  * map(digit, _.toString.toInt).run("5")  // Success(5, 1)
  * }}}
  */
def map[E, A, B](p: Parser[E, A], f: A => B): Parser[E, B] =
  p match {
    case Parser.Map(inner, g) =>
      Parser.Map(inner, g.andThen(f))
    case _ =>
      Parser.Map(p, f)
  }

/** Sequences two parsers where the second depends on the first's result.
  *
  * Monad flatMap operation. Enables sequencing parsers where the choice of continuation depends on
  * the value parsed.
  *
  * @param p
  *   The first parser
  * @param f
  *   Function from first result to second parser
  * @return
  *   A parser that sequences p and f(result)
  *
  * Example:
  * {{{
  * flatMap(digit, n => count(n.toString.toInt, char('x')))
  * // Parses a digit then that many 'x' characters
  * }}}
  */
inline def flatMap[E, A, B](p: Parser[E, A], f: A => Parser[E, B]): Parser[E, B] =
  Parser.FlatMap(p, f)

/** Sequences two parsers, keeping both results => a tuple.
  *
  * Applicative product operation. Runs p1 then p2, combining results.
  *
  * @param p1
  *   First parser
  * @param p2
  *   Second parser
  * @return
  *   A parser that runs both and returns (result1, result2)
  *
  * Example:
  * {{{
  * zip(char('a'), char('b')).run("ab")  // Success(('a', 'b'), 2)
  * }}}
  */
def zip[E, A, B](p1: Parser[E, A], p2: Parser[E, B]): Parser[E, (A, B)] =
  Parser.Zip(p1, p2)

/** Sequences two parsers, keeping only the left result.
  *
  * Runs p1 then p2, but discards p2's result.
  *
  * @param p1
  *   Parser whose result to keep
  * @param p2
  *   Parser whose result to discard
  * @return
  *   A parser that runs both but returns only p1's result
  *
  * Example:
  * {{{
  * zipLeft(char('a'), char('b')).run("ab")  // Success('a', 2)
  * }}}
  */
def zipLeft[E, A, B](p1: Parser[E, A], p2: Parser[E, B]): Parser[E, A] =
  Parser.SkipRight(p1, p2)

/** Sequences two parsers, keeping only the right result.
  *
  * Runs p1 then p2, but discards p1's result.
  *
  * @param p1
  *   Parser whose result to discard
  * @param p2
  *   Parser whose result to keep
  * @return
  *   A parser that runs both but returns only p2's result
  *
  * Example:
  * {{{
  * zipRight(char('a'), char('b')).run("ab")  // Success('b', 2)
  * }}}
  */
def zipRight[E, A, B](p1: Parser[E, A], p2: Parser[E, B]): Parser[E, B] =
  Parser.SkipLeft(p1, p2)

/** Parses a value between two delimiters.
  *
  * Runs left, then p, then right, returning only the middle result. Essential for parsing
  * parenthesized expressions, bracketed lists, etc.
  *
  * @param p
  *   The parser for the content between delimiters
  * @param left
  *   Parser for the opening delimiter
  * @param right
  *   Parser for the closing delimiter
  * @return
  *   A parser that parses left-p-right and returns p's result
  *
  * Example:
  * {{{
  * between(digit, char('('), char(')')).run("(5)")  // Success('5', 3)
  * between(letter.many, string("<<"), string(">>")).run("<<abc>>")
  *   // Success(List('a', 'b', 'c'), 7)
  * }}}
  */
def between[E, A, L, R](p: Parser[E, A], left: Parser[E, L], right: Parser[E, R]): Parser[E, A] =
  zipRight(left, zipLeft(p, right))

/** Tries the left parser, and if it fails, tries the right parser.
  *
  * Backtracking alternative combinator. If left fails (returns Failure), restores position and
  * tries right. Collects errors from the furthest parse point.
  *
  * Note: If left returns Success or Partial, right is NOT tried. Partial means "succeeded with
  * errors" - for error recovery where you want to try alternatives even on partial success, use
  * `orElse` instead.
  *
  * @param left
  *   First parser to try
  * @param right
  *   Alternative parser if left fails
  * @return
  *   A parser that tries left, then right if left fails
  *
  * Example:
  * {{{
  * or(char('a'), char('b')).run("b")  // Success('b', 1)
  * }}}
  */
def or[E, A](left: Parser[E, A], right: Parser[E, A]): Parser[E, A] = {
  // Recursively flatten nested Or/Choice/StringChoice into a single list
  def flatten(p: Parser[E, A]): List[Parser[E, A]] = p match {
    case Parser.Or(l, r) => flatten(l) ++ flatten(r)
    case Parser.Choice(alts) => alts.flatMap(flatten)
    case Parser.StringChoice(_, strs) => strs.map(s => Parser.StringMatch(s)).toList
    case other => List(other)
  }

  val allAlts = flatten(left) ++ flatten(right)

  // Check if all are string matches - use StringChoice optimization
  val allStrings = allAlts.forall {
    case Parser.StringMatch(_) => true
    case _ => false
  }
  if allStrings && allAlts.size > 2 then {
    val targets = allAlts.collect { case Parser.StringMatch(s) => s }.toArray
    val radix = RadixNode.fromStrings(targets)
    Parser.StringChoice(radix, targets).asInstanceOf[Parser[E, A]] // scalafix:ok DisableSyntax.asInstanceOf
  } else if allAlts.size == 2 then {
    Parser.Or(allAlts.head, allAlts(1))
  } else {
    Parser.Choice(allAlts)
  }
}

/** Tries a list of parsers in order, succeeding with the first success.
  *
  * Automatically optimizes choice of string literals to use StringChoice, which avoids intermediate
  * result allocation during backtracking.
  *
  * @param parsers
  *   Non-empty list of parsers to try
  * @return
  *   A parser that tries each parser in order
  *
  * Example:
  * {{{
  * choice(List(char('a'), char('b'), char('c'))).run("c")
  * // Success('c', 1)
  * }}}
  */
def choice[E, A](parsers: List[Parser[E, A]]): Parser[E, A] =
  parsers match {
    case Nil => throw new IllegalArgumentException("choice requires at least one parser")
    case p :: Nil => p
    case _ :: _ :: Nil => or(parsers.head, parsers(1))
    case _ =>
      val allStrings = parsers.forall {
        case Parser.StringMatch(_) => true
        case _ => false
      }
      if allStrings then {
        val targets = parsers.collect { case Parser.StringMatch(s) => s }.toArray
        val radix = RadixNode.fromStrings(targets)
        Parser.StringChoice(radix, targets).asInstanceOf[Parser[E, A]] // scalafix:ok DisableSyntax.asInstanceOf
      } else {
        Parser.Choice(parsers)
      }
  }

/** First-character dispatch: routes the next input character to the parser that handles it, in a
  * single O(1) table lookup instead of `Or`/`Choice`'s linear backtracking scan.
  *
  * Each entry maps a string of *possible leading characters* to a parser — e.g. `"n" -> jsonNull`,
  * `"tf" -> jsonBool`, `"-0123456789" -> jsonNumber`. Every character may appear in at most one
  * key; a key must be non-empty. `fallback` runs when the next character matches no key; without a
  * fallback the parser fails with an "one of \"…\"" error naming the dispatch characters.
  *
  * @param dispatch
  *   Ordered (leading-chars, parser) pairs. Order is preserved in the error message.
  * @param fallback
  *   Optional parser run when the next character matches no dispatch key
  * @return
  *   A parser that dispatches on the first input character
  *
  * Example:
  * {{{
  * val jsonValue = firstCharChoice(List(
  *   "n" -> jsonNull,
  *   "tf" -> jsonBool,
  *   "-0123456789" -> jsonNumber,
  *   "\"" -> jsonString
  * ))
  * }}}
  */
def firstCharChoice[A](
  dispatch: List[(String, Parser[ParseError, A])],
  fallback: Option[Parser[ParseError, A]] = None
): Parser[ParseError, A] = {
  val table = scala.collection.mutable.Map.empty[Char, Parser[ParseError, A]]
  val expectedBuf = new StringBuilder
  for (key, p) <- dispatch do {
    if key.isEmpty then throw new IllegalArgumentException("firstCharChoice: dispatch keys must be non-empty strings")
    for ch <- key do {
      if table.contains(ch) then
        throw new IllegalArgumentException(
          s"firstCharChoice: duplicate leading char \"$ch\" across dispatch keys"
        )
      table(ch) = p
      expectedBuf.append(ch)
    }
  }
  Parser.FirstCharChoice(table.toMap, expectedBuf.result(), fallback)
}

/** Parses zero or more occurrences of p.
  *
  * Greedy - consumes => much => possible. Always succeeds.
  *
  * @param p
  *   The parser to repeat
  * @return
  *   A parser that returns a list of results
  *
  * Example:
  * {{{
  * many(char('a')).run("aaa")  // Success(List('a', 'a', 'a'), 3)
  * }}}
  */
inline def many[E, A](p: Parser[E, A]): Parser[E, List[A]] =
  Parser.Many(p)

/** Parses one or more occurrences of p.
  *
  * Greedy - consumes => much => possible. Fails if zero matches.
  *
  * @param p
  *   The parser to repeat
  * @return
  *   A parser that returns a non-empty list of results
  *
  * Example:
  * {{{
  * many1(char('a')).run("aaab")  // Success(List('a', 'a', 'a'), 3)
  * many1(char('a')).run("b")     // Failure
  * }}}
  */
inline def many1[E, A](p: Parser[E, A]): Parser[E, List[A]] =
  Parser.Many1(p)

/** Parses zero or one occurrence of p.
  *
  * Always succeeds - returns Some(value) if p succeeds, None otherwise.
  *
  * @param p
  *   The parser to try
  * @return
  *   A parser that returns Option[A]
  *
  * Example:
  * {{{
  * optional(char('a')).run("ab")  // Success(Some('a'), 1)
  * optional(char('a')).run("b")   // Success(None, 0)
  * }}}
  */
inline def optional[E, A](p: Parser[E, A]): Parser[E, Option[A]] =
  Parser.Optional(p)

/** Parses zero or more occurrences of p separated by sep.
  *
  * Does not require trailing separator. Always succeeds.
  *
  * @param p
  *   The element parser
  * @param sep
  *   The separator parser
  * @return
  *   A parser that returns a list of elements
  *
  * Example:
  * {{{
  * sepBy(digit, char(',')).run("1,2,3")  // Success(List('1','2','3'), 5)
  * sepBy(digit, char(',')).run("")       // Success(List(), 0)
  * }}}
  */
def sepBy[E, A, Sep](p: Parser[E, A], sep: Parser[E, Sep]): Parser[E, List[A]] =
  or(sepBy1(p, sep), succeed(List.empty))

/** Parses one or more occurrences of p separated by sep.
  *
  * Does not require trailing separator. Fails if zero matches.
  *
  * @param p
  *   The element parser
  * @param sep
  *   The separator parser
  * @return
  *   A parser that returns a non-empty list of elements
  *
  * Example:
  * {{{
  * sepBy1(digit, char(',')).run("1,2,3")  // Success(List('1','2','3'), 5)
  * sepBy1(digit, char(',')).run("")       // Failure
  * }}}
  */
def sepBy1[E, A, Sep](p: Parser[E, A], sep: Parser[E, Sep]): Parser[E, List[A]] =
  flatMap(p, (head: A) => map(many(zipRight(sep, p)), (tail: List[A]) => head :: tail))

/** Parses zero or more occurrences of p, each terminated by end.
  *
  * Requires trailing terminator. Always succeeds.
  *
  * @param p
  *   The element parser
  * @param end
  *   The terminator parser
  * @return
  *   A parser that returns a list of elements
  *
  * Example:
  * {{{
  * endBy(digit, char(';')).run("1;2;3;")  // Success(List('1','2','3'), 6)
  * }}}
  */
def endBy[E, A, End](p: Parser[E, A], end: Parser[E, End]): Parser[E, List[A]] =
  many(zipLeft(p, end))

/** Parses exactly n occurrences of p.
  *
  * Fails if fewer than n matches found.
  *
  * @param n
  *   Number of times to repeat (must be >= 0)
  * @param p
  *   The parser to repeat
  * @return
  *   A parser that returns a list of exactly n elements
  *
  * Example:
  * {{{
  * count(3, char('a')).run("aaa")  // Success(List('a','a','a'), 3)
  * count(3, char('a')).run("aa")   // Failure
  * }}}
  */
def count[E, A](n: Int, p: Parser[E, A]): Parser[E, List[A]] =
  if n <= 0 then {
    succeed(List.empty)
  } else {
    flatMap(p, (head: A) => map(count(n - 1, p), (tail: List[A]) => head :: tail))
  }

/** Alias for count - repeats parser exactly n times.
  *
  * @param n
  *   Number of times to repeat (must be >= 0)
  * @param p
  *   The parser to repeat
  * @return
  *   A parser that returns a list of exactly n elements
  *
  * Example:
  * {{{
  * times(3, digit).run("123")  // Success(List('1','2','3'), 3)
  * }}}
  */
@deprecated("Use count instead", "0.4.0")
inline def times[E, A](n: Int, p: Parser[E, A]): Parser[E, List[A]] =
  count(n, p)

/** Parses p without consuming input.
  *
  * Succeeds if p would succeed, but resets input position. Useful for checking what comes next
  * without committing.
  *
  * @param p
  *   The parser to look ahead with
  * @return
  *   A parser that succeeds like p but consumes nothing
  *
  * Example:
  * {{{
  * lookAhead(char('a')).run("ab")  // Success('a', 0)
  * // Note: consumed = 0
  * }}}
  */
inline def lookAhead[E, A](p: Parser[E, A]): Parser[E, A] =
  Parser.LookAhead(p)

/** Succeeds only if p would fail.
  *
  * Negative lookahead - ensures p does not match without consuming input.
  *
  * @param p
  *   The parser that must NOT match
  * @return
  *   A parser that succeeds with () if p fails
  *
  * Example:
  * {{{
  * notFollowedBy(char('a')).run("b")  // Success((), 0)
  * notFollowedBy(char('a')).run("a")  // Failure
  * }}}
  */
inline def notFollowedBy[A](p: Parser[ParseError, A]): Parser[ParseError, Unit] =
  Parser.NotFollowedBy(p)

/** Captures the result of p => a value instead of propagating errors.
  *
  * Reifies the parse result - turns a Parser[E, A] into Parser[Nothing, Result[E, A]]. Always
  * succeeds, returning either Success or Failure => a value.
  *
  * @param p
  *   The parser to attempt
  * @return
  *   A parser that always succeeds with Result[E, A]
  *
  * Example:
  * {{{
  * attempt(char('a')).run("b")
  * // Success(Failure(...), 0)  -- note: Success wrapping Failure!
  * }}}
  */
inline def attempt[E, A](p: Parser[E, A]): Parser[Nothing, Result[E, A]] =
  Parser.Attempt(p)

/** Recovers from parse failures by providing a default value.
  *
  * If p succeeds, returns its value. If p fails, applies f to the error to produce a fallback
  * value. Always succeeds.
  *
  * @param p
  *   The parser to try
  * @param f
  *   Function to produce fallback value from error
  * @return
  *   A parser that always succeeds
  *
  * Example:
  * {{{
  * recover(digit)(_ => '0').run("x")  // Success('0', 0)
  * }}}
  */
def recover[E, A](p: Parser[E, A])(f: E => A): Parser[Nothing, A] =
  map(
    attempt(p),
    (result: Result[E, A]) =>
      result match {
        case Result.Success(value, _) => value
        case Result.Partial(value, _, _) => value
        case Result.Failure(errors, _) => f(errors.head)
      }
  )

/** Recovers from parse failures by providing a fallback parser.
  *
  * If p succeeds, returns its value. If p fails, runs f(error) to try an alternative parser. Useful
  * for error correction.
  *
  * @param p
  *   The parser to try
  * @param f
  *   Function to produce fallback parser from error
  * @return
  *   A parser that tries p then f(error) if p fails
  *
  * Example:
  * {{{
  * recoverWith(digit)(_ => char('?')).run("x")  // tries digit, then '?'
  * }}}
  */
def recoverWith[E, E2, A](p: Parser[E, A])(f: E => Parser[E2, A]): Parser[E2, A] =
  flatMap(
    attempt(p),
    (result: Result[E, A]) =>
      result match {
        case Result.Success(value, _) => succeed(value)
        case Result.Partial(value, _, _) => succeed(value)
        case Result.Failure(errors, _) => f(errors.head)
      }
  )

/** Expect a token of the given kind; on failure synthesise a [[GreenNode.Missing]] placeholder and
  * continue as a [[Result.Partial]].
  *
  * On success, behaves exactly like [[inner]] — returns the token it produced.
  *
  * On failure, consumes no input, returns `GreenNode.Missing(kind)` as the value, and surfaces the
  * original errors via `Partial.errors`. The caller's tree gets a zero-width Missing placeholder at
  * the point where the token was expected, and parsing continues so downstream structure can still
  * be recovered.
  *
  * The lossless invariant is preserved: `GreenNode.toSource(result.tree) == originalSource` because
  * Missing contributes zero characters.
  *
  * Typical use:
  * {{{
  * val closeParen: Parser[ParseError, DefaultLanguage.Green] =
  *   char(')').map(c => GreenNode.Token(DefaultLanguage.Tokens.RightParen, c.toString))
  * val exprInParens: Parser[ParseError, DefaultLanguage.Green] =
  *   (char('(') *> expr <* expectToken(DefaultLanguage.Tokens.RightParen, closeParen)).map(...)
  * }}}
  *
  * @param kind
  *   The language's token kind that was expected; recorded in the `Missing` node for diagnostics
  *   and quick-fix synthesis ("insert `)`").
  * @param inner
  *   The parser that would have produced the token on success.
  */
def expectToken[Tok, Syn](
  kind: Tok,
  inner: Parser[ParseError, GreenNodeOf[Tok, Syn]]
): Parser[ParseError, GreenNodeOf[Tok, Syn]] =
  Parser.RecoverWith(
    inner,
    Parser.Succeed(GreenNode.Missing[Tok, Syn](kind))
  )

/** Panic-mode recovery: if [[inner]] fails, consume characters until one of [[syncChars]] is seen
  * (or end of input), wrap what was skipped in [[GreenNode.Unexpected]], and surface the original
  * errors via [[Result.Partial]]. The sync character itself is left unconsumed so the caller can
  * match it next.
  *
  * This is the skip half of the SwiftSyntax-style resilient-parse pair (the other half is
  * [[expectToken]]). Use it at structural boundaries — statement, field, case arm — where the
  * grammar knows a specific terminator follows. When the inner parser derails mid-rule, the skipped
  * region is captured as a single `errorTokenKind`-kinded token inside an [[GreenNode.Unexpected]]
  * wrapper, so `GreenNode.toSource(tree) + restOfInput == originalInput` round-trips losslessly.
  *
  * Semantics (by case):
  *
  *   1. `inner` succeeds → its value is returned unchanged; no Unexpected node is allocated.
  *   2. `inner` fails and a sync character is at the failure offset → returns
  *      `Partial(Unexpected(Vector.empty), innerErrors, 0)`. Zero-width placeholder; nothing is
  *      consumed so the caller can match the sync character next.
  *   3. `inner` fails and a sync character appears after M skipped characters → returns
  *      `Partial(Unexpected(Vector(Token(Error, skippedText))), innerErrors :+ unexpectedRegion, M)`.
  *      `M` is the number of characters skipped, not including the sync character itself.
  *   4. `inner` fails and no sync character is ever found → returns
  *      `Partial(Unexpected(Vector(Token(Error, allRemaining))), innerErrors :+ unexpectedRegion, remainingLen)`.
  *      The parser does not catastrophically fail; a resilient tree covering all remaining input is
  *      still produced.
  *
  * Lossless invariant: when `inner` fails, the returned tree covers exactly the characters from the
  * failure offset up to (but not including) the sync character or end-of-input. The caller's
  * remaining input picks up at the sync character, so concatenation reproduces the original text.
  *
  * Valid-prefix behavior: `syncUntil` is all-or-nothing recovery at the boundary. If [[inner]]
  * consumes input before failing, that consumed prefix is part of the `Unexpected` region on
  * failure — there is no partial commit of the valid prefix into structure. For instance, parsing
  * `5+garbage` with an arithmetic statement parser: the statement parser consumes `5+` successfully
  * then fails on `g`; `syncUntil` rolls all of `5+garbage` into the `Unexpected` wrapper rather
  * than preserving `5+` as a partial expression tree. This is intentional — committing a mid-parse
  * prefix requires cooperation between the inner parser and the recovery combinator (a commit-point
  * discipline), which `Parser.RecoverWith` does not offer. A future `syncUntilCommitted` variant
  * could do commit-point recovery; it is not in this implementation.
  *
  * Lexer question: this combinator is tokenizer-agnostic and char-level — it matches the existing
  * combinator primitives ([[Parser.Satisfy]], [[anyChar]], [[notFollowedBy]]). Grammars with a
  * dedicated tokenizer can wrap their sync-token detection into a `Set[Char]` of leading
  * characters, or will grow a `syncUntilKinds`-style sibling once kind-dispatched lexing lands.
  *
  * Progress guard: when both `inner` fails AND the current position is already at end-of-input AND
  * there is nothing to skip, this combinator returns `Partial(Unexpected(Vector.empty), …, 0)`.
  * That is a zero-consumption partial, which will livelock a [[many]] loop. Callers placing
  * `syncUntil` inside `.many` should guard the element with `notFollowedBy(eof) *> syncUntil(…)`
  * (or similar) so the loop terminates at EOF.
  *
  * Typical use:
  * {{{
  * val statement: Parser[ParseError, DefaultLanguage.Green] = expression.map(wrapStatement)
  * val resilientStatement: Parser[ParseError, DefaultLanguage.Green] =
  *   syncUntil(statement, Set('\n', ';'), DefaultLanguage.Tokens.Error)
  * // On a derailed statement, the bad region becomes an Unexpected node in the tree,
  * // parsing resumes at the next newline or semicolon, and downstream structure survives.
  * }}}
  *
  * @param inner
  *   The parser whose failure triggers recovery.
  * @param syncChars
  *   The set of characters that terminate the skip region. The first of these encountered stops
  *   skipping and is left unconsumed for the caller.
  * @param errorTokenKind
  *   The language's token kind used to wrap skipped text inside [[GreenNode.Unexpected]]. Each
  *   grammar supplies its own error kind so `syncUntil` stays language-agnostic.
  */
def syncUntil[Tok, Syn](
  inner: Parser[ParseError, GreenNodeOf[Tok, Syn]],
  syncChars: Set[Char],
  errorTokenKind: Tok
): Parser[ParseError, GreenNodeOf[Tok, Syn]] = {
  val syncP: Parser[ParseError, Char] =
    satisfy(syncChars.contains, "sync character")
  val skipStep: Parser[ParseError, Char] =
    zipRight(notFollowedBy(syncP), anyChar)
  val skipUntilSync: Parser[ParseError, String] =
    capture(skipMany(skipStep))
  val recovery: Parser[ParseError, GreenNodeOf[Tok, Syn]] =
    flatMap(
      skipUntilSync,
      { (skipped: String) =>
        if skipped.isEmpty then {
          Parser.Succeed(GreenNode.unexpectedOfVec[Tok, Syn](Vector.empty))
        } else {
          // Location plumbing is deferred to a later session; use the anchor location here.
          // The authoritative position is recoverable from the Unexpected node's offset in RedTree.
          val errLoc: Location = (line = 0, column = 0, offset = 0)
          val unexpectedRegion = ParseError.Custom(s"Unexpected input: '$skipped'", errLoc)
          val wrapped: GreenNodeOf[Tok, Syn] =
            GreenNode.unexpectedOfVec[Tok, Syn](
              Vector(GreenNode.Token[Tok, Syn](errorTokenKind, skipped))
            )
          Parser.RecoverWith(Parser.Fail(unexpectedRegion), Parser.Succeed(wrapped))
        }
      }
    )
  Parser.RecoverWith(inner, recovery)
}

/** Tries a parser, falling back to another if it fails (fast alternation).
  *
  * This is simple alternation without error tracking. When the primary parser fails, the fallback
  * is tried. If fallback succeeds, returns [[Result.Success]] with no error information from the
  * failed primary parser.
  *
  * Use `orElse` for:
  *   - Simple alternation between parsers (e.g., `keyword1.orElse(keyword2)`)
  *   - Performance-critical code where error tracking isn't needed
  *   - Choice between valid alternatives
  *
  * Use [[recover]] instead when you need:
  *   - Error tracking for diagnostics
  *   - Resilient parsing with error accumulation
  *   - IDE error reporting (red squiggles on recovery)
  *
  * @param p
  *   The primary parser to try first
  * @param fallback
  *   The parser to use if p fails
  * @return
  *   A parser that tries p, then fallback on failure
  *
  * Example:
  * {{{
  * val letter = char('a').orElse(char('b')).orElse(char('c'))
  * letter.run("b")  // Success('b', 1) - fast, no error tracking
  * letter.run("x")  // Failure - neither matched
  * }}}
  */
inline def orElse[E, A](p: Parser[E, A], fallback: Parser[E, A]): Parser[E, A] =
  // Flatten through or(): chains collapse into a single Choice, and pure-string chains
  // into the radix StringChoice dispatch — nested Or trees pay a linear walk per branch.
  or(p, fallback)

/** Tries a parser, falling back to another with error tracking (resilient parsing).
  *
  * Unlike [[orElse]] which discards errors, `recover` tracks errors from the primary parser even
  * when the fallback succeeds. This creates [[Result.Partial]] results which contain both a value
  * and errors, enabling error recovery without losing diagnostic information.
  *
  * When the primary parser fails:
  *   - The parser state is restored
  *   - The fallback is tried
  *   - If fallback succeeds: returns [[Result.Partial]] with fallback value + primary errors
  *   - If fallback fails: returns [[Result.Failure]] combining both error lists
  *
  * Use `recover` for:
  *   - Resilient parsing (continue despite errors)
  *   - Error accumulation for reporting
  *   - IDE integration (show errors on recovered code)
  *
  * Use [[orElse]] instead when you:
  *   - Just need simple alternation
  *   - Want maximum performance
  *   - Don't need error diagnostics
  *
  * @param p
  *   The primary parser to try first
  * @param fallback
  *   The parser to use if p fails
  * @return
  *   A parser that tries p, then fallback on failure with error tracking
  *
  * Example:
  * {{{
  * val number = digit.many1.map(_.mkString.toInt)
  * val resilient = recover(number, succeed(0))
  * resilient.run("abc")  // Partial(0, List(error), 0) - recovered with errors
  * resilient.run("42")   // Success(42, 2) - primary succeeded
  * }}}
  */
inline def recover[E, A](p: Parser[E, A], fallback: Parser[E, A]): Parser[E, A] =
  Parser.RecoverWith(p, fallback)

/** Replaces parser errors with a custom message.
  *
  * When the parser fails, the error is replaced with a single `ParseError.Custom` containing the
  * provided message. This is useful for providing domain-specific error messages.
  *
  * Unlike `named` which adds to the expected set, `expect` completely replaces the error with a
  * custom message.
  *
  * @param p
  *   The parser whose errors to replace
  * @param message
  *   The custom error message
  * @return
  *   A parser with custom error messages on failure
  *
  * Example:
  * {{{
  * val email = (alphaNum.many1 ~ char('@') ~ alphaNum.many1).map(...)
  * val emailWithError = expect(email, "valid email address required")
  * emailWithError.run("invalid")  // Failure: "valid email address required"
  * }}}
  */
inline def expect[A](p: Parser[ParseError, A], message: String): Parser[ParseError, A] =
  Parser.Expect(p, message)

/** Labels a parser with a name for better error messages.
  *
  * When the parser fails, the name will appear in the expected set.
  *
  * @param p
  *   The parser to label
  * @param name
  *   The label to use in error messages
  * @return
  *   A parser with enhanced error reporting
  *
  * Example:
  * {{{
  * named(digit, "number").run("x")
  * // Failure: expected number, got 'x'
  * }}}
  */
inline def named[A](p: Parser[ParseError, A], name: String): Parser[ParseError, A] =
  Parser.Named(p, name)

/** Adds tracing output to a parser for debugging.
  *
  * Prints trace messages to stderr showing parse attempts, successes, and failures. Does not modify
  * the parser's behavior, only adds logging side effects.
  *
  * @param p
  *   The parser to trace
  * @param label
  *   The label to include in trace messages
  * @return
  *   A parser with identical behavior but trace output
  *
  * Example:
  * {{{
  * trace(digit, "number").run("5")
  * // [TRACE] number: trying at offset 0
  * // [TRACE] number: success, consumed 1 chars
  * // Success('5', 1)
  * }}}
  */
inline def trace[E, A](p: Parser[E, A], label: String): Parser[E, A] =
  Parser.Trace(p, label)

/** Adds debug output to a parser showing parsed values.
  *
  * Prints debug messages to stderr showing parse attempts with offsets, successful values, and
  * error details. Does not modify the parser's behavior, only adds logging side effects.
  *
  * @param p
  *   The parser to debug
  * @param label
  *   The label to include in debug messages
  * @return
  *   A parser with identical behavior but debug output
  *
  * Example:
  * {{{
  * debug(digit, "number").run("5")
  * // [DEBUG] number: trying at offset 0
  * // [DEBUG] number: success, parsed '5'
  * // Success('5', 1)
  * }}}
  */
inline def debug[E, A](p: Parser[E, A], label: String): Parser[E, A] =
  Parser.Debug(p, label)

/** Parses one or more occurrences of p separated by op, left-associative.
  *
  * Used for parsing left-associative binary operators. Applies operators from left to right: ((a op
  * b) op c) op d
  *
  * @param p
  *   The parser for operands
  * @param op
  *   The parser for operators, returns a binary function
  * @return
  *   A parser that builds left-associative parse tree
  *
  * Example:
  * {{{
  * val num = digit.map(_.toString.toInt)
  * val minus = char('-').as((a: Int, b: Int) => a - b)
  * chainl1(num, minus).run("5-3-1")  // Success(1, 5)  // (5-3)-1 = 1
  * }}}
  */
def chainl1[E, A](p: Parser[E, A], op: Parser[E, (A, A) => A]): Parser[E, A] = {
  def rest(acc: A): Parser[E, A] =
    or(
      flatMap(op, (f: (A, A) => A) => flatMap(p, (right: A) => rest(f(acc, right)))),
      succeed(acc)
    )

  flatMap(p, (initial: A) => rest(initial))
}

/** Parses one or more occurrences of p separated by op, right-associative.
  *
  * Used for parsing right-associative binary operators. Applies operators from right to left: a op
  * (b op (c op d))
  *
  * @param p
  *   The parser for operands
  * @param op
  *   The parser for operators, returns a binary function
  * @return
  *   A parser that builds right-associative parse tree
  *
  * Example:
  * {{{
  * val num = digit.map(_.toString.toInt)
  * val power = char('^').as((a: Int, b: Int) => Math.pow(a, b).toInt)
  * chainr1(num, power).run("2^3^2")  // Success(512, 5)  // 2^(3^2) = 512
  * }}}
  */
def chainr1[E, A](p: Parser[E, A], op: Parser[E, (A, A) => A]): Parser[E, A] =
  flatMap(
    p,
    (left: A) =>
      or(
        flatMap(op, (f: (A, A) => A) => map(chainr1(p, op), (right: A) => f(left, right))),
        succeed(left)
      )
  )

/** Parses one or more occurrences of p.
  *
  * Alias for `many1` with a clearer name.
  *
  * @param p
  *   The parser to repeat
  * @return
  *   A parser that returns a non-empty list of results
  */
@deprecated("Use many1 instead", "0.4.0")
inline def manyNonEmpty[E, A](p: Parser[E, A]): Parser[E, List[A]] =
  many1(p)

/** Parses zero or more occurrences of p separated by sep.
  *
  * Alias for `sepBy` with a clearer name.
  *
  * @param p
  *   The element parser
  * @param sep
  *   The separator parser
  * @return
  *   A parser that returns a list of elements
  */
@deprecated("Use sepBy instead", "0.4.0")
def separatedBy[E, A, Sep](p: Parser[E, A], sep: Parser[E, Sep]): Parser[E, List[A]] =
  sepBy(p, sep)

/** Parses one or more occurrences of p separated by sep.
  *
  * Alias for `sepBy1` with a clearer name.
  *
  * @param p
  *   The element parser
  * @param sep
  *   The separator parser
  * @return
  *   A parser that returns a non-empty list of elements
  */
@deprecated("Use sepBy1 instead", "0.4.0")
def separatedByNonEmpty[E, A, Sep](p: Parser[E, A], sep: Parser[E, Sep]): Parser[E, List[A]] =
  sepBy1(p, sep)

/** Parses zero or more occurrences of p, each terminated by end.
  *
  * Alias for `endBy` with a clearer name.
  *
  * @param p
  *   The element parser
  * @param end
  *   The terminator parser
  * @return
  *   A parser that returns a list of elements
  */
@deprecated("Use endBy instead", "0.4.0")
def endedBy[E, A, End](p: Parser[E, A], end: Parser[E, End]): Parser[E, List[A]] =
  endBy(p, end)

/** Parses one or more occurrences of p separated by op, left-associative.
  *
  * Alias for `chainl1` with a clearer name.
  *
  * @param p
  *   The parser for operands
  * @param op
  *   The parser for operators, returns a binary function
  * @return
  *   A parser that builds left-associative parse tree
  */
@deprecated("Use chainl1 instead", "0.4.0")
def chainLeft1[E, A](p: Parser[E, A], op: Parser[E, (A, A) => A]): Parser[E, A] =
  chainl1(p, op)

/** Parses one or more occurrences of p separated by op, right-associative.
  *
  * Alias for `chainr1` with a clearer name.
  *
  * @param p
  *   The parser for operands
  * @param op
  *   The parser for operators, returns a binary function
  * @return
  *   A parser that builds right-associative parse tree
  */
@deprecated("Use chainr1 instead", "0.4.0")
def chainRight1[E, A](p: Parser[E, A], op: Parser[E, (A, A) => A]): Parser[E, A] =
  chainr1(p, op)

/** Operator description for the `pratt` combinator.
  *
  * Each operator carries its own `symbol` parser (what input signals this operator), a binding
  * power, and a combining function. Binding powers drive precedence and associativity:
  *
  *   - Higher bp binds tighter (`*` has bp=20, `+` has bp=10 → `1+2*3` parses as `1+(2*3)`)
  *   - `InfixLeft(bp)` gives `lbp=bp, rbp=bp` — RHS must exceed bp to continue, yielding left
  *     nesting
  *   - `InfixRight(bp)` gives `lbp=bp, rbp=bp-1` — RHS accepts equal bp, yielding right nesting
  *   - `Prefix` applies before its operand at binding power `bp`
  *   - `Postfix` applies to the accumulated LHS at binding power `bp`
  */
enum Operator[A] {
  case InfixLeft[A](symbol: Parser[ParseError, ?], bp: Int, fn: (A, A) => A) extends Operator[A]
  case InfixRight[A](symbol: Parser[ParseError, ?], bp: Int, fn: (A, A) => A) extends Operator[A]
  case Prefix[A](symbol: Parser[ParseError, ?], bp: Int, fn: A => A) extends Operator[A]
  case Postfix[A](symbol: Parser[ParseError, ?], bp: Int, fn: A => A) extends Operator[A]
}

/** Top-Down Operator Precedence (Pratt) expression combinator.
  *
  * Builds a parser for expressions formed from an atom parser and a list of operators. Each
  * operator specifies its binding power and combining function; the parser automatically handles
  * precedence, associativity, and the mixing of infix/prefix/postfix forms.
  *
  * The resulting parser handles operator chains in linear input size with a shared dispatch parser,
  * avoiding the nested `FlatMap` closures that `chainl1` constructs per application.
  *
  * Example:
  * {{{
  * val num = digit.map(_.toString.toInt)
  * val expr = pratt(
  *   num,
  *   List(
  *     Operator.InfixLeft(char('+'), 10, (a: Int, b: Int) => a + b),
  *     Operator.InfixLeft(char('-'), 10, (a: Int, b: Int) => a - b),
  *     Operator.InfixLeft(char('*'), 20, (a: Int, b: Int) => a * b),
  *     Operator.InfixRight(char('^'), 30, (a: Int, b: Int) => math.pow(a, b).toInt),
  *     Operator.Prefix(char('-'), 40, (a: Int) => -a)
  *   )
  * )
  * expr.run("1+2*3")   // Success(7, 5)
  * expr.run("2^3^2")   // Success(512, 5)  right-assoc: 2^(3^2)
  * expr.run("-5+3")    // Success(-2, 4)
  * }}}
  *
  * @param atom
  *   Parser for leaf expressions (literals, identifiers, parenthesized sub-expressions)
  * @param operators
  *   List of operator descriptions; order matters only for tie-breaking in the dispatch parser
  * @return
  *   A parser that recognizes expressions formed from atoms and operators with correct precedence
  */
def pratt[A](atom: Parser[ParseError, A], operators: List[Operator[A]]): Parser[ParseError, A] = {
  val prefixOps = operators.collect { case p: Operator.Prefix[A] => p }
  val infixAndPostfix = operators.filter {
    case _: Operator.Prefix[A] => false
    case _ => true
  }
  val getOp = compileGetOp(infixAndPostfix)
  val opTable: PrattOpTable[A] | Null = compileOpTable(infixAndPostfix)

  val nud: Parser[ParseError, A] = prefixOps match {
    case Nil => atom
    case _ =>
      val prefixParsers: List[Parser[ParseError, A]] = prefixOps.map { pre =>
        flatMap(
          pre.symbol,
          (_: Any) => map(Parser.Pratt(atom, getOp, pre.bp, opTable), pre.fn)
        )
      }
      choice(prefixParsers ::: List(atom))
  }

  Parser.Pratt(nud, getOp, 0, opTable)
}

/** C-family operator precedence preset for [[pratt]] — 15 operators across 7 levels.
  *
  * Returns the standard left-associative arithmetic, comparison, and boolean infix operators plus
  * prefix unary `-` and `!`, with binding powers matching the conventional C / Java / Scala ladder:
  * multiplicative binds tighter than additive, additive than comparison, comparison than equality,
  * equality than `&&`, `&&` than `||`; prefix unary binds tighter than any infix.
  *
  * Binding powers (low to high):
  *   - `||` 10
  *   - `&&` 20
  *   - `==`, `!=` 30
  *   - `<=`, `>=`, `<`, `>` 40 (`<=`/`>=` listed first so `<` does not consume the `<` of `<=`)
  *   - `+`, `-` 50
  *   - `*`, `/`, `%` 60
  *   - prefix `-`, `!` 70
  *
  * @param sym
  *   Builds the symbol parser for a given operator string (e.g. `s => lexeme(string(s))`)
  * @param binary
  *   Combines two operands for an infix operator, receiving the operator string
  * @param unary
  *   Applies a prefix operator to its operand, receiving the operator string
  * @return
  *   The operator list to pass to [[pratt]]
  */
def cFamilyPrecedence[A](
  sym: String => Parser[ParseError, ?],
  binary: (String, A, A) => A,
  unary: (String, A) => A
): List[Operator[A]] = List(
  Operator.InfixLeft(sym("||"), 10, (a, b) => binary("||", a, b)),
  Operator.InfixLeft(sym("&&"), 20, (a, b) => binary("&&", a, b)),
  Operator.InfixLeft(sym("=="), 30, (a, b) => binary("==", a, b)),
  Operator.InfixLeft(sym("!="), 30, (a, b) => binary("!=", a, b)),
  Operator.InfixLeft(sym("<="), 40, (a, b) => binary("<=", a, b)),
  Operator.InfixLeft(sym(">="), 40, (a, b) => binary(">=", a, b)),
  Operator.InfixLeft(sym("<"), 40, (a, b) => binary("<", a, b)),
  Operator.InfixLeft(sym(">"), 40, (a, b) => binary(">", a, b)),
  Operator.InfixLeft(sym("+"), 50, (a, b) => binary("+", a, b)),
  Operator.InfixLeft(sym("-"), 50, (a, b) => binary("-", a, b)),
  Operator.InfixLeft(sym("*"), 60, (a, b) => binary("*", a, b)),
  Operator.InfixLeft(sym("/"), 60, (a, b) => binary("/", a, b)),
  Operator.InfixLeft(sym("%"), 60, (a, b) => binary("%", a, b)),
  Operator.Prefix(sym("-"), 70, (a) => unary("-", a)),
  Operator.Prefix(sym("!"), 70, (a) => unary("!", a))
)

/** If every operator's symbol is a `char(c)` — detected by the `Parser.Satisfy(_, "'c'")` shape
  * produced by the `char` primitive — build a direct character dispatch table. Otherwise return
  * null; the Pratt interpreter will fall back to running `getOp` as before.
  */
private def compileOpTable[A](ops: List[Operator[A]]): PrattOpTable[A] | Null = {
  def charOf(p: Parser[ParseError, ?]): Option[Char] = p match {
    case Parser.Satisfy(_, expected)
        if expected.length == 3 && expected.charAt(0) == '\'' && expected.charAt(2) == '\'' =>
      Some(expected.charAt(1))
    case _ => None
  }
  val pairs: List[Option[(Char, PrattOp[A])]] = ops.map {
    case Operator.InfixLeft(sym, bp, fn) =>
      charOf(sym).map(c => (c, PrattOp.Infix[A](bp, bp, fn)))
    case Operator.InfixRight(sym, bp, fn) =>
      charOf(sym).map(c => (c, PrattOp.Infix[A](bp, bp - 1, fn)))
    case Operator.Postfix(sym, bp, fn) =>
      charOf(sym).map(c => (c, PrattOp.Postfix[A](bp, fn)))
    case _: Operator.Prefix[A] => None
  }
  if pairs.forall(_.isDefined) && pairs.nonEmpty then PrattOpTable.fromPairs(pairs.flatten)
  else null // scalafix:ok DisableSyntax.null
}

private def compileGetOp[A](ops: List[Operator[A]]): Parser[ParseError, PrattOp[A]] = {
  val branches: List[Parser[ParseError, PrattOp[A]]] = ops.map {
    case Operator.InfixLeft(sym, bp, fn) =>
      map(sym, (_: Any) => PrattOp.Infix[A](bp, bp, fn))
    case Operator.InfixRight(sym, bp, fn) =>
      map(sym, (_: Any) => PrattOp.Infix[A](bp, bp - 1, fn))
    case Operator.Postfix(sym, bp, fn) =>
      map(sym, (_: Any) => PrattOp.Postfix[A](bp, fn))
    case _: Operator.Prefix[A] =>
      sys.error("compileGetOp: Prefix operators are compiled into nud, not getOp")
  }
  branches match {
    case Nil => fail(ParseError.Custom("pratt: no operators", (line = 0, column = 0, offset = 0)))
    case _ => choice(branches)
  }
}

/** Parses zero or more occurrences of p, discarding the results.
  *
  * More efficient than `many(p).void` as it doesn't build a list.
  *
  * @param p
  *   The parser to repeat
  * @return
  *   A parser that returns Unit
  */
inline def skipMany[E, A](p: Parser[E, A]): Parser[E, Unit] =
  Parser.SkipMany(p)

/** Parses one or more occurrences of p, discarding the results.
  *
  * More efficient than `many1(p).void` as it doesn't build a list.
  *
  * @param p
  *   The parser to repeat
  * @return
  *   A parser that returns Unit
  */
def skipMany1[E, A](p: Parser[E, A]): Parser[E, Unit] =
  flatMap(p, (_: A) => Parser.SkipMany(p))

@deprecated("Use skipMany1 instead", "0.4.0")
def skipManyNonEmpty[E, A](p: Parser[E, A]): Parser[E, Unit] =
  skipMany1(p)

inline def capture[E, A](p: Parser[E, A]): Parser[E, String] =
  Parser.Capture(p)

/** Parses at least n occurrences of p.
  *
  * @param n
  *   Minimum number of matches required
  * @param p
  *   The parser to repeat
  * @return
  *   A parser that returns a list of at least n elements
  */
def manyAtLeast[E, A](n: Int)(p: Parser[E, A]): Parser[E, List[A]] =
  flatMap(count(n, p), (required: List[A]) => map(many(p), (rest: List[A]) => required ++ rest))

/** Parses p surrounded by the same delimiter on both sides.
  *
  * Convenience for `between(p, delim, delim)`.
  *
  * @param delim
  *   The delimiter parser
  * @param p
  *   The content parser
  * @return
  *   A parser that parses delim-p-delim and returns p's result
  */
def surroundedBy[E, A, D](delim: Parser[E, D])(p: Parser[E, A]): Parser[E, A] =
  between(p, delim, delim)

/** Parses zero or more occurrences of p separated by op, left-associative.
  *
  * Returns default if no matches.
  *
  * @param p
  *   The parser for operands
  * @param op
  *   The parser for operators
  * @param default
  *   Value to return if no matches
  * @return
  *   A parser that builds left-associative parse tree or returns default
  */
def chainLeft[E, A](p: Parser[E, A], op: Parser[E, (A, A) => A], default: A): Parser[E, A] =
  or(chainl1(p, op), succeed(default))

/** Parses zero or more occurrences of p separated by op, right-associative.
  *
  * Returns default if no matches.
  *
  * @param p
  *   The parser for operands
  * @param op
  *   The parser for operators
  * @param default
  *   Value to return if no matches
  * @return
  *   A parser that builds right-associative parse tree or returns default
  */
def chainRight[E, A](p: Parser[E, A], op: Parser[E, (A, A) => A], default: A): Parser[E, A] =
  or(chainr1(p, op), succeed(default))

/** Runs [[inner]] and interns its produced green through the parse-scoped [[GreenCache]]. Every
  * structurally-equal token green (e.g. every `Token(Number, "5")` across the parse) collapses to
  * one canonical heap instance.
  *
  * Grammars opt in at token-producing leaves:
  * {{{
  * val digitTok: Parser[ParseError, GreenNode] = internToken(digit.map(numberToken))
  * }}}
  *
  * Tokens are leaves, so their equality cost is `kind.== && text.==`. For tree-level interning use
  * [[internTree]]; both combinators produce the same `Parser.InternedGreen` ADT case, the
  * distinction is a signal to the reader about what's being interned and at what equality cost.
  *
  * Correctness contract: interning makes structurally-equal siblings `eq`-equal. Sibling
  * disambiguation in `RedTree` must not rely on `_.green eq target.green`. Session 2a moved those
  * lookups over to `RedTree.childIndex`; new code must not reintroduce the `eq` pattern.
  *
  * Scope: the cache is per-[[parser.runtime.run]] invocation and discarded when parsing finishes.
  * See [[GreenCache]] for the Option A vs B shape rationale.
  */
def internToken[E, Tok, Syn](inner: Parser[E, GreenNodeOf[Tok, Syn]]): Parser[E, GreenNodeOf[Tok, Syn]] =
  Parser.InternedGreen(inner)

/** Runs [[inner]] and interns its produced green through the parse-scoped [[GreenCache]]. Intended
  * for tree-producing inners (e.g. an expression or statement parser that wraps its children in a
  * `Tree(...)`); every structurally-equal tree — same kind, same children in the same order,
  * children themselves structurally equal — collapses to one canonical heap instance.
  *
  * Grammars opt in at tree-producing sites:
  * {{{
  * val internedExpression: Parser[ParseError, GreenNode] = internTree(expression)
  * }}}
  *
  * Cost profile differs from [[internToken]]: the cache's structural equality/hash recurses into
  * the tree's children, so the per-intern lookup cost scales with subtree size. Amortisation
  * depends on how often structurally-equal subtrees recur in the workload — if they do, one cache
  * hit replaces the allocation of every descendant; if they don't, the recursive equality cost buys
  * nothing.
  *
  * Same correctness contract as [[internToken]] (see that combinator's doc for sibling-identity
  * invariants). Same `Parser.InternedGreen` ADT case behind both combinators; the split is
  * grammar-author-facing naming only.
  */
def internTree[E, Tok, Syn](inner: Parser[E, GreenNodeOf[Tok, Syn]]): Parser[E, GreenNodeOf[Tok, Syn]] =
  Parser.InternedGreen(inner)

/** Compose child-producing parsers into a [[GreenNodeOf.Tree]] without the intermediate `Tuple2`s /
  * `Vector` wrappers that `~`-based composition accumulates. The child parsers run in sequence;
  * their green-node results are appended into a fresh `VectorBuilder` per parse, whose result
  * becomes the Tree's children.
  *
  * Analogous shape to Rowan's `GreenNodeBuilder.start_node` / `finish_node` pair, exposed here as a
  * combinator-library primitive for grammar authors who prefer the direct shape over the `~`/`.map`
  * composition tax. See `docs/PARSER_RESEARCH_PLAN.md` §4 Stage 1E for the broader context in which
  * this primitive's measured cost/benefit matters.
  *
  * '''Implementation shape — Option A'''
  *
  * Desugars to a [[defer]]-wrapped left-fold over [[parts]]: `defer` gives each parse a fresh
  * `VectorBuilder` (re-running the thunk on every interpretation), the fold chains each part via
  * [[flatMap]] side-effecting `builder += child`, and the terminal [[map]] reads `builder.result`
  * and wraps it. No new `Parser` ADT case, no new interpreter handler. Trade-off: re-constructs the
  * flatMap-chain Parser value once per parse invocation, which itself allocates — the measurement
  * (session 4, opt4) records whether that reconstruction cost exceeds the tuple allocation it
  * replaces. The alternative (Option B, new `Parser.TreeOf` ADT case) would skip the reconstruction
  * but adds an interpreter case; Option A was picked for simplicity and because the per-parse chain
  * reconstruction is bounded by `parts.length`.
  *
  * @param kind
  *   The syntax kind for the constructed `Tree`.
  * @param parts
  *   Child parsers, each producing a green node. Run in sequence; all must succeed for the composed
  *   parser to succeed. Children appear in the result in the order they're passed.
  */
def treeOf[Tok, Syn](
  kind: Syn,
  parts: Parser[ParseError, GreenNodeOf[Tok, Syn]]*
): Parser[ParseError, GreenNodeOf[Tok, Syn]] =
  defer {
    val builder = scala.collection.immutable.Vector.newBuilder[GreenNodeOf[Tok, Syn]]
    val chained: Parser[ParseError, Unit] =
      parts.foldLeft[Parser[ParseError, Unit]](succeed(())) { (acc, part) =>
        flatMap(
          acc,
          (_: Unit) =>
            map(
              part,
              (child: GreenNodeOf[Tok, Syn]) => {
                val _ = builder += child
                ()
              }
            )
        )
      }
    map(chained, (_: Unit) => GreenNodeOf.treeOfVec[Tok, Syn](kind, builder.result()))
  }
