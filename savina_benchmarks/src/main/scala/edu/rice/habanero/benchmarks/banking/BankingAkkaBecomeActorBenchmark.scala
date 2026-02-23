package edu.rice.habanero.benchmarks.banking

import org.apache.pekko.actor.{ActorRef, Props, Stash}
import org.apache.pekko.util.Timeout
import mccct.actors.{PekkoActor, PekkoActorState}
import mccct.given
import mccct.*
import edu.rice.habanero.benchmarks.banking.BankingConfig._
import edu.rice.habanero.benchmarks.{Benchmark, BenchmarkRunner, PseudoRandom}
import scala.concurrent.duration._

import scala.concurrent.duration._

/** @author
  *   <a href="http://shams.web.rice.edu/">Shams Imam</a> (shams@rice.edu)
  */
object BankingPekkoBecomeActorBenchmark {

  @main def BankingBecomeActor(args: String*) = {
    BenchmarkRunner.runBenchmark(args.toArray, new BankingPekkoBecomeActorBenchmark)
  }

  private final class BankingPekkoBecomeActorBenchmark extends Benchmark {
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

  protected class Account(id: Int, var balance: Double) extends PekkoActor[AnyRef] with Stash {

    override def process(theMsg: AnyRef) = {
      theMsg match {
        case dm: DebitMessage =>

          balance += dm.amount
          val senderRef = sender()
          sendTo(ReplyMessage.ONLY, senderRef)

        case cm: CreditMessage =>

          balance -= cm.amount

          val destAccount = cm.recipient.asInstanceOf[ActorRef]

          implicit val timeout: Timeout = Timeout(60000.seconds)
          sendTo(new DebitMessage(sender(), cm.amount), destAccount)

          val senderRef = sender()
          context.become(
            {
              case rm: ReplyMessage =>

                sendTo(ReplyMessage.ONLY, senderRef)

                unstashAll()
                context.unbecome()

              case message =>
                stash()

            },
            discardOld = false
          )

        case _: StopMessage =>
          exit()

        case message =>
          val ex = new IllegalArgumentException("Unsupported message: " + message)
          ex.printStackTrace(System.err)
      }
    }
  }

}
