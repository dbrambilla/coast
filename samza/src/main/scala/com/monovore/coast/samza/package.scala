package com.monovore.coast

import com.google.common.primitives.{Ints, Longs}
import com.monovore.coast.format.WireFormat
import com.monovore.coast.model._
import org.apache.samza.config.{Config, MapConfig}

import scala.collection.JavaConverters._

package object samza {

  val TaskKey = "coast.task.serialized.base64"
  val TaskName = "coast.task.name"

  def formatPath(path: List[String]): String = {
    if (path.isEmpty) "."
    else path.reverse.mkString(".")
  }

  private[this] def sourcesFor[A, B](element: Node[A, B]): Set[String] = element match {
    case Source(name) => Set(name)
    case Transform(up, _, _) => sourcesFor(up)
    case Merge(ups) => ups.flatMap(sourcesFor).toSet
    case GroupBy(up, _) => sourcesFor(up)
  }

  val longPairFormat = new WireFormat[(Long, Long)] {
    override def write(value: (Long, Long)): Array[Byte] = {
      Longs.toByteArray(value._1) ++ Longs.toByteArray(value._2)
    }
    override def read(bytes: Array[Byte]): (Long, Long) = {
      Longs.fromByteArray(bytes.take(8)) -> Longs.fromByteArray(bytes.drop(8))
    }
  }

  case class Storage(name: String, keyString: String, valueString: String)

  private[this] def storageFor[A, B](element: Node[A, B], path: List[String]): Seq[Storage] = element match {
    case Source(_) => Seq(Storage(
      name = formatPath(path),
      keyString = SerializationUtil.toBase64(format.pretty.UnitFormat),
      valueString = SerializationUtil.toBase64(format.pretty.LongFormat)
    ))
    case PureTransform(up, _) => storageFor(up, path)
    case Merge(ups) => {
      ups.zipWithIndex
        .flatMap { case (up, i) => storageFor(up, s"merge-$i" :: path)}
    }
    case agg @ Aggregate(up, _, _) => {
      val upstreamed = storageFor(up, "aggregated" :: path)
      upstreamed :+ Storage(
        name = formatPath(path),
        keyString = SerializationUtil.toBase64(agg.keyFormat),
        valueString = SerializationUtil.toBase64(agg.stateFormat)
      )
    }
    case GroupBy(up, _) => storageFor(up, path)
  }

  def configureFlow(flow: Flow[_])(
    system: String = "kafka",
    baseConfig: Config = new MapConfig()
  ): Map[String, Config] = {

    val baseConfigMap = baseConfig.asScala.toMap

    val configs = flow.bindings.map { case (name -> sink) =>

      val inputs = sourcesFor(sink.element)

      val storage = storageFor(sink.element, List(name))

      val factory: MessageSink.Factory = new MessageSink.FromElement(sink)

      val configMap = Map(

        // Job
        "job.name" -> name,

        // Task
        "task.class" -> "com.monovore.coast.samza.CoastTask",
        "task.inputs" -> inputs.map { i => s"$system.$i"}.mkString(","),

        "serializers.registry.string.class" -> "org.apache.samza.serializers.StringSerdeFactory",
        "serializers.registry.bytes.class" -> "org.apache.samza.serializers.ByteSerdeFactory",

        // TODO: checkpoints should be configurable
        "task.checkpoint.factory" -> "org.apache.samza.checkpoint.kafka.KafkaCheckpointManagerFactory",
        "task.checkpoint.system" -> system,

        // Coast-specific
        TaskKey -> SerializationUtil.toBase64(factory),
        TaskName -> name
      )

//      val offsetStorage = Storage(
//        s"offsets",
//        SerializationUtil.toBase64(format.pretty.UnitFormat),
//        SerializationUtil.toBase64(format.pretty.UnitFormat)
//      )

      val storageMap = storage
        .map { case Storage(name, keyFormat, msgFormat) =>

          val keyName = s"coast-key-$name"
          val msgName = s"coast-msg-$name"

          Map(
            s"stores.$name.factory" -> "com.monovore.coast.samza.CoastStoreFactory",
            s"stores.$name.subfactory" -> "org.apache.samza.storage.kv.inmemory.InMemoryKeyValueStorageEngineFactory",
            s"stores.$name.key.serde" -> keyName,
            s"stores.$name.msg.serde" -> msgName,
            s"stores.$name.changelog" -> s"$system.coast-cl-$name",
            s"serializers.registry.$keyName.class" -> "com.monovore.coast.samza.CoastSerdeFactory",
            s"serializers.registry.$keyName.serialized.base64" -> keyFormat,
            s"serializers.registry.$msgName.class" -> "com.monovore.coast.samza.CoastSerdeFactory",
            s"serializers.registry.$msgName.serialized.base64" -> msgFormat
          )
        }
        .flatten.toMap

      name -> new MapConfig(
        (baseConfigMap ++ configMap ++ storageMap).asJava
      )
    }

    configs.toMap
  }

  def config(pairs: (String -> String)*): Config = new MapConfig(pairs.toMap.asJava)
}
