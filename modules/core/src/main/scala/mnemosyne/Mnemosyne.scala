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
import scala.concurrent.duration.*
import scala.jdk.DurationConverters.*

import cats.effect.{Async, Clock, Sync, Temporal}
import cats.syntax.all.*
import cats.{Monad, Show}

import org.typelevel.log4cats.LoggerFactory

import model.*

trait Mnemosyne[F[_], Id, ProcessorId, Memoized] {

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
  def tryStartProcess(id: Id): F[Outcome[F, Memoized]]

  /** Do the best effort to ensure a process to be successfully executed only once.
    *
    * If the process has already runned successfully before, it will run the ifDuplicate. Otherwise,
    * it will run the ifNew.
    *
    * The return value is either the result of ifNew or ifDuplicate.
    *
    * @param id
    *   The id of the process to protect
    * @param ifNew
    *   The effect to run if the process is new.
    * @param ifDuplicate
    *   The effect to run if the process is a duplicate.
    * @return
    *   The result of the effect that was run.
    */
  final def protect(id: Id, fa: F[Memoized])(implicit flatMap: Monad[F]): F[Memoized] =
    tryStartProcess(id)
      .flatMap {
        case Outcome.New(markAsComplete) =>
          fa.flatTap(a => markAsComplete(a))
        case Outcome.Duplicate(a) =>
          a.pure[F]
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

  def apply[F[_]: Async, ID: Show, ProcessorID: Show, Memoized](
      repo: Persistence[F, ID, ProcessorID, Memoized],
      config: Config[ProcessorID],
      loggerFactory: LoggerFactory[F]
  ): F[Mnemosyne[F, ID, ProcessorID, Memoized]] = loggerFactory.create.map { loggerTemplate =>

    val logger = loggerTemplate.addContext(Map("mnemosyne.processorId" -> config.processorId.show))

    new Mnemosyne[F, ID, ProcessorID, Memoized] {

      override def tryStartProcess(id: ID): F[Outcome[F, Memoized]] = {

        val pollStrategy = config.pollStrategy

        def processStatus(
            maxProcessingTime: FiniteDuration,
            now: Instant
        )(p: Process[ID, ProcessorID, Memoized]): ProcessStatus = {

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
            ProcessStatus.Completed(p.memoized.get)
          } else if (isTimeout) {
            ProcessStatus.Timeout
          } else {
            ProcessStatus.Running
          }
        }

        def doIt(
            startedAt: Instant,
            pollNo: Int,
            pollDelay: FiniteDuration
        ): F[Outcome[F, Memoized]] = {

          val doItlogger = logger.addContext(
            Map(
              "mnemosyne.id" -> id.show,
              "mnemosyne.startedAt" -> startedAt.toString,
              "mnemosyne.pollNo" -> pollNo.toString
            )
          )

          def nextStep(ps: ProcessStatus): F[Outcome[F, Memoized]] = ps match {
            case ProcessStatus.Running =>
              val totalDurationF = Clock[F].realTimeInstant
                .map(now => (now.toEpochMilli - startedAt.toEpochMilli).milliseconds)

              val stopRetry = doItlogger.warn(s"Process still running, stop retry-ing") >> Sync[F]
                .raiseError[Outcome[F, Memoized]](
                  new TimeoutException(s"Stop polling after ${pollNo} polls")
                )

              val retry = doItlogger.debug(s"Process still running, retry-ing") >>
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
              doItlogger
                .debug(s"Process status is ${ps}, starting now")
                .as {
                  val markAsComplete = (value: Memoized) =>
                    Clock[F].realTimeInstant
                      .flatMap { now =>
                        repo.completeProcess(id, config.processorId, now, config.ttl, value)
                      }
                      .flatTap { _ =>
                        doItlogger.debug(s"Process marked as completed")
                      }

                  Outcome.New(markAsComplete)
                }
                .widen[Outcome[F, Memoized]]

            case ProcessStatus.Completed(memoized) =>
              doItlogger
                .debug(s"Process is duplicated, already completed")
                .as(Outcome.Duplicate(memoized.asInstanceOf[Memoized]))
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
          logger.debug(s"Process invalidated")
        }
      }
    }
  }

}
