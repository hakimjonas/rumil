package parser.core

/** Radix tree (compressed trie) for efficient string matching.
  *
  * A radix tree compresses common prefixes, making it ideal for matching one of many string
  * alternatives in O(m) time where m is the length of the matched string, independent of the number
  * of alternatives.
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
  * The tree uses bit-masked hashing to quickly find the right branch without scanning all
  * alternatives.
  *
  * @param matched
  *   The string matched so far on the path to this node
  * @param bitMask
  *   Bit mask for hashing characters to array indices
  * @param prefixes
  *   Array of prefix strings for each branch (null if no branch)
  * @param children
  *   Array of child nodes (null if leaf or no branch)
  */
final class RadixNode private (
  val matched: String,
  private val isTerminal: Boolean, // True if this node represents a complete word
  private val bitMask: Int,
  private val prefixes: Array[String | Null],
  private val children: Array[RadixNode | Null]
) {

  /** Attempts to match a string at the given offset.
    *
    * @param input
    *   The input string to match against
    * @param offset
    *   The position in input to start matching
    * @return
    *   The end position if matched, or -1 if no match
    */
  def matchAt(input: String, offset: Int): Int = {
    val result = matchAtOrNull(input, offset)
    if result eq null then -1 else offset + result.length // scalafix:ok DisableSyntax.null
  }

  /** Attempts to match and returns the matched string or null.
    *
    * @param input
    *   The input string to match against
    * @param offset
    *   The position in input to start matching
    * @return
    *   The matched string, or null if no match
    */
  def matchAtOrNull(input: String, offset: Int): String | Null =
    matchLoop(input, offset, null) // scalafix:ok DisableSyntax.null

  /** Core matching loop - tail recursive traversal of the radix tree.
    *
    * @param input
    *   The input string
    * @param offset
    *   Current position in input
    * @param currentMatch
    *   The best match found so far
    * @return
    *   The longest matching string, or null if none
    */
  @annotation.tailrec
  private def matchLoop(input: String, offset: Int, currentMatch: String | Null): String | Null = {
    val validMatch = if isTerminal then matched else currentMatch

    if offset >= input.length then {
      validMatch
    } else {
      val c = input.charAt(offset)
      val idx = c.toInt & bitMask

      if idx >= prefixes.length then {
        validMatch
      } else {
        val prefix = prefixes(idx)
        if prefix eq null then { // scalafix:ok DisableSyntax.null
          validMatch
        } else {
          val prefixLen = prefix.length
          if offset + prefixLen > input.length then {
            validMatch
          } else if !input.regionMatches(offset, prefix, 0, prefixLen) then {
            validMatch
          } else {
            val child = children(idx)
            if child eq null then { // scalafix:ok DisableSyntax.null
              if matched.isEmpty then prefix else matched + prefix
            } else {
              child.matchLoop(input, offset + prefixLen, validMatch)
            }
          }
        }
      }
    }
  }
}

object RadixNode {

  /** Builds a radix tree from a collection of strings.
    *
    * @param strings
    *   The strings to include in the tree
    * @return
    *   A RadixNode that can match any of the input strings
    */
  def fromStrings(strings: Iterable[String]): RadixNode = {
    val list = strings.toList.distinct.filter(_.nonEmpty)
    if list.isEmpty then {
      new RadixNode("", false, 0, Array.empty, Array.empty)
    } else {
      buildNode("", list)
    }
  }

  /** Recursively builds a radix tree node.
    *
    * @param matched
    *   The string matched to reach this node
    * @param strings
    *   The remaining strings to organize under this node
    * @return
    *   A RadixNode for the given strings
    */
  private def buildNode(matched: String, strings: List[String]): RadixNode = {
    val (empty, nonEmpty) = strings.partition(_.isEmpty)
    val isTerminal = empty.nonEmpty

    if nonEmpty.isEmpty then {
      new RadixNode(matched, true, 0, Array.empty, Array.empty)
    } else {
      val grouped = nonEmpty.groupBy(_.charAt(0))

      val chars = grouped.keys.map(_.toInt).toSet
      val bitMask = computeBitMask(chars)
      val arraySize = bitMask + 1

      val prefixes = new Array[String | Null](arraySize)
      val children = new Array[RadixNode | Null](arraySize)

      grouped.foreach { case (c, strs) =>
        val idx = c.toInt & bitMask

        val commonPrefix = strs.reduce(commonPrefixOf)

        val remaining = strs.map(_.substring(commonPrefix.length))

        prefixes(idx) = commonPrefix

        if remaining.forall(_.isEmpty) then {
          children(idx) = null // scalafix:ok DisableSyntax.null
        } else {
          children(idx) = buildNode(matched + commonPrefix, remaining)
        }
      }

      new RadixNode(matched, isTerminal, bitMask, prefixes, children)
    }
  }

  /** Computes the minimum bit mask needed to distinguish a set of characters.
    *
    * We want the smallest mask such that (c & mask) is unique for each c. This minimizes array size
    * while avoiding collisions.
    */
  private def computeBitMask(chars: Set[Int]): Int = {
    var mask = 0
    var bits = 0
    while bits < 16 && hasCollision(chars, mask) do {
      bits += 1
      mask = (1 << bits) - 1
    }
    mask
  }

  /** Checks if any two characters collide under the given mask.
    */
  private def hasCollision(chars: Set[Int], mask: Int): Boolean = {
    val masked = chars.map(_ & mask)
    masked.size != chars.size
  }

  /** Returns the longest common prefix of two strings.
    */
  private def commonPrefixOf(a: String, b: String): String = {
    val len = math.min(a.length, b.length)
    var i = 0
    while i < len && a.charAt(i) == b.charAt(i) do i += 1
    a.substring(0, i)
  }
}
