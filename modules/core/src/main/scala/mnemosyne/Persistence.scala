package com.filippodeluca.mnemosyne

import java.time.Instant
import scala.concurrent.duration.FiniteDuration

import model.*

trait Persistence[F[_], Id, ProcessorId] {

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

  def invalidateProcess(
      id: Id,
      processorId: ProcessorId
  ): F[Unit]
}
