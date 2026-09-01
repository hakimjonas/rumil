package parser.core

/** Hash-consing cache for [[GreenNodeOf]] values. Interning two structurally-equal greens through
  * the same cache returns one canonical instance, so identical `Token(Number, "5")` subtrees
  * produced by different parse iterations share memory instead of each allocating a fresh object.
  *
  * '''Shape choice — Option A (canonical-instance return), not Option B (GreenId indirection)'''
  *
  * Fungal's `TypeTable` (see `fungal_core/src/types/type_table.rs`) wraps a
  * `HashMap[TypeNode, TypeId] + Vector[TypeNode]` pair and hands out integer handles. The
  * indirection pays for itself in a type checker because types are compared (equality during
  * substitution, inference, subsumption) far more often than they are constructed, and
  * integer-equality is cheaper than structural-equality on nested types. Greens have the opposite
  * workload: built once, then walked (`toSource`, `findReparseRegion`, `RedTree` projection)
  * without needing to compare pairs of greens for equality. An extra ID layer would force every
  * consumer of the current API to carry a cache through to resolve IDs back to nodes — pure
  * friction for the same deduplication benefit. So we return the canonical `GreenNodeOf` instance
  * directly; `eq` on the returned reference is the O(1) comparison a future consumer could exploit
  * if one ever wanted TypeTable-style fast equality checks.
  *
  * '''Structural equality'''
  *
  * Scala 3 enum cases derive structural `equals`/`hashCode`, which is exactly the hash-cons key
  * here: `Token(Number, "5") == Token(Number, "5")` regardless of construction site. For trees this
  * recurses on children, so the per-intern cost scales with subtree size. The cost/benefit tradeoff
  * between leaf (`internToken`) and tree-level (`internTree`) interning has to be measured per
  * workload — whether a given grammar produces enough structurally-equal subtrees for one cache hit
  * to amortise the whole subtree's re-construction.
  *
  * '''Lifecycle'''
  *
  * Each `run(parser, input)` starts with [[empty]]; the cache lives on `ParserState` for the
  * duration of one parse. Greens from different documents are never cross-contaminated. The cache
  * on `ParserState` is stored as `GreenCache[?, ?]` (existential wildcard) — the library is
  * type-safe under the invariant "one language (`Tok`, `Syn`) per parse", justified inline at the
  * ParserState field site.
  *
  * '''Sibling identity contract'''
  *
  * Interning makes structurally-equal siblings `eq`-equal. `RedTree`'s sibling disambiguation uses
  * [[RedTree.childIndex]], not `_.green eq target.green` (see session 2a). Don't add an `eq`-based
  * sibling lookup anywhere and expect it to survive.
  */
opaque type GreenCache[Tok, Syn] = Map[GreenNodeOf[Tok, Syn], GreenNodeOf[Tok, Syn]]

object GreenCache {

  /** Empty cache, used at the start of every parse. */
  def empty[Tok, Syn]: GreenCache[Tok, Syn] = Map.empty

  extension [Tok, Syn](cache: GreenCache[Tok, Syn]) {

    /** Intern [[node]]; return the canonical instance (`eq`-equal to any prior intern of a
      * structurally equal node) plus the updated cache. First occurrence stores `node` as its own
      * canonical instance; later structurally-equal calls return that stored instance.
      *
      * Amortised O(1) on small caches (the bench grammar's ~15 unique tokens); log32 on large
      * caches per Scala's persistent `HashMap`. The parse-scoped lifecycle keeps caches small.
      */
    def intern(node: GreenNodeOf[Tok, Syn]): (GreenCache[Tok, Syn], GreenNodeOf[Tok, Syn]) =
      cache.get(node) match {
        case Some(canonical) => (cache, canonical)
        case None => (cache.updated(node, node), node)
      }

    /** Number of distinct interned nodes. Diagnostic only. */
    def size: Int = cache.size
  }
}
