package com.monovore.coast

case class Name[A, B](name: String)

/**
 * A mechanism for maintaining name bindings.
 * @param bindings
 * @param value
 * @tparam A
 */
case class Flow[A](bindings: Seq[String -> Element[_, _]], value: A) {

  def map[B](func: A => B): Flow[B] = copy(value = func(value))

  def flatMap[B](func: A => Flow[B]): Flow[B] = {

    val result = func(value)

    val duplicateName = bindings.exists { case (name, _) =>
      result.bindings.exists { case (other, _) => name == other }
    }

    if (duplicateName) throw new IllegalArgumentException("Reused name binding!")

    Flow(bindings ++ result.bindings, result.value)
  }
}

object Flow {

  def merge[A, B](upstreams: Stream[A, B]*): Stream[A, B] =
    new Stream[A, B](Merge(upstreams.map { _.element }))

  def source[A,B](name: Name[A,B]): Stream[A, B] = new Stream[A, B](Source(name.name))

  sealed trait Labellable[A] { def label(name: String, value: A): Flow[A] }

  implicit def labelStreams[A, B]: Labellable[Stream[A, B]] = new Labellable[Stream[A, B]] {
    override def label(name: String, value: Stream[A, B]): Flow[Stream[A, B]] = {
      Flow(Seq(name -> value.element), new Stream[A, B](Source(name)))
    }
  }

  implicit def labelPools[A, B]: Labellable[Pool[A, B]] = new Labellable[Pool[A, B]] {
    override def label(name: String, value: Pool[A, B]): Flow[Pool[A, B]] = {
      Flow(Seq(name -> value.element), new Pool[A, B](value.initial, Source(name)))
    }
  }

  def label[A](name: String)(value: A)(implicit lbl: Labellable[A]): Flow[A] =
    lbl.label(name, value)

  def sink[A, B](name: Name[A, B])(flow: Stream[A, B]): Flow[Unit] = {
    Flow(Seq(name.name -> flow.element), ())
  }
}
