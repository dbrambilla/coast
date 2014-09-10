package com.monovore.coast
package toy

import com.twitter.algebird.Semigroup

object Whatever {

  case class Node[A, B](sources: Map[String, Any => Seq[B]])

  def prepare(sys: System)(graph: sys.Graph[Unit]): Machine = {

    def compile[A,B](flow: sys.Flow[A,B]): Node[A, B] = flow match {
      case sys.Source(name) => Node(
        sources = Map(name -> { case b: B @unchecked => Seq(b) })
      )
      case sys.Transform(upstream, transform) => {
        val compiled = compile(upstream)

        val sources = compiled.sources
          .mapValues { _ andThen { _.flatMap { b => transform(b) } } }

        Node(sources)
      }
      case _ => ???
    }

    val nodes = graph.state
      .mapValues(new Mapper[sys.Flow, Node] {
        override def convert[A, B](in: sys.Flow[A, B]) = {
          compile(in)
        }
      })

    val initialState = nodes
      .mapValues(new Mapper[Node, Machine.State] {
        override def convert[A, B](in: Node[A, B]) = {
          Machine.State(input = in.sources.mapValues { _ => Map.empty })
        }
      })

    Machine(nodes, initialState)
  }
}

object Machine {

  type Grouped[A,B] = Map[A, Seq[B]]

  case class State[A, B](
    input: Map[String, Map[Any, Seq[Any]]],
    output: Map[A, Seq[B]] = Map.empty[A, Seq[B]].withDefaultValue(Seq.empty)
  ) {
    def source[A,B](name: Name[A,B]) = input(name.name).asInstanceOf[Map[A, Seq[B]]]
  }
}

case class Machine(graph: NameMap[Whatever.Node], state: NameMap[Machine.State]) {

  def push[A, B](from: Name[A, B], messages: (A,B)*): Machine = {

    val grouped = messages.groupByKey

    val withInput = state.keys.foldLeft(NameMap.empty[Machine.State]) { case (newState, name: Name[aT, bT]) =>

      val nodeState = state(name)

      val updated = nodeState.input
        .map {
          case (from.name, items) if from != name => {
            from.name -> Semigroup.plus(items, grouped.asInstanceOf[Map[Any,Seq[Any]]])
          }
          case other => other
        }

      newState.put(name, nodeState.copy(input = updated))
    }

    val withOutput = {
      val fromNode = withInput.apply(from)
      withInput.put(from, fromNode.copy(output = Semigroup.plus(fromNode.output, grouped).withDefaultValue(Seq.empty)))
    }

    copy(state = withOutput)
  }

  def process: Set[Process] = {

    val everything = state.keys.flatMap { case name: Name[aT, bT] =>

      val nodeState = state(name)

      val node: Whatever.Node[aT, bT] = graph(name)

      for {
        (source, pending) <- nodeState.input
        (key, values) <- pending
        if values.nonEmpty
      } yield {
        new Process {
          def next() = {

            val output: Seq[(aT, bT)] = node.sources(source)(values.head).map { key.asInstanceOf[aT] -> _ }

            val newPending = {
              val tail = values.tail
              if (tail.isEmpty) pending - key
              else pending + (key -> values.tail)
            }

            val newState =
              copy(state = state.put(name,
                nodeState.copy(input = nodeState.input + (source -> newPending))
              ))
              .push(name, output: _*)

            newState
          }
        }
      }
    }

    everything.toSet
  }
}

trait Process {
  def next(): Machine
}
