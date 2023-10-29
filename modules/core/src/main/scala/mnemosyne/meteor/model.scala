package com.filippodeluca.mnemosyne.meteor
package model

import software.amazon.awssdk.services.dynamodb.model.AttributeValue

case class EncodedResult(value: AttributeValue)
