package com.filippodeluca.mnemosyne
package meteor

import java.time.Instant
import java.{util => ju}
import scala.reflect.ClassTag

import _root_.meteor.codec.Codec
import _root_.meteor.syntax._

import org.scalacheck.Arbitrary
import org.scalacheck.Prop._

import com.filippodeluca.mnemosyne.Generators._
import com.filippodeluca.mnemosyne.meteor.codecs._
import com.filippodeluca.mnemosyne.meteor.model.EncodedResult
import com.filippodeluca.mnemosyne.model._

class MeteorCodecSuite extends munit.ScalaCheckSuite {

  checkEncodeDecode[String]
  checkEncodeDecode[Int]
  checkEncodeDecode[Long]
  checkEncodeDecode[ju.UUID]
  checkEncodeDecode[Instant]

  def checkEncodeDecode[T: Arbitrary: Codec](
      implicit loc: munit.Location,
      classTag: ClassTag[T]
  ): Unit = {
    property(s"should encode/decode ${classTag.runtimeClass}") {
      val codec = Codec[Process[T, T, EncodedResult]]
      forAll { proc: Process[T, T, T] =>
        val encProc: Process[T, T, EncodedResult] = proc.copy(
          result = proc.result.map(v => EncodedResult(v.asAttributeValue))
        )
        val result = codec.read(codec.write(encProc))
        assertEquals(result, Right(encProc))
      }
    }
  }

}
