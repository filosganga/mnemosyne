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
import java.util.concurrent.TimeoutException
import scala.jdk.DurationConverters.*
import scala.concurrent.duration.*

import cats.FlatMap
import cats.effect.{Async, Clock, Sync, Temporal}
import cats.syntax.all.*

import model.*
import org.typelevel.log4cats.LoggerFactory

trait Mnemosyne[F[_], Id, ProcessorId] {

  /** Try to start a process.
    *
    * If the process with the giving id has never started before, this will start a new process and
    * return Outcome.New If another process with the same id has already completed, this will do
    * nothing and return Outcome.Duplicate If another process with the same id has already started
    * and timeouted, this will start a new process and return Outcome.New If another process with
    * the same id is still running, this will wait until it will complete or timeout and return
    * Outcome.Duplicate or Outcome.New
    *
    * If markAsComplete fails, the process will likely be duplicated. If the process takes more time
    * than maxProcessingTime, you may have duplicate if two processes with same ID happen at the
    * same time
    *
    * eg
    * ```
    * tryStartProcess(id)
    *   .flatMap {
    *     case Outcome.New(markAsComplete) =>
    *       doYourStuff.flatTap(_ => markAsComplete)
    *     case Outcome.Duplicate() =>
    *       dontDoYourStuff
    *   }
    * ```
    *
    * @param id
    *   The process id to start
    * @return
    *   An Outcome.New or Outcome.Duplicate. The Outcome.New will contain an effect to complete the
    *   just started process.
    */
  def tryStartProcess(id: Id): F[Outcome[F]]

  /** Do the best effort to ensure a process to be successfully executed only once.
    *
    * If the process has already runned successfully before, it will run the [[ifDuplicate]].
    * Otherwise, it will run the [[ifNew]].
    *
    * The return value is either the result of [[ifNew]] or [[ifDuplicate]].
    *
    * @param id
    *   The id of the process to run.
    * @param ifNew
    *   The effect to run if the process is new.
    * @param ifDuplicate
    *   The effect to run if the process is duplicate.
    * @return
    *   the result of [[ifNew]] or [[ifDuplicate]].
    */
  final def protect[A](id: Id, ifNew: F[A], ifDuplicate: F[A])(implicit flatMap: FlatMap[F]): F[A] =
    tryStartProcess(id)
      .flatMap {
        case Outcome.New(markAsComplete) =>
          ifNew.flatTap(_ => markAsComplete)
        case Outcome.Duplicate() =>
          ifDuplicate
      }

  /** Invalidate a process.
    *
    * If the process exists, the record is deleted.
    *
    * @param id
    *   The process id to invalidate
    * @return
    *   Unit
    */
  def invalidate(id: Id): F[Unit]
}

object Mnemosyne {

  def processStatus(
      maxProcessingTime: FiniteDuration,
      now: Instant
  )(p: Process[?, ?]): ProcessStatus = {

    val isCompleted =
      p.completedAt.isDefined

    val isExpired = p.expiresOn
      .map(_.instant)
      .exists(_.isBefore(now))

    val isTimeout = p.startedAt
      .plus(maxProcessingTime.toJava)
      .isBefore(now)

    if (isExpired) {
      ProcessStatus.Expired
    } else if (isCompleted) {
      ProcessStatus.Completed
    } else if (isTimeout) {
      ProcessStatus.Timeout
    } else {
      ProcessStatus.Running
    }
  }

  def apply[F[_]: Async, ID, ProcessorID](
      repo: Persistence[F, ID, ProcessorID],
      config: Config[ProcessorID],
      loggerFactory: LoggerFactory[F]
  ): F[Mnemosyne[F, ID, ProcessorID]] = loggerFactory.create.map { logger =>
    new Mnemosyne[F, ID, ProcessorID] {

      override def tryStartProcess(id: ID): F[Outcome[F]] = {

        val pollStrategy = config.pollStrategy

        def doIt(
            startedAt: Instant,
            pollNo: Int,
            pollDelay: FiniteDuration
        ): F[Outcome[F]] = {

          def logContext =
            s"processorId=${config.processorId}, id=${id}, startedAt=${startedAt}, pollNo=${pollNo}"

          def nextStep(ps: ProcessStatus): F[Outcome[F]] = ps match {
            case ProcessStatus.Running =>
              val totalDurationF = Clock[F].realTimeInstant
                .map(now => (now.toEpochMilli - startedAt.toEpochMilli).milliseconds)

              val stopRetry = logger.warn(
                s"Process still running, stop retry-ing ${logContext}"
              ) >> Sync[F]
                .raiseError[Outcome[F]](new TimeoutException(s"Stop polling after ${pollNo} polls"))

              val retry = logger.debug(
                s"Process still running, retry-ing ${logContext}"
              ) >>
                Temporal[F].sleep(pollDelay) >>
                doIt(
                  startedAt,
                  pollNo + 1,
                  config.pollStrategy.nextDelay(pollNo, pollDelay)
                )

              // retry until it is either Completed or Timeout
              totalDurationF
                .map(td => td >= pollStrategy.maxPollDuration)
                .ifM(stopRetry, retry)

            case ProcessStatus.NotStarted | ProcessStatus.Timeout | ProcessStatus.Expired =>
              logger
                .debug(
                  s"Process status is ${ps}, starting now ${logContext}"
                )
                .as {
                  Outcome.New(
                    Clock[F].realTimeInstant.flatMap { now =>
                      repo.completeProcess(id, config.processorId, now, config.ttl).flatTap { _ =>
                        logger.debug(
                          s"Process marked as completed ${logContext}"
                        )
                      }
                    }
                  )
                }
                .widen[Outcome[F]]

            case ProcessStatus.Completed =>
              logger
                .debug(
                  s"Process is duplicated processorId=${config.processorId}, id=${id}, startedAt=${startedAt}"
                )
                .as(Outcome.Duplicate())
          }

          for {
            now <- Clock[F].realTimeInstant
            processOpt <- repo.startProcessingUpdate(id, config.processorId, now)
            status = processOpt
              .fold[ProcessStatus](ProcessStatus.NotStarted) { p =>
                processStatus(config.maxProcessingTime, now)(p)
              }
            sample <- nextStep(status)
          } yield sample
        }

        Clock[F].realTimeInstant.flatMap(now => doIt(now, 0, pollStrategy.initialDelay))
      }

      override def invalidate(id: ID): F[Unit] = {
        repo.invalidateProcess(id, config.processorId).flatTap { _ =>
          logger.debug(
            s"Process invalidated processorId=${config.processorId}, id=$id"
          )
        }
      }
    }
  }

}
