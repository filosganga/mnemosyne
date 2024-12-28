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
import java.{util => ju}
import scala.concurrent.duration.*

import cats.implicits.*

import org.scalacheck.Prop.*

import model.*
import Generators.*

class ProcessStatusSuite extends munit.ScalaCheckSuite {

  property(
    "processStatus should return Completed if the completedAt is present and expiredOn is None"
  ) {
    forAll { (process: Process[ju.UUID, ju.UUID]) =>

      val now = Instant.now()
      val maxProcessingTime = 5.minutes

      val sample = process.copy(
        completedAt = now.some,
        expiresOn = None
      )

      val status = Mnemosyne.processStatus(maxProcessingTime, now)(sample)

      assertEquals(clue(status), ProcessStatus.Completed)
    }
  }

  property(
    "processStatus should return Completed if the completedAt is present and expiredOn is in the future"
  ) {
    forAll { (process: Process[ju.UUID, ju.UUID]) =>

      val now = Instant.now()
      val maxProcessingTime = 5.minutes

      val sample = process.copy(
        completedAt = now.some,
        expiresOn = Expiration(now.plusMillis(1)).some
      )

      val status = Mnemosyne.processStatus(maxProcessingTime, now)(sample)

      assertEquals(clue(status), ProcessStatus.Completed)
    }
  }

  property(
    "processStatus should return Expired if the expiresOn is present and it is in the past"
  ) {
    forAll { (process: Process[ju.UUID, ju.UUID]) =>

      val now = Instant.now()
      val maxProcessingTime = 5.minutes

      val sample = process.copy(
        expiresOn = Expiration(now.minusMillis(1)).some
      )

      val status = Mnemosyne.processStatus(maxProcessingTime, now)(sample)

      assertEquals(clue(status), ProcessStatus.Expired)
    }
  }

  property(
    "processStatus should return Timeout if startedAt is more than maxProcessingTime in the past, completedAt and expiresOn are None"
  ) {
    forAll { (process: Process[ju.UUID, ju.UUID]) =>

      val now = Instant.now()
      val maxProcessingTime = 5.minutes

      val sample = process.copy(
        startedAt = now.minusMillis(maxProcessingTime.toMillis).minusMillis(1),
        completedAt = None,
        expiresOn = None
      )

      val status = Mnemosyne.processStatus(maxProcessingTime, now)(sample)

      assertEquals(clue(status), ProcessStatus.Timeout)
    }
  }

  property(
    "processStatus should return Timeout if startedAt is more than maxProcessingTime in the past, expiresOn is in the future and completedAt is None"
  ) {
    forAll { (process: Process[ju.UUID, ju.UUID]) =>

      val now = Instant.now()
      val maxProcessingTime = 5.minutes

      val sample = process.copy(
        startedAt = now.minusMillis(maxProcessingTime.toMillis).minusMillis(1),
        completedAt = None,
        expiresOn = Expiration(now.plusMillis(1)).some
      )

      val status = Mnemosyne.processStatus(maxProcessingTime, now)(sample)

      assertEquals(clue(status), ProcessStatus.Timeout)
    }
  }

  property(
    "processStatus returns running if startedAt is less than maxProcessingTime in the past, completedAt and expiresOn are None"
  ) {
    forAll { (process: Process[ju.UUID, ju.UUID]) =>

      val now = Instant.now()
      val maxProcessingTime = 5.minutes

      val sample = process.copy(
        startedAt = now.minusMillis(maxProcessingTime.toMillis).plusMillis(1),
        completedAt = None,
        expiresOn = None
      )

      val status = Mnemosyne.processStatus(maxProcessingTime, now)(sample)

      assertEquals(clue(status), ProcessStatus.Running)
    }
  }

  property(
    "processStatus returns running if startedAt is less than maxProcessingTime in the past,  expiresOn is in the future and completedAt is None"
  ) {
    forAll { (process: Process[ju.UUID, ju.UUID]) =>

      val now = Instant.now()
      val maxProcessingTime = 5.minutes

      val sample = process.copy(
        startedAt = now.minusMillis(maxProcessingTime.toMillis).plusMillis(1),
        completedAt = None,
        expiresOn = Expiration(now.plusMillis(1)).some
      )

      val status = Mnemosyne.processStatus(maxProcessingTime, now)(sample)

      assertEquals(clue(status), ProcessStatus.Running)
    }
  }
}
