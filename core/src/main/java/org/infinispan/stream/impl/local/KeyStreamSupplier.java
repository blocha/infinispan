package org.infinispan.stream.impl.local;

import org.infinispan.AdvancedCache;
import org.infinispan.Cache;
import org.infinispan.commons.util.CloseableIterator;
import org.infinispan.context.Flag;
import org.infinispan.distribution.ch.ConsistentHash;
import org.infinispan.stream.impl.RemovableCloseableIterator;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;

import java.util.BitSet;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Stream supplier that is to be used when the underlying stream is composed by key instances.  This supplier will do
 * the proper filtering by assuming each element is the key itself.
 */
public class KeyStreamSupplier<K, V> implements AbstractLocalCacheStream.StreamSupplier<K, Stream<K>> {
   private static final Log log = LogFactory.getLog(KeyStreamSupplier.class);
   private static final boolean trace = log.isTraceEnabled();

   private final Cache<K, V> cache;
   private final ConsistentHash hash;
   private final Supplier<Stream<K>> supplier;

   public KeyStreamSupplier(Cache<K, V> cache, ConsistentHash hash, Supplier<Stream<K>> supplier) {
      this.cache = cache;
      this.hash = hash;
      this.supplier = supplier;
   }

   @Override
   public Stream<K> buildStream(Set<Integer> segmentsToFilter, Set<?> keysToFilter) {
      Stream<K> stream;
      if (keysToFilter != null) {
         if (trace) {
            log.tracef("Applying key filtering %s", keysToFilter);
         }
         // Make sure we aren't going remote to retrieve these
         AdvancedCache<K, V> advancedCache = cache.getAdvancedCache().withFlags(Flag.CACHE_MODE_LOCAL);
         stream = (Stream<K>) keysToFilter.stream().filter(k -> advancedCache.containsKey(k));
      } else {
         stream = supplier.get();
      }
      if (segmentsToFilter != null && hash != null) {
         if (trace) {
            log.tracef("Applying segment filter %s", segmentsToFilter);
         }
         BitSet bitSet = new BitSet(hash.getNumSegments());
         segmentsToFilter.forEach(bitSet::set);
         stream = stream.filter(k -> bitSet.get(hash.getSegment(k)));
      }
      return stream;
   }

   @Override
   public CloseableIterator<K> removableIterator(CloseableIterator<K> realIterator) {
      return new RemovableCloseableIterator<>(realIterator, cache, Function.identity());
   }
}
