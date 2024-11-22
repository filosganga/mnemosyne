val catsVersion = "2.10.0"
val catsEffectVersion = "3.5.2"
val slf4jVersion = "1.7.36"
val scalaJava8CompatVersion = "0.9.1"
val awsSdkVersion = "2.21.10"
val meteorVersion = "1.0.78"
val log4CatsVersion = "2.6.0"
val munitVersion = "1.0.0-M10"
val munitCatsEffectVersion = "1.0.7"
val logBackVersion = "1.4.4"
val log4j2Version = "2.24.2"

Global / onChangedBuildSource := ReloadOnSourceChanges

ThisBuild / githubWorkflowJavaVersions ++= List(
  JavaSpec.temurin("17"),
  JavaSpec.temurin("21")
)

ThisBuild / organization := "com.filippodeluca.mnemosyne"
ThisBuild / organizationHomepage := Some(url("http://filippodeluca.com"))
ThisBuild / licenses := Seq(("Apache-2.0", url("http://www.apache.org/licenses/LICENSE-2.0")))
ThisBuild / scalaVersion := "2.13.12"
// ThisBuild / crossScalaVersions ++= List("3.3.1")

ThisBuild / scmInfo := Some(
      ScmInfo(
        url("https://github.com/filosganga/mnemosyne"),
        "git@github.com:filosganga/mnemosyne.git"
      )
    )
ThisBuild /developers := List(
      Developer(
        "filosganga",
        "Filippo De Luca",
        "filippo.deluca@ovoenergy.com",
        url("https://github.com/filosganga")
      ),
      Developer(
        "SystemFw",
        "Fabio Labella",
        "fabio.labella@ovoenergy.com",
        url("https://github.com/SystemFw")
      )
    )

ThisBuild / semanticdbEnabled := true
ThisBuild / semanticdbVersion := scalafixSemanticdb.revision
ThisBuild / excludeDependencies ++= Seq(
  ExclusionRule("commons-logging", "commons-logging"),
  ExclusionRule("org.slf4j", "slf4j-log4j12"),
  ExclusionRule("log4j", "log4j"),
  ExclusionRule("org.apache.logging.log4j", "log4j-core"),
  ExclusionRule("org.apache.logging.log4j", "log4j-slf4j-impl")
)

ThisBuild / version ~= (_.replace('+', '-'))
ThisBuild / dynver ~= (_.replace('+', '-'))

val core = project
  .in(file("modules/core"))
  .enablePlugins(BuildInfoPlugin)
  .settings(
    name := "mnemosyne",
    scalafmtOnCompile := true,
    scalacOptions -= "-Xfatal-warnings", // enable all options from sbt-tpolecat except fatal warnings
    initialCommands := s"import com.filosganga.mnemosyne._",
    javacOptions ++= Seq("-Xlint:unchecked", "-Xlint:deprecation"),
    addCompilerPlugin("org.typelevel" %% "kind-projector" % "0.13.2" cross CrossVersion.full),
    buildInfoPackage := "com.filippodeluca.mnemosyne",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-core" % catsVersion,
      "org.typelevel" %% "cats-effect" % catsEffectVersion,
      "software.amazon.awssdk" % "dynamodb" % awsSdkVersion,
      "io.github.d2a4u" %% "meteor-awssdk" % meteorVersion,
      "org.typelevel" %% "log4cats-core" % log4CatsVersion,
      "org.typelevel" %% "log4cats-slf4j" % log4CatsVersion,
      "org.slf4j" % "slf4j-api" % slf4jVersion,
      "org.typelevel" %% "cats-effect-laws" % catsEffectVersion % Test,
      "org.typelevel" %% "munit-cats-effect-3" % munitCatsEffectVersion % Test,
      "org.scalameta" %% "munit-scalacheck" % munitVersion % Test,
      "ch.qos.logback" % "logback-classic" % logBackVersion % Test,
      "org.slf4j" % "slf4j-api" % slf4jVersion % Test,
      "org.slf4j" % "log4j-over-slf4j" % slf4jVersion % Test,
      "org.slf4j" % "jcl-over-slf4j" % slf4jVersion % Test,
      "org.slf4j" % "jul-to-slf4j" % slf4jVersion % Test,
      "org.apache.logging.log4j" % "log4j-to-slf4j" % log4j2Version % Test
    )
  )

val it = project
  .in(file("modules/it"))
  .settings(
    name := "mnemosyne-it",
    scalafmtOnCompile := true,
    scalacOptions -= "-Xfatal-warnings", // enable all options from sbt-tpolecat except fatal warnings
    initialCommands := s"import com.filosganga.mnemosyne._",
    javacOptions ++= Seq("-Xlint:unchecked", "-Xlint:deprecation"),
    addCompilerPlugin("org.typelevel" %% "kind-projector" % "0.13.2" cross CrossVersion.full),
    libraryDependencies ++= Seq(
      "org.typelevel" %% "munit-cats-effect-3" % munitCatsEffectVersion % Test,
      "org.scalameta" %% "munit-scalacheck" % munitVersion % Test,
      "ch.qos.logback" % "logback-classic" % logBackVersion % Test,
      "org.slf4j" % "slf4j-api" % slf4jVersion % Test,
      "org.slf4j" % "log4j-over-slf4j" % slf4jVersion % Test,
      "org.slf4j" % "jcl-over-slf4j" % slf4jVersion % Test,
      "org.slf4j" % "jul-to-slf4j" % slf4jVersion % Test,
      "org.apache.logging.log4j" % "log4j-to-slf4j" % log4j2Version % Test
    )
  )

val mnemosyne = project
  .in(file("."))
  .aggregate(core, it)
  