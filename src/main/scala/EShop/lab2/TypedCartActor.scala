package EShop.lab2

import EShop.lab3.{OrderManager, Payment}
import akka.actor.Cancellable
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}

import scala.concurrent.Await
import scala.language.postfixOps
import scala.concurrent.duration._

object TypedCartActor {
//  def apply(): Behavior[Command] = Behaviors.setup{context =>
//    val mainActor = context.spawn(TypedCartActor(),"cart")
//    mainActor ! RemoveItem("DEF")
//    mainActor ! AddItem("ABC")
//    mainActor ! RemoveItem("DEF")
//    mainActor ! RemoveItem("ABC")
//    mainActor ! AddItem("ABC")
//    mainActor ! StartCheckout
//    mainActor ! ConfirmCheckoutClosed
//    Behaviors.receiveMessage(_ => Behaviors.stopped)
//
//  }


  sealed trait Command
  case class AddItem(item: Any)                 extends Command
  case class RemoveItem(item: Any)              extends Command
  case object ExpireCart                        extends Command
  case object StartCheckout                     extends Command
  case object ConfirmCheckoutCancelled          extends Command
  case object ConfirmCheckoutClosed             extends Command
  case class GetItems(sender: ActorRef[Cart])   extends Command

  sealed trait Event
  case class CheckoutStarted(checkoutRef: ActorRef[TypedCheckout.Command]) extends Event
}

class TypedCartActor(orderManagerCartHandler: ActorRef[TypedCartActor.Event],
                    orderManagerCheckoutHandler: ActorRef[TypedCheckout.Event],
                     orderManagerPaymentHandler: ActorRef[Payment.Event]) {

  import TypedCartActor._

  val cartTimerDuration: FiniteDuration = 5 seconds

  private def scheduleTimer(context: ActorContext[TypedCartActor.Command], finiteDuration: FiniteDuration, command: Command): Cancellable = {
    context.scheduleOnce(finiteDuration, context.self, command)
  }

  def start: Behavior[TypedCartActor.Command] = empty

  def empty: Behavior[TypedCartActor.Command] = Behaviors.receive(
    (ctx,msg) =>
      msg match {
        case AddItem(item) =>
          nonEmpty(Cart(List(item)), scheduleTimer(ctx,cartTimerDuration,ExpireCart))
        case GetItems(sender) =>
          sender ! Cart.empty
          Behaviors.same
      }
  )

  def nonEmpty(cart: Cart, timer: Cancellable): Behavior[TypedCartActor.Command] = Behaviors.receive(
    (ctx,msg) =>
      msg match {
        case AddItem(item) =>
          nonEmpty(cart.addItem(item), scheduleTimer(ctx,cartTimerDuration,ExpireCart))

        case RemoveItem(item) =>
          timer.cancel()
          if (cart.size > 1) {
           nonEmpty(cart.removeItem(item), scheduleTimer(ctx,cartTimerDuration,ExpireCart))
          }else if (!cart.contains(item)){
            nonEmpty(cart, scheduleTimer(ctx,cartTimerDuration,ExpireCart))
          } else {
            empty
          }

        case ExpireCart =>
          timer.cancel()
          empty

        case StartCheckout =>
          timer.cancel()
          val checkout = ctx.spawnAnonymous(
            new TypedCheckout(ctx.self, orderManagerCheckoutHandler, orderManagerPaymentHandler).start
          )
          checkout ! TypedCheckout.StartCheckout
          orderManagerCartHandler ! CheckoutStarted(checkout)
          inCheckout(cart)

        case GetItems(sender) =>
          sender ! cart
          Behaviors.same
      }

  )

  def inCheckout(cart: Cart): Behavior[TypedCartActor.Command] = Behaviors.receive(
    (ctx,msg) =>
      msg match {
        case ConfirmCheckoutCancelled =>
          nonEmpty(cart, scheduleTimer(ctx,cartTimerDuration,ExpireCart))
        case ConfirmCheckoutClosed =>
          empty
        case GetItems(sender) =>
          sender ! cart
          Behaviors.same
      }
  )
}

//object TypedCartActorApp extends App {
//  val system = ActorSystem(TypedCartActor(), "mainActor")
//
//  Await.result(system.whenTerminated, Duration.Inf)
//}
