package com.filippodeluca.mnemosyne

import com.filippodeluca.mnemosyne.meteor.model._

package object meteor {
  type MeteorDeduplication[F[_], ID, ContextID] = Deduplication[F, ID, ContextID, EncodedResult]
}
