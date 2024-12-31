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

import java.util.UUID
import java.util.concurrent.TimeoutException
import scala.concurrent.duration.*

import cats.effect.{IO, Ref, Resource}
import cats.implicits.*

import org.typelevel.log4cats.slf4j.Slf4jFactory

import munit.*
import org.scalacheck.Arbitrary

import com.filippodeluca.mnemosyne.dynamodb.{DynamoDbDecoder, DynamoDbEncoder}
import TestUtils.*
import model.*
import Config.*

class MnemosyneSuite extends CatsEffectSuite {

  def a[A: Arbitrary]: A = Arbitrary.arbitrary[A].sample.get

  test("Mnemosyne should always process event for the first time") {
    val processorId = a[UUID]
    val id1 = a[UUID]
    val id2 = a[UUID]

    deduplicationResource[Unit](processorId)
      .use { ps =>
        for {
          ref <- Ref[IO].of(0)
          a <- ps.protect(id1, ref.update(_ + 1))
          b <- ps.protect(id2, ref.update(_ + 1))
          result <- ref.get
        } yield assertEquals(result, 2)
      }
  }

  test("Mnemosyne should process event for the first time, ignore after that") {

    val processorId = a[UUID]
    val id = a[UUID]

    deduplicationResource[Unit](processorId)
      .use { ps =>
        for {
          ref <- Ref[IO].of(0)
          a <- ps.protect(id, ref.update(_ + 1))
          b <- ps.protect(id, ref.update(_ + 1))
          result <- ref.get
        } yield assertEquals(result, 1)
      }
  }

  test("Mnemosyne should re-process the event if it failed the first time") {

    val processorId = a[UUID]
    val id = a[UUID]

    deduplicationResource[Unit](processorId, 1.seconds)
      .use { ps =>
        for {
          ref <- Ref[IO].of(0)
          _ <- ps
            .protect(id, IO.raiseError[Unit](new RuntimeException("Expected exception")))
            .attempt
          _ <- ps.protect(id, ref.set(1))
          _ <- ps.protect(id, ref.set(3))
          result <- ref.get
        } yield {
          assertEquals(result, 1)
        }
      }
  }

  // TODO this test is ignored because it is the symptoms of the issue we have with this library
  test("Mnemosyne should not re-process multiple event if it failed the first time".ignore) {

    val processorId = a[UUID]
    val id = a[UUID]

    deduplicationResource[Unit](processorId, 1.seconds)
      .use { ps =>
        for {
          ref <- Ref[IO].of(0)
          _ <- ps
            .protect(id, IO.raiseError[Unit](new RuntimeException("Expected exception")))
            .attempt
          _ <- List
            .fill(100)(id)
            .parTraverse { _ =>
              ps.protect(id, ref.update(_ + 1))
            }
          result <- ref.get
        } yield {
          assertEquals(result, 1)
        }
      }
  }

  test("Mnemosyne should process the second event after the first one timeout") {

    val processorId = a[UUID]
    val id = a[UUID]

    val maxProcessingTime = 1.seconds
    deduplicationResource[Unit](processorId, maxProcessingTime)
      .use { ps =>
        for {
          _ <- ps.tryStartProcess(id)
          _ <- IO.sleep(maxProcessingTime + 1.second)
          result <- ps.tryStartProcess(id)
        } yield {
          assert(clue(result).isInstanceOf[Outcome.New[IO, Unit]])
        }
      }
  }

  test("Mnemosyne should fail with timeout if maxPoll < maxProcessingTime") {

    val processorId = a[UUID]
    val id = a[UUID]

    val maxProcessingTime = 10.seconds
    val maxPollingtime = 1.second

    deduplicationResource[Unit](processorId, maxProcessingTime, maxPollingtime)
      .use { ps =>
        for {
          _ <- ps.tryStartProcess(id)
          result <- ps.tryStartProcess(id).attempt
        } yield {
          assert(clue(result).isLeft)
          assert(result.left.exists(_.isInstanceOf[TimeoutException]))
        }
      }

  }

  test("Mnemosyne should process only one event out of multiple concurrent events") {

    val processorId = a[UUID]
    val id = a[UUID]

    val n = 120

    deduplicationResource[Int](processorId, maxProcessingTime = 30.seconds)
      .use { d =>
        List
          .fill(math.abs(n))(id)
          .parTraverse { i =>
            d.protect(i, IO(1))
          }
          .map { xs =>
            assertEquals(xs.sum, 1)
          }
      }
  }

  def deduplicationResource[A: DynamoDbEncoder: DynamoDbDecoder](
      processorId: UUID,
      maxProcessingTime: FiniteDuration = 5.seconds,
      maxPollingTime: FiniteDuration = 15.seconds
  ): Resource[IO, Mnemosyne[IO, UUID, UUID, A]] =
    for {
      persistence <- persistenceResource[IO, A]
      config = Config(
        processorId = processorId,
        maxProcessingTime = maxProcessingTime,
        ttl = 1.day.some,
        pollStrategy = PollStrategy.backoff(maxDuration = maxPollingTime)
      )
      mnemosyne <- Resource.eval(
        Mnemosyne[IO, UUID, UUID, A](persistence, config, Slf4jFactory.create[IO])
      )
    } yield mnemosyne

}
