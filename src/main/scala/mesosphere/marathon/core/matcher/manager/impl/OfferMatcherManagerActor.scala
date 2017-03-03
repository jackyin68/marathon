package mesosphere.marathon
package core.matcher.manager.impl

import akka.actor.{ Actor, ActorLogging, Props }
import akka.event.LoggingReceive
import akka.pattern.pipe
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.matcher.base.OfferMatcher
import mesosphere.marathon.core.matcher.base.OfferMatcher.{ InstanceOpWithSource, MatchedInstanceOps }
import mesosphere.marathon.core.matcher.base.util.ActorOfferMatcher
import mesosphere.marathon.core.matcher.manager.OfferMatcherManagerConfig
import mesosphere.marathon.core.matcher.manager.impl.OfferMatcherManagerActor.{ MatchTimeout, OfferData }
import mesosphere.marathon.core.task.Task.LocalVolumeId
import mesosphere.marathon.metrics.{ Metrics, ServiceMetric, SettableGauge }
import mesosphere.marathon.state.{ PathId, Timestamp }
import mesosphere.marathon.stream.Implicits._
import mesosphere.marathon.tasks.ResourceUtil
import org.apache.mesos.Protos.{ Offer, OfferID }
import rx.lang.scala.Observer

import scala.collection.immutable.Queue
import scala.concurrent.Promise
import scala.util.Random
import scala.util.control.NonFatal

private[manager] class OfferMatcherManagerActorMetrics() {
  private[manager] val launchTokenGauge: SettableGauge =
    Metrics.atomicGauge(ServiceMetric, getClass, "launchTokens")
  private[manager] val currentOffersGauge: SettableGauge =
    Metrics.atomicGauge(ServiceMetric, getClass, "currentOffers")
}

/**
  * This actor offers one interface to a dynamic collection of matchers
  * and includes logic for limiting the amount of launches.
  */
private[manager] object OfferMatcherManagerActor {
  def props(
    metrics: OfferMatcherManagerActorMetrics,
    random: Random, clock: Clock,
    offerMatcherConfig: OfferMatcherManagerConfig, offersWanted: Observer[Boolean]): Props = {
    Props(new OfferMatcherManagerActor(metrics, random, clock, offerMatcherConfig, offersWanted))
  }

  /**
    *
    * @constructor Create a new instance that bundles offer, deadline and ops.
    * @param offer The offer that is matched.
    * @param deadline If an offer is not processed until the deadline the promise
    *     is succeeded without a match.
    * @param promise The promise will receive the matched instance ops if a match
    *     if found. The promise might be fulfilled by the sender, e.g. [[mesosphere.marathon.core.matcher.base.util.ActorOfferMatcher]]
    *     if the deadline is reached before the offer has been processed.
    * @param matcherQueue The offer matchers which should be applied to the
    *     offer.
    * @param ops ???
    * @param matchPasses ???
    * @param resendThisOffer ???
    */
  private case class OfferData(
      offer: Offer,
      deadline: Timestamp,
      promise: Promise[OfferMatcher.MatchedInstanceOps],
      matcherQueue: Queue[OfferMatcher],
      ops: Seq[InstanceOpWithSource] = Seq.empty,
      matchPasses: Int = 0,
      resendThisOffer: Boolean = false) {

    def addMatcher(matcher: OfferMatcher): OfferData = copy(matcherQueue = matcherQueue.enqueue(matcher))
    def nextMatcherOpt: Option[(OfferMatcher, OfferData)] = {
      matcherQueue.dequeueOption map {
        case (nextMatcher, newQueue) => nextMatcher -> copy(matcherQueue = newQueue)
      }
    }

    def addInstances(added: Seq[InstanceOpWithSource]): OfferData = {
      val leftOverOffer = added.foldLeft(offer) { (offer, nextOp) => nextOp.op.applyToOffer(offer) }

      copy(
        offer = leftOverOffer,
        ops = added ++ ops
      )
    }
  }

  private case class MatchTimeout(offerId: OfferID)
}

