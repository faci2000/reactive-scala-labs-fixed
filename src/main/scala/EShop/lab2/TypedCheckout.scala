package EShop.lab2

import EShop.lab3.Payment
import akka.actor.Cancellable
import akka.actor.typed.javadsl.TimerScheduler
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}

import scala.concurrent.Await
import scala.language.postfixOps
import scala.concurrent.duration._
import EShop.lab3.OrderManager

object TypedCheckout {

//  def apply(): Behavior[Command] = Behaviors.setup{context =>
//    val mainActor = context.spawn(TypedCheckout(),"checkout")
//    mainActor ! StartCheckout
//    mainActor ! ExpireCheckout
//    mainActor ! StartCheckout
//    mainActor ! CancelCheckout
//    mainActor ! StartCheckout
//    mainActor ! SelectDeliveryMethod("post")
//    mainActor ! SelectPayment("blik")
//    mainActor ! ConfirmPaymentReceived
//    Behaviors.receiveMessage(_ => Behaviors.stopped)
//
//  }


  sealed trait Data
  case object Uninitialized                               extends Data
  case class SelectingDeliveryStarted(timer: Cancellable) extends Data
  case class ProcessingPaymentStarted(timer: Cancellable) extends Data

  sealed trait Command
  case object StartCheckout                                                                  extends Command
  case class SelectDeliveryMethod(method: String)                                            extends Command
  case object CancelCheckout                                                                 extends Command
  case object ExpireCheckout                                                                 extends Command
  case class SelectPayment(payment: String) extends Command
  case object ExpirePayment                                                                  extends Command
  case object ConfirmPaymentReceived                                                         extends Command

  sealed trait Event
  case object CheckOutClosed                           extends Event
  case class PaymentStarted(paymentRef: ActorRef[Payment.Command]) extends Event
}

class TypedCheckout(cartActor: ActorRef[TypedCartActor.Command],
                    orderManagerCheckoutHandler: ActorRef[TypedCheckout.Event],
                    orderManagerPaymentHandler: ActorRef[Payment.Event]){
  import TypedCheckout._

  val checkoutTimerDuration: FiniteDuration = 1 seconds
  val paymentTimerDuration: FiniteDuration  = 1 seconds

  private def scheduleTimer(context: ActorContext[TypedCheckout.Command], finiteDuration: FiniteDuration, command: Command): Cancellable = {
    context.scheduleOnce(finiteDuration, context.self, command)
  }

  def start: Behavior[TypedCheckout.Command] = Behaviors.receive(
    (ctx,msg) =>
      msg match {
        case StartCheckout =>
          selectingDelivery(scheduleTimer(ctx,checkoutTimerDuration, ExpireCheckout))
      }
  )

  def selectingDelivery(timer: Cancellable): Behavior[TypedCheckout.Command] = Behaviors.receive(
    (ctx,msg) =>
      msg match {
        case SelectDeliveryMethod(_) =>
          selectingPaymentMethod(timer)

        case CancelCheckout | ExpireCheckout =>
          cartActor ! TypedCartActor.ConfirmCheckoutCancelled
          timer.cancel()
          cancelled
      }
  )

  def selectingPaymentMethod(timer: Cancellable): Behavior[TypedCheckout.Command] = Behaviors.receive(
    (ctx,msg) =>
      msg match {
        case SelectPayment(payment) =>
          val paymentRef = ctx.spawnAnonymous(new Payment(payment, orderManagerPaymentHandler, ctx.self).start)
          orderManagerCheckoutHandler ! PaymentStarted(paymentRef)
          processingPayment(scheduleTimer(ctx,paymentTimerDuration,ExpirePayment))

        case CancelCheckout | ExpireCheckout | ExpirePayment =>
          cartActor ! TypedCartActor.ConfirmCheckoutCancelled
          timer.cancel()
          cancelled
      }
  )

  def processingPayment(timer: Cancellable): Behavior[TypedCheckout.Command] = Behaviors.receive(
    (ctx,msg) =>
      msg match {
        case ConfirmPaymentReceived =>
          cartActor ! TypedCartActor.ConfirmCheckoutClosed
          timer.cancel()
          closed
        case CancelCheckout | ExpireCheckout | ExpirePayment =>
          cartActor ! TypedCartActor.ConfirmCheckoutCancelled
          timer.cancel()
          cancelled
      }
  )

  def cancelled: Behavior[TypedCheckout.Command] = Behaviors.stopped

  def closed: Behavior[TypedCheckout.Command] = Behaviors.stopped

}
//object TypedCheckoutApp extends App {
//  val system = ActorSystem(TypedCartActor(), "mainActor")
//
//  Await.result(system.whenTerminated, Duration.Inf)
//}

