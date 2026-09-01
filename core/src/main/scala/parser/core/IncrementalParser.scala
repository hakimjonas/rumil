package parser.core

import scala.collection.immutable.Vector

import parser.runtime.run

/** Incremental parsing for Rumil.
  *
  * Reparses only the smallest subtree affected by an edit, splicing the result back into the
  * existing green tree. Falls back to full reparsing when the edit's reparseable region exceeds a
  * fraction of the document, when the sub-parse fails, or when no registered parser covers the
  * region's syntax kind.
  *
  * Strategy, in order of preference:
  *   1. Token-level: the edit is entirely inside one "simple" token (as declared by the grammar's
  *      `isSimpleToken` predicate) — update the token's text in place.
  *   2. Block-level: find the smallest ancestor whose kind has a registered sub-parser, reparse the
  *      corresponding region's text with that parser, splice the subtree back.
  *   3. Full reparse: parse the whole new source with [[ReparseableParsers.full]].
  *
  * Grammar authors declare the shape of their incremental parse by supplying a
  * [[ReparseableParsers]] carrying four pieces of policy:
  *   - `full` — the whole-file parser used for the initial parse and the full-reparse fallback.
  *   - `byKind` — parsers indexed by syntax kind for the block-level reparse path.
  *   - `isSimpleToken` — predicate selecting kinds whose text can be edited in place.
  *   - `onParseFailure` — a `String => GreenNodeOf[Tok, Syn]` callback that builds a fallback tree
  *     when `full` returns [[Result.Failure]] on the whole document, preserving the lossless
  *     invariant `GreenNode.toSource(tree) == source` on every result path.
  *
  * All four are language-specific. The `Tok`/`Syn` parameters on [[ReparseableParsers]] and
  * [[incrementalParse]] keep them from mixing across grammars.
  */
object IncrementalParser {

  /** Result of an incremental parse operation.
    *
    * @param tree
    *   The updated green tree.
    * @param reparseRegion
    *   The span that was reparsed, for diagnostics. [[None]] on full reparse.
    * @param fullReparse
    *   True iff the full file was reparsed (i.e. neither the token-level nor the block-level path
    *   succeeded).
    */
  final case class IncrementalResult[Tok, Syn](
    tree: GreenNodeOf[Tok, Syn],
    reparseRegion: Option[Span],
    fullReparse: Boolean
  )

  /** A bundle of parsers for incremental reparsing.
    *
    * @param full
    *   The whole-file parser; used for the initial parse and the full-reparse fallback.
    * @param byKind
    *   Parsers indexed by syntax kind. A kind is reparseable iff it appears here; the value is the
    *   parser that accepts a bare subtree of that kind as its full input.
    * @param isSimpleToken
    *   Predicate identifying token kinds whose text can be edited in place without risking a kind
    *   change — identifiers, numbers, strings, whitespace, comments are the typical answer.
    *   Structural kinds (operators, braces) must always go through a block-level reparse because a
    *   text edit can turn `==` into `=` and change the kind.
    * @param onParseFailure
    *   Called when the full-file parser returns [[Result.Failure]] for the whole document. Takes
    *   the source text and returns a language-specific tree to hand back to the caller, so the
    *   lossless invariant `GreenNode.toSource(tree) == source` holds even on total parse failure.
    *   Typical implementation: wrap the source in an `Error`-kinded token inside an `Unexpected`
    *   wrapper. No default — the shape of an "unparseable" tree is grammar policy, not a library
    *   concern.
    */
  final case class ReparseableParsers[Tok, Syn, E](
    full: Parser[E, GreenNodeOf[Tok, Syn]],
    byKind: Map[Syn, Parser[E, GreenNodeOf[Tok, Syn]]],
    isSimpleToken: Tok => Boolean,
    onParseFailure: String => GreenNodeOf[Tok, Syn]
  ) {
    def reparsableKinds: Set[Syn] = byKind.keySet
  }

  object ReparseableParsers {

    /** A degenerate bundle that carries only the whole-file parser; every edit falls back to full
      * reparse. Useful for tests or cases where the grammar has no reparseable substructures.
      *
      * The caller must still specify the parse-failure tree — the library doesn't know what
      * "unparseable" looks like in the grammar's alphabet.
      */
    def onlyFull[Tok, Syn, E](
      full: Parser[E, GreenNodeOf[Tok, Syn]],
      onParseFailure: String => GreenNodeOf[Tok, Syn]
    ): ReparseableParsers[Tok, Syn, E] =
      ReparseableParsers(full, Map.empty, _ => false, onParseFailure)
  }

