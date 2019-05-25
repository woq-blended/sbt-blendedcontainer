
import sbt._
import sbt.Keys._

object Dependencies {

  /** Attach these attributes to dependencies to sbt plugins */
  val sbtAttributes = Def.setting(Map(
    "e:scalaVersion" -> scalaBinaryVersion.value,
    "e:sbtVersion" -> "1.0"
  ))

  val sbtBlendedFeature = Def.setting("de.wayofquality.blended" % "sbt-blendedfeature" % "0.1-SNAPSHOT" withExtraAttributes sbtAttributes.value)

  val sbtFilterResources = Def.setting("de.wayofquality.sbt" % "sbt-filterresources" % "0.1.2" withExtraAttributes sbtAttributes.value)

  val sbtNativePackager = Def.setting("com.typesafe.sbt" % "sbt-native-packager" % "1.3.9" withExtraAttributes sbtAttributes.value)

}
