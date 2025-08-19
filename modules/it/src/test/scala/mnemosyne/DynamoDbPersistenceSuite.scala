/*
 * Copyright 2020 com.filippodeluca
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.filippodeluca.mnemosyne

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import scala.jdk.CollectionConverters.*

import cats.effect.*
import cats.implicits.*

import software.amazon.awssdk.services.dynamodb.model.{AttributeValue, GetItemRequest}
import software.amazon.awssdk.services.dynamodb.{model as _, *}

import munit.*
import org.scalacheck.Arbitrary

import dynamodb.{DynamoDbConfig, DynamoDbPersistence}
import TestUtils.*
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest

class DynamoDbProcessRepoSuite extends CatsEffectSuite {

  def a[A: Arbitrary]: IO[A] =
    IO.fromOption(Arbitrary.arbitrary[A].sample)(
      new RuntimeException("Unable to generate a sample")
    )

  test("startProcessingUpdate should add the record in dynamo") {

    resources.use { resources =>
      for {
        id <- a[UUID]
        processorId <- a[UUID]
        now <- Clock[IO].realTimeInstant
        _ <- resources.persistence.startProcessingUpdate(
          id = id,
          processorId = processorId,
          now = now
        )
        optItem <- resources.getItem(id, processorId)
      } yield assertEquals(optItem.isDefined, true, clue(optItem))
    }
  }

  test("startProcessingUpdate should populate only startedAt when there is not previous record") {

    resources.use { resources =>
      for {
        id <- a[UUID]
        processorId <- a[UUID]
        now <- Clock[IO].realTimeInstant
        _ <- resources.persistence.startProcessingUpdate(
          id = id,
          processorId = processorId,
          now = now
        )
        optItem <- resources.getItem(id, processorId)
        item <- IO.fromOption(optItem)(new RuntimeException("Item not found"))
      } yield {

        val testeeStartedAt = Option(item.m)
          .flatMap { m =>
            Option(m.get(DynamoDbPersistence.field.startedAt))
          }
          .flatMap { id =>
            Option(id.n())
          }
          .map { n =>
            Instant.ofEpochMilli(n.toLong)
          }

        assertEquals(clue(testeeStartedAt), now.truncatedTo(ChronoUnit.MILLIS).some, clue(item))

        val testeeCompletedAt = Option(item.m)
          .flatMap { m =>
            Option(m.get(DynamoDbPersistence.field.completedAt))
          }

        assertEquals(clue(testeeCompletedAt), None, clue(item))

        val testeeExpiredOn = Option(item.m)
          .flatMap { m =>
            Option(m.get(DynamoDbPersistence.field.expiresOn))
          }

        assertEquals(clue(testeeExpiredOn), None, clue(item))
      }
    }
  }

  test("startProcessingUpdate should read the memoized from dynamo") {

    resources.use { resources =>
      for {
        id <- a[UUID]
        processorId <- a[UUID]
        now <- Clock[IO].realTimeInstant
        _ <- resources.putItem(
          Map(
            "id" -> AttributeValue.builder().s(id.toString()).build,
            "processorId" -> AttributeValue.builder().s(processorId.toString()).build,
            "memoized" -> AttributeValue.builder().s("foo").build
          )
        )
        processOpt <- resources.persistence.startProcessingUpdate(
          id = id,
          processorId = processorId,
          now = now
        )
        process <- IO.fromOption(processOpt)(new RuntimeException("process muct be Some"))
      } yield assertEquals(process.memoized, Some("foo"), clue(process))
    }
  }

  test("completeProcess should not populate expiresOn when there is no ttl provided") {

    resources.use { resources =>
      for {
        id <- a[UUID]
        processorId <- a[UUID]
        now <- Clock[IO].realTimeInstant
        _ <- resources.persistence.completeProcess(
          id = id,
          processorId = processorId,
          now = now,
          ttl = None,
          value = "foo"
        )
        optItem <- resources.getItem(id, processorId)
        item <- IO.fromOption(optItem)(new RuntimeException("Item not found"))
      } yield {

        val testeeCompletedAt = Option(item.m)
          .flatMap { m =>
            Option(m.get(DynamoDbPersistence.field.completedAt))
          }
          .flatMap { id =>
            Option(id.n())
          }
          .map { n =>
            Instant.ofEpochMilli(n.toLong)
          }

        assertEquals(clue(testeeCompletedAt), now.truncatedTo(ChronoUnit.MILLIS).some, clue(item))

        val testeeExpiredOn = Option(item.m)
          .flatMap { m =>
            Option(m.get(DynamoDbPersistence.field.expiresOn))
          }

        assertEquals(clue(testeeExpiredOn), None, clue(item))
      }
    }
  }

  test("completeProcess should populate memoized") {

    resources.use { resources =>
      for {
        id <- a[UUID]
        processorId <- a[UUID]
        now <- Clock[IO].realTimeInstant
        _ <- resources.persistence.completeProcess(
          id = id,
          processorId = processorId,
          now = now,
          ttl = None,
          value = "foo"
        )
        optItem <- resources.getItem(id, processorId)
        item <- IO.fromOption(optItem)(new RuntimeException("Item not found"))
      } yield {

        val testeeMemoized = Option(item.m)
          .flatMap { m =>
            Option(m.get(DynamoDbPersistence.field.memoized))
          }
          .flatMap { id =>
            Option(id.s())
          }

        assertEquals(clue(testeeMemoized), "foo".some, clue(item))
      }
    }
  }

  test("invalidateProcess should remove the record in dynamo") {

    resources.use { resources =>
      for {
        id <- a[UUID]
        processorId <- a[UUID]
        now <- Clock[IO].realTimeInstant
        _ <- resources.persistence.startProcessingUpdate(
          id = id,
          processorId = processorId,
          now = now
        )
        _ <- resources.persistence.invalidateProcess(
          id = id,
          processorId = processorId
        )
        optItem <- resources.getItem(id, processorId)
      } yield {
        assertEquals(optItem, none[AttributeValue])
      }
    }
  }

  case class Resources(
      config: DynamoDbConfig,
      dynamoclient: DynamoDbAsyncClient,
      persistence: Persistence[IO, UUID, UUID, String]
  ) {

    def putItem(item: Map[String, AttributeValue]): IO[Unit] = {
      val request = PutItemRequest
        .builder()
        .tableName(config.tableName.value)
        .item(item.asJava)
        .build()

      val response = IO.fromCompletableFuture(
        IO.delay(
          dynamoclient
            .putItem(request)
        )
      )

      response.void
    }

    def getItem(id: UUID, processorId: UUID): IO[Option[AttributeValue]] = {
      val request = GetItemRequest
        .builder()
        .tableName(config.tableName.value)
        .key(
          Map(
            "id" -> AttributeValue.builder.s(id.toString()).build(),
            "processorId" -> AttributeValue.builder.s(processorId.toString()).build()
          ).asJava
        )
        .consistentRead(true)
        .build()

      val response = IO.fromCompletableFuture(
        IO.delay(
          dynamoclient
            .getItem(request)
        )
      )

      response.map { r =>
        if (r.hasItem()) {
          AttributeValue.builder().m(r.item).build.some
        } else {
          none[AttributeValue]
        }
      }
    }

  }

  val resources: Resource[IO, Resources] = for {
    dynamoClient <- dynamoClientResource[IO]
    tableName <- Resource.eval(randomTableName[IO])
    table <- tableResource[IO](dynamoClient, tableName)
    config = DynamoDbConfig(DynamoDbConfig.TableName(table))
  } yield Resources(
    config,
    dynamoClient,
    DynamoDbPersistence[IO, UUID, UUID, String](
      config,
      dynamoClient
    )
  )
}
