/*
 * This file is part of AckCord, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2019 Katrix
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
package ackcord.gateway

import scala.concurrent.duration._
import scala.concurrent.{Future, Promise}

import ackcord.gateway.GatewayProtocol._
import ackcord.util.AckCordGatewaySettings
import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.event.Logging
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.ws.{BinaryMessage, Message, TextMessage, WebSocketUpgradeResponse}
import akka.stream._
import akka.stream.scaladsl.{Compression, Flow, GraphDSL, Keep, Merge}
import akka.stream.stage._
import akka.util.ByteString
import cats.syntax.all._
import io.circe
import io.circe.{Encoder, parser}
import io.circe.syntax._

class GatewayHandlerGraphStage(settings: GatewaySettings, prevResume: Option[ResumeData])
    extends GraphStageWithMaterializedValue[
      FanOutShape2[GatewayMessage[_], GatewayMessage[_], GatewayMessage[_]],
      (Future[(Option[ResumeData], Boolean)], Future[Unit])
    ] {
  val in: Inlet[GatewayMessage[_]]           = Inlet("GatewayHandlerGraphStage.in")
  val dispatchOut: Outlet[GatewayMessage[_]] = Outlet("GatewayHandlerGraphStage.dispatchOut")

  val out: Outlet[GatewayMessage[_]] = Outlet("GatewayHandlerGraphStage.out")

  override def shape: FanOutShape2[GatewayMessage[_], GatewayMessage[_], GatewayMessage[_]] =
    new FanOutShape2(in, out, dispatchOut)

  override def createLogicAndMaterializedValue(
      inheritedAttributes: Attributes
  ): (GraphStageLogic, (Future[(Option[ResumeData], Boolean)], Future[Unit])) = {
    val resumePromise          = Promise[(Option[ResumeData], Boolean)]
    val successullStartPromise = Promise[Unit]

    val logic = new TimerGraphStageLogicWithLogging(shape) with InHandler with OutHandler {
      var resume: ResumeData = prevResume.orNull
      var receivedAck        = false

      val HeartbeatTimerKey: String = "HeartbeatTimer"

      def restart(resumable: Boolean, waitBeforeRestart: Boolean): Unit = {
        resumePromise.success(if (resumable) (Some(resume), waitBeforeRestart) else (None, waitBeforeRestart))
        completeStage()
      }

      def handleHello(data: HelloData): Unit = {
        val response = prevResume match {
          case Some(resumeData) => Resume(resumeData)
          case None =>
            val identifyObject = IdentifyData(
              token = settings.token,
              properties = IdentifyData.createProperties,
              compress = true,
              largeThreshold = settings.largeThreshold,
              shard = Seq(settings.shardNum, settings.shardTotal),
              presence = StatusData(settings.idleSince, settings.activity, settings.status, afk = settings.afk),
              guildSubscriptions = settings.guildSubscriptions,
              intents = settings.intents
            )

            Identify(identifyObject)
        }

        push(out, response)

        receivedAck = true
        scheduleAtFixedRate(HeartbeatTimerKey, 0.millis, data.heartbeatInterval.millis)
      }

      override def onPush(): Unit = {
        val event = grab(in)

        event match {
          case Hello(data) => handleHello(data)
          case Dispatch(seq, event) =>
            event match {
              case GatewayEvent.Ready(_, _) | GatewayEvent.Resumed(_) =>
                successullStartPromise.success(())
              case _ =>
            }

            resume = event match {
              case GatewayEvent.Ready(_, readyData) =>
                readyData.value match {
                  case Right(ready) => ResumeData(settings.token, ready.sessionId, seq)
                  case Left(e) =>
                    log.error(e, "Failed to decode ready event. Stuff will probably break on resume")
                    null
                }

              case _ =>
                if (resume != null) {
                  resume.copy(seq = seq)
                } else null
            }

          case Heartbeat(_) => onTimer(HeartbeatTimerKey)
          case HeartbeatACK =>
            log.debug("Received HeartbeatACK")
            receivedAck = true
          case Reconnect                 => restart(resumable = true, waitBeforeRestart = false)
          case InvalidSession(resumable) => restart(resumable, waitBeforeRestart = true)
          case _                         => //Ignore
        }

        emit(dispatchOut, event)

        if (!hasBeenPulled(in) && !isClosed(in)) pull(in)
      }

      override def onUpstreamFinish(): Unit = {
        if (!resumePromise.isCompleted) {
          resumePromise.trySuccess((Option(resume), false))
        }

        super.onUpstreamFinish()
      }

      override def onUpstreamFailure(ex: Throwable): Unit = {
        resumePromise.failure(ex)
        successullStartPromise.tryFailure(ex)
        super.onUpstreamFailure(ex)
      }

      override def onDownstreamFinish(cause: Throwable): Unit = {
        if (!resumePromise.isCompleted) resumePromise.trySuccess((Option(resume), false))

        super.onDownstreamFinish(cause)
      }

      override protected def onTimer(timerKey: Any): Unit = {
        timerKey match {
          case HeartbeatTimerKey =>
            if (receivedAck) {
              log.debug("Sending heartbeat")
              emit(out, Heartbeat(Option(resume).map(_.seq)))
            } else {
              val e = new IllegalStateException("Did not receive HeartbeatACK between heartbeats")
              fail(out, e)
              resumePromise.failure(e)
              successullStartPromise.tryFailure(e)
            }

        }
      }

      setHandler(in, this)

      override def onPull(): Unit = if (!hasBeenPulled(in)) pull(in)

      override def postStop(): Unit = {
        val e = new AbruptStageTerminationException(this)
        if (!resumePromise.isCompleted) resumePromise.tryFailure(e)
        if (!successullStartPromise.isCompleted) successullStartPromise.tryFailure(e)
      }

      setHandler(out, this)
      setHandler(dispatchOut, this)
    }

    (logic, (resumePromise.future, successullStartPromise.future))
  }
}
object GatewayHandlerGraphStage {

  def flow(wsUri: Uri, settings: GatewaySettings, prevResume: Option[ResumeData])(
      implicit system: ActorSystem[Nothing]
  ): Flow[GatewayMessage[_], GatewayMessage[
    _
  ], (Future[WebSocketUpgradeResponse], Future[(Option[ResumeData], Boolean)], Future[Unit])] = {
    val msgFlow =
      createMessage
        .viaMat(wsFlow(wsUri))(Keep.right)
        .viaMat(parseMessage)(Keep.left)
        .collect {
          case Right(msg) => msg
          case Left(e)    => throw new GatewayJsonException(e.show, e)
        }
        .named("GatewayMessageProcessing")

    val wsGraphStage = new GatewayHandlerGraphStage(settings, prevResume).named("GatewayLogic")

    val graph = GraphDSL.create(msgFlow, wsGraphStage)(Keep.both) {
      implicit builder => (msgFlowShape, wsHandlerShape) =>
        import GraphDSL.Implicits._

        val wsMessages = builder.add(Merge[GatewayMessage[_]](2, eagerComplete = true))

        // format: OFF

        msgFlowShape.out ~> wsHandlerShape.in
                            wsHandlerShape.out0 ~> wsMessages.in(1)
        msgFlowShape.in                         <~ wsMessages.out

        // format: ON

        FlowShape(wsMessages.in(0), wsHandlerShape.out1)
    }

    Flow.fromGraph(graph).mapMaterializedValue(t => (t._1, t._2._1, t._2._2))
  }

  /**
    * Turn a websocket [[akka.http.scaladsl.model.ws.Message]] into a [[GatewayMessage]].
    */
  def parseMessage(
      implicit system: ActorSystem[Nothing]
  ): Flow[Message, Either[circe.Error, GatewayMessage[_]], NotUsed] = {
    val jsonFlow = Flow[Message]
      .collect {
        case t: TextMessage => t.textStream
        case b: BinaryMessage =>
          b.dataStream
            .fold(ByteString.empty)(_ ++ _)
            .via(Compression.inflate())
            .map(_.utf8String)
      }
      .flatMapConcat(_.fold("")(_ + _))

    val withLogging = if (AckCordGatewaySettings().LogReceivedWs) {
      jsonFlow.log("Received payload").withAttributes(Attributes.logLevels(onElement = Logging.DebugLevel))
    } else jsonFlow

    withLogging.map(parser.parse(_).flatMap(_.as[GatewayMessage[_]]))
  }

  /**
    * Turn a [[GatewayMessage]] into a websocket [[akka.http.scaladsl.model.ws.Message]].
    */
  def createMessage(implicit system: ActorSystem[Nothing]): Flow[GatewayMessage[_], Message, NotUsed] = {
    val flow = Flow[GatewayMessage[_]].map {
      case msg: GatewayMessage[d] =>
        msg match {
          case StatusUpdate(data) => data.game.foreach(_.requireCanSend())
          case _                  =>
        }

        val json = msg.asJson(wsMessageEncoder.asInstanceOf[Encoder[GatewayMessage[d]]]).noSpaces
        require(json.getBytes.length < 4096, "Can only send at most 4096 bytes in a message over the gateway")
        TextMessage(json)
    }

    if (AckCordGatewaySettings().LogSentWs) flow.log("Sending payload", _.text) else flow
  }

  private def wsFlow(
      wsUri: Uri
  )(implicit system: ActorSystem[Nothing]): Flow[Message, Message, Future[WebSocketUpgradeResponse]] = {
    import akka.actor.typed.scaladsl.adapter._
    Http(system.toClassic).webSocketClientFlow(wsUri)
  }
}
