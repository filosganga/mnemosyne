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

package com.filippodeluca.mnemosyne.model

import java.time.Instant

sealed trait MnemosyneError extends Throwable {
  def message: String
  def cause: Option[Throwable]
}

object MnemosyneError {
  case class Timeout(message: String, cause: Option[Throwable] = None) extends MnemosyneError
  case class MemoizedEncodingError(message: String, cause: Option[Throwable] = None)
      extends MnemosyneError
  case class MemoizedDecodingError(message: String, cause: Option[Throwable] = None)
      extends MnemosyneError
}

/** The outcome of starting a process.
  *
  * It should be either New or Duplicate. The New has a markAsComplete member that should be used to
  * mark the process as complete after it has succeeded
  */
sealed trait Outcome[F[_]]
object Outcome {
  case class New[F[_]](
      completeProcess: F[Unit]
  ) extends Outcome[F]

  case class Duplicate[F[_]]() extends Outcome[F]
}

/** The outcome of starting a process.
  *
  * It should be either New or Duplicate. The New has a markAsComplete member that should be used to
  * mark the process as complete after it has succeeded
  */
sealed trait MemoizedOutcome[F[_], A]
object MemoizedOutcome {
  case class New[F[_], A](
      completeProcess: A => F[Unit]
  ) extends MemoizedOutcome[F, A]

  case class Duplicate[F[_], A](
      value: F[Option[A]]
  ) extends MemoizedOutcome[F, A]
}

sealed trait ProcessStatus
object ProcessStatus {
  case object NotStarted extends ProcessStatus
  case object Running extends ProcessStatus
  case object Completed extends ProcessStatus
  case object Timeout extends ProcessStatus
  case object Expired extends ProcessStatus
}

case class Expiration(instant: Instant)
case class Process[Id, ProcessorId](
    id: Id,
    processorId: ProcessorId,
    startedAt: Instant,
    completedAt: Option[Instant],
    expiresOn: Option[Expiration]
)
