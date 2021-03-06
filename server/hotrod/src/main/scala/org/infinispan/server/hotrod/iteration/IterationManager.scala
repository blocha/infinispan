package org.infinispan.server.hotrod.iteration

import java.util.stream.{Collectors, Stream}
import java.util.{Collections, UUID, BitSet => JavaBitSet, Set => JavaSet}

import org.infinispan.{BaseCacheStream, CacheStream}
import org.infinispan.commons.marshall.Marshaller
import org.infinispan.commons.util.CollectionFactory
import org.infinispan.configuration.cache.CompatibilityModeConfiguration
import org.infinispan.container.entries.CacheEntry
import org.infinispan.filter.CacheFilters.filterAndConvert
import org.infinispan.filter.{KeyValueFilterConverter, KeyValueFilterConverterFactory, ParamKeyValueFilterConverterFactory}
import org.infinispan.manager.EmbeddedCacheManager
import org.infinispan.server.hotrod.OperationStatus.OperationStatus
import org.infinispan.server.hotrod._
import org.infinispan.server.hotrod.logging.Log

import scala.collection.JavaConversions._
import scala.collection.mutable
import scala.language.existentials

/**
 * @author gustavonalle
 * @since 8.0
 */
trait IterationManager {
   type IterationId = String
   def start(cacheName: String, segments: Option[JavaBitSet], filterConverterFactory: NamedFactory, batch: Integer, metadata: Boolean): IterationId
   def next(cacheName: String, iterationId: IterationId): IterableIterationResult
   def close(cacheName: String, iterationId: IterationId): Boolean
   def addKeyValueFilterConverterFactory[K, V, C](name: String, factory: KeyValueFilterConverterFactory[K, V, C]): Unit
   def removeKeyValueFilterConverterFactory(name: String): Unit
   def setMarshaller(maybeMarshaller: Option[Marshaller]): Unit
   def activeIterations: Int
}

class IterationSegmentsListener extends BaseCacheStream.SegmentCompletionListener {
   private val finished = mutable.Set[Integer]()
   private var justFinished = mutable.Set[Integer]()

   def getFinished(endOfIteration: Boolean) = {
      synchronized {
         if (endOfIteration) finished ++ justFinished
         else {
            val diff = finished -- justFinished
            finished.clear()
            finished ++= justFinished
            diff
         }
      }
   }

   override def segmentCompleted(segments: JavaSet[Integer]): Unit = {
      if (!segments.isEmpty) {
         synchronized {
            justFinished = segments
            finished ++= segments
         }
      }
   }
}

class IterationState(val listener: IterationSegmentsListener, val iterator: Iterator[CacheEntry[AnyRef, AnyRef]],
                     val stream: CacheStream[CacheEntry[AnyRef, AnyRef]], val batch: Integer, val compatInfo: CompatInfo, val metadata: Boolean)

class IterableIterationResult(finishedSegments: JavaSet[Integer], val statusCode: OperationStatus, val entries: List[CacheEntry[AnyRef, AnyRef]], compatInfo: CompatInfo, val metadata: Boolean) {

   lazy val compatEnabled = compatInfo.enabled

   def segmentsToBytes = {
      val bs = new JavaBitSet()
      finishedSegments.stream().forEach((i: Integer) => bs.set(i))
      bs.toByteArray
   }

   def unbox(value: AnyRef) = compatInfo.hotRodTypeConverter.get.unboxValue(value)

}

class CompatInfo(val enabled: Boolean, val hotRodTypeConverter: Option[HotRodTypeConverter])

object CompatInfo {
   def apply(config: CompatibilityModeConfiguration) =
      new CompatInfo(config.enabled(), if (config.enabled()) Some(HotRodTypeConverter(config.marshaller())) else None)
}

class DefaultIterationManager(val cacheManager: EmbeddedCacheManager) extends IterationManager with Log {
   @volatile var marshaller: Option[_ <: Marshaller] = None

   private val iterationStateMap = CollectionFactory.makeConcurrentMap[String, IterationState]()
   private val filterConverterFactoryMap = CollectionFactory.makeConcurrentMap[String, KeyValueFilterConverterFactory[_, _, _]]()

