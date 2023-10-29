val catsVersion = "2.8.0"
val catsEffectVersion = "3.5.2"
val slf4jVersion = "1.7.36"
val scalaJava8CompatVersion = "0.9.1"
val awsSdkVersion = "2.18.13"
val meteorVersion = "1.0.31"
val log4CatsVersion = "2.5.0"
val munitVersion = "1.0.0-M7"
val munitCatsEffectVersion = "1.0.7"
val logBackVersion = "1.4.4"
val log4j2Version = "2.19.0"

Global / onChangedBuildSource := ReloadOnSourceChanges

ThisBuild / scalafixDependencies += "com.github.liancheng" %% "organize-imports" % "0.6.0"
ThisBuild / semanticdbEnabled := true
ThisBuild / semanticdbVersion := scalafixSemanticdb.revision
ThisBuild / excludeDependencies ++= Seq(
  ExclusionRule("commons-logging", "commons-logging"),
  ExclusionRule("org.slf4j", "slf4j-log4j12"),
  ExclusionRule("log4j", "log4j"),
  ExclusionRule("org.apache.logging.log4j", "log4j-core"),
  ExclusionRule("org.apache.logging.log4j", "log4j-slf4j-impl")
)

lazy val deduplication = (project in file("."))
  .enablePlugins(BuildInfoPlugin)
  .configs(IntegrationTest)
  .settings(inConfig(IntegrationTest)(Defaults.testSettings))
  .settings(
    IntegrationTest / fork := true,
    IntegrationTest / javaOptions ++= Seq(
      "-Dlogback.configurationFile=logback-integration-test.xml",
      "-Dsoftware.amazon.awssdk.http.async.service.impl=software.amazon.awssdk.http.nio.netty.NettySdkAsyncHttpService"
    )
  )
  .settings(
    organization := "com.kaluza.mnemosyne",
    organizationHomepage := Some(url("http://www.kaluza.com")),
    licenses := Seq(("Apache-2.0", url("http://www.apache.org/licenses/LICENSE-2.0"))),
    scalaVersion := "2.13.10",
    scalafmtOnCompile := true,
    scalacOptions -= "-Xfatal-warnings", // enable all options from sbt-tpolecat except fatal warnings
    initialCommands := s"import com.kaluza.mnemosyne._",
    javacOptions ++= Seq("-Xlint:unchecked", "-Xlint:deprecation"),
    addCompilerPlugin("org.typelevel" %% "kind-projector" % "0.13.2" cross CrossVersion.full),
    publishTo := Some("Artifactory Realm" at "https://kaluza.jfrog.io/artifactory/maven-private/"),
    credentials += {
      for {
        usr <- sys.env.get("ARTIFACTORY_USER")
        password <- sys.env.get("ARTIFACTORY_PASS")
      } yield Credentials("Artifactory Realm", "kaluza.jfrog.io", usr, password)
    }.getOrElse(Credentials(Path.userHome / ".ivy2" / ".credentials")),
    scmInfo := Some(
      ScmInfo(
        url("https://github.com/ovotech/comms-deduplication"),
        "git@github.com:ovotech/comms-deduplication.git"
      )
    ),
    developers := List(
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
    ),
    name := "mnemosyne",
    buildInfoPackage := "com.kaluza.mnemosyne",
    version ~= (_.replace('+', '-')),
    dynver ~= (_.replace('+', '-')),
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-core" % catsVersion,
      "org.typelevel" %% "cats-effect" % catsEffectVersion,
      "software.amazon.awssdk" % "dynamodb" % awsSdkVersion,
      "io.github.d2a4u" %% "meteor-awssdk" % meteorVersion,
      "org.typelevel" %% "log4cats-core" % log4CatsVersion,
      "org.typelevel" %% "log4cats-slf4j" % log4CatsVersion,
      "org.slf4j" % "slf4j-api" % slf4jVersion,
      "org.typelevel" %% "cats-effect-laws" % catsEffectVersion % Test,
      "org.typelevel" %% "munit-cats-effect-3" % munitCatsEffectVersion % IntegrationTest,
      "org.scalameta" %% "munit-scalacheck" % munitVersion % Test,
      "ch.qos.logback" % "logback-classic" % logBackVersion % s"${Test};${IntegrationTest}",
      "org.slf4j" % "slf4j-api" % slf4jVersion % s"${Test};${IntegrationTest}",
      "org.slf4j" % "log4j-over-slf4j" % slf4jVersion % s"${Test};${IntegrationTest}",
      "org.slf4j" % "jcl-over-slf4j" % slf4jVersion % s"${Test};${IntegrationTest}",
      "org.slf4j" % "jul-to-slf4j" % slf4jVersion % s"${Test};${IntegrationTest}",
      "org.apache.logging.log4j" % "log4j-to-slf4j" % log4j2Version % s"${Test};${IntegrationTest}"
    )
  )
