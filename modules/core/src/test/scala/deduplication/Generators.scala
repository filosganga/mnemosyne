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

import org.scalacheck.Arbitrary.*
import org.scalacheck.*

import model.*

object Generators {

  implicit val genForInstant: Gen[Instant] = for {
    ms <- Gen.posNum[Long]
  } yield Instant.ofEpochMilli(ms)

  implicit val genForExpiration: Gen[Expiration] = for {
    instant <- arbitrary[Instant]
  } yield Expiration(instant)

  implicit def genForProcess[Id: Arbitrary, ProcessorId: Arbitrary]: Gen[Process[Id, ProcessorId]] =
    for {
      id <- arbitrary[Id]
      processorId <- arbitrary[ProcessorId]
      startedAt <- arbitrary[Instant]
      completedAt <- Gen.option(Gen.choose(500L, 5000L).map(n => startedAt.plusMillis(n)))
      expiresOn <- Gen.option(
        Gen.choose(7L, 90L).map(n => startedAt.plus(n, ChronoUnit.DAYS)).map(Expiration(_))
      )
    } yield Process[Id, ProcessorId](
      id = id,
      processorId = processorId,
      startedAt = startedAt,
      completedAt = completedAt,
      expiresOn = expiresOn
    )

  implicit def genForProcessStatus: Gen[ProcessStatus] = Gen.oneOf(
    ProcessStatus.Completed,
    ProcessStatus.Expired,
    ProcessStatus.NotStarted,
    ProcessStatus.Running,
    ProcessStatus.Timeout
  )

  implicit def arbForGen[A](implicit gen: Gen[A]): Arbitrary[A] = Arbitrary(gen)
}
