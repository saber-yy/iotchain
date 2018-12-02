package jbok.common.metrics

package org.http4s.metrics.dropwizard

import cats.effect.Sync
import com.codahale.metrics.MetricRegistry
import java.util.concurrent.TimeUnit

object Dropwizard {

  /**
    * Creates a [[MetricsOps]] that supports Dropwizard metrics
    *
    * @param registry a dropwizard metric registry
    * @param prefix a prefix that will be added to all metrics
    */
  def apply[F[_]](registry: MetricRegistry, prefix: String)(implicit F: Sync[F]): MetricsOps[F] =
    new MetricsOps[F] {

      override def increaseCount(classifier: Option[String]): F[Unit] = F.delay {
        registry.counter(s"${namespace(prefix, classifier)}.count").inc()
      }

      override def decreaseCount(classifier: Option[String]): F[Unit] = F.delay {
        registry.counter(s"${namespace(prefix, classifier)}.count").dec()
      }

      override def recordTime(classifier: Option[String], elapsed: Long): F[Unit] = F.delay {
        registry
          .timer(s"${namespace(prefix, classifier)}.time")
          .update(elapsed, TimeUnit.NANOSECONDS)
      }

      override def recordAbnormalTermination(
          classifier: Option[String],
          elapsed: Long,
          terminationType: TerminationType
      ): F[Unit] = terminationType match {
        case TerminationType.Abnormal => recordAbnormal(elapsed, classifier)
        case TerminationType.Error    => recordError(elapsed, classifier)
        case TerminationType.Timeout  => recordTimeout(elapsed, classifier)
      }

      private def recordAbnormal(elapsed: Long, classifier: Option[String]): F[Unit] = F.delay {
        registry
          .timer(s"${namespace(prefix, classifier)}.abnormal-terminations")
          .update(elapsed, TimeUnit.NANOSECONDS)
      }

      private def recordError(elapsed: Long, classifier: Option[String]): F[Unit] = F.delay {
        registry
          .timer(s"${namespace(prefix, classifier)}.errors")
          .update(elapsed, TimeUnit.NANOSECONDS)
      }

      private def recordTimeout(elapsed: Long, classifier: Option[String]): F[Unit] = F.delay {
        registry
          .timer(s"${namespace(prefix, classifier)}.timeouts")
          .update(elapsed, TimeUnit.NANOSECONDS)
      }

      private def namespace(prefix: String, classifier: Option[String]): String =
        classifier.map(d => s"${prefix}.${d}").getOrElse(s"${prefix}.default")
    }
}
