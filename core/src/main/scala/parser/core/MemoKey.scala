package parser.core

/**
 * Type-safe key for memoization table.
 *
 * This is the principled approach to heterogeneous memoization:
 * - Each MemoKey carries phantom type parameters [E, A] representing the result type
 * - The key identity is established at creation time in `rule`
 * - Type safety is maintained because the same key instance is used for both store and retrieve
 *
 * IMPORTANT: MemoKey uses reference equality (===), not structural equality (==).
 * Each MemoKey instance is unique - two keys created separately will never match,
 * even if they have the same type parameters. This is intentional: each `rule`
 * call site gets its own unique key for memoization.
 *
 * The cast in `MemoTable.get` is safe because:
 * 1. Keys are created once per `rule` call site
 * 2. The same key instance is used to store and retrieve
 * 3. Store operation uses the key's type parameters
 * 4. Therefore retrieve returns the same type that was stored
 *
 * @tparam E The error type of the memoized parser result
 * @tparam A The value type of the memoized parser result
 */
final class MemoKey[E, A] private[parser] ()

object MemoKey {

  /** Creates a new unique memo key. Called once per `rule` instantiation. */
  private[parser] def apply[E, A](): MemoKey[E, A] = new MemoKey[E, A]()
}
