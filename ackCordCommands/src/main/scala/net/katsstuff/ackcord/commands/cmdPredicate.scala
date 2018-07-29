/*
 * This file is part of AckCord, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2018 Katrix
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
package net.katsstuff.ackcord.commands

import scala.language.higherKinds

import cats.Monad
import cats.data.EitherT
import cats.syntax.all._
import net.katsstuff.ackcord.CacheSnapshot

/**
  * An object used to refine [[RawCmd]] into [[Cmd]], or return errors instead.
  */
trait CmdRefiner[F[_]] {

  /**
    * Refines the raw command object to a command object if a command should run,
    * if it should not return, an error object may be returned instead.
    * @param raw The raw command object.
    */
  def refine(raw: RawCmd[F]): EitherT[F, Option[CmdMessage[F] with CmdError[F]], Cmd[F]]
}

/**
  * A [[CmdRefiner]] which works on the normal structure of a command. A prefix
  * at the start, then an alias. Also supports [[CmdFilter]]s.
  */
abstract class AbstractCmdInfo[F[_]: Monad] extends CmdRefiner[F] {

  /**
    * Get the prefix to use for the given raw command.
    */
  def prefix(raw: RawCmd[F]): F[String]

  /**
    * Get the valid aliases for the given raw command.
    */
  def aliases(raw: RawCmd[F]): F[Seq[String]]

  /**
    * Get the filters to use for the given raw command.
    */
  def filters(raw: RawCmd[F]): F[Seq[CmdFilter]]

  override def refine(raw: RawCmd[F]): EitherT[F, Option[CmdMessage[F] with CmdError[F]], Cmd[F]] = {
    val canRun = prefix(raw)
      .map(_ == raw.prefix)
      .map2(aliases(raw).map(_.contains(raw.cmd)))(_ && _)

    lazy val shouldRun = filters(raw).flatMap { filters =>
      import cats.instances.list._
      implicit val cache: CacheSnapshot[F] = raw.c
      val ret = filters.toList.traverse(filter => filter.isAllowed(raw.msg).map(_ -> filter)).map { processedFilters =>
        val filtersNotPassed = processedFilters.collect {
          case (passed, filter) if !passed => filter
        }
        if (filtersNotPassed.isEmpty) Right(Cmd(raw.msg, raw.args, raw.c))
        else Left(Some(FilteredCmd(filtersNotPassed, raw)): Option[CmdMessage[F] with CmdError[F]])
      }

      ret
    }

    EitherT(canRun.map(b => Either.cond(b, (), None))).flatMapF(_ => shouldRun)
  }
}

/**
  * A [[CmdRefiner]] that can be used when the prefix, aliases, and filters for a
  * command are known in advance.
  * @param prefix The prefix to use for the command.
  * @param aliases The aliases to use for the command.
  * @param filters The filters to use for the command.
  */
case class CmdInfo[F[_]: Monad](
    prefix: String,
    aliases: Seq[String],
    filters: Seq[CmdFilter] = Seq.empty
) extends AbstractCmdInfo[F] {

  override def prefix(raw: RawCmd[F]): F[String] = prefix.pure

  override def aliases(raw: RawCmd[F]): F[Seq[String]] = aliases.pure

  override def filters(raw: RawCmd[F]): F[Seq[CmdFilter]] = filters.pure
}
