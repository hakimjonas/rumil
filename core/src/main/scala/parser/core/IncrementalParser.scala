package parser.core

import scala.collection.immutable.Vector
import parser.runtime.run

/**
 * Incremental parsing support for Rumil.
 *
 * Provides efficient re-parsing of syntax trees after text edits.
 * Instead of parsing the entire document from scratch, we identify
 * the minimal region that needs reparsing and splice the result
 * back into the existing tree.
 *
 * This follows the "block reparsing" strategy used by rust-analyzer:
 * 1. Find the smallest reparsable ancestor containing the edit
 * 2. Extract the text for that region (with adjusted offsets)
 * 3. Parse just that region
 * 4. Splice the new subtree back into the original tree
 *
 * The approach is simple and effective for most editing scenarios,
 * achieving O(log n) to O(n) reparsing depending on edit location.
 */
object IncrementalParser {

  /**
   * Result of an incremental parse operation.
   *
   * @param tree The updated green tree
   * @param reparseRegion The span that was reparsed (for debugging/metrics)
   * @param fullReparse True if we fell back to full reparsing
   */
  final case class IncrementalResult(
    tree: GreenNode,
    reparseRegion: Option[Span],
    fullReparse: Boolean
  )

  /**
   * Configuration for incremental parsing.
   *
   * @param reparsableKinds Syntax kinds that form reparse boundaries
   * @param minReparseSize Minimum region size before attempting incremental (bytes)
   */
  final case class Config(
    reparsableKinds: Set[SyntaxKind],
    minReparseSize: Int = 50
  )

  /**
   * Default configuration suitable for block-structured languages.
   */
  val defaultConfig: Config = Config(
    reparsableKinds = Set(
      SyntaxKind.Block,
      SyntaxKind.Function,
      SyntaxKind.Statement,
      SyntaxKind.SourceFile
    )
  )

  /**
   * Incrementally update a syntax tree after a text edit.
   *
   * Attempts to reparse only the minimal region affected by the edit.
   * Falls back to full reparsing if incremental parsing is not possible
   * or not beneficial.
   *
   * Strategy (in order of preference):
   * 1. Token-level: If edit is within a single token, just re-lex that token
   * 2. Block-level: Find smallest reparsable ancestor, reparse just that
   * 3. Full reparse: Fall back to parsing entire document
   *
   * @param previousTree The existing green tree
   * @param previousSource The source text before the edit
   * @param edit The text edit to apply
   * @param parser The parser to use for reparsing regions
   * @param config Configuration for reparse boundaries
   * @return The updated tree and metadata about the reparse operation
   */
  def incrementalParse[E](
    previousTree: GreenNode,
    previousSource: String,
    edit: TextEdit,
    parser: Parser[E, GreenNode],
    config: Config = defaultConfig
  ): IncrementalResult = {
    // Apply the edit to get the new source
    val newSource = edit(previousSource)

    // Strategy 1: Try token-level update first (fastest)
    tryTokenLevelUpdate(previousTree, previousSource, edit, newSource) match {
      case Some(result) => result
      case None =>
        // Strategy 2: Try block-level incremental reparsing
        blockLevelReparse(previousTree, edit, newSource, parser, config)
    }
  }

