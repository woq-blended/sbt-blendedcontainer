import blended.sbt.feature._

lazy val feature1 = project.in(file("feature1"))
  .enablePlugins(BlendedFeaturePlugin)
  .settings(
    name := "blended.sbt.container.scripted.simple.feature1",
    version := "0.1",
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
    name := "blended.sbt.container.scripted.simple.feature2",
    version := "0.1",
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
    name := "blended.sbt.container.scripted.simple",
    version := "0.1",
    scalaVersion := "2.12.8",

    blendedVersion := "3.0-M5",
    materializeExtraFeatures := Seq(
      (feature1 / featureGenerate).value,
      (feature2 / featureGenerate).value
    )
  )

TaskKey[Unit]("verify") :=  {
  println("DUMMY TEST")
}

