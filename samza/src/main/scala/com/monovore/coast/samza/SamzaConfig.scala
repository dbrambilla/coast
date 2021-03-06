package com.monovore.coast.samza

import com.monovore.coast.samza.ConfigGenerator.Storage
import org.apache.samza.config.{TaskConfig, Config, MapConfig}

import collection.JavaConverters._
import reflect.ClassTag

object SamzaConfig {

  val TaskKey = "coast.task.serialized.base64"
  val TaskName = "coast.task.name"
  val RegroupedStreams = "coast.streams.regrouped"

  def from(pairs: (String, String)*) = new MapConfig(pairs.toMap.asJava)

  def format(config: Config): String = {

    config.entrySet().asScala.toSeq
      .sortBy { _.getKey }
      .map { pair =>  s"  ${pair.getKey} -> [${pair.getValue}]"  }
      .mkString("\n")
  }

  /**
   * Samza often wants to be configured with specific class names; this helps
   * to ensure that those names match actual classes.
   */
  def className[A](implicit tag: ClassTag[A]): String = tag.runtimeClass.getName

  case class Base(base: Config) {

    val system = base.get("coast.system.name", "kafka")

    val windowMs = base.get(TaskConfig.WINDOW_MS, "5000")

    def merge(name: String) = {
      val prefix = base.get("coast.prefix.merge", "coast.mg")
      s"$prefix.$name"
    }

    def checkpoint(name: String) = {
      val prefix = base.get("coast.prefix.checkpoint", "coast.cp")
      s"$prefix.$name"
    }

    def changelog(name: String) = {
      val changelogPrefix = base.get("coast.prefix.changelog", "coast.cl")
      s"$changelogPrefix.$name"
    }

    def storageConfig(storage: Storage) = {

      import storage._

      val basic = Map(
        s"stores.$name.factory" -> className[org.apache.samza.storage.kv.inmemory.InMemoryKeyValueStorageEngineFactory[_,_]]
      )

      val defaults = base.subset("coast.default.stores.").asScala.toMap
        .map { case (key, value) => s"stores.$name.$key" -> value }

      val preconfigured = base.subset(s"stores.$name", false).asScala.toMap

      val keyName = s"coast.key.$name"
      val msgName = s"coast.msg.$name"

      basic ++ defaults ++ preconfigured ++ Map(
        s"stores.$name.key.serde" -> keyName,
        s"stores.$name.msg.serde" -> msgName,
        s"stores.$name.changelog" -> s"$system.${changelog(name)}",
        s"serializers.registry.$keyName.class" -> className[CoastSerdeFactory[_]],
        s"serializers.registry.$keyName.serialized.base64" -> SerializationUtil.toBase64(keyString),
        s"serializers.registry.$msgName.class" -> className[CoastSerdeFactory[_]],
        s"serializers.registry.$msgName.serialized.base64" -> SerializationUtil.toBase64(valueString)
      )
    }
  }
}
