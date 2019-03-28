package blended.sbt.container

import java.io.File
import java.net.URLClassLoader

import scala.util.Try

import blended.sbt.feature.Feature
import com.typesafe.config.ConfigFactory
import com.typesafe.sbt.packager.MappingsHelper
import com.typesafe.sbt.packager.universal.{Archives, UniversalDeployPlugin, UniversalPlugin}
import sbt._
import sbt.Keys._
import sbt.librarymanagement.{Constant, ModuleID, UnresolvedWarning, UnresolvedWarningConfiguration, UpdateConfiguration}
import de.wayofquality.sbt.filterresources.FilterResources
import de.wayofquality.sbt.filterresources.FilterResources.autoImport._

object BlendedContainerPlugin extends AutoPlugin {

  object autoImport {
    val profileName = settingKey[String]("The profile name")
    val blendedVersion = settingKey[String]("Blended version")

    val blendedLauncher = settingKey[ModuleID]("Blended Launcher")
    val unpackLauncherZip = taskKey[File]("Unpack the launcher ZIP")

    val materializeDebug = settingKey[Boolean]("Enable debug mode")
    val materializeSourceProfile = settingKey[File]("Source profile")
    val materializeTargetDir = settingKey[File]("Target directory")
    val materializeToolsDeps = settingKey[Seq[ModuleID]]("Dependencies needed as tools classpath for the RuntimeConfigBuilder / Materializer")
    val materializeExplodeResources = settingKey[Boolean]("Should resources already be exploded")
    val materializeLaunchConf = settingKey[Option[File]]("The name of the optional created launch.conf file")

    val materializeProfile = taskKey[Unit]("Materialize the profile")
    val materializeExtraDeps = taskKey[Seq[(ModuleID, File)]]("Extra dependencies, which can't be expressed as libraryDependencies, e.g. other sub-projects for resources")
    val materializeFeatures = taskKey[Seq[ModuleID]]("Dependencies denoting feature configuration files")
    val materializeFetchFeatureDeps = taskKey[Seq[(ModuleID, File, Seq[ModuleID])]]("Dependencies denoting features, which will be fetched and examined to generate a dependencies list")
    val materializeExtraFeatures = taskKey[Seq[(Feature, File)]]("Extra dependencies representing feature conf files, which can't be expressed as libraryDependencies, e.g. other sub-projects for resources")
    val materializeToolsCp = taskKey[Seq[File]]("Tools Classpath for the RuntimeConfigBuilder / Materializer")

    val materializeOverlays = taskKey[Seq[(ModuleID, File)]]("Additional overlays that should be applied to the materialized profile")

    val packageFullNoJreMapping = taskKey[Seq[(File, String)]]("Mapping for product package without a JRE")

    val packageFullNoJreZipArtifact = settingKey[Artifact]("Artifact")
    val packageFullNoJreZip = taskKey[File]("Create a product package without a JRE")
    val packageFullNoJreTarGzArtifact = settingKey[Artifact]("Artifact")
    val packageFullNoJreTarGz = taskKey[File]("Create a product package without a JRE")

    val packageDeploymentPackArtifact = settingKey[Artifact]("Artifact")
    val packageDeploymentPack = taskKey[File]("Create deployment pack")

  }

  import autoImport._

  override def requires = super.requires &&
    FilterResources &&
    UniversalPlugin &&
    UniversalDeployPlugin

