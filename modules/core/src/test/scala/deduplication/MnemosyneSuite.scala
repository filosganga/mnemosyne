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
import java.util as ju
import java.util.UUID
import scala.concurrent.duration.*

import cats.effect.IO
import cats.syntax.all.*

import org.typelevel.log4cats.slf4j.Slf4jFactory
import io.circe.Json

import munit.*
import org.scalacheck.Arbitrary

import model.*
import Config.*
import memoization.*

class DeduplicationSuite extends CatsEffectSuite {

  def a[A: Arbitrary]: A = Arbitrary.arbitrary[A].sample.get

  implicit def memoizedDecoderForJson[A](implicit
      decoder: io.circe.Decoder[A]
  ): MemoizedDecoder[A, Json] =
    new MemoizedDecoder[A, Json] {
      def decodeMemoized(json: Json): Either[MnemosyneError.MemoizedDecodingError, A] =
        json.as[A].leftMap(e => MnemosyneError.MemoizedDecodingError(e.message))
    }

  implicit def memoizedEncoderForJson[A](implicit
      encoder: io.circe.Encoder[A]
  ): MemoizedEncoder[A, Json] =
    new MemoizedEncoder[A, Json] {
      def encodeMemoized(a: A): Either[MnemosyneError.MemoizedEncodingError, Json] =
        encoder(a).asRight[MnemosyneError.MemoizedEncodingError]
    }

  class MockPersistence extends Persistence[IO, UUID, UUID, Json] {

    def startProcessingUpdate(
        id: UUID,
        processorId: ju.UUID,
        now: Instant
    ): IO[Option[Process[UUID, UUID]]] = none[Process[UUID, UUID]].pure[IO]

    def completeProcess(
        id: UUID,
        processorId: UUID,
        now: Instant,
        ttl: Option[FiniteDuration]
    ): IO[Unit] =
      IO.unit

    def completeProcessWithMemoization[A](
        id: UUID,
        processorId: UUID,
        now: Instant,
        ttl: Option[FiniteDuration],
        memoized: A
    )(implicit memoizedEncoder: MemoizedEncoder[A, Json]): IO[Unit] = IO.unit

    def getMemoizedValue[A](
        id: UUID,
        processorId: UUID
    )(implicit memoizedDecoder: MemoizedDecoder[A, Json]): IO[Option[A]] =
      none[A].pure[IO]

    def invalidateProcess(id: UUID, processorId: UUID): IO[Unit] =
      IO.unit
  }

  test(
    "tryStartProcess should be sound"
  ) {

    val processId = a[UUID]
    val processorId = a[UUID]

    val mnemosyne = Mnemosyne[IO, UUID, UUID, Json](
      new MockPersistence {
        override def startProcessingUpdate(
            id: UUID,
            processorId: ju.UUID,
            now: Instant
        ): IO[Option[Process[UUID, UUID]]] = none[Process[UUID, UUID]].pure[IO]
      },
      Config(
        processorId = processorId,
        maxProcessingTime = 5.minutes,
        ttl = 30.days.some,
        pollStrategy = PollStrategy.linear()
      ),
      Slf4jFactory.create[IO]
    )

    val fa = IO("foo")

    mnemosyne
      .flatMap { mnemosyne =>
        mnemosyne.memoizedTryStartProcess[String](processId)
      }
      .flatMap {
        case d: MemoizedOutcome.Duplicate[IO, String] =>
          d.value
        case n: MemoizedOutcome.New[IO, String] =>
          fa.flatTap(a => n.completeProcess(a))
      }
  }

  test(
    "tryStartProcess should return New when the process with the a ID has never started before"
  ) {

    val processId = a[UUID]
    val processorId = a[UUID]

    val mnemosyne = Mnemosyne[IO, UUID, UUID, Json](
      new MockPersistence {
        override def startProcessingUpdate(
            id: UUID,
            processorId: ju.UUID,
            now: Instant
        ): IO[Option[Process[UUID, UUID]]] = none[Process[UUID, UUID]].pure[IO]
      },
      Config(
        processorId = processorId,
        maxProcessingTime = 5.minutes,
        ttl = 30.days.some,
        pollStrategy = PollStrategy.linear()
      ),
      Slf4jFactory.create[IO]
    )

    mnemosyne
      .flatMap { mnemosyne =>
        mnemosyne.tryStartProcess(processId)
      }
      .map { outcome =>
        assert(outcome.isInstanceOf[Outcome.New[IO]], clue(outcome))
      }

  }

