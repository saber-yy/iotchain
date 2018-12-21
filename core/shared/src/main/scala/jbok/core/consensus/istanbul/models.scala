package jbok.core.consensus.istanbul

import cats.effect.{Concurrent, ConcurrentEffect, IO, Sync}
import cats.effect.concurrent.{Deferred, Ref}
import jbok.core.models.{Address, Block}
import scodec.bits.ByteVector
import cats.implicits._
import io.circe.generic.JsonCodec
import jbok.core.messages.{IstanbulMessage, Message}
import jbok.crypto.signature.{CryptoSignature, KeyPair}

import scala.collection.Iterable
import scala.collection.mutable.ArrayBuffer

case class IstanbulExtra(
    validators: List[Address],
    seal: ByteVector,
    committedSeals: List[ByteVector]
)

sealed trait CheckResult
object CheckResult {
  case object UnauthorizedAddress extends CheckResult
  case object InvalidMessage      extends CheckResult
  case object FutureMessage       extends CheckResult
  case object OldMessage          extends CheckResult
  case object Success             extends CheckResult
}

sealed trait ProposalCheckResult
object ProposalCheckResult {
  case object UnauthorizedProposer extends ProposalCheckResult
  case object InvalidProposal      extends ProposalCheckResult
  case object FutureBlock          extends ProposalCheckResult
  case object Success              extends ProposalCheckResult
}

//case class IstanbulMessage(
//  msgCode: Int,
//  msg: ByteVector = ByteVector.empty,
//  address: Address = Address.empty,
//  signature: ByteVector = ByteVector.empty,
//  ommittedSeal: ByteVector = ByteVector.empty
//) extends Message(0x5000)

//object IstanbulMessage {
//  val msgPreprepareCode = 0
//  val msgPrepareCode    = 1
//  val msgCommitCode     = 2
//  val msgRoundChange    = 3
//  val msgAll            = 4
//}

case class Preprepare(view: View, block: Block)

/**
  *
  * @param view
  * @param digest proposal hash
  */
case class Subject(view: View, digest: ByteVector)

//context for state transition
case class StateContext[F[_]](
    keyPair: KeyPair,
    validatorSet: Ref[F, ValidatorSet],
    current: Ref[F, RoundState],
    state: Ref[F, State],
    roundChanges: Ref[F, Map[Int, MessageSet]],
    roundChangePromise: Ref[F, Deferred[F, Int]],
    signFunc: ByteVector => F[CryptoSignature],
    eventHandler: IstanbulMessage => F[Unit]
)(implicit F: Concurrent[F]) {
  def address: Address = Address(keyPair)

  def lockHash(): F[Unit] =
    current.update(s => {
      s.preprepare match {
        case Some(p) => s.copy(lockedHash = p.block.header.hash)
        case None    => s
      }
    })
  def unlockHash(): F[Unit] = current.update(_.copy(lockedHash = ByteVector.empty))

  def prepareReady: F[Boolean] =
    for {
      rs <- current.get
      vs <- validatorSet.get
      enough = rs.prepares.messages.size > 2 * vs.f
    } yield enough

  def commitReady: F[Boolean] =
    for {
      rs <- current.get
      vs <- validatorSet.get
      enough = rs.commits.messages.size > 2 * vs.f
    } yield enough

  def view: F[View]               = current.get.map(s => View(s.round, s.blockNumber))
  def proposal: F[Option[Block]]  = current.get.map(_.preprepare.map(p => p.block))
  def setState(s: State): F[Unit] = state.set(s)

  def addPrepare(message: IstanbulMessage): F[Unit] =
    current.update(rs => rs.copy(prepares = rs.prepares.addMessage(message)))

  def addCommit(message: IstanbulMessage): F[Unit] =
    current.update(rs => rs.copy(commits = rs.commits.addMessage(message)))

  def addRoundChange(round: Int, message: IstanbulMessage): F[Unit] =
    roundChanges.update(m => m + (round -> (m.getOrElse(round, MessageSet.empty).addMessage(message))))

  /**
    * delete the messages with smaller round
    */
  private def clearRoundChange(round: Int): F[Unit] =
    roundChanges.update(_.filter(_._1 >= round))

  /**
    *
    * update current round number and keep previous sequence number
    * with no checking the given round number is valid or not, so you must check it by yourself.
    *
    * if is Locked, only set round number to RoundState,
    * else set round number and clear preprepare
    *
    * locked means we can only propose this block, so we don't clear preprepare when it's locked
    * and otherwise we clear preprepare when it's not locked since we fire a NEW ROUND when this
    * function is called
    */
  def updateCurrentRound(round: Int): F[Unit] =
    for {
      _ <- current.update(s => {
        if (s.isLocked)
          s.copy(round = round, waitingForRoundChange = true, prepares = MessageSet.empty, commits = MessageSet.empty)
        else
          s.copy(round = round,
                 preprepare = None,
                 waitingForRoundChange = true,
                 prepares = MessageSet.empty,
                 commits = MessageSet.empty)
      })
      _ <- clearRoundChange(round)
    } yield ()

  def setPreprepare(preprepare: Preprepare): F[Unit] = current.update(_.copy(preprepare = Some(preprepare)))

  def currentSubject(): F[Option[Subject]] =
    current.get.map(rs => rs.preprepare.map(p => Subject(View(rs.round, rs.blockNumber), p.block.header.hash)))
}

case class View(
    val round: Int,
    val blockNumber: BigInt
)
object View {
  def empty: View = View(0, 0)
}

@JsonCodec
case class ValidatorSet(
    proposer: Address,
    validators: List[Address]
) {

  def isProposer(address: Address): Boolean = contains(address) && proposer == address

  /**
    * f represent the constant F in Istanbul BFT defined
    */
  def f = Math.ceil(validators.size / 3.0).toInt - 1

  def contains(address: Address): Boolean = validators.contains(address)

  def calculateProposer(lastProposer: Address, round: Int, policy: Int): Option[Address] =
    policy match {
      case IstanbulConfig.roundRobin => roundRobinProposer(lastProposer, round)
      case IstanbulConfig.sticky     => stickyProposer(lastProposer, round)
      case _                         => None
    }

  private def roundRobinProposer(lastProposer: Address, round: Int): Option[Address] = {
    if (validators.isEmpty) return None

    val seed =
      if (proposer == Address.empty || !validators.contains(lastProposer)) round
      else validators.toList.indexOf(lastProposer) + round

    val robin = seed % validators.size
    Option(validators(robin.intValue()))
  }

  private def stickyProposer(lastProposer: Address, round: Int): Option[Address] = None

}
object ValidatorSet {
  def empty: ValidatorSet = ValidatorSet(proposer = Address.empty, validators = List.empty)

  def apply(
      proposer: Address,
      validators: List[Address]
  ): ValidatorSet = new ValidatorSet(proposer, validators)

  def apply(
      validators: Iterable[Address]
  ): ValidatorSet = ValidatorSet(validators.head, validators.toList)
}

case class MessageSet(
    messages: Map[Address, IstanbulMessage]
) {
  def addMessage(message: IstanbulMessage): MessageSet =
    copy(messages = messages + (message.address -> message))

}
object MessageSet {
  def empty: MessageSet = MessageSet(Map.empty)
}

case class RoundState(
    round: Int,
    blockNumber: BigInt,
    preprepare: Option[Preprepare],
    prepares: MessageSet,
    commits: MessageSet,
    lockedHash: ByteVector,
    waitingForRoundChange: Boolean = false
) {
  def isLocked: Boolean = lockedHash != ByteVector.empty
}
