// Copyright (C) 2017-2018 John A. De Goes. All rights reserved.
package scalaz.zio

/**
 * A fiber is a lightweight thread of execution that never consumes more than a
 * whole thread (but may consume much less, depending on contention). Fibers are
 * spawned by forking `IO` actions, which, conceptually at least, runs them
 * concurrently with the parent `IO` action.
 *
 * Fibers can be joined, yielding their result other fibers, or interrupted,
 * which terminates the fiber with a runtime error.
 *
 * Fork-Join Identity: fork >=> join = id
 *
 * {{{
 * for {
 *   fiber1 <- io1.fork
 *   fiber2 <- io2.fork
 *   _      <- fiber1.interrupt(e)
 *   a      <- fiber2.join
 * } yield a
 * }}}
 */
trait Fiber[+E, +A] { self =>

  /**
   * Joins the fiber, with suspends the joining fiber until the result of the
   * fiber has been determined. Attempting to join a fiber that has been or is
   * killed before producing its result will result in a catchable error.
   */
  def join: IO[E, A]

  /**
   * Interrupts the fiber with no specified reason. If the fiber has already
   * terminated, either successfully or with error, this will resume
   * immediately. Otherwise, it will resume when the fiber has been
   * successfully interrupted or has produced its result.
   */
  def interrupt: IO[Nothing, Unit] = interrupt0(Nil)

  /**
   * Interrupts the fiber with the specified error(s). If the fiber has already
   * terminated, either successfully or with error, this will resume
   * immediately. Otherwise, it will resume when the fiber has been
   * successfully interrupted or has produced its result.
   */
  def interrupt(t: Throwable, ts: Throwable*): IO[Nothing, Unit] = interrupt0(t :: ts.toList)

  /**
   * Interrupts the fiber with a list of error(s).
   */
  def interrupt0(ts: List[Throwable]): IO[Nothing, Unit]

  /**
   * Add an exit handler for when the fiber terminates and receive information
   * on whether the fiber terminated normally, with unhandled error, or with
   * exception.
   *
   * The specified action will be invoked after the fiber has finished running
   * (including all finalizers). If the specified action throws an exception,
   * it will be reported to the parent fiber's unhandled error handler.
   */
  def onComplete(f: ExitResult[E, A] => IO[Nothing, Unit]): IO[Nothing, Unit]

  /**
   * Zips this fiber with the specified fiber, combining their results using
   * the specified combiner function. Both joins and interruptions are performed
   * in sequential order from left to right.
   */
  final def zipWith[E1 >: E, B, C](that: => Fiber[E1, B])(f: (A, B) => C): Fiber[E1, C] =
    new Fiber[E1, C] {
      def join: IO[E1, C] =
        self.join.seqWith(that.join)(f)

      def interrupt0(ts: List[Throwable]): IO[Nothing, Unit] =
        self.interrupt0(ts) *> that.interrupt0(ts)

      def onComplete(fc: ExitResult[E1, C] => IO[Nothing, Unit]): IO[Nothing, Unit] =
        self.onComplete { ra: ExitResult[E, A] =>
          that.onComplete { rb: ExitResult[E1, B] =>
            (ra, rb) match {
              case (ExitResult.Completed(a), ExitResult.Completed(b))   => fc(ExitResult.Completed(f(a, b)))
              case (ExitResult.Failed(e, ts), rb)                       => fc(ExitResult.Failed(e, combine(ts, rb)))
              case (ExitResult.Terminated(ts), rb)                      => fc(ExitResult.Terminated(combine(ts, rb)))
              case (ExitResult.Completed(_), ExitResult.Failed(e, ts))  => fc(ExitResult.Failed(e, ts))
              case (ExitResult.Completed(_), ExitResult.Terminated(ts)) => fc(ExitResult.Terminated(ts))
            }
          }
        }

      private def combine(ts: List[Throwable], r: ExitResult[_, _]) = r match {
        case ExitResult.Failed(_, ts2)  => ts ++ ts2
        case ExitResult.Terminated(ts2) => ts ++ ts2
        case _                          => ts
      }
    }

  /**
   * Zips this fiber and the specified fiber togther, producing a tuple of their
   * output.
   */
  final def zip[E1 >: E, B](that: => Fiber[E1, B]): Fiber[E1, (A, B)] =
    zipWith(that)((a, b) => (a, b))

  /**
   * Same as `zip` but discards the output of the left hand side.
   */
  final def *>[E1 >: E, B](that: Fiber[E1, B]): Fiber[E1, B] =
    zip(that).map(_._2)

  /**
   * Same as `zip` but discards the output of the right hand side.
   */
  final def <*[E1 >: E, B](that: Fiber[E1, B]): Fiber[E1, A] =
    zip(that).map(_._1)

  /**
   * Maps over the value the Fiber computes.
   */
  final def map[B](f: A => B): Fiber[E, B] =
    new Fiber[E, B] {
      def join: IO[E, B] = self.join.map(f)

      def interrupt0(ts: List[Throwable]): IO[Nothing, Unit] = self.interrupt0(ts)

      def onComplete(fb: ExitResult[E, B] => IO[Nothing, Unit]): IO[Nothing, Unit] =
        self.onComplete { r: ExitResult[E, A] =>
          fb(r.map(f))
        }
    }
}

object Fiber {
  final def point[E, A](a: => A): Fiber[E, A] =
    new Fiber[E, A] {
      def join: IO[E, A]                                       = IO.point(a)
      def interrupt0(ts: List[Throwable]): IO[Nothing, Unit]   = IO.unit
      def onComplete(f: ExitResult[E, A] => IO[Nothing, Unit]) = f(ExitResult.Completed(a))
    }

  final def interruptAll(fs: Iterable[Fiber[_, _]]): IO[Nothing, Unit] =
    fs.foldLeft(IO.unit)((io, f) => io *> f.interrupt)

  final def joinAll(fs: Iterable[Fiber[_, _]]): IO[Nothing, Unit] =
    fs.foldLeft(IO.unit)((io, f) => io *> f.join.attempt.void)
}
