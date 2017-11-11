/*
 * This file is part of AckCord, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2017 Katrix
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package net.katsstuff.ackcord

import akka.actor.ActorRef
import akka.event.LoggingAdapter
import net.katsstuff.ackcord.handlers.{CacheHandler, CacheSnapshotBuilder}

/**
  * Represents some sort of event handled by the cache
  *
  * @tparam Data The data it contains
  */
sealed trait CacheHandlerEvent[Data] {

  /**
    * The data to update
    */
  def data: Data

  /**
    * A handler for the data
    */
  def handler: CacheHandler[Data]

  /**
    * Updates a [[net.katsstuff.ackcord.handlers.CacheSnapshotBuilder]] with the data in this object.
    */
  def handle(builder: CacheSnapshotBuilder)(implicit log: LoggingAdapter): Unit =
    handler.handle(builder, data)
}

/**
  * An event that should publish an [[APIMessage]].
  * @param data The data.
  * @param sendEvent A function to gather the needed variables to send the
  *                  event. The [[net.katsstuff.ackcord.data.CacheSnapshot]]s passed is the current, and
  *                  previous snapshot, in that order.
  * @param handler The handler to process the data of this event with.
  * @tparam Data The data it contains.
  */
case class APIMessageHandlerEvent[Data](
    data: Data,
    sendEvent: CacheState => Option[APIMessage],
    handler: CacheHandler[Data]
) extends CacheHandlerEvent[Data]

/**
  * An event where after the data has been processed, it's sent to a receiver.
  * @param data The data.
  * @param handler The handler to process the data of this event with.
  * @param findData Find the data to send.
  * @param sendTo The receiver of the handled data.
  * @tparam Data The data it contains.
  */
case class SendHandledDataEvent[Data](
    data: Data,
    handler: CacheHandler[Data],
    findData: CacheState => Option[Any],
    sendTo: ActorRef
) extends CacheHandlerEvent[Data]

/**
  * Any other event that updates the cache with it's data.
  * @param data The data.
  * @param handler The handler to process the data of this event with.
  * @tparam Data The data it contains.
  */
case class MiscHandlerEvent[Data](data: Data, handler: CacheHandler[Data]) extends CacheHandlerEvent[Data]