  /**
   * Try to update a single token without reparsing.
   *
   * This is the fastest path - if an edit is entirely within a single token
   * and doesn't change the token's kind, we can just update the token text.
   *
   * @return Some(result) if token-level update succeeded, None to fall back
   */
  private def tryTokenLevelUpdate(
    tree: GreenNode,
    @annotation.unused oldSource: String,
    edit: TextEdit,
    @annotation.unused newSource: String
  ): Option[IncrementalResult] = {
    val redTree = RedTree(tree)

    // Find the token containing the edit start
    redTree.nodeAt(edit.startOffset).flatMap { node =>
      node.green match {
        case GreenNode.Token(kind, oldText, oldSpan) =>
          val tokenStart = node.span.start.offset
          val tokenEnd = node.span.end.offset

          // Check if edit is entirely within this token
          if (edit.startOffset >= tokenStart && edit.endOffset <= tokenEnd) {
            // Calculate the new token text
            val editStartInToken = edit.startOffset - tokenStart
            val editEndInToken = edit.endOffset - tokenStart
            val newText = oldText.substring(0, editStartInToken) +
              edit.newText +
              oldText.substring(editEndInToken)

            // For now, only allow token update if the token kind is "simple"
            // (identifiers, numbers, strings, whitespace, comments)
            // Operators and delimiters might change the parse structure
            val isSimpleToken = kind match {
              case TokenKind.Identifier | TokenKind.Number | TokenKind.String |
                   TokenKind.Whitespace | TokenKind.Comment => true
              case _ => false
            }

            if (isSimpleToken && newText.nonEmpty) {
              // Create the new token with adjusted span
              val newEnd = (
                line = oldSpan.end.line,
                column = oldSpan.start.column + newText.length,
                offset = tokenStart + newText.length
              )
              val newSpan: Span = (start = oldSpan.start, end = newEnd)
              val newToken = GreenNode.Token(kind, newText, newSpan)

              // Splice it in
              val path = node.pathFromRoot
              TreeSplicing.replaceAt(tree, path, newToken).map { newTree =>
                // Adjust spans for subsequent nodes
                val adjusted = TreeSplicing.adjustSpans(newTree, edit, 0)
                IncrementalResult(
                  adjusted,
                  Some(node.span),
                  fullReparse = false
                )
              }
            } else {
              None // Fall back to block-level
            }
          } else {
            None // Edit spans multiple tokens
          }

        case GreenNode.Tree(_, _) =>
          None // Not a token
      }
    }
  }

  /**
   * Block-level incremental reparsing.
   *
   * Finds the smallest reparsable ancestor containing the edit,
   * reparses just that subtree, and splices the result back in.
   */
  private def blockLevelReparse[E](
    previousTree: GreenNode,
    edit: TextEdit,
    newSource: String,
    parser: Parser[E, GreenNode],
    config: Config
  ): IncrementalResult = {
    findReparseRegion(previousTree, edit, config) match {
      case Some((reparseNode, path)) =>
        // Calculate the text region to reparse
        val regionStart = reparseNode.span.start.offset
        val regionEnd = reparseNode.span.end.offset

        // Adjust for the edit's effect on the region boundaries
        val adjustedEnd = if (edit.endOffset <= regionEnd) {
          regionEnd + edit.lengthDelta
        } else {
          regionEnd
        }

        // Check if incremental is worthwhile (region significantly smaller than total)
        if (adjustedEnd - regionStart >= newSource.length - config.minReparseSize) {
          // Region is too large - just do full reparse
          fullReparse(newSource, parser)
        } else {
          // Extract the region text from new source
          val regionText = newSource.substring(regionStart, adjustedEnd)

          // Parse just this region
          run(parser, regionText) match {
            case Result.Success(newSubtree, _) =>
              // Splice the new subtree into the tree
              TreeSplicing.replaceAt(previousTree, path, newSubtree) match {
                case Some(newTree) =>
                  // Adjust spans for nodes after the edit
                  val adjusted = TreeSplicing.adjustSpans(newTree, edit, 0)
                  IncrementalResult(
                    adjusted,
                    Some(reparseNode.span),
                    fullReparse = false
                  )
                case None =>
                  // Splicing failed - fall back to full reparse
                  fullReparse(newSource, parser)
              }

            case Result.Partial(newSubtree, _, _) =>
              // Partial success - still use the result
              TreeSplicing.replaceAt(previousTree, path, newSubtree) match {
                case Some(newTree) =>
                  val adjusted = TreeSplicing.adjustSpans(newTree, edit, 0)
                  IncrementalResult(
                    adjusted,
                    Some(reparseNode.span),
                    fullReparse = false
                  )
                case None =>
                  fullReparse(newSource, parser)
              }

            case Result.Failure(_, _) =>
              // Region parse failed - fall back to full reparse
              fullReparse(newSource, parser)
          }
        }

      case None =>
        // No suitable reparse region found - full reparse
        fullReparse(newSource, parser)
    }
  }

