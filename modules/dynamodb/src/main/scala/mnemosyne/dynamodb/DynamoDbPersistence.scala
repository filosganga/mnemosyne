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
package dynamodb

import java.time.Instant
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters.*
import scala.jdk.DurationConverters.*

import cats.data.NonEmptyList
import cats.effect.*
import cats.syntax.all.*

import software.amazon.awssdk.services.dynamodb.*
import software.amazon.awssdk.services.dynamodb.model.*

import com.filippodeluca.mnemosyne.memoization.*
import com.filippodeluca.mnemosyne.model.*

object DynamoDbPersistence {

  implicit class RichAttributeValue(av: AttributeValue) {
    def get[A: DynamoDbDecoder](key: String): Either[DecoderFailure, A] =
      for {
        xs <- DynamoDbDecoder[Map[String, AttributeValue]].read(av)
        x <- DynamoDbDecoder[A].read(xs.getOrElse(key, AttributeValue.builder().nul(true).build()))
      } yield x
  }

  implicit val dynamoDbDecoderForExpiration: DynamoDbDecoder[Expiration] =
    DynamoDbDecoder[Long].flatMap { seconds =>
      DynamoDbDecoder
        .instance[Instant] { _ =>
          Either
            .catchNonFatal(Instant.ofEpochSecond(seconds))
            .leftMap(e => DecoderFailure(e.getMessage(), e.some))
        }
        .map(Expiration(_))
    }

  implicit def memoizedDecoder[A: DynamoDbDecoder]: MemoizedDecoder[A, AttributeValue] =
    new MemoizedDecoder[A, AttributeValue] {
      def decodeMemoized(
          native: AttributeValue
      ): Either[MnemosyneError.MemoizedDecodingError, A] =
        DynamoDbDecoder[A]
          .read(native)
          .leftMap(e => MnemosyneError.MemoizedDecodingError(e.message, e.cause))
    }

  implicit def memoizedEncoder[A: DynamoDbEncoder]: MemoizedEncoder[A, AttributeValue] =
    new MemoizedEncoder[A, AttributeValue] {
      def encodeMemoized(
          a: A
      ): Either[MnemosyneError.MemoizedEncodingError, AttributeValue] =
        DynamoDbEncoder[A]
          .write(a)
          .asRight[MnemosyneError.MemoizedEncodingError]
    }

  object field {
    val id = "id"
    val processorId = "processorId"
    val startedAt = "startedAt"
    val completedAt = "completedAt"
    val expiresOn = "expiresOn"
    val memoized = "memoized"
  }

  def resource[F[
      _
  ]: Async, ID: DynamoDbDecoder: DynamoDbEncoder, ProcessorID: DynamoDbDecoder: DynamoDbEncoder](
      config: DynamoDbConfig
  ): Resource[F, Persistence[F, ID, ProcessorID, AttributeValue]] = {
    Resource
      .make(Sync[F].delay(config.modifyClientBuilder(DynamoDbAsyncClient.builder()).build())) {
        client =>
          Sync[F].delay(client.close())
      }
      .map(client => apply(config, client))
  }

