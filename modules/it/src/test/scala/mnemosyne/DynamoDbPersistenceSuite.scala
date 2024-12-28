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

import software.amazon.awssdk.services.dynamodb.model.{AttributeValue, GetItemRequest, PutItemRequest}
import software.amazon.awssdk.services.dynamodb.{model as _, *}

import munit.*
import org.scalacheck.Arbitrary

import dynamodb.{DynamoDbConfig, DynamoDbPersistence}
import dynamodb.DynamoDbPersistence.*
import TestUtils.*

// import com.filippodeluca.mnemosyne.dynamodb.DynamoDbDecoder.*;
// import com.filippodeluca.mnemosyne.dynamodb.DynamoDbEncoder.*;

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
        _ <- resources.processRepoR.startProcessingUpdate(
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
        _ <- resources.processRepoR.startProcessingUpdate(
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

  test("completeProcess should not populate expiresOn when there is no ttl provided") {

    resources.use { resources =>
      for {
        id <- a[UUID]
        processorId <- a[UUID]
        now <- Clock[IO].realTimeInstant
        _ <- resources.processRepoR.completeProcess(
          id = id,
          processorId = processorId,
          now = now,
          ttl = None
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

  test("completeProcessWithMemoization should add the record in dynamo with memoized value") {

    resources.use { resources =>
      for {
        id <- a[UUID]
        processorId <- a[UUID]
        now <- Clock[IO].realTimeInstant
        _ <- resources.processRepoR.completeProcessWithMemoization(
          id = id,
          processorId = processorId,
          now = now,
          ttl = None,
          memoized = "memoized"
        )
        maybeItem <- resources.getItem(id, processorId)
      } yield {
        val testee = for
          item <- maybeItem
          m <- Option(item.m())
          memoized <- Option(m.get(DynamoDbPersistence.field.memoized))
        yield memoized
        assertEquals(testee, Some(AttributeValue.builder().s("memoized").build()), clue(maybeItem))
      }
    }
  }

  test("getMemoizedValue should return the memoized value") {

    resources.use { resources =>
      for {
        id <- a[UUID]
        processorId <- a[UUID]
        now <- Clock[IO].realTimeInstant
        _ <- resources.putItem(
          AttributeValue
            .builder()
            .m(
              Map(
                DynamoDbPersistence.field.id -> AttributeValue.builder.s(id.toString()).build(),
                DynamoDbPersistence.field.processorId -> AttributeValue.builder
                  .s(processorId.toString())
                  .build(),
                DynamoDbPersistence.field.memoized -> AttributeValue.builder.s("memoized").build()
              ).asJava
            )
            .build()
        )
        maybeValue <- resources.processRepoR.getMemoizedValue[String](
          id = id,
          processorId = processorId
        )
      } yield {
        assertEquals(
          maybeValue,
          Some("memoized"),
          clue(maybeValue)
        )
      }
    }
  }

  test("invalidateProcess should remove the record in dynamo") {

    resources.use { resources =>
      for {
        id <- a[UUID]
        processorId <- a[UUID]
        now <- Clock[IO].realTimeInstant
        _ <- resources.processRepoR.startProcessingUpdate(
          id = id,
          processorId = processorId,
          now = now
        )
        _ <- resources.processRepoR.invalidateProcess(
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
      processRepoR: Persistence[IO, UUID, UUID, AttributeValue]
  ) {

    def putItem(value: AttributeValue): IO[Unit] = {
      IO.fromOption(Option(value.m()))(new RuntimeException("The given AttributeValur is not an M"))
        .flatMap { item =>
          val request = PutItemRequest
            .builder()
            .tableName(config.tableName.value)
            .item(item)
            .build()

          IO.fromCompletableFuture(
            IO.delay(
              dynamoclient
                .putItem(request)
            )
          ).void
        }

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
    DynamoDbPersistence[IO, UUID, UUID](
      config,
      dynamoClient
    )
  )
}
