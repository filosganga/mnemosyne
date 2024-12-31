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
import scala.concurrent.duration.FiniteDuration

import model.*

trait Persistence[F[_], Id, ProcessorId, A] {

  def startProcessingUpdate(
      id: Id,
      processorId: ProcessorId,
      now: Instant
  ): F[Option[Process[Id, ProcessorId, A]]]

  def completeProcess(
      id: Id,
      processorId: ProcessorId,
      now: Instant,
      ttl: Option[FiniteDuration],
      value: A
  ): F[Unit]

  def invalidateProcess(
      id: Id,
      processorId: ProcessorId
  ): F[Unit]
}