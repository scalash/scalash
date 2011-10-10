import sbt._
import Keys._
import sbt.Package._
import java.util.jar.Attributes.Name._

object ScalashBuild extends Build {
    lazy val scalash = Project(
        id          = "scalash",
        base        = file("."),
        settings    = standardSettings,
        aggregate   = Seq(core, sql, full)
    )

    lazy val core = Project(
        id          = "scalash-core",
        base        = file("core"),
        settings    = standardSettings ++ Seq(
            (sourceGenerators in Compile) <+= (sourceManaged in Compile) map {
                dir => Seq(Boilerplate.generateTupleW(dir))
            }
        )

    )

    lazy val sql = Project(
        id          = "scalash-sql",
        base        = file("sql"),
        settings    = standardSettings
    )


    lazy val full = {
        // The projects that are packaged in the full distribution.
        val projects = Seq(core, sql)

        // Some intermediate keys to simplify extracting a task or setting from `projects`.
        val allPackagedArtifacts = TaskKey[Seq[Map[Artifact, File]]]("all-packaged-artifacts")
        val allSources           = TaskKey[Seq[Seq[File]]]("all-sources")
        val allSourceDirectories = SettingKey[Seq[Seq[File]]]("all-source-directories")

        def artifactMappings(rootBaseDir: File, baseDir: File, scalaVersion: String, version: String,
                             fullDocDir: File, artifacts: Seq[Map[Artifact, File]]): Seq[(File, String)] = {
            val sxrDocDirectory = new File(fullDocDir.getAbsolutePath + ".sxr")

            // Include a root folder in the generated archive.
            val newBase = "scalash_%s-%s".format(scalaVersion, version)

            val jarsAndPomMappings = artifacts.flatMap(_.values) x flatRebase(newBase)
            val etcMappings        = ((rootBaseDir / "etc" ** "*") +++ Seq(rootBaseDir / "README")) x rebase(rootBaseDir, newBase)
            val fullDocMappings    = (fullDocDir ** "*") x rebase(fullDocDir.getParentFile, newBase)
            val sxrDocMappings     = (sxrDocDirectory ** "*") x rebase(sxrDocDirectory.getParentFile, newBase)
            jarsAndPomMappings ++ etcMappings ++ fullDocMappings ++ sxrDocMappings
          }

          /** Scalac options for SXR */
          def sxrOptions(baseDir: File, sourceDirs: Seq[Seq[File]]): Seq[String] = {
            val xplugin = "-Xplugin:" + (baseDir / "lib" / "sxr_2.9.0-0.2.7.jar").asFile.getAbsolutePath
            val baseDirs = sourceDirs.flatten
            val sxrBaseDir = "-P:sxr:base-directory:" + baseDirs.mkString(":")
            Seq(xplugin, sxrBaseDir)
          }

          Project(
            id           = "scalash-full",
            base         = file("full"),
            dependencies = Seq(core, sql),
            settings     = standardSettings ++ Seq(
                allSources           <<= projects.map(sources in Compile in _).join, // join: Seq[Task[A]] => Task[Seq[A]]
                allSourceDirectories <<= projects.map(sourceDirectories in Compile in _).join,
                allPackagedArtifacts <<= projects.map(packagedArtifacts in _).join,

                // Combine the sources of other modules to generate Scaladoc and SXR annotated sources
                (sources in Compile) <<= (allSources).map(_.flatten),

                // Avoid compiling the sources here; we just are after scaladoc.
                (compile in Compile) := inc.Analysis.Empty,

                // Include SXR in the Scaladoc Build to generated HTML annotated sources.
                (scaladocOptions in Compile) <++= (baseDirectory, allSourceDirectories, scalaVersion) map {
                  (bd, asd, sv) => if (sv.startsWith("2.10") || sv.startsWith("2.8")) Seq() else sxrOptions(bd, asd)
                },

                // Package an archive containing all artifacts, readme, licence, and documentation.
                // Use `LocalProject("scalash")` rather than `scalash` to avoid a circular reference.
                (mappings in packageBin in Compile) <<= (
                        baseDirectory in LocalProject("scalash"), baseDirectory, scalaVersion, version,
                        target in doc in Compile, allPackagedArtifacts) map artifactMappings
            )
        )
    }

/*    lazy val full = {
        // The projects that are packaged in the full distribution.
        val projects = Seq(core, sql)

        // Some intermediate keys to simplify extracting a task or setting from `projects`.
        val allPackagedArtifacts = TaskKey[Seq[Map[Artifact, File]]]("all-packaged-artifacts")
        val allSources           = TaskKey[Seq[Seq[File]]]("all-sources")
        val allSourceDirectories = SettingKey[Seq[Seq[File]]]("all-source-directories")

        // Include a root folder in the generated archive.
        val newBase = "scalash_%s-%s".format(scalaVersion, version)

        val jarsAndPomMappings = artifacts.flatMap(_.values) x flatRebase(newBase)

        jarsAndPomMappings
    }

    Project(
        id = "scalash-full",
        base = file("full"),
        dependencies = Seq(core, sql),
            settings = standardSettings ++ Seq(
                allSources <<= projects.map(sources in Compile in _).join, // join: Seq[Task[A]] => Task[Seq[A]]
                allSourceDirectories <<= projects.map(sourceDirectories in Compile in _).join,
                allPackagedArtifacts <<= projects.map(packagedArtifacts in _).join,

                // Combine the sources of other modules to generate Scaladoc and SXR annotated sources
                (sources in Compile) <<= (allSources).map(_.flatten),

                // Avoid compiling the sources here; we just are after scaladoc.
                (compile in Compile) := inc.Analysis.Empty,

                // Package an archive containing all artifacts, readme, licence, and documentation.
                // Use `LocalProject("scalash")` rather than `scalash` to avoid a circular reference.
                (mappings in packageBin in Compile) <<= (
                    baseDirectory in LocalProject("scalash"), baseDirectory, scalaVersion, version,
                    target in doc in Compile, allPackagedArtifacts) map artifactMappings
            )
    )*/