  override def projectSettings: Seq[Def.Setting[_]] = super.projectSettings ++ Seq(
    profileName := s"${name.value}_${scalaBinaryVersion.value}",

    // Compile / packageBin / publishArtifact := false,
    Compile / packageDoc / publishArtifact := false,
    Compile / packageSrc / publishArtifact := false,

    // TODO: Leave filtering to project
    Compile / filterSources := Seq(baseDirectory.value / "src" / "main" / "resources"),
    Compile / filterTargetDir := target.value / "filteredResources",
    Compile / filterProperties := Map(
      "profile.version" -> version.value,
      "profile.name" -> profileName.value,
      "blended.version" -> blendedVersion.value
    ),
    Compile / filterResourcesFailOnMissingMatch := false,

    blendedLauncher := "de.wayofquality.blended" %% "blended.launcher" % blendedVersion.value,

    unpackLauncherZip := {
      val log = streams.value.log

      val launcher = blendedLauncher.value.artifacts(Artifact(name = blendedLauncher.value.name, `type` = "zip", extension = "zip"))
      println("launcher: " + launcher + ", explicit artifacts: " + launcher.explicitArtifacts)

      val depRes = (Compile / dependencyResolution).value
      val files = depRes.retrieve(
        launcher.intransitive(), // .withExclusions(Vector(InclExclRule())),
        scalaModuleInfo.value,
        target.value / "dependencies",
        log
      ).toOption.get.distinct
      println("resolved: " + files)

      val destDir = target.value / "launcher"
      files.foreach { f =>
        IO.unzip(f, destDir)
      }

      destDir
    },

    materializeDebug := false,

    materializeTargetDir := target.value / "profile",

    materializeLaunchConf := Some(target.value / "container" / "launch.conf"),

    materializeSourceProfile := (Compile / filterTargetDir).value / "profile" / "profile.conf",

    materializeFeatures := Seq(),

    materializeExtraDeps := Seq(),

    materializeExtraFeatures := Seq(),

    materializeToolsDeps := Seq(
      "de.wayofquality.blended" %% "blended.updater.tools" % blendedVersion.value
    ),

    materializeToolsCp := {
      val log = streams.value.log
      val depRes = (Compile / dependencyResolution).value

      materializeToolsDeps.value.flatMap { dep =>

        val resolved: Either[UnresolvedWarning, UpdateReport] =
          depRes.update(
            depRes.wrapDependencyInModule(dep, scalaModuleInfo.value),
            UpdateConfiguration(),
            UnresolvedWarningConfiguration(),
            log
          )

        val files = resolved match {
          case Right(report) =>
            val files: Seq[(ConfigRef, ModuleID, Artifact, File)] = report.toSeq
            files.map(_._4)
          case Left(w) => throw w.resolveException
        }

        files
      }
    },

    materializeExplodeResources := true,

    materializeProfile := {
      val log = streams.value.log

      // depend on filtered resources
      (Compile / filterResources).value

      log.debug("Running Mojo materialize-profile")

      val targetProfile = materializeTargetDir.value / "profile.conf"

      val srcProfile = materializeSourceProfile.value

      val depRes = (Compile / dependencyResolution).value

      def resolveDepToToolArg[T](dep: ModuleID)(gavAndFileAction: (String, File) => Seq[T]): Seq[T] = {
        val gav = moduleIdToGav(dep)
        val updateConfiguration = UpdateConfiguration()
        val unresolvedWarningConfiguration = UnresolvedWarningConfiguration()

        val resolved: Either[UnresolvedWarning, UpdateReport] = depRes.update(
          depRes.wrapDependencyInModule(dep.intransitive(), scalaModuleInfo.value),
          updateConfiguration,
          unresolvedWarningConfiguration,
          log
        )
        val files = resolved match {
          case Right(report) =>
            val files: Seq[(ConfigRef, ModuleID, Artifact, File)] = report.toSeq
            files.map(_._4)
          case Left(w) => throw w.resolveException
        }
        files.headOption match {
          case Some(file) =>
            log.debug(s"Associated file with dependency: ${dep} => ${gav} --> ${file.getAbsolutePath()}")
            if (!file.exists()) {
              log.error(s"Resolved file does not exist: ${file}")
            }
            gavAndFileAction(gav, file)
          case None =>
            log.warn(s"No file associated with dependency: ${dep} => ${gav}")
            Seq()
        }
      }

      // Features, which are resolved from a repository
      val featureDeps = materializeFeatures.value
      log.debug(s"Feature dependencies: ${featureDeps}")

      val featureGavs = featureDeps.map(d =>
        d.withExplicitArtifacts(Vector(Artifact(
          name = d.name,
          `type` = "conf",
          extension = "conf"
        ))))

      val featureArgs = featureGavs.flatMap { dep =>
        resolveDepToToolArg(dep)((gav, file) => Seq("--feature-repo", file.getAbsolutePath()))
      }

      val featureFiles = featureGavs.flatMap { dep =>
        resolveDepToToolArg(dep)((gav, file) => Seq(file.getAbsoluteFile()))
      }

      import scala.collection.JavaConverters._

      val examinedFeatureDeps: Seq[ModuleID] = featureFiles.flatMap { file =>
        Try {
          val urls = ConfigFactory.parseFile(file).getConfigList("bundles").asScala.map(o => o.getString("url"))
          urls.flatMap { url =>
            if (url.startsWith("mvn:")) {
              val gav = url.split("[:]")
              Seq(gav(1) % gav(2) % gav(3))
            } else {
              Seq()
            }
          }
        }.getOrElse(Seq())
      }
      log.debug(s"Examined feature dependencies: ${examinedFeatureDeps}")

      // TODO: also examine resolved features and fetch their declared bundles

      // We need to declare all bundles as libraryDependencies!
      // but we also support all referenced bundles from features
      val libDeps: Seq[ModuleID] =
        (Compile / libraryDependencies).value ++ examinedFeatureDeps ++
          materializeExtraFeatures.value.flatMap {
            case (feature, file) =>
              feature.libDeps
          }

      val artifactArgs = libDeps.flatMap { dep =>
        resolveDepToToolArg(dep)((gav, file) => Seq("--maven-artifact", gav, file.getAbsolutePath()))
      }
      log.debug(s"artifact args: ${artifactArgs}")

      val extraArtifactArgs = materializeExtraDeps.value.flatMap {
        case (moduleId, file) =>
          log.debug(s"Extra dep: artifact ${file}")
          Seq("--maven-artifact", moduleIdToGav(moduleId), file.getAbsolutePath())
      }
      log.debug(s"extra artifact args: ${extraArtifactArgs}")

      val extraFeatureArgs = materializeExtraFeatures.value.flatMap {
        case (feature, file) =>
          log.debug(s"Extra dep: feature repo ${file}")
          Seq("--feature-repo", file.getAbsolutePath())
      }
      log.debug(s"extra feature args: ${extraFeatureArgs}")

      val explodeResourcesArgs = if (materializeExplodeResources.value) Seq("--explode-resources") else Nil

      //          val overlayArgs =
      //          // prepend base dir if set
      //            Option(overlays).getOrElse(ju.Collections.emptyList()).asScala.map { o =>
      //              Option(overlaysDir) match {
      //                case None => o
      //                case _ if o.isAbsolute() => o
      //                case Some(f) => new File(f, o.getPath())
      //              }
      //            }.
      //              // create args
      //              flatMap(o => Seq("--add-overlay-file", o.getAbsolutePath())).toArray
      //

      val launchConfArgs = materializeLaunchConf.value.toList.flatMap { cf =>
        Option(cf.getParentFile()).foreach(_.mkdirs())
        Seq("--create-launch-config", cf.getAbsolutePath())
      }

      val debugArgs = if (materializeDebug.value) Seq("--debug") else Nil

      val profileArgs = Seq(
        Seq("-f", srcProfile.getAbsolutePath(),
          "-o", targetProfile.getAbsolutePath(),
          "--download-missing",
          "--update-checksums",
          "--write-overlays-config"),
        debugArgs,
        featureArgs,
        artifactArgs,
        extraArtifactArgs,
        extraFeatureArgs,
        explodeResourcesArgs,
        launchConfArgs
      ).flatten

      //  ++ overlayArgs

      log.debug("About to run RuntimeConfigBuilder.run with args: " + profileArgs.mkString("\n    "))

      // We use a separate Classloader with NULL as parent
      // to avoid incompatible classes (e.g. with incompatible older versions of typesafe config in sbt)
      val cl = new URLClassLoader(materializeToolsCp.value.map(_.toURI().toURL()).toArray, null)

      val builder = cl.loadClass("blended.updater.tools.configbuilder.RuntimeConfigBuilder")
      val runMethod = builder.getMethod("run", Seq(classOf[Array[String]]): _*)

      runMethod.invoke(null, profileArgs.toArray)

      // We can't call the tool directly, as some sbt deps (typesafe-config) collide
      //            RuntimeConfigBuilder.run(
      //              args = profileArgs.toArray,
      //              debugLog = Some(msg => log.debug(msg)),
      //              infoLog = msg => log.info(msg),
      //              errorLog = msg => log.error(msg)
      //            )

    },

    packageFullNoJreMapping := {
      val log = streams.value.log

      // trigger
      (Compile / filterResources).value
      materializeProfile.value

      val launcherDir = unpackLauncherZip.value
      val containerResources = (Compile / filterTargetDir).value / "container"
      val profileDir = materializeTargetDir.value

      PathFinder(launcherDir).allPaths.pair(MappingsHelper.relativeTo(launcherDir)).
        // We provide that ourselves
        filter(_._2 != "etc/logback.xml") ++
        PathFinder(containerResources).allPaths.pair(MappingsHelper.relativeTo(containerResources)) ++
        PathFinder(profileDir).allPaths.pair(
          f => IO.relativize(profileDir, f).map(p => s"profiles/${profileName.value}/${version.value}/${p}")
        ) ++
          Seq(profileDir -> s"profiles/${profileName.value}/${version.value}") ++
          materializeLaunchConf.value.toList.map(f => f -> f.getName())
    },

    packageFullNoJreZip := {
      validateMapping(packageFullNoJreMapping.value, streams.value.log)

      val outputName = s"${profileName.value}-${version.value}-full-nojre"
      Archives.makeZip(
        target = target.value,
        name = outputName,
        mappings = packageFullNoJreMapping.value,
        top = Some(s"${profileName.value}-${version.value}"),
        options = Nil
      )
    },

    packageFullNoJreTarGz := {
      validateMapping(packageFullNoJreMapping.value, streams.value.log)

      val outputName = s"${profileName.value}-${version.value}-full-nojre"
      Archives.makeTarball(Archives.gzip, ".tar.gz")(
        target = target.value,
        name = outputName,
        mappings = packageFullNoJreMapping.value,
        top = Some(s"${profileName.value}-${version.value}")
      )
    },

    //        Compile / packageBin := packageFullNoJreTarGz.value,

    packageDeploymentPack := {
      // trigger
      materializeProfile.value

      val outputName = s"${profileName.value}-${version.value}-deploymentpack"
      val profileDir = materializeTargetDir.value
      val mapping =
        Seq(profileDir / "profile.conf" -> "profile.conf") ++
          PathFinder(profileDir / "bundles").allPaths.pair(MappingsHelper.relativeTo(profileDir)) ++
          PathFinder(profileDir / "resources").allPaths.---(PathFinder(profileDir / "resources").glob(".*")).pair(MappingsHelper.relativeTo(profileDir))

      validateMapping(mapping, streams.value.log)

      IO.delete(target.value / s"${outputName}.zip")

      Archives.makeZip(
        target = target.value,
        name = outputName,
        mappings = mapping,
        top = None,
        options = Nil
      )
    },

    packageDeploymentPackArtifact := Artifact(
      name = moduleName.value,
      `type` = "zip",
      extension = "zip",
      classifier = "deploymentpack"
    ),

    packageFullNoJreZipArtifact := Artifact(
      name = moduleName.value,
      `type` = "zip",
      extension = "zip",
      classifier = "full-nojre"
    ),

    packageFullNoJreTarGzArtifact := Artifact(
      name = moduleName.value,
      `type` = "tar.gz",
      extension = "tar.gz",
      classifier = "full-nojre"
    ),

    artifacts ++= Seq(
      packageDeploymentPackArtifact.value,
      packageFullNoJreTarGzArtifact.value,
      packageFullNoJreZipArtifact.value
    ),

    packagedArtifacts := {
      packagedArtifacts.value ++ Map(
        packageDeploymentPackArtifact.value -> packageDeploymentPack.value,
        packageFullNoJreZipArtifact.value -> packageFullNoJreZip.value,
        packageFullNoJreTarGzArtifact.value -> packageFullNoJreTarGz.value
      )
    }

  )