  /**
   * Find the minimal region to reparse for an edit.
   *
   * Returns the RedTree node and its path if found.
   */
  private def findReparseRegion(
    tree: GreenNode,
    edit: TextEdit,
    config: Config
  ): Option[(RedTree, Vector[Int])] = {
    // Build a red tree for navigation
    val redTree = RedTree(tree)

    // Find the reparse region
    redTree.findReparseRegion(
      edit.startOffset,
      edit.endOffset,
      config.reparsableKinds
    ).map { node =>
      (node, node.pathFromRoot)
    }
  }

  /**
   * Perform a full reparse of the entire source.
   */
  private def fullReparse[E](
    source: String,
    parser: Parser[E, GreenNode]
  ): IncrementalResult = {
    run(parser, source) match {
      case Result.Success(tree, _) =>
        IncrementalResult(tree, None, fullReparse = true)
      case Result.Partial(tree, _, _) =>
        IncrementalResult(tree, None, fullReparse = true)
      case Result.Failure(_, _) =>
        // Even full parse failed - return an error tree
        val errorSpan: Span = (
          start = (line = 1, column = 1, offset = 0),
          end = (line = 1, column = 1, offset = source.length)
        )
        val errorTree = GreenNode.Token(TokenKind.Error, source, errorSpan)
        IncrementalResult(errorTree, None, fullReparse = true)
    }
  }

  /**
   * Batch multiple edits and apply them incrementally.
   *
   * Edits must be sorted by offset and non-overlapping.
   * This is more efficient than applying edits one at a time
   * when there are many small edits.
   *
   * @param previousTree The existing green tree
   * @param previousSource The source text before edits
   * @param edits The edits to apply (sorted by offset, non-overlapping)
   * @param parser The parser to use for reparsing
   * @param config Configuration for reparse boundaries
   * @return The final updated tree
   */
  def batchIncrementalParse[E](
    previousTree: GreenNode,
    previousSource: String,
    edits: List[TextEdit],
    parser: Parser[E, GreenNode],
    config: Config = defaultConfig
  ): IncrementalResult = {
    if (edits.isEmpty) {
      IncrementalResult(previousTree, None, fullReparse = false)
    } else if (edits.length == 1) {
      incrementalParse(previousTree, previousSource, edits.head, parser, config)
    } else {
      // For multiple edits, we need to apply them in order
      // Apply edits to get final source
      val newSource = edits.foldLeft(previousSource) { (src, edit) =>
        edit(src)
      }

      // Find the overall affected region
      val minStart = edits.map(_.startOffset).min
      val maxEnd = edits.map(e => e.endOffset + e.lengthDelta).max

      // Check if we should just do full reparse
      if (maxEnd - minStart > newSource.length / 2) {
        fullReparse(newSource, parser)
      } else {
        // Create a synthetic edit covering the whole range
        val combinedEdit = TextEdit(
          minStart,
          edits.last.endOffset,
          newSource.substring(minStart, minStart + (maxEnd - minStart))
        )
        incrementalParse(previousTree, previousSource, combinedEdit, parser, config)
      }
    }
  }
}

/**
 * Extension methods for convenient incremental parsing.
 */
extension (tree: GreenNode) {
  /**
   * Apply an edit and reparse incrementally.
   */
  def applyEdit[E](
    source: String,
    edit: TextEdit,
    parser: Parser[E, GreenNode],
    config: IncrementalParser.Config = IncrementalParser.defaultConfig
  ): IncrementalParser.IncrementalResult =
    IncrementalParser.incrementalParse(tree, source, edit, parser, config)
}
