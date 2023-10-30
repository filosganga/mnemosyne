package com.kaluza.mnemosyne
package meteor

import cats.effect._

import _root_.meteor.codec.Codec
import _root_.meteor.{Client, CompositeKeysTable}

import com.kaluza.mnemosyne.meteor.model._

object MeteorDeduplication {

  def apply[F[_]: Async, ID: Codec, ContextID: Codec](
      client: Client[F],
      table: CompositeKeysTable[ID, ContextID],
      config: Config
  ): F[MeteorDeduplication[F, ID, ContextID]] =
    Deduplication(
      MeteorProcessRepo(client, table, false),
      config
    )

  def resource[F[_]: Async, ID: Codec, ContextID: Codec](
      client: Client[F],
      table: CompositeKeysTable[ID, ContextID],
      config: Config
  ): Resource[F, MeteorDeduplication[F, ID, ContextID]] =
    Resource.eval[F, Deduplication[F, ID, ContextID, EncodedResult]](apply(client, table, config))

}