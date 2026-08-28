package com.filippodeluca.mnemosyne
package dynamodb

class UnitRoundTripSuite extends munit.FunSuite {
  test("Option[Unit] survives a round trip") {
    val someUnit = DynamoDbEncoder[Option[Unit]].write(Some(()))
    val noneUnit = DynamoDbEncoder[Option[Unit]].write(None)

    assertNotEquals(someUnit, noneUnit, "Some(()) and None must not encode identically")
    assertEquals(DynamoDbDecoder[Option[Unit]].read(someUnit), Right(Some(())))
    assertEquals(DynamoDbDecoder[Option[Unit]].read(noneUnit), Right(None))
  }
}
