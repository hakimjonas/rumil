package parser.typeclasses

// ============================================================================
// TYPE CLASS DEFINITIONS
// ============================================================================

/**
 * Functor - types that can be mapped over.
 *
 * Laws:
 * - Identity: map(fa)(x => x) == fa
 * - Composition: map(map(fa)(f))(g) == map(fa)(f andThen g)
 *
 * @tparam F The type constructor (e.g., List, Option, Parser)
 */
trait Functor[F[_]] {
  def map[A, B](fa: F[A])(f: A => B): F[B]
}

/**
 * Applicative - functors that can sequence effects.
 *
 * Extends Functor with pure (lift values) and ap (apply wrapped functions).
 *
 * Laws:
 * - Identity: ap(pure(id))(fa) == fa
 * - Composition: ap(ap(ap(pure(compose))(u))(v))(w) == ap(u)(ap(v)(w))
 * - Homomorphism: ap(pure(f))(pure(x)) == pure(f(x))
 * - Interchange: ap(u)(pure(y)) == ap(pure(f => f(y)))(u)
 *
 * @tparam F The type constructor
 */
trait Applicative[F[_]] extends Functor[F] {
  def pure[A](a: A): F[A]
  def ap[A, B](ff: F[A => B])(fa: F[A]): F[B]

  override def map[A, B](fa: F[A])(f: A => B): F[B] = {
    ap(pure(f))(fa)
  }
}

/**
 * Monad - applicatives with flatMap for dependent sequencing.
 *
 * Enables chaining computations where later steps depend on earlier results.
 *
 * Laws:
 * - Left identity: flatMap(pure(a))(f) == f(a)
 * - Right identity: flatMap(fa)(pure) == fa
 * - Associativity: flatMap(flatMap(fa)(f))(g) == flatMap(fa)(a => flatMap(f(a))(g))
 *
 * @tparam F The type constructor
 */
trait Monad[F[_]] extends Applicative[F] {
  def flatMap[A, B](fa: F[A])(f: A => F[B]): F[B]

  override def ap[A, B](ff: F[A => B])(fa: F[A]): F[B] = {
    flatMap(ff)(f => map(fa)(f))
  }

  override def map[A, B](fa: F[A])(f: A => B): F[B] = {
    flatMap(fa)(a => pure(f(a)))
  }
}

/**
 * Show - types that can be converted to strings.
 *
 * Provides a principled way to display values.
 */
trait Show[A] {
  def show(a: A): String
}

/**
 * Eq - types that support equality comparison.
 *
 * Law:
 * - Reflexivity: eqv(a, a) == true
 * - Symmetry: eqv(a, b) == eqv(b, a)
 * - Transitivity: if eqv(a, b) && eqv(b, c) then eqv(a, c)
 */
trait Eq[A] {
  def eqv(a: A, b: A): Boolean
}