  def moduleIdToGav(dep: ModuleID): String = {
    val optExt = dep.explicitArtifacts.headOption.map { a => a.extension }
    val optClassifier = dep.explicitArtifacts.headOption.flatMap(a => a.classifier).filter(_ != "jar")

    s"${dep.organization}:${dep.name}${artifactNameSuffix(dep)}:${optClassifier.getOrElse("")}:${dep.revision}:${optExt.getOrElse("jar")}"
  }

  def validateMapping(mapping: Seq[(File, String)], log: Logger): Unit = {
    // security measure
    var entries = Set[String]()
    mapping.foreach {
      case (k, v) =>
        if (k.isFile()) {
          if (entries.contains(v)) {
            log.warn(s"The resulting mapping will contain colliding entry [${v}] from: [${mapping.filter(_._2 == v).map(_._1)}]")
          } else {
            entries += v
          }
        }
    }
  }

  /**
   *
   * @param moduleID
   * @param scalaBinVersion We hard-code the default, to avoid to make this def a sbt setting.
   * @return
   */
  def artifactNameSuffix(moduleID: ModuleID, scalaBinVersion: String = "2.12"): String = moduleID.crossVersion match {
    case b: Binary => s"_${b.prefix}${scalaBinVersion}${b.suffix}"
    case c: Constant => s"_${c.value}"
    case _ => ""
  }

}
