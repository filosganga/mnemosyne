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

/** The outcome of starting a process.
  *
  * It should be either New or Duplicate. The New has a markAsComplete member that should be used to
  * mark the process as complete after it has succeeded
  */
sealed trait Outcome[F[_], A]
object Outcome {
  case class New[F[_], A](completeProcess: A => F[Unit]) extends Outcome[F, A]
  case class Duplicate[F[_], A](value: A) extends Outcome[F, A]
}

sealed trait ProcessStatus
object ProcessStatus {
  case object NotStarted extends ProcessStatus
  case object Running extends ProcessStatus
  case class Completed[A](memoized: A) extends ProcessStatus
  case object Timeout extends ProcessStatus
  case object Expired extends ProcessStatus
}

case class Expiration(instant: Instant)
case class Process[Id, ProcessorId, A](
    id: Id,
    processorId: ProcessorId,
    startedAt: Instant,
    completedAt: Option[Instant],
    expiresOn: Option[Expiration],
    memoized: Option[A]
)
