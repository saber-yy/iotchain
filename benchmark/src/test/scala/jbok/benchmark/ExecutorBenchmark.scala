//package jbok.benchmark
//
//import cats.effect.IO
//import jbok.common.log.Logger
//import jbok.core.ledger.BlockExecutor
//import jbok.core.models.{Block, SignedTransaction}
//import jbok.common.testkit._
//import jbok.core.testkit._
//import org.openjdk.jmh.annotations._
//
//class ExecutorBenchmark extends JbokBenchmark {
//  Logger.setRootLevel[IO](jbok.common.log.Level.Error).unsafeRunSync()
//
//  implicit val config = testConfig
//  val executor         = random[BlockExecutor[IO]]
//  val tx               = random[List[SignedTransaction]](genTxs(1, 1))
//  val block            = random[Block](genBlock(stxsOpt = Some(tx)))
//
//  @Benchmark
//  def executeBlockTransactions() =
//    executor.executeBlock(block).unsafeRunSync()
//}
