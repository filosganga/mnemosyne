package com.filippodeluca.mnemosyne

import _root_.meteor._
import cats.effect._
import cats.syntax.all._
import com.filippodeluca.mnemosyne.meteor._
import java.util.UUID
import scala.concurrent.duration._
import software.amazon.awssdk.services.dynamodb.model.BillingMode
import java.time.Instant
import com.filippodeluca.mnemosyne.meteor.model.EncodedResult
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider

object DeduplicationTestUtils {

  trait TestProcess[A] {
    def startedAt: Option[Instant]
    def completedAt: Option[Instant]
    def started: Boolean
    def completed: Boolean
    def run: IO[A]
  }

  object TestProcess {
    case object NoValue extends Throwable
    def apply[A](result: Option[A], delay: FiniteDuration = 0.seconds): IO[TestProcess[A]] =
      IO.delay {
        new TestProcess[A] {
          var startedAt: Option[Instant] = none
          var completedAt: Option[Instant] = none
          def started = startedAt.isDefined
          def completed = completedAt.isDefined
          def run =
            for {
              started <- IO.realTime.map(_.toMillis)
              _ <- IO.delay { startedAt = Instant.ofEpochMilli(started).some }
              _ <- IO.sleep(delay)
              res <- result match {
                case Some(res) =>
                  for {
                    completed <- IO.realTime.map(_.toMillis)
                    _ <- IO.delay { completedAt = Instant.ofEpochMilli(completed).some }
                  } yield res
                case None => IO.raiseError[A](NoValue)
              }
            } yield res
        }
      }
  }

  def createTestTable(client: Client[IO], name: String) = {
    val partitionKey = KeyDef[String]("id", DynamoDbType.S)
    val sortKey = KeyDef[String]("contextId", DynamoDbType.S)
    client
      .createCompositeKeysTable(
        name,
        partitionKey,
        sortKey,
        BillingMode.PAY_PER_REQUEST
      )
      .map(_ => CompositeKeysTable(name, partitionKey, sortKey))
  }

  def deleteTable(client: Client[IO], name: String) =
    client.deleteTable(name)

  val uuidF = IO(UUID.randomUUID())

  val clientR = Resource
    .eval {
      IO(sys.env.get("DYNAMODB_ENDPOINT")).flatMap { endpoint =>
        endpoint.traverse { endpoint =>
          IO(java.net.URI.create(endpoint))
        }
      }
    }
    .flatMap { endpoint =>
      endpoint.fold(
        Client.resource[IO]
      ) { endpoint =>
        Client.resource[IO](
          cred = DefaultCredentialsProvider.builder().build(),
          endpoint = endpoint,
          region = DefaultAwsRegionProviderChain.builder().build().getRegion()
        )
      }

    }

  val testRepo: Resource[IO, ProcessRepo[IO, String, String, EncodedResult]] =
    for {
      uuid <- Resource.eval(uuidF)
      tableName = s"mnemosyne-test-${uuid}"
      client <- clientR
      table <- Resource.make[IO, CompositeKeysTable[String, String]](
        IO(println(s"Creating table ${tableName}")) >> createTestTable(client, tableName)
      )(_ => deleteTable(client, tableName))
    } yield MeteorProcessRepo[IO, String, String](client, table, readConsistently = true)

  val defaultPollStrategy = Config.PollStrategy.linear(1.second, 10.seconds)

  def testDeduplication(
      maxProcessingTime: FiniteDuration = 5.seconds,
      ttl: Option[FiniteDuration] = none,
      pollStrategy: Config.PollStrategy = defaultPollStrategy
  ): Resource[IO, Deduplication[IO, String, String, EncodedResult]] = {
    val config = Config(maxProcessingTime, ttl, pollStrategy)
    testRepo.evalMap(Deduplication.apply(_, config))
  }

}
