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

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.*

import Config.*

/** Configure the library
  *
  * The [[PollStrategy]] controls the polling for waiting for a started process to complete or
  * timeout. For this reason is important for the pollStrategy.maxPollDuration to be >
  * maxProcessingTime otherwise the poll will always timeout in case of a stale process.
  *
  * @param tableName
  * @param processorId
  * @param maxProcessingTime
  * @param ttl
  * @param pollStrategy
  */
case class Config[ProcessorId](
    processorId: ProcessorId,
    maxProcessingTime: FiniteDuration,
    ttl: Option[FiniteDuration],
    pollStrategy: PollStrategy
)

object Config {

  trait PollStrategy {
    def maxPollDuration: FiniteDuration
    def initialDelay: FiniteDuration
    def nextDelay(pollNo: Int, previousDelay: FiniteDuration): FiniteDuration
  }

  object PollStrategy {

    def linear(
        delay: FiniteDuration = 50.milliseconds,
        maxDuration: FiniteDuration = 3.seconds
    ) = new PollStrategy {
      def maxPollDuration = maxDuration
      def initialDelay = delay
      def nextDelay(pollNo: Int, previousDelay: FiniteDuration) = delay
    }

    def backoff(
        baseDelay: FiniteDuration = 50.milliseconds,
        multiplier: Double = 1.5d,
        maxDuration: FiniteDuration = 3.seconds
    ) = new PollStrategy {
      def maxPollDuration = maxDuration
      def initialDelay = baseDelay
      def nextDelay(pollNo: Int, previousDelay: FiniteDuration) =
        FiniteDuration((previousDelay.toMillis * multiplier).toLong, TimeUnit.MILLISECONDS)
    }
  }

}