private[impl] class OfferMatcherManagerActor private (
  metrics: OfferMatcherManagerActorMetrics,
  random: Random, clock: Clock, conf: OfferMatcherManagerConfig, offersWantedObserver: Observer[Boolean])
    extends Actor with ActorLogging {

  private[this] var launchTokens: Int = 0

  private[this] var matchers: Set[OfferMatcher] = Set.empty

  private[this] var offerQueues: Map[OfferID, OfferMatcherManagerActor.OfferData] = Map.empty

  override def receive: Receive = LoggingReceive {
    Seq[Receive](
      receiveSetLaunchTokens,
      receiveChangingMatchers,
      receiveProcessOffer,
      receiveMatchedInstances
    ).reduceLeft(_.orElse[Any, Unit](_))
  }

  private[this] def receiveSetLaunchTokens: Receive = {
    case OfferMatcherManagerDelegate.SetInstanceLaunchTokens(tokens) =>
      val tokensBeforeSet = launchTokens
      launchTokens = tokens
      metrics.launchTokenGauge.setValue(launchTokens.toLong)
      if (tokens > 0 && tokensBeforeSet <= 0)
        updateOffersWanted()
    case OfferMatcherManagerDelegate.AddInstanceLaunchTokens(tokens) =>
      launchTokens += tokens
      metrics.launchTokenGauge.setValue(launchTokens.toLong)
      if (tokens > 0 && launchTokens == tokens)
        updateOffersWanted()
  }

  private[this] def receiveChangingMatchers: Receive = {
    case OfferMatcherManagerDelegate.AddOrUpdateMatcher(matcher) =>
      if (!matchers(matcher)) {
        log.info("activating matcher {}.", matcher)
        offerQueues.map { case (id, data) => id -> data.addMatcher(matcher) }
        matchers += matcher
        updateOffersWanted()
      }

      sender() ! OfferMatcherManagerDelegate.MatcherAdded(matcher)

    case OfferMatcherManagerDelegate.RemoveMatcher(matcher) =>
      if (matchers(matcher)) {
        log.info("removing matcher {}", matcher)
        matchers -= matcher
        updateOffersWanted()
      }
      sender() ! OfferMatcherManagerDelegate.MatcherRemoved(matcher)
  }

  private[this] def offersWanted: Boolean = matchers.nonEmpty && launchTokens > 0
  private[this] def updateOffersWanted(): Unit = offersWantedObserver.onNext(offersWanted)

  private[impl] def offerMatchers(offer: Offer): Queue[OfferMatcher] = {
    //the persistence id of a volume encodes the app id
    //we use this information as filter criteria
    val appReservations: Set[PathId] = offer.getResourcesList.view
      .filter(r => r.hasDisk && r.getDisk.hasPersistence && r.getDisk.getPersistence.hasId)
      .map(_.getDisk.getPersistence.getId)
      .collect { case LocalVolumeId(volumeId) => volumeId.runSpecId }
      .toSet
    val (reserved, normal) = matchers.toSeq.partition(_.precedenceFor.exists(appReservations))
    //1 give the offer to the matcher waiting for a reservation
    //2 give the offer to anybody else
    //3 randomize both lists to be fair
    (random.shuffle(reserved) ++ random.shuffle(normal)).to[Queue]
  }

  private[this] def receiveProcessOffer: Receive = {
    case ActorOfferMatcher.MatchOffer(deadline, offer: Offer, promise: Promise[OfferMatcher.MatchedInstanceOps]) if !offersWanted =>
      log.debug(s"Ignoring offer ${offer.getId.getValue}: No one interested.")
      promise.trySuccess(OfferMatcher.MatchedInstanceOps.noMatch(offer.getId, resendThisOffer = false))

    case ActorOfferMatcher.MatchOffer(deadline, offer: Offer, promise: Promise[OfferMatcher.MatchedInstanceOps]) =>
      log.debug(s"Start processing offer ${offer.getId.getValue}")

      // setup initial offer data
      val randomizedMatchers = offerMatchers(offer)
      val data = OfferMatcherManagerActor.OfferData(offer, deadline, promise, randomizedMatchers)
      offerQueues += offer.getId -> data
      metrics.currentOffersGauge.setValue(offerQueues.size.toLong)

      // deal with the timeout
      import context.dispatcher
      context.system.scheduler.scheduleOnce(
        clock.now().until(deadline),
        self,
        MatchTimeout(offer.getId))

      // process offer for the first time
      scheduleNextMatcherOrFinish(data)
  }

  private[this] def receiveMatchedInstances: Receive = {
    case OfferMatcher.MatchedInstanceOps(offerId, addedOps, resendOffer) =>
      def processAddedInstances(data: OfferData): OfferData = {
        val dataWithInstances = try {
          val (acceptedOps, rejectedOps) =
            addedOps.splitAt(Seq(launchTokens, addedOps.size, conf.maxInstancesPerOffer() - data.ops.size).min)

          rejectedOps.foreach(_.reject("not enough launch tokens OR already scheduled sufficient instances on offer"))

          val newData: OfferData = data.addInstances(acceptedOps)
          launchTokens -= acceptedOps.size
          metrics.launchTokenGauge.setValue(launchTokens.toLong)
          newData
        } catch {
          case NonFatal(e) =>
            log.error(s"unexpected error processing ops for ${offerId.getValue} from ${sender()}", e)
            data
        }

        dataWithInstances.nextMatcherOpt match {
          case Some((matcher, contData)) =>
            val contDataWithActiveMatcher =
              if (addedOps.nonEmpty) contData.addMatcher(matcher)
              else contData
            offerQueues += offerId -> contDataWithActiveMatcher
            contDataWithActiveMatcher
          case None =>
            log.warning(s"Got unexpected matched ops from ${sender()}: $addedOps")
            dataWithInstances
        }
      }

      offerQueues.get(offerId) match {
        case Some(data) =>
          val resend = data.resendThisOffer | resendOffer
          val nextData = processAddedInstances(data.copy(matchPasses = data.matchPasses + 1, resendThisOffer = resend))
          scheduleNextMatcherOrFinish(nextData)

        case None =>
          addedOps.foreach(_.reject(s"offer '${offerId.getValue}' already timed out"))
      }

    case MatchTimeout(offerId) =>
      // When the timeout is reached, we will answer with all matching instances we found until then.
      // Since we cannot be sure if we found all matching instances, we set resendThisOffer to true.
      offerQueues.get(offerId).foreach(completeWithMatchResult(_, resendThisOffer = true))
  }

  private[this] def scheduleNextMatcherOrFinish(data: OfferData): Unit = {
    val nextMatcherOpt = if (data.deadline < clock.now()) {
      log.warning(s"Deadline for ${data.offer.getId.getValue} overdue. Scheduled ${data.ops.size} ops so far.")
      None
    } else if (data.ops.size >= conf.maxInstancesPerOffer()) {
      log.info(
        s"Already scheduled the maximum number of ${data.ops.size} instances on this offer. " +
          s"Increase with --${conf.maxInstancesPerOfferFlag.name}.")
      None
    } else if (launchTokens <= 0) {
      log.info(
        s"No launch tokens left for ${data.offer.getId.getValue}. " +
          "Tune with --launch_tokens/launch_token_refresh_interval.")
      None
    } else {
      data.nextMatcherOpt
    }

    nextMatcherOpt match {
      case Some((nextMatcher, newData)) =>
        import context.dispatcher
        log.debug(s"query next offer matcher $nextMatcher for offer id ${data.offer.getId.getValue}")
        nextMatcher
          .matchOffer(clock.now(), newData.deadline, newData.offer)
          .recover {
            case NonFatal(e) =>
              log.warning("Received error from {}", e)
              MatchedInstanceOps.noMatch(data.offer.getId, resendThisOffer = true)
          }.pipeTo(self)
      case None => completeWithMatchResult(data, data.resendThisOffer)
    }
  }

  private[this] def completeWithMatchResult(data: OfferData, resendThisOffer: Boolean): Unit = {
    data.promise.trySuccess(OfferMatcher.MatchedInstanceOps(data.offer.getId, data.ops, resendThisOffer))
    offerQueues -= data.offer.getId
    metrics.currentOffersGauge.setValue(offerQueues.size.toLong)
    val maxRanges = if (log.isDebugEnabled) 1000 else 10
    log.info(s"Finished processing ${data.offer.getId.getValue} from ${data.offer.getHostname}. " +
      s"Matched ${data.ops.size} ops after ${data.matchPasses} passes. " +
      s"${ResourceUtil.displayResources(data.offer.getResourcesList.to[Seq], maxRanges)} left.")
  }
}
