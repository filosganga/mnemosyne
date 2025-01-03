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

import java.net.URI
import java.util.UUID
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

import cats.*
import cats.effect.*
import cats.implicits.*

import software.amazon.awssdk.services.dynamodb.model.*
import software.amazon.awssdk.services.dynamodb.{model as _, *}

import dynamodb.*

package object TestUtils {

  def persistenceResource[F[_]: Async]: Resource[F, Persistence[F, UUID, UUID]] =
    for {
      dynamoclient <- dynamoClientResource[F]
      tableName <- Resource.eval(randomTableName)
      table <- tableResource[F](dynamoclient, tableName)
    } yield DynamoDbPersistence[F, UUID, UUID](
      DynamoDbConfig(DynamoDbConfig.TableName(table)),
      dynamoclient
    )

  def tableResource[F[_]: Async](
      client: DynamoDbAsyncClient,
      tableName: String
  ): Resource[F, String] =
    Resource.make(createTable(client, tableName))(deleteTable(client, _))

  def createTable[F[_]: Async](
      client: DynamoDbAsyncClient,
      tableName: String
  ): F[String] = {
    val createTableRequest =
      CreateTableRequest
        .builder()
        .tableName(tableName)
        .billingMode(BillingMode.PAY_PER_REQUEST)
        .keySchema(
          List(
            dynamoKey(DynamoDbPersistence.field.id, KeyType.HASH),
            dynamoKey(DynamoDbPersistence.field.processorId, KeyType.RANGE)
          ).asJava
        )
        .attributeDefinitions(
          dynamoAttribute(DynamoDbPersistence.field.id, ScalarAttributeType.S),
          dynamoAttribute(DynamoDbPersistence.field.processorId, ScalarAttributeType.S)
        )
        .build()
    Async[F]
      .fromCompletableFuture(Sync[F].delay(client.createTable(createTableRequest)))
      .map(_.tableDescription().tableName())
      .flatTap(waitForTableCreation(client, _))
      .onError { case NonFatal(e) =>
        Sync[F].delay(println(s"Error creating DynamoDb table: $e"))
      }
  }

  def deleteTable[F[_]: Async](client: DynamoDbAsyncClient, tableName: String): F[Unit] = {
    val deleteTableRequest = DeleteTableRequest.builder().tableName(tableName).build()
    Async[F]
      .fromCompletableFuture(Sync[F].delay(client.deleteTable(deleteTableRequest)))
      .void
      .onError { case NonFatal(e) =>
        Concurrent[F].delay(println(s"Error creating DynamoDb table: $e"))
      }
  }

  def waitForTableCreation[F[_]: Async](
      client: DynamoDbAsyncClient,
      tableName: String,
      pollEveryMs: FiniteDuration = 100.milliseconds
  )(implicit ME: MonadError[F, Throwable]): F[Unit] = {
    val request = DescribeTableRequest.builder().tableName(tableName).build()
    for {
      response <- Async[F].fromCompletableFuture(Sync[F].delay(client.describeTable(request)))
      _ <- response.table().tableStatus() match {
        case TableStatus.ACTIVE => ().pure[F]
        case TableStatus.CREATING | TableStatus.UPDATING =>
          Temporal[F].sleep(pollEveryMs) >> waitForTableCreation(client, tableName, pollEveryMs)
        case status =>
          ME.raiseError(
            new Exception(s"Unexpected status ${status.toString()} for table ${tableName}")
          )
      }
    } yield ()
  }

  def dynamoKey(attributeName: String, keyType: KeyType): KeySchemaElement =
    KeySchemaElement
      .builder()
      .attributeName(attributeName)
      .keyType(keyType)
      .build()

  def dynamoAttribute(
      attributeName: String,
      attributeType: ScalarAttributeType
  ): AttributeDefinition =
    AttributeDefinition
      .builder()
      .attributeName(attributeName)
      .attributeType(attributeType)
      .build()

  def dynamoClientResource[F[_]: Sync]: Resource[F, DynamoDbAsyncClient] = {
    Resource.eval(Sync[F].delay(sys.env.get("DYNAMODB_ENDPOINT"))).flatMap { endpoint =>
      Resource.make(Sync[F].delay {
        val builder = DynamoDbAsyncClient.builder

        endpoint.foreach { endpoint =>
          builder.endpointOverride(URI.create(endpoint))
        }

        builder.build()
      })(c => Sync[F].delay(c.close()))
    }

  }

  def randomTableName[F[_]: Sync]: F[String] =
    Sync[F].delay(s"mnemosyne-${UUID.randomUUID()}")

}