   override def start(cacheName: String, segments: Option[JavaBitSet], namedFactory: NamedFactory, batch: Integer, metadata: Boolean): IterationId = {
      val iterationId = UUID.randomUUID().toString
      val stream = cacheManager.getCache(cacheName).getAdvancedCache.cacheEntrySet.stream().asInstanceOf[CacheStream[CacheEntry[AnyRef, AnyRef]]]
      segments.map(bitSet => {
         val segments = bitSet.stream().boxed().collect(Collectors.toSet[Integer])
         stream.filterKeySegments(segments)
      })

      val segmentListener = new IterationSegmentsListener
      val compatInfo = CompatInfo(cacheManager.getCache(cacheName).getCacheConfiguration.compatibility())

      val filteredStream = for {
         (name, params) <- namedFactory
         factory <- getFactory(name)
         (filter, isBinary) <- buildFilter(factory, params.toArray)
         iterationFilter <- Some(new IterationFilter(compatInfo.enabled, Some(filter), marshaller, isBinary))
         stream <- Some(filterAndConvert(stream.asInstanceOf[Stream[CacheEntry[Any, Any]]], iterationFilter.asInstanceOf[KeyValueFilterConverter[Any, Any, Any]]))
      } yield stream

      val iterator = filteredStream.getOrElse(stream).asInstanceOf[CacheStream[CacheEntry[AnyRef, AnyRef]]].segmentCompletionListener(segmentListener).iterator()

      val iterationState = new IterationState(segmentListener, iterator, stream, batch, compatInfo, metadata)

      iterationStateMap.put(iterationId, iterationState)
      iterationId
   }

   private def getFactory(name: String) =
      Option(filterConverterFactoryMap.get(name)).orElse(throw log.missingKeyValueFilterConverterFactory(name))

   private def buildFilter(factory: KeyValueFilterConverterFactory[_, _, _], params: Array[Bytes]) = {
      factory match {
         case f: ParamKeyValueFilterConverterFactory[_, _, _] =>
            val parameters: Array[AnyRef] = if (f.binaryParam()) params.toArray else unmarshallParams(params.toArray, f)
            Some(f.getFilterConverter(parameters), f.binaryParam())
         case f: KeyValueFilterConverterFactory[_, _, _] => Some(f.getFilterConverter, false)
      }
   }

   private def unmarshallParams(params: Array[Bytes], factory: AnyRef): Array[AnyRef] = {
      val m = marshaller.getOrElse(MarshallerBuilder.genericFromInstance(Some(factory)))
      params.map(m.objectFromByteBuffer)
   }

   override def next(cacheName: String, iterationId: IterationId): IterableIterationResult = {
      val iterationState = Option(iterationStateMap.get(iterationId))
      iterationState.map { state =>
         val iterator = state.iterator
         val listener = state.listener
         val batch = state.batch
         val entries = for (i <- 0 to batch - 1; if iterator.hasNext) yield iterator.next()
         new IterableIterationResult(listener.getFinished(entries.isEmpty), OperationStatus.Success, entries.toList, state.compatInfo, state.metadata)
      }.getOrElse(new IterableIterationResult(Collections.emptySet(), OperationStatus.InvalidIteration, List.empty, null, false))
   }

   override def close(cacheName: String, iterationId: IterationId): Boolean = {
      val iterationState = Option(iterationStateMap.get(iterationId))
      val removed = iterationState.map { state =>
         state.stream.close()
         iterationStateMap.remove(iterationId)
      }
      Option(removed).isDefined
   }

   override def addKeyValueFilterConverterFactory[K, V, C](name: String, factory: KeyValueFilterConverterFactory[K, V, C]): Unit = filterConverterFactoryMap.put(name, factory)

   override def removeKeyValueFilterConverterFactory(name: String) = filterConverterFactoryMap.remove(name)

   override def activeIterations: Int = iterationStateMap.size()

   override def setMarshaller(maybeMarshaller: Option[Marshaller]): Unit = this.marshaller = maybeMarshaller
}