  /** Incremental-parse configuration.
    *
    * @param minReparseSize
    *   If the reparse region is within `minReparseSize` bytes of the whole document, do a full
    *   reparse instead — the incremental bookkeeping isn't worth it for near-whole-document edits.
    */
  final case class Config(minReparseSize: Int = 50)

  val defaultConfig: Config = Config()

  /** Incrementally update a syntax tree after a text edit. See the object docstring for the
    * strategy; returns the updated tree plus metadata describing which path fired.
    */
  def incrementalParse[Tok, Syn, E](
    previousTree: GreenNodeOf[Tok, Syn],
    previousSource: String,
    edit: TextEdit,
    parsers: ReparseableParsers[Tok, Syn, E],
    config: Config = defaultConfig
  ): IncrementalResult[Tok, Syn] = {
    // `newSource = edit(previousSource)` is an O(file) string splice — compute it LAZILY so the
    // token-level fast path (which never needs the whole new source, only the edited token's text)
    // doesn't pay it. Only the block-level / full-reparse fallback forces it. Eager computation here
    // kept the keystroke O(file) regardless of how cheap the descent/splice became.
    lazy val newSource = edit(previousSource)
    tryTokenLevelUpdate(previousTree, edit, parsers.isSimpleToken) match {
      case Some(result) => result
      case None => blockLevelReparse(previousTree, edit, newSource, parsers, config)
    }
  }

  /** Token-level fast path: if the edit falls entirely inside one "simple" token, splice a new
    * token with the updated text. Returns [[None]] when the edit straddles a token boundary or the
    * token's kind is structural (operators, braces, etc.) where a text change could imply a kind
    * change.
    */
  private def tryTokenLevelUpdate[Tok, Syn](
    tree: GreenNodeOf[Tok, Syn],
    edit: TextEdit,
    isSimpleToken: Tok => Boolean
  ): Option[IncrementalResult[Tok, Syn]] = {
    val redTree = RedTree(tree)

    redTree.nodeAt(edit.startOffset).flatMap { node =>
      node.green match {
        case GreenNode.Token(kind, oldText) =>
          val tokenStart = node.span.start.offset
          val tokenEnd = node.span.end.offset

          if edit.startOffset >= tokenStart && edit.endOffset <= tokenEnd then {
            val editStartInToken = edit.startOffset - tokenStart
            val editEndInToken = edit.endOffset - tokenStart
            val newText = oldText.substring(0, editStartInToken) +
              edit.newText +
              oldText.substring(editEndInToken)

            if isSimpleToken(kind) && newText.nonEmpty then {
              val newToken: GreenNodeOf[Tok, Syn] = GreenNode.Token[Tok, Syn](kind, newText)
              val path = node.pathFromRoot
              TreeSplicing.replaceAt(tree, path, newToken).map { newTree =>
                IncrementalResult(newTree, Some(node.span), fullReparse = false)
              }
            } else {
              None
            }
          } else {
            None
          }

        // Missing / Unexpected / Tree: never a token-level update. An edit touching an error
        // marker must go through the block-level path so the subtree is re-parsed cleanly.
        case _ =>
          None
      }
    }
  }

  /** Block-level reparse: find the smallest ancestor whose kind appears in `parsers.byKind`,
    * reparse its region's text with the matching sub-parser, and splice the result back.
    */
  private def blockLevelReparse[Tok, Syn, E](
    previousTree: GreenNodeOf[Tok, Syn],
    edit: TextEdit,
    newSource: String,
    parsers: ReparseableParsers[Tok, Syn, E],
    config: Config
  ): IncrementalResult[Tok, Syn] =
    findReparseRegion(previousTree, edit, parsers.reparsableKinds) match {
      case Some((reparseNode, path)) =>
        reparseNode.syntaxKind.flatMap(parsers.byKind.get) match {
          case None =>
            fullReparse(newSource, parsers)

          case Some(subParser) =>
            val regionStart = reparseNode.span.start.offset
            val regionEnd = reparseNode.span.end.offset

            val adjustedEnd = if edit.endOffset <= regionEnd then {
              regionEnd + edit.lengthDelta
            } else {
              regionEnd
            }

            if adjustedEnd - regionStart >= newSource.length - config.minReparseSize then {
              fullReparse(newSource, parsers)
            } else {
              val regionText = newSource.substring(regionStart, adjustedEnd)
              run(subParser, regionText) match {
                case Result.Success(newSubtree, _) =>
                  spliceOrFallback(previousTree, path, reparseNode.span, newSubtree, newSource, parsers)
                case Result.Partial(newSubtree, _, _) =>
                  spliceOrFallback(previousTree, path, reparseNode.span, newSubtree, newSource, parsers)
                case Result.Failure(_, _) =>
                  fullReparse(newSource, parsers)
              }
            }
        }

      case None =>
        fullReparse(newSource, parsers)
    }

