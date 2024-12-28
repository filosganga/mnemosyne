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

import cats.*
import cats.syntax.all.*

import software.amazon.awssdk.services.dynamodb.model._

trait DynamoDbEncoder[A] {
  def write(a: A): AttributeValue
}

object DynamoDbEncoder {
  def apply[A](implicit dd: DynamoDbEncoder[A]): DynamoDbEncoder[A] = dd

  def instance[A](f: A => AttributeValue): DynamoDbEncoder[A] = new DynamoDbEncoder[A] {
    def write(a: A): AttributeValue = f(a)
  }

  def const[A](av: AttributeValue): DynamoDbEncoder[A] = new DynamoDbEncoder[A] {
    def write(a: A): AttributeValue = av
  }

  implicit def contravariantForDynamoDbDecoder: Contravariant[DynamoDbEncoder] =
    new Contravariant[DynamoDbEncoder] {
      def contramap[A, B](fa: DynamoDbEncoder[A])(f: B => A): DynamoDbEncoder[B] =
        new DynamoDbEncoder[B] {
          def write(b: B): AttributeValue = fa.write(f(b))
        }
    }

  implicit def dynamoEncoderForOption[A: DynamoDbEncoder]: DynamoDbEncoder[Option[A]] =
    DynamoDbEncoder.instance { fa =>
      fa.fold(AttributeValue.builder().nul(true).build())(DynamoDbEncoder[A].write)
    }

  implicit val dynamoEncoderForBoolean: DynamoDbEncoder[Boolean] =
    DynamoDbEncoder.instance(bool => AttributeValue.builder().bool(bool).build())

  implicit val dynamoEncoderForString: DynamoDbEncoder[String] =
    DynamoDbEncoder.instance(str => AttributeValue.builder().s(str).build())

  implicit val dynamoEncoderForUUID: DynamoDbEncoder[ju.UUID] =
    DynamoDbEncoder[String].contramap(_.toString)

  implicit val dynamoEncoderForLong: DynamoDbEncoder[Long] =
    DynamoDbEncoder.instance(long => AttributeValue.builder().n(long.toString).build())

  implicit val dynamoEncoderForInt: DynamoDbEncoder[Int] =
    DynamoDbEncoder.instance(int => AttributeValue.builder().n(int.toString).build())

  implicit val dynamoEncoderForInstant: DynamoDbEncoder[Instant] =
    dynamoEncoderForLong.contramap(instant => instant.toEpochMilli())

}
