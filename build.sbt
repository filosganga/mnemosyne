val catsVersion = "2.12.0"
val catsEffectVersion = "3.5.7"
val slf4jVersion = "2.0.16"
val scalaJava8CompatVersion = "1.0.2"
val awsSdkVersion = "2.29.43"
val log4CatsVersion = "2.7.0"
val munitVersion = "1.0.3"
val munitScalacheckVersion = "1.0.0"
val munitCatsEffectVersion = "2.0.0"
val logBackVersion = "1.5.15"
val log4j2Version = "2.24.3"

Global / onChangedBuildSource := ReloadOnSourceChanges

ThisBuild / semanticdbEnabled := true
ThisBuild / semanticdbVersion := scalafixSemanticdb.revision
ThisBuild / scalaVersion := "3.6.2"
ThisBuild / crossScalaVersions ++= Seq("3.3.4", "2.13.15")
ThisBuild / scalacOptions ++= {
  if (scalaVersion.value.startsWith("2.13")) {
    Seq("-Xsource:3")
  } else {
    Seq.empty
  }
}
ThisBuild / versionScheme := Some("early-semver")
ThisBuild / licenses := Seq(("Apache-2.0", url("http://www.apache.org/licenses/LICENSE-2.0")))
ThisBuild / organization := "com.filippodeluca"
ThisBuild / organizationHomepage := Some(url("http://filippodeluca.com"))
ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/filosganga/mnemosyne"),
    "git@github.com:filosganga/mnemosyne.git"
  )
)
ThisBuild / developers := List(
  Developer(
    "filosganga",
    "Filippo De Luca",
    "me@filippodeluca.com",
    url("https://github.com/filosganga")
  ),
  Developer(
    "SystemFw",
    "Fabio Labella",
    "fabio.labella@xyz.com",
    url("https://github.com/SystemFw")
  )
)

ThisBuild / excludeDependencies ++= Seq(
  ExclusionRule("commons-logging", "commons-logging"),
  ExclusionRule("org.slf4j", "slf4j-log4j12"),
  ExclusionRule("log4j", "log4j"),
  ExclusionRule("org.apache.logging.log4j", "log4j-core"),
  ExclusionRule("org.apache.logging.log4j", "log4j-slf4j-impl")
)

val loggingOverrideDependencies = List(
  "org.slf4j" % "log4j-over-slf4j" % slf4jVersion,
  "org.slf4j" % "jcl-over-slf4j" % slf4jVersion,
  "org.slf4j" % "jul-to-slf4j" % slf4jVersion,
  "org.apache.logging.log4j" % "log4j-to-slf4j" % log4j2Version
)

lazy val it = project
  .in(file("modules/it"))
  .dependsOn(core, dynamodb)
  .settings(
    name := "mnemosyne-it",
    Test / fork := true,
    Test / javaOptions ++= Seq(
      "-Dlogback.configurationFile=logback-integration-test.xml",
      "-Dsoftware.amazon.awssdk.http.async.service.impl=software.amazon.awssdk.http.nio.netty.NettySdkAsyncHttpService"
    ),
    libraryDependencies ++= Seq(
      "org.scalameta" %% "munit" % munitVersion % Test,
      "org.scalameta" %% "munit-scalacheck" % munitScalacheckVersion % Test,
      "org.typelevel" %% "munit-cats-effect" % munitCatsEffectVersion % Test,
      "ch.qos.logback" % "logback-classic" % logBackVersion % Test
    ),
    libraryDependencies ++= loggingOverrideDependencies.map(_ % Test)
  )

lazy val core = project
  .in(file("modules/core"))
  .enablePlugins(BuildInfoPlugin)
  .settings(
    name := "mnemosyne-core",
    buildInfoPackage := "com.filippodeluca.mnemosyne",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-core" % catsVersion,
      "org.typelevel" %% "cats-effect" % catsEffectVersion,
      "org.scala-lang.modules" %% "scala-java8-compat" % scalaJava8CompatVersion,
      "org.typelevel" %% "log4cats-core" % log4CatsVersion,
      "org.typelevel" %% "log4cats-slf4j" % log4CatsVersion, // TODO We should not depend directly on it
      "ch.qos.logback" % "logback-classic" % logBackVersion % Test,
      "org.typelevel" %% "cats-effect-laws" % catsEffectVersion % Test,
      "org.scalameta" %% "munit" % munitVersion % Test,
      "org.scalameta" %% "munit-scalacheck" % munitScalacheckVersion % Test,
      "org.typelevel" %% "munit-cats-effect" % munitCatsEffectVersion % Test
    ),
    libraryDependencies ++= loggingOverrideDependencies.map(_ % Test)
  )

lazy val dynamodb = project
  .in(file("modules/dynamodb"))
  .dependsOn(core % "compile->compile;test->test")
  .enablePlugins(BuildInfoPlugin)
  .settings(
    name := "mnemosyne-dynamodb",
    buildInfoPackage := "com.filippodeluca.mnemosyne",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-core" % catsVersion,
      "org.typelevel" %% "cats-effect" % catsEffectVersion,
      "org.scala-lang.modules" %% "scala-java8-compat" % scalaJava8CompatVersion,
      "software.amazon.awssdk" % "dynamodb" % awsSdkVersion,
      "org.typelevel" %% "log4cats-core" % log4CatsVersion,
      "org.typelevel" %% "log4cats-slf4j" % log4CatsVersion, // TODO We should not depend directly on it
      "ch.qos.logback" % "logback-classic" % logBackVersion % Test,
      "org.typelevel" %% "cats-effect-laws" % catsEffectVersion % Test,
      "org.scalameta" %% "munit" % munitVersion % Test,
      "org.scalameta" %% "munit-scalacheck" % munitScalacheckVersion % Test,
      "org.typelevel" %% "munit-cats-effect" % munitCatsEffectVersion % Test
    ),
    libraryDependencies ++= loggingOverrideDependencies.map(_ % Test)
  )

lazy val mnemosyne = project
  .in(file("."))
  .aggregate(core, dynamodb, it)
  .settings(
    name := "mnemosyne",
    publishArtifact := false
  )
