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
import scala.jdk.DurationConverters.*

import cats.effect.IO
import cats.syntax.all.*

import org.typelevel.log4cats.slf4j.Slf4jFactory

import munit.*
import org.scalacheck.Arbitrary

import model.*
import Config.*
import cats.effect.kernel.Ref
import com.filippodeluca.mnemosyne.model.Outcome.New
import com.filippodeluca.mnemosyne.model.Outcome.Duplicate

class MnemosyneSuite extends CatsEffectSuite {

  def a[A: Arbitrary]: A = Arbitrary.arbitrary[A].sample.get

  class MockPersistence[A] extends Persistence[IO, UUID, UUID, A] {
    override def startProcessingUpdate(
        id: UUID,
        processorId: ju.UUID,
        now: Instant
    ): IO[Option[Process[UUID, UUID, A]]] = none[Process[UUID, UUID, A]].pure[IO]

    override def completeProcess(
        id: UUID,
        processorId: UUID,
        now: Instant,
        ttl: Option[FiniteDuration],
        value: A
    ): IO[Unit] =
      IO.unit
    def invalidateProcess(id: UUID, processorId: UUID): IO[Unit] = IO.unit
  }

  test(
    "tryStartProcess should return New when the process with the a ID has never started before"
  ) {

    val processId = a[UUID]
    val processorId = a[UUID]

    val mnemosyne = Mnemosyne[IO, UUID, UUID, String](
      new MockPersistence[String] {
        override def startProcessingUpdate(
            id: UUID,
            processorId: ju.UUID,
            now: Instant
        ): IO[Option[Process[UUID, UUID, String]]] = none[Process[UUID, UUID, String]].pure[IO]
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
        assert(outcome.isInstanceOf[Outcome.New[IO, String]], clue(outcome))
      }

  }

  test(
    "tryStartProcess should return Duplicate when the process with the a ID has already completed"
  ) {

    val processId = a[UUID]
    val processorId = a[UUID]

    val mnemosyne = Mnemosyne[IO, UUID, UUID, String](
      new MockPersistence[String] {
        override def startProcessingUpdate(
            id: UUID,
            processorId: ju.UUID,
            now: Instant
        ): IO[Option[Process[UUID, UUID, String]]] = {
          IO.realTimeInstant.map { now =>
            Process(
              id = id,
              processorId = processorId,
              startedAt = now.minusMillis(750),
              completedAt = now.minusMillis(250).some,
              expiresOn = None,
              memoized = "foo".some
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
        assertEquals(outcome, Outcome.Duplicate[IO, String]("foo"))
      }
  }

  test(
    "tryStartProcess should return New when another process with the same Id has never completed and expired"
  ) {

    val processId = a[UUID]
    val processorId = a[UUID]

    val mnemosyne = Mnemosyne[IO, UUID, UUID, String](
      new MockPersistence[String] {
        override def startProcessingUpdate(
            id: UUID,
            processorId: ju.UUID,
            now: Instant
        ): IO[Option[Process[UUID, UUID, String]]] = {
          IO.realTimeInstant.map { now =>
            Process(
              id = id,
              processorId = processorId,
              startedAt = now.minusMillis(750),
              completedAt = None,
              expiresOn = Expiration(now.minusMillis(250)).some,
              memoized = None
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
        assert(outcome.isInstanceOf[Outcome.New[IO, String]], clue(outcome))
      }
  }

  test(
    "tryStartProcess should return New when another process with the same Id has completed and expired"
  ) {

    val processId = a[UUID]
    val processorId = a[UUID]

    val mnemosyne = Mnemosyne[IO, UUID, UUID, String](
      new MockPersistence[String] {
        override def startProcessingUpdate(
            id: UUID,
            processorId: ju.UUID,
            now: Instant
        ): IO[Option[Process[UUID, UUID, String]]] = {
          IO.realTimeInstant.map { now =>
            Process(
              id = id,
              processorId = processorId,
              startedAt = now.minusMillis(750),
              completedAt = now.minusMillis(500).some,
              expiresOn = Expiration(now.minusMillis(250)).some,
              memoized = None
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
        assert(outcome.isInstanceOf[Outcome.New[IO, String]], clue(outcome))
      }
  }

  test(
    "tryStartProcess should return New when another process with the same Id has timeout"
  ) {

    val processId = a[UUID]
    val processorId = a[UUID]

    val mnemosyne = Mnemosyne[IO, UUID, UUID, String](
      new MockPersistence[String] {
        override def startProcessingUpdate(
            id: UUID,
            processorId: ju.UUID,
            now: Instant
        ): IO[Option[Process[UUID, UUID, String]]] = {
          IO.realTimeInstant.map { now =>
            Process(
              id = id,
              processorId = processorId,
              startedAt = now.minusSeconds(300),
              completedAt = None,
              expiresOn = None,
              memoized = None
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
        assert(outcome.isInstanceOf[Outcome.New[IO, String]], clue(outcome))
      }
  }

  test(
    "tryStartProcess should return New that stores the result upon completion"
  ) {

    val processId = a[UUID]
    val processorId = a[UUID]

    Ref[IO].of(none[String]).flatMap { ref =>
      Mnemosyne[IO, UUID, UUID, String](
        new MockPersistence[String] {
          override def startProcessingUpdate(
              id: UUID,
              processorId: ju.UUID,
              now: Instant
          ): IO[Option[Process[UUID, UUID, String]]] = none[Process[UUID, UUID, String]].pure[IO]
          override def completeProcess(
              id: UUID,
              processorId: UUID,
              now: Instant,
              ttl: Option[FiniteDuration],
              value: String
          ): IO[Unit] =
            ref.set(Some(value))
        },
        Config(
          processorId = processorId,
          maxProcessingTime = 5.minutes,
          ttl = 30.days.some,
          pollStrategy = PollStrategy.linear()
        ),
        Slf4jFactory.create[IO]
      ).flatMap { mnemosyne =>
        mnemosyne.tryStartProcess(processId)
      }.flatMap {
        case New(complete) =>
          complete("Foo")
        case Duplicate(value) =>
          fail("Outcome.New was expected")
      }.flatMap { _ =>
        assertIO(ref.get, Some("Foo"))
      }
    }
  }

  test(
    "tryStartProcess should return Duplicate that contains the result of a previos completion"
  ) {

    val processId = a[UUID]
    val processorId = a[UUID]

    Mnemosyne[IO, UUID, UUID, String](
      new MockPersistence[String] {
        override def startProcessingUpdate(
            id: UUID,
            processorId: ju.UUID,
            now: Instant
        ): IO[Option[Process[UUID, UUID, String]]] = Process(
          id,
          processorId,
          now.minus(2.days.toJava),
          Some(now.minus(1.day.toJava)),
          expiresOn = None,
          Some("Foo")
        ).some.pure[IO]
      },
      Config(
        processorId = processorId,
        maxProcessingTime = 5.minutes,
        ttl = 30.days.some,
        pollStrategy = PollStrategy.linear()
      ),
      Slf4jFactory.create[IO]
    ).flatMap { mnemosyne =>
      mnemosyne.tryStartProcess(processId)
    }.map {
      case New(_) =>
        fail("Outcome.Duplicate was expected")
      case Duplicate(value) =>
        assertEquals(value, "Foo")
    }
  }
}
