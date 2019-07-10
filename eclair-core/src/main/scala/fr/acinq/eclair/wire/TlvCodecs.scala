/*
 * Copyright 2019 ACINQ SAS
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

package fr.acinq.eclair.wire

import fr.acinq.eclair
import fr.acinq.eclair.UInt64
import fr.acinq.eclair.wire.CommonCodecs._
import scodec.codecs._
import scodec.{Attempt, Codec, Err}

/**
  * Created by t-bast on 20/06/2019.
  */

object TlvCodecs {

  private val genericTlv: Codec[GenericTlv] = (("type" | varint) :: variableSizeBytesLong(varintoverflow, bytes)).as[GenericTlv]

  private def tlvFallback(codec: DiscriminatorCodec[Tlv, UInt64]): Codec[Tlv] = discriminatorFallback(genericTlv, codec).xmap({
    case Left(l) => l
    case Right(r) => r
  }, {
    case g: GenericTlv => Left(g)
    case o => Right(o)
  })

  def tag(codec: DiscriminatorCodec[Tlv, UInt64], record: Tlv): UInt64 = record match {
    case generic: GenericTlv => generic.`type`
    case _ => codec.encode(record).flatMap(bits => varint.decode(bits)).require.value
  }

  def validateStream(codec: DiscriminatorCodec[Tlv, UInt64], records: Seq[Tlv]): Either[Err, Option[eclair.UInt64]] = {
    records.foldLeft(Right(None): Either[Err, Option[eclair.UInt64]]) {
      case (l@Left(_), _) =>
        l
      case (_, record: GenericTlv) if record.`type`.toBigInt % 2 == 0 =>
        Left(Err("tlv streams must not contain unknown even tlv types"))
      case (Right(None), record: Tlv) =>
        Right(Some(tag(codec, record)))
      case (Right(Some(previousTag)), record: Tlv) =>
        val currentTag = tag(codec, record)
        if (currentTag == previousTag) {
          Left(Err("tlv streams must not contain duplicate records"))
        } else if (currentTag < previousTag) {
          Left(Err("tlv records must be ordered by monotonically-increasing types"))
        } else {
          Right(Some(currentTag))
        }
    }
  }

  /**
    * A tlv stream codec relies on an underlying tlv codec.
    * This allows tlv streams to have different namespaces, increasing the total number of tlv types available.
    *
    * @param codec codec used for the tlv records contained in the stream.
    */
  def tlvStream(codec: DiscriminatorCodec[Tlv, UInt64]): Codec[TlvStream] = list(tlvFallback(codec)).exmap(
    records => {
      validateStream(codec, records) match {
        case Left(err) => Attempt.failure(err)
        case _ => Attempt.successful(TlvStream(records))
      }
    },
    stream =>
      validateStream(codec, stream.records) match {
        case Left(err) => Attempt.failure(err)
        case _ => Attempt.successful(stream.records.toList)
      }
  )

  /**
    * When used inside a message, a tlv stream needs to specify its length.
    * Note that some messages will have an independent length field and won't need this codec.
    */
  def lengthPrefixedTlvStream(codec: DiscriminatorCodec[Tlv, UInt64]): Codec[TlvStream] = variableSizeBytesLong(CommonCodecs.varintoverflow, tlvStream(codec))

}
