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
package dynamodb

import java.time.Instant
import java.{util => ju}
import scala.reflect.ClassTag

import software.amazon.awssdk.services.dynamodb.model._

import org.scalacheck.Arbitrary
import org.scalacheck.Prop._

import Generators._

class DynamoDbCodecSuite extends munit.ScalaCheckSuite {

  checkEncode[String](str => AttributeValue.builder().s(str).build())
  checkEncodeDecode[String]

  checkEncode[Int](int => AttributeValue.builder().n(s"$int").build())
  checkEncodeDecode[Int]

  checkEncode[Long](long => AttributeValue.builder().n(s"$long").build())
  checkEncodeDecode[Long]

  checkEncode[ju.UUID](uuid => AttributeValue.builder().s(uuid.toString).build())
  checkEncodeDecode[ju.UUID]

  checkEncode[Instant](instant => AttributeValue.builder().n(s"${instant.toEpochMilli()}").build())
  checkEncodeDecode[Instant]

  def checkEncode[T: Arbitrary: DynamoDbEncoder](
      expected: T => AttributeValue
  )(implicit loc: munit.Location, classTag: ClassTag[T]): Unit = {
    property(s"should encode ${classTag.runtimeClass}") {
      forAll { (t: T) =>
        val result = DynamoDbEncoder[T].write(t)
        assertEquals(result, expected(t))
      }
    }
  }

  def checkEncodeDecode[T: Arbitrary: DynamoDbEncoder: DynamoDbDecoder](implicit
      loc: munit.Location,
      classTag: ClassTag[T]
  ): Unit = {
    property(s"should encode/decode ${classTag.runtimeClass}") {
      forAll { (t: T) =>
        val result = DynamoDbDecoder[T].read(DynamoDbEncoder[T].write(t))
        assertEquals(result, Right(t))
      }
    }
  }

}
