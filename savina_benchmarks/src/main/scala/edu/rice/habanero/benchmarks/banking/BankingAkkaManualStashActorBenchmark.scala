package edu.rice.habanero.benchmarks.banking

import org.apache.pekko.actor.{ActorRef, Props}
import mccct.actors.{PekkoActor, PekkoActorState}
import mccct.given
import mccct.*
import edu.rice.habanero.benchmarks.banking.BankingConfig._
import edu.rice.habanero.benchmarks.{Benchmark, BenchmarkRunner, PseudoRandom}

import scala.collection.mutable.ListBuffer

/** @author
  *   <a href="http://shams.web.rice.edu/">Shams Imam</a> (shams@rice.edu)
  */
object BankingPekkoManualStashActorBenchmark {

  @main def BankingManualStash(args: String*) = {
    BenchmarkRunner.runBenchmark(args.toArray, new BankingPekkoManualStashActorBenchmark)
  }

  private final class BankingPekkoManualStashActorBenchmark extends Benchmark {
    def initialize(args: Array[String]) = {
      BankingConfig.parseArgs(args)
    }

    def printArgInfo() = {
      BankingConfig.printArgs()
    }

    def runIteration() = {

      val system = PekkoActorState.newActorSystem("Banking")

      val master = system.actorOf(Props(new Teller(BankingConfig.A, BankingConfig.N)))
      PekkoActorState.startActor(master)
      master ! StartMessage.ONLY

      PekkoActorState.awaitTermination(system)
    }

    def cleanupIteration(lastIteration: Boolean, execTimeMillis: Double) = {}
  }

  protected class Teller(numAccounts: Int, numBankings: Int) extends PekkoActor[AnyRef] {

    private val accounts = Array.tabulate[ActorRef](numAccounts)((i) => {
      context.system.actorOf(Props(new Account(i, BankingConfig.INITIAL_BALANCE)))
    })
    private var numCompletedBankings = 0

    private val randomGen = new PseudoRandom(123456)

    protected override def onPostStart() = {
      accounts.foreach(loopAccount => PekkoActorState.startActor(loopAccount))
    }

    override def process(theMsg: AnyRef) = {
      theMsg match {

        case sm: BankingConfig.StartMessage =>

          var m = 0
          while (m < numBankings) {
            generateWork()
            m += 1
          }

        case sm: BankingConfig.ReplyMessage =>

          numCompletedBankings += 1
          if (numCompletedBankings == numBankings) {
            accounts.foreach(loopAccount => sendTo(StopMessage.ONLY, loopAccount))
            exit()
          }

        case message =>

          val ex = new IllegalArgumentException("Unsupported message: " + message)
          ex.printStackTrace(System.err)
      }
    }

    def generateWork(): Unit = {
      // src is lower than dest id to ensure there is never a deadlock
      val srcAccountId = randomGen.nextInt((accounts.length / 10) * 8)
      var loopId       = randomGen.nextInt(accounts.length - srcAccountId)
      if (loopId == 0) {
        loopId += 1
      }
      val destAccountId = srcAccountId + loopId

      val srcAccount  = accounts(srcAccountId)
      val destAccount = accounts(destAccountId)
      val amount      = Math.abs(randomGen.nextDouble()) * 1000

      val sender = self
      val cm     = new CreditMessage(sender, amount, destAccount)

      sendTo(cm, srcAccount)
    }
  }

  protected class Account(id: Int, var balance: Double) extends PekkoActor[AnyRef] {

    private var inReplyMode           = false
    private var replyTeller: ActorRef = null
    private val stashedMessages       = new ListBuffer[AnyRef]()

    override def process(theMsg: AnyRef) = {

      if (inReplyMode) {

        theMsg match {

          case _: ReplyMessage =>

            inReplyMode = false
            sendTo(ReplyMessage.ONLY, replyTeller)
            if (!stashedMessages.isEmpty) {
              val newMsg = stashedMessages.remove(0)
              sendTo(newMsg, self)
            }

          case message =>

            stashedMessages.append(message)
        }

      } else {

        // process the message
        theMsg match {
          case dm: DebitMessage =>

            balance += dm.amount
            val creditor = dm.sender.asInstanceOf[ActorRef]
            sendTo(ReplyMessage.ONLY, creditor)

          case cm: CreditMessage =>

            balance -= cm.amount
            replyTeller = cm.sender.asInstanceOf[ActorRef]

            val sender      = self
            val destAccount = cm.recipient.asInstanceOf[ActorRef]
            sendTo(new DebitMessage(sender, cm.amount), destAccount)
            inReplyMode = true

          case _: StopMessage =>
            exit()

          case message =>
            val ex = new IllegalArgumentException("Unsupported message: " + message)
            ex.printStackTrace(System.err)
        }

        // recycle stashed messages
        if (!inReplyMode && !stashedMessages.isEmpty) {
          val newMsg = stashedMessages.remove(0)
          sendTo(newMsg, self)
        }
      }
    }
  }

}
