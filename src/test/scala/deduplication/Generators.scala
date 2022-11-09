package com.kaluza.mnemosyne

import java.time.Instant
import java.time.temporal.ChronoUnit

import org.scalacheck.Arbitrary._
import org.scalacheck._

import model._

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