  test(
    "tryStartProcess should return Duplicate when the process with the a ID has already completed"
  ) {

    val processId = a[UUID]
    val processorId = a[UUID]

    val mnemosyne = Mnemosyne[IO, UUID, UUID, Json](
      new MockPersistence {
        override def startProcessingUpdate(
            id: UUID,
            processorId: ju.UUID,
            now: Instant
        ): IO[Option[Process[UUID, UUID]]] = {
          IO.realTimeInstant.map { now =>
            Process(
              id = id,
              processorId = processorId,
              startedAt = now.minusMillis(750),
              completedAt = now.minusMillis(250).some,
              expiresOn = None
            ).some
          }
        }
      },
      Config(
        processorId = processorId,
        maxProcessingTime = 5.minutes,
        ttl = 30.days.some,
        pollStrategy = PollStrategy.linear()
      ),
      Slf4jFactory.create[IO]
    )

    mnemosyne
      .flatMap { mnemosyne =>
        mnemosyne.tryStartProcess(processId)
      }
      .map { outcome =>
        assertEquals(outcome, Outcome.Duplicate[IO]())
      }
  }

  test(
    "tryStartProcess should return New when another process with the same Id has never completed and expired"
  ) {

    val processId = a[UUID]
    val processorId = a[UUID]

    val mnemosyne = Mnemosyne[IO, UUID, UUID, Json](
      new MockPersistence {
        override def startProcessingUpdate(
            id: UUID,
            processorId: ju.UUID,
            now: Instant
        ): IO[Option[Process[UUID, UUID]]] = {
          IO.realTimeInstant.map { now =>
            Process(
              id = id,
              processorId = processorId,
              startedAt = now.minusMillis(750),
              completedAt = None,
              expiresOn = Expiration(now.minusMillis(250)).some
            ).some
          }
        }
      },
      Config(
        processorId = processorId,
        maxProcessingTime = 5.minutes,
        ttl = 30.days.some,
        pollStrategy = PollStrategy.linear()
      ),
      Slf4jFactory.create[IO]
    )

    mnemosyne
      .flatMap { mnemosyne =>
        mnemosyne.tryStartProcess(processId)
      }
      .map { outcome =>
        assert(outcome.isInstanceOf[Outcome.New[IO]], clue(outcome))
      }
  }

  test(
    "tryStartProcess should return New when another process with the same Id has completed and expired"
  ) {

    val processId = a[UUID]
    val processorId = a[UUID]

    val mnemosyne = Mnemosyne[IO, UUID, UUID, Json](
      new MockPersistence {
        override def startProcessingUpdate(
            id: UUID,
            processorId: ju.UUID,
            now: Instant
        ): IO[Option[Process[UUID, UUID]]] = {
          IO.realTimeInstant.map { now =>
            Process(
              id = id,
              processorId = processorId,
              startedAt = now.minusMillis(750),
              completedAt = now.minusMillis(500).some,
              expiresOn = Expiration(now.minusMillis(250)).some
            ).some
          }
        }
      },
      Config(
        processorId = processorId,
        maxProcessingTime = 5.minutes,
        ttl = 30.days.some,
        pollStrategy = PollStrategy.linear()
      ),
      Slf4jFactory.create[IO]
    )

    mnemosyne
      .flatMap { mnemosyne =>
        mnemosyne.tryStartProcess(processId)
      }
      .map { outcome =>
        assert(outcome.isInstanceOf[Outcome.New[IO]], clue(outcome))
      }
  }

  test(
    "tryStartProcess should return New when another process with the same Id has timeout"
  ) {

    val processId = a[UUID]
    val processorId = a[UUID]

    val mnemosyne = Mnemosyne[IO, UUID, UUID, Json](
      new MockPersistence {
        override def startProcessingUpdate(
            id: UUID,
            processorId: ju.UUID,
            now: Instant
        ): IO[Option[Process[UUID, UUID]]] = {
          IO.realTimeInstant.map { now =>
            Process(
              id = id,
              processorId = processorId,
              startedAt = now.minusSeconds(300),
              completedAt = None,
              expiresOn = None
            ).some
          }
        }
      },
      Config(
        processorId = processorId,
        maxProcessingTime = 100.seconds,
        ttl = 30.days.some,
        pollStrategy = PollStrategy.linear()
      ),
      Slf4jFactory.create[IO]
    )

    mnemosyne
      .flatMap { mnemosyne =>
        mnemosyne.tryStartProcess(processId)
      }
      .map { outcome =>
        assert(outcome.isInstanceOf[Outcome.New[IO]], clue(outcome))
      }
  }
}
