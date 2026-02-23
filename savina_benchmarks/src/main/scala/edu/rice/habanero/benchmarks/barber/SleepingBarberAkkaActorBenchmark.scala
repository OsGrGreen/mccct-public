package edu.rice.habanero.benchmarks.barber

import java.util.concurrent.atomic.AtomicLong

import org.apache.pekko.actor.{ActorRef, Props}
import mccct.actors.{PekkoActor, PekkoActorState}
import mccct.given
import mccct.*
import edu.rice.habanero.benchmarks.barber.SleepingBarberConfig._
import edu.rice.habanero.benchmarks.{Benchmark, BenchmarkRunner, PseudoRandom}

import scala.collection.mutable.ListBuffer

/** source: https://code.google.com/p/gparallelizer/wiki/ActorsExamples
  *
  * @author
  *   <a href="http://shams.web.rice.edu/">Shams Imam</a> (shams@rice.edu)
  */
object SleepingBarberPekkoActorBenchmark {

  @main def SleepingBarber(args: String*) = {
    BenchmarkRunner.runBenchmark(args.toArray, new SleepingBarberPekkoActorBenchmark)
  }

  private final class SleepingBarberPekkoActorBenchmark extends Benchmark {
    def initialize(args: Array[String]) = {
      SleepingBarberConfig.parseArgs(args)
    }

    def printArgInfo() = {
      SleepingBarberConfig.printArgs()
    }

    def runIteration() = {

      val idGenerator = new AtomicLong(0)

      val system = PekkoActorState.newActorSystem("SleepingBarber")

      val barber       = system.actorOf(Props(new BarberActor()))
      val room         = system.actorOf(Props(new WaitingRoomActor(SleepingBarberConfig.W, barber)))
      val factoryActor = system.actorOf(Props(new CustomerFactoryActor(idGenerator, SleepingBarberConfig.N, room)))

      PekkoActorState.startActor(barber)
      PekkoActorState.startActor(room)
      PekkoActorState.startActor(factoryActor)

      factoryActor ! Start.ONLY

      PekkoActorState.awaitTermination(system)

      track("CustomerAttempts", idGenerator.get())
    }

    def cleanupIteration(lastIteration: Boolean, execTimeMillis: Double) = {}
  }

  private case class Enter(customer: ActorRef, room: ActorRef)

  private case class Returned(customer: ActorRef)

  private class WaitingRoomActor(capacity: Int, barber: ActorRef) extends PekkoActor[AnyRef] {

    private val waitingCustomers = new ListBuffer[ActorRef]()
    private var barberAsleep     = true

    override def process(msg: AnyRef) = {
      msg match {
        case message: Enter =>

          val customer = message.customer
          if (waitingCustomers.size == capacity) {
            sendTo(Full.ONLY, customer)
          } else {

            waitingCustomers.append(customer)
            if (barberAsleep) {

              barberAsleep = false
              sendTo(Next.ONLY, self)

            } else {

              sendTo(Wait.ONLY, customer)
            }
          }

        case message: Next =>

          if (waitingCustomers.size > 0) {

            val customer = waitingCustomers.remove(0)
            sendTo(new Enter(customer, self), barber)

          } else {

            sendTo(Wait.ONLY, barber)
            barberAsleep = true

          }

        case message: Exit =>

          sendTo(Exit.ONLY, barber)
          exit()

      }
    }
  }

  private class BarberActor extends PekkoActor[AnyRef] {

    private val random = new PseudoRandom()

    override def process(msg: AnyRef) = {
      msg match {
        case message: Enter =>

          val customer = message.customer
          val room     = message.room

          sendTo(Start.ONLY, customer)
          // println("Barber: Processing customer " + customer)
          SleepingBarberConfig.busyWait(random.nextInt(SleepingBarberConfig.AHR) + 10)
          sendTo(Done.ONLY, customer)
          sendTo(Next.ONLY, room)

        case message: Wait =>

        // println("Barber: No customers. Going to have a sleep")

        case message: Exit =>

          exit()

      }
    }
  }

  private class CustomerFactoryActor(idGenerator: AtomicLong, haircuts: Int, room: ActorRef)
      extends PekkoActor[AnyRef] {

    private val random           = new PseudoRandom()
    private var numHairCutsSoFar = 0

    override def process(msg: AnyRef) = {
      msg match {
        case message: Start =>

          var i = 0
          while (i < haircuts) {
            sendCustomerToRoom()
            SleepingBarberConfig.busyWait(random.nextInt(SleepingBarberConfig.APR) + 10)
            i += 1
          }

        case message: Returned =>

          idGenerator.incrementAndGet()
          sendCustomerToRoom(message.customer)

        case message: Done =>

          numHairCutsSoFar += 1
          if (numHairCutsSoFar == haircuts) {
            println("Total attempts: " + idGenerator.get())
            sendTo(Exit.ONLY, room)
            exit()
          }
      }
    }

    private def sendCustomerToRoom(): Unit = {
      val customer = context.system.actorOf(Props(new CustomerActor(idGenerator.incrementAndGet(), self)))
      PekkoActorState.startActor(customer)

      sendCustomerToRoom(customer)
    }

    private def sendCustomerToRoom(customer: ActorRef) = {
      val enterMessage = new Enter(customer, room)
      sendTo(enterMessage, room)
    }
  }

  private class CustomerActor(val id: Long, factoryActor: ActorRef) extends PekkoActor[AnyRef] {

    override def process(msg: AnyRef) = {
      msg match {
        case message: Full =>

          // println("Customer-" + id + " The waiting room is full. I am leaving.")
          sendTo(new Returned(self), factoryActor)

        case message: Wait =>

        // println("Customer-" + id + " I will wait.")

        case message: Start =>

        // println("Customer-" + id + " I am now being served.")

        case message: Done =>

          //  println("Customer-" + id + " I have been served.")
          sendTo(Done.ONLY, factoryActor)
          exit()
      }
    }
  }

}