    object Dependency {
        // SBT's built in '%%' is not flexible enough. When we build with a snapshot version of the compiler,
        // we want to fetch dependencies from the last stable release (hopefully binary compatibility).
        def dependencyScalaVersion(currentScalaVersion: String): String = currentScalaVersion match {
            case "2.10.0-SNAPSHOT" => "2.9.0-1"
            case "2.9.1" => "2.9.0-1"
            case x => x
        }
    }

    val dependencyScalaVersionTranslator = SettingKey[(String => String)](
        "dependency-scala-version-translator",
        "Function to translate the current scala version to the version used for dependency resolution"
    )
    val dependencyScalaVersion = SettingKey[String](
        "dependency-scala-version",
        "The version of scala appended to module id of dependencies"
    )

    lazy val standardSettings = Defaults.defaultSettings ++ Seq(
        organization        := "org.scalash",
        version             := "0.0.1-SNAPSHOT",
        scalaVersion        := "2.9.1",
        crossScalaVersions  := Seq("2.9.1", "2.9.0-1", "2.8.1"),
        resolvers           += ScalaToolsSnapshots,

        dependencyScalaVersionTranslator := (Dependency.dependencyScalaVersion _),
        dependencyScalaVersion           <<= (dependencyScalaVersionTranslator, scalaVersion)((t, sv) => t(sv)),
        //publishSetting,

        // TODO remove after deprecating Scala 2.9.0.1
        (unmanagedClasspath in Compile) += Attributed.blank(file("dummy")),

        scalacOptions  ++= Seq("-encoding", "UTF-8"),
        packageOptions ++= Seq[PackageOption](
            ManifestAttributes(
                (IMPLEMENTATION_TITLE,  "Scalash"),
                (IMPLEMENTATION_URL,    "https://github.com/scalash/scalash"),
                (IMPLEMENTATION_VENDOR, "The Scalash Project"),
                (SEALED, "true"))
        )
    )
}

