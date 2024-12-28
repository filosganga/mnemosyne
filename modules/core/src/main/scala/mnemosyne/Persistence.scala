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
import memoization.*

trait Persistence[F[_], Id, ProcessorId, MemoizedFormat] {

  def startProcessingUpdate(
      id: Id,
      processorId: ProcessorId,
      now: Instant
  ): F[Option[Process[Id, ProcessorId]]]

  def completeProcess(
      id: Id,
      processorId: ProcessorId,
      now: Instant,
      ttl: Option[FiniteDuration]
  ): F[Unit]

  def completeProcessWithMemoization[A](
      id: Id,
      processorId: ProcessorId,
      now: Instant,
      ttl: Option[FiniteDuration],
      memoized: A
  )(implicit memoizedEncoder: MemoizedEncoder[A, MemoizedFormat]): F[Unit]

  def getMemoizedValue[A](
      id: Id,
      processorId: ProcessorId
  )(implicit memoizedDecoder: MemoizedDecoder[A, MemoizedFormat]): F[Option[A]]

  def invalidateProcess(
      id: Id,
      processorId: ProcessorId
  ): F[Unit]
}
