package parser.core

/**
 * Radix tree (compressed trie) for efficient string matching.
 *
 * A radix tree compresses common prefixes, making it ideal for matching
 * one of many string alternatives in O(m) time where m is the length of
 * the matched string, independent of the number of alternatives.
 *
 * Example tree for ["aaa", "aab", "bbb", "bbc"]:
 * {{{
 *        root
 *       /    \
 *     "aa"   "bb"
 *     / \    / \
 *   "a" "b" "b" "c"
 * }}}
 *
 * The tree uses bit-masked hashing to quickly find the right branch
 * without scanning all alternatives.
 *
 * @param matched The string matched so far on the path to this node
 * @param bitMask Bit mask for hashing characters to array indices
 * @param prefixes Array of prefix strings for each branch (null if no branch)
 * @param children Array of child nodes (null if leaf or no branch)
 */
final class RadixNode private (
    val matched: String,
    private val bitMask: Int,
    private val prefixes: Array[String | Null],
    private val children: Array[RadixNode | Null]
) {

  /**
   * Attempts to match a string at the given offset.
   *
   * @param input The input string to match against
   * @param offset The position in input to start matching
   * @return The end position if matched, or -1 if no match
   */
  def matchAt(input: String, offset: Int): Int = {
    val result = matchAtOrNull(input, offset)
    if (result == null) -1 else offset + result.length
  }

  /**
   * Attempts to match and returns the matched string or null.
   *
   * @param input The input string to match against
   * @param offset The position in input to start matching
   * @return The matched string, or null if no match
   */
  def matchAtOrNull(input: String, offset: Int): String =
    matchLoop(input, offset, matched)

  /**
   * Core matching loop - tail recursive traversal of the radix tree.
   *
   * @param input The input string
   * @param offset Current position in input
   * @param currentMatch The best match found so far
   * @return The longest matching string, or null if none
   */
  @annotation.tailrec
  private def matchLoop(input: String, offset: Int, currentMatch: String): String = {
    // If we've reached end of input, return current match
    if (offset >= input.length) {
      currentMatch
    } else {
      // Get next character and compute hash index
      val c = input.charAt(offset)
      val idx = c.toInt & bitMask

      // Check if we have a prefix at this index
      if (idx >= prefixes.length) {
        currentMatch
      } else {
        val prefix = prefixes(idx)
        if (prefix eq null) {
          currentMatch
        } else {
          // Verify the full prefix matches (not just the hashed character)
          val prefixLen = prefix.length
          if (offset + prefixLen > input.length) {
            currentMatch
          } else if (!input.regionMatches(offset, prefix, 0, prefixLen)) {
            currentMatch
          } else {
            // Prefix matched - get child and continue
            val child = children(idx)
            if (child eq null) {
              // Leaf node - return the match
              // Optimization: avoid concatenation when currentMatch is empty (root level)
              if (currentMatch.isEmpty) prefix else currentMatch + prefix
            } else {
              // Continue matching in child
              child.matchLoop(input, offset + prefixLen, child.matched)
            }
          }
        }
      }
    }
  }
}

object RadixNode {

  /**
   * Builds a radix tree from a collection of strings.
   *
   * @param strings The strings to include in the tree
   * @return A RadixNode that can match any of the input strings
   */
  def fromStrings(strings: Iterable[String]): RadixNode = {
    val list = strings.toList.distinct.filter(_.nonEmpty)
    if (list.isEmpty) {
      // Empty tree that matches nothing
      new RadixNode("", 0, Array.empty, Array.empty)
    } else {
      buildNode("", list)
    }
  }

  /**
   * Recursively builds a radix tree node.
   *
   * @param matched The string matched to reach this node
   * @param strings The remaining strings to organize under this node
   * @return A RadixNode for the given strings
   */
  private def buildNode(matched: String, strings: List[String]): RadixNode = {
    // Separate empty strings (which are matches at this node) from non-empty
    val (_, nonEmpty) = strings.partition(_.isEmpty)

    if (nonEmpty.isEmpty) {
      // All strings matched - leaf node
      new RadixNode(matched, 0, Array.empty, Array.empty)
    } else {
      // Group strings by their first character
      val grouped = nonEmpty.groupBy(_.charAt(0))

      // Calculate bit mask - minimum bits needed to distinguish first chars
      val chars = grouped.keys.map(_.toInt).toSet
      val bitMask = computeBitMask(chars)
      val arraySize = bitMask + 1

      // Build prefix and children arrays
      val prefixes = new Array[String | Null](arraySize)
      val children = new Array[RadixNode | Null](arraySize)

      grouped.foreach { case (c, strs) =>
        val idx = c.toInt & bitMask

        // Find common prefix among all strings in this group
        val commonPrefix = strs.reduce(commonPrefixOf)

        // Remove the common prefix from all strings
        val remaining = strs.map(_.substring(commonPrefix.length))

        prefixes(idx) = commonPrefix

        // Build child node if there are remaining suffixes
        if (remaining.forall(_.isEmpty)) {
          // All strings fully matched by prefix - no child needed
          children(idx) = null
        } else {
          children(idx) = buildNode(matched + commonPrefix, remaining)
        }
      }

      new RadixNode(matched, bitMask, prefixes, children)
    }
  }

  /**
   * Computes the minimum bit mask needed to distinguish a set of characters.
   *
   * We want the smallest mask such that (c & mask) is unique for each c.
   * This minimizes array size while avoiding collisions.
   */
  private def computeBitMask(chars: Set[Int]): Int = {
    var mask = 0
    var bits = 0
    while (bits < 16 && hasCollision(chars, mask)) {
      bits += 1
      mask = (1 << bits) - 1
    }
    mask
  }

  /**
   * Checks if any two characters collide under the given mask.
   */
  private def hasCollision(chars: Set[Int], mask: Int): Boolean = {
    val masked = chars.map(_ & mask)
    masked.size != chars.size
  }

  /**
   * Returns the longest common prefix of two strings.
   */
  private def commonPrefixOf(a: String, b: String): String = {
    val len = math.min(a.length, b.length)
    var i = 0
    while (i < len && a.charAt(i) == b.charAt(i)) {
      i += 1
    }
    a.substring(0, i)
  }
}
