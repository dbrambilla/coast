package com.monovore.coast

import scala.language.higherKinds

sealed trait Context[K, X[+_]] {
  def unwrap[A](wrapped: X[A]): (K => A)
  def map[A, B](wrapped: X[A])(function: A => B): X[B]
}

class NoContext[K] extends Context[K, Id] {
  override def unwrap[A](wrapped: Id[A]): (K) => A = { _ => wrapped }
  override def map[A, B](wrapped: Id[A])(function: (A) => B): Id[B] = function(wrapped)
}

class FnContext[K] extends Context[K, From[K]#To] {
  override def unwrap[A](wrapped: From[K]#To[A]): (K) => A = wrapped
  override def map[A, B](wrapped: From[K]#To[A])(function: (A) => B): From[K]#To[B] = wrapped andThen function
}

class AnyGrouping
class Grouped extends AnyGrouping

class StreamBuilder[WithKey[+_], +G <: AnyGrouping, A, +B](
  private[coast] val context: Context[A, WithKey],
  private[coast] val element: Element[A, B]
) { self =>

  def stream: StreamDef[G, A, B] = new StreamDef(element)

  def flatMap[B0](func: WithKey[B => Seq[B0]]): StreamDef[G, A, B0] = new StreamDef(
    PureTransform[A, B, B0](self.element, {
      context.unwrap(func) andThen { unkeyed =>
        (b: B) => unkeyed(b) }
    })
  )

  def filter(func: WithKey[B => Boolean]): StreamDef[G, A, B] = flatMap {
    context.map(func) { func =>
      { a => if (func(a)) Seq(a) else Seq.empty }
    }
  }

  def map[B0](func: WithKey[B => B0]): StreamDef[G, A, B0] =
    flatMap(context.map(func) { func => func andThen { b => Seq(b)} })

  def transform[S, B0](init: S)(func: WithKey[(S, B) => (S, Seq[B0])])(implicit isGrouped: G <:< Grouped): Stream[A, B0] = {

    val keyedFunc = context.unwrap(func)

    new StreamDef(Transform[S, A, B, B0](self.element, init, keyedFunc))
  }

  def fold[B0](init: B0)(func: WithKey[(B0, B) => B0])(implicit isGrouped: G <:< Grouped): Pool[A, B0] = {

    val transformer = context.map(func) { fn =>

      (s: B0, b: B) => {
        val b0 = fn(s, b)
        b0 -> Seq(b0)
      }
    }

    new PoolDef(init, Transform(self.element, init, context.unwrap(transformer)))
  }

  def latestOr[B0 >: B](init: B0): PoolDef[G, A, B0] =
    new PoolDef(init, element)

  def groupBy[A0](func: B => A0): StreamDef[AnyGrouping, A0, B] =
    new StreamDef[G, A0, B](GroupBy(self.element, func))

  def groupByKey[A0, B0](implicit asPair: B <:< (A0, B0)) =
    stream.groupBy { _._1 }.map { _._2 }

  def flatten[B0](implicit func: B => Traversable[B0]) = stream.flatMap(func andThen { _.toSeq })

  def join[B0](pool: Pool[A, B0])(implicit isGrouped: G <:< Grouped): Stream[A, B -> B0] = {

    Flow.merge(pool.stream.map(Left(_)), new StreamDef(element).map(Right(_)))
      .transform(pool.initial) { (state: B0, msg: Either[B0, B]) =>
        msg match {
          case Left(newState) => newState -> Seq.empty
          case Right(msg) => state -> Seq(msg -> state)
        }
      }
  }
}

class StreamDef[+G <: AnyGrouping, A, +B](element: Element[A, B]) extends StreamBuilder[Id, G, A, B](new NoContext[A], element) {

  def withKeys: StreamBuilder[From[A]#To, G, A, B] =
    new StreamBuilder[From[A]#To, G, A, B](new FnContext[A], element)
}

class PoolDef[+G <: AnyGrouping, A, +B](
  private[coast] val initial: B,
  private[coast] val element: Element[A, B]
) { self =>

  def stream: StreamDef[G, A, B] = new StreamDef(element)

  def map[B0](function: B => B0): PoolDef[G, A, B0] =
    stream.map(function).latestOr(function(initial))

  def join[B0](other: Pool[A, B0])(implicit isGrouped: G <:< Grouped): PoolDef[Grouped, A, (B, B0)] = {

    val grouped: PoolDef[Grouped, A, B] = new PoolDef(initial, element)

    val merged = Flow.merge(grouped.stream.map(Left(_)), other.stream.map(Right(_)))

    merged
      .fold(initial, other.initial) { (state, update) =>
        update.fold(
          { left => (left, state._2) },
          { right => (state._1, right) }
        )
      }
  }
}
