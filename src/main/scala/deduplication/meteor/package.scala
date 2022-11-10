package com.kaluza.mnemosyne

import com.kaluza.mnemosyne.meteor.model._

package object meteor {
  type MeteorDeduplication[F[_], ID, ContextID] = Deduplication[F, ID, ContextID, EncodedResult]
}
