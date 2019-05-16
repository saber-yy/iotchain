package jbok.common

import cats.effect.IO
import jbok.common.log.{Level, Logger}
import org.scalatest._
import org.scalatest.concurrent.AsyncTimeLimitedTests
import org.scalatest.time.Span

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

trait CommonSpec extends AsyncWordSpec with Matchers with AsyncTimeLimitedTests {
  implicit val cs = IO.contextShift(ExecutionContext.global)

  implicit val timer = IO.timer(ExecutionContext.global)

  implicit val ce = IO.ioConcurrentEffect(cs)

  override def timeLimit: Span = 60.seconds

  Logger.setRootHandlers(Logger.consoleHandler(minimumLevel = Some(Level.Info))).unsafeRunSync()

  implicit def futureUnitToFutureAssertion(fu: Future[Unit]): Future[Assertion] = fu.map(_ => Succeeded)

  implicit def ioUnitToFutureAssertion(iou: IO[Unit]): Future[Assertion] = iou.map(_ => Succeeded).unsafeToFuture()
}