  def apply[F[
      _
  ], ID: DynamoDbDecoder: DynamoDbEncoder, ProcessorID: DynamoDbDecoder: DynamoDbEncoder](
      config: DynamoDbConfig,
      client: DynamoDbAsyncClient
  )(implicit F: Async[F]): Persistence[F, ID, ProcessorID, AttributeValue] = {

    def update(request: UpdateItemRequest) = {
      Async[F].fromCompletableFuture(Sync[F].delay(client.updateItem(request)))
    }

    def delete(request: DeleteItemRequest) = {
      Async[F].fromCompletableFuture(Sync[F].delay(client.deleteItem(request)))
    }

    def readProcess(
        attributes: AttributeValue
    ): Either[DecoderFailure, Process[ID, ProcessorID]] =
      for {
        id <- attributes.get[ID](field.id)
        processorId <- attributes.get[ProcessorID](field.processorId)
        startedAt <- attributes.get[Instant](field.startedAt)
        completedAt <- attributes.get[Option[Instant]](field.completedAt)
        expiresOn <- attributes.get[Option[Expiration]](field.expiresOn)
      } yield Process(
        id,
        processorId,
        startedAt,
        completedAt,
        expiresOn
      )

    new Persistence[F, ID, ProcessorID, AttributeValue] {

      def startProcessingUpdate(
          id: ID,
          processorId: ProcessorID,
          now: Instant
      ): F[Option[Process[ID, ProcessorID]]] = {

        val startedAtVar = ":startedAt"

        val request = UpdateItemRequest
          .builder()
          .tableName(config.tableName.value)
          .key(
            Map(
              field.id -> DynamoDbEncoder[ID].write(id),
              field.processorId -> DynamoDbEncoder[ProcessorID].write(processorId)
            ).asJava
          )
          .updateExpression(
            s"SET ${field.startedAt} = if_not_exists(${field.startedAt}, ${startedAtVar})"
          )
          .expressionAttributeValues(
            Map(
              startedAtVar -> AttributeValue.builder().n(now.toEpochMilli().toString).build()
            ).asJava
          )
          .returnValues(ReturnValue.ALL_OLD)
          .build()

        update(request).map { res =>
          Option(res.attributes())
            .filter(_.size > 0)
            .map(xs => AttributeValue.builder().m(xs).build())
            .traverse(readProcess)
        }.rethrow
      }

      override def getMemoizedValue[A](id: ID, processorId: ProcessorID)(implicit
          memoizedDecoder: MemoizedDecoder[A, AttributeValue]
      ): F[Option[A]] = {

        val request = GetItemRequest
          .builder()
          .tableName(config.tableName.value)
          .key(
            Map(
              field.id -> DynamoDbEncoder[ID].write(id),
              field.processorId -> DynamoDbEncoder[ProcessorID].write(processorId)
            ).asJava
          )
          .build()

        F.fromCompletableFuture(Sync[F].delay(client.getItem(request)))
          .map { res =>
            Option(res.item())
              .filter(_.size > 0)
              .flatMap(xs => Option(xs.get(field.memoized)))
              .traverse(memoizedDecoder.decodeMemoized)
          }
          .rethrow
      }

      def completeProcess(
          id: ID,
          processorId: ProcessorID,
          now: Instant,
          ttl: Option[FiniteDuration]
      ): F[Unit] = {
        val completedAtVar = ":completedAt"
        val expiresOnVar = ":expiresOn"
        val updateExpression = s"SET ${field.completedAt}=$completedAtVar" + ttl.fold("")(_ =>
          s", ${field.expiresOn}=$expiresOnVar"
        )

        val request = UpdateItemRequest
          .builder()
          .tableName(config.tableName.value)
          .key(
            Map(
              field.id -> DynamoDbEncoder[ID].write(id),
              field.processorId -> DynamoDbEncoder[ProcessorID].write(processorId)
            ).asJava
          )
          .updateExpression(updateExpression)
          .expressionAttributeValues(
            (Map(
              completedAtVar -> AttributeValue
                .builder()
                .n(now.toEpochMilli.toString)
                .build()
            ) ++ ttl.map(ttl =>
              expiresOnVar -> AttributeValue
                .builder()
                .n(now.plus(ttl.toJava).getEpochSecond.toString)
                .build()
            )).asJava
          )
          .returnValues(ReturnValue.NONE)
          .build()

        update(request).void
      }

      override def completeProcessWithMemoization[A](
          id: ID,
          processorId: ProcessorID,
          now: Instant,
          ttl: Option[FiniteDuration],
          memoized: A
      )(implicit memoizedEncoder: MemoizedEncoder[A, AttributeValue]): F[Unit] = {

        F.fromEither(memoizedEncoder.encodeMemoized(memoized))
          .flatMap { memoizedAv =>

            val completedAtVar = ":completedAt"
            val expiresOnVar = ":expiresOn"
            val memoizedVar = ":memoized"

            val updateExpression = (NonEmptyList.of(
              s"${field.completedAt}=$completedAtVar",
              s"${field.memoized}=$memoizedVar"
            ) ++ ttl.toList.as(s"${field.expiresOn}=$expiresOnVar")).mkString_("SET ", ", ", "")

            val expressionAttributeValues = Map(
              completedAtVar -> AttributeValue
                .builder()
                .n(now.toEpochMilli.toString)
                .build(),
              memoizedVar -> memoizedAv
            ) ++ ttl.map(ttl =>
              expiresOnVar -> AttributeValue
                .builder()
                .n(now.plus(ttl.toJava).getEpochSecond.toString)
                .build()
            )

            val request = UpdateItemRequest
              .builder()
              .tableName(config.tableName.value)
              .key(
                Map(
                  field.id -> DynamoDbEncoder[ID].write(id),
                  field.processorId -> DynamoDbEncoder[ProcessorID].write(processorId)
                ).asJava
              )
              .updateExpression(updateExpression)
              .expressionAttributeValues(expressionAttributeValues.asJava)
              .returnValues(ReturnValue.NONE)
              .build()

            update(request).void
          }
      }

      def invalidateProcess(id: ID, processorId: ProcessorID): F[Unit] = {
        val request = DeleteItemRequest
          .builder()
          .tableName(config.tableName.value)
          .key(
            Map(
              field.id -> DynamoDbEncoder[ID].write(id),
              field.processorId -> DynamoDbEncoder[ProcessorID].write(processorId)
            ).asJava
          )
          .returnValues(ReturnValue.NONE)
          .build()

        delete(request).void
      }
    }

  }

}
