import blended.sbt.feature._

val testOrg = "temporary.scripted.blended.sbt.blendedcontainer.test"
val f1Name = "blended.sbt.container.scripted.simple.feature1"
val f2Name = "blended.sbt.container.scripted.simple.feature2"
val testVersion = "0.1-SNAPSHOT"

lazy val feature1 = project.in(file("feature1"))
  .enablePlugins(BlendedFeaturePlugin)
  .settings(
    organization := testOrg,
    name := f1Name,
    moduleName := f1Name,
    version := testVersion,
    featureConfig := Feature(
      name = "feature1",
      bundles = Seq(
        FeatureBundle("org.apache.felix" % "org.apache.felix.framework" % "5.6.0", startLevel = Some(0))
      )
    )
  )

lazy val feature2 = project.in(file("feature2"))
  .enablePlugins(BlendedFeaturePlugin)
  .settings(
    organization := testOrg,
    name := f2Name,
    version := testVersion,
    featureConfig := Feature(
      name = "feature2",
      featureRefs = Seq(
        FeatureRef("feature1")
      ),
      bundles = Seq(
        FeatureBundle("org.slf4j" % "slf4j-api" % "1.7.25")
      )
    )
  )


lazy val root = project.in(file("."))
  .enablePlugins(BlendedContainerPlugin)
  .aggregate(feature1, feature2)
  .settings(
    organization := testOrg,
    name := "blended.sbt.container.scripted.simple",
    version := testVersion,
    scalaVersion := "2.12.8",

    blendedVersion := "3.0-M5",
    materializeDebug := true,

    materializeFeatures := Seq(
      testOrg %% f1Name % testVersion
    ),
    // TODO: automatically fetch
    libraryDependencies += "org.apache.felix" % "org.apache.felix.framework" % "5.6.0",

    materializeExtraFeatures := Seq(
      (feature2 / featureGenerate).value
    )
  )

TaskKey[Unit]("verify") :=  {
  println("DUMMY TEST")
}