  private def spliceOrFallback[Tok, Syn, E](
    previousTree: GreenNodeOf[Tok, Syn],
    path: Vector[Int],
    reparseSpan: Span,
    newSubtree: GreenNodeOf[Tok, Syn],
    newSource: String,
    parsers: ReparseableParsers[Tok, Syn, E]
  ): IncrementalResult[Tok, Syn] =
    TreeSplicing.replaceAt(previousTree, path, newSubtree) match {
      case Some(newTree) => IncrementalResult(newTree, Some(reparseSpan), fullReparse = false)
      case None => fullReparse(newSource, parsers)
    }

  private def findReparseRegion[Tok, Syn](
    tree: GreenNodeOf[Tok, Syn],
    edit: TextEdit,
    reparsableKinds: Set[Syn]
  ): Option[(RedTree[Tok, Syn], Vector[Int])] =
    if reparsableKinds.isEmpty then {
      None
    } else {
      RedTree(tree)
        .findReparseRegion(edit.startOffset, edit.endOffset, reparsableKinds)
        .map(node => (node, node.pathFromRoot))
    }

  /** Full-reparse fallback. On [[Result.Failure]] the bundle's `onParseFailure` callback builds the
    * fallback tree from the source, preserving the lossless invariant
    * `GreenNode.toSource(tree) == source` even when no parse was possible.
    */
  private def fullReparse[Tok, Syn, E](
    source: String,
    parsers: ReparseableParsers[Tok, Syn, E]
  ): IncrementalResult[Tok, Syn] =
    run(parsers.full, source) match {
      case Result.Success(tree, _) =>
        IncrementalResult(tree, None, fullReparse = true)
      case Result.Partial(tree, _, _) =>
        IncrementalResult(tree, None, fullReparse = true)
      case Result.Failure(_, _) =>
        IncrementalResult(parsers.onParseFailure(source), None, fullReparse = true)
    }

  /** Batch several edits into one incremental update.
    *
    * Edits must be sorted by start offset and non-overlapping. The implementation combines them
    * into a single super-edit over the union of their ranges and defers to [[incrementalParse]]. If
    * the combined range exceeds half the new document, falls back to full reparse.
    */
  def batchIncrementalParse[Tok, Syn, E](
    previousTree: GreenNodeOf[Tok, Syn],
    previousSource: String,
    edits: List[TextEdit],
    parsers: ReparseableParsers[Tok, Syn, E],
    config: Config = defaultConfig
  ): IncrementalResult[Tok, Syn] =
    if edits.isEmpty then {
      IncrementalResult(previousTree, None, fullReparse = false)
    } else if edits.length == 1 then {
      incrementalParse(previousTree, previousSource, edits.head, parsers, config)
    } else {
      val newSource = edits.foldLeft(previousSource)((src, edit) => edit(src))
      val minStart = edits.map(_.startOffset).min
      val maxEnd = edits.map(e => e.endOffset + e.lengthDelta).max

      if maxEnd - minStart > newSource.length / 2 then {
        fullReparse(newSource, parsers)
      } else {
        val combinedEdit = TextEdit(
          minStart,
          edits.last.endOffset,
          newSource.substring(minStart, minStart + (maxEnd - minStart))
        )
        incrementalParse(previousTree, previousSource, combinedEdit, parsers, config)
      }
    }
}

/** Convenience for calling `incrementalParse` fluently on any green node. */
extension [Tok, Syn](tree: GreenNodeOf[Tok, Syn]) {
  def applyEdit[E](
    source: String,
    edit: TextEdit,
    parsers: IncrementalParser.ReparseableParsers[Tok, Syn, E],
    config: IncrementalParser.Config = IncrementalParser.defaultConfig
  ): IncrementalParser.IncrementalResult[Tok, Syn] =
    IncrementalParser.incrementalParse(tree, source, edit, parsers, config)
}
