package com.kaluza.mnemosyne

trait ResultCodec[A, B] {
  def read(a: A): Either[Throwable, B]
  def write(b: B): Either[Throwable, A]
}
