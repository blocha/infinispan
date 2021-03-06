package org.infinispan.client.hotrod.impl;

import org.infinispan.client.hotrod.CacheTopologyInfo;
import org.infinispan.client.hotrod.configuration.Configuration;
import org.infinispan.client.hotrod.impl.consistenthash.ConsistentHash;
import org.infinispan.client.hotrod.impl.consistenthash.ConsistentHashFactory;
import org.infinispan.client.hotrod.impl.consistenthash.SegmentConsistentHash;
import org.infinispan.client.hotrod.impl.protocol.HotRodConstants;
import org.infinispan.client.hotrod.logging.Log;
import org.infinispan.client.hotrod.logging.LogFactory;
import org.infinispan.commons.equivalence.AnyEquivalence;
import org.infinispan.commons.equivalence.ByteArrayEquivalence;
import org.infinispan.commons.util.CollectionFactory;
import org.infinispan.commons.util.Immutables;

import java.net.SocketAddress;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.IntStream.range;

/**
 * Maintains topology information about caches.
 *
 * @author gustavonalle
 */
public final class TopologyInfo {

   private static final Log log = LogFactory.getLog(TopologyInfo.class, Log.class);
   private static final boolean trace = log.isTraceEnabled();
   private static final byte[] EMPTY_BYTES = new byte[0];

   private Map<byte[], Collection<SocketAddress>> servers = CollectionFactory.makeMap(ByteArrayEquivalence.INSTANCE, AnyEquivalence.getInstance());
   private Map<byte[], ConsistentHash> consistentHashes = CollectionFactory.makeMap(ByteArrayEquivalence.INSTANCE, AnyEquivalence.getInstance());
   private Map<byte[], Integer> segmentsByCache = CollectionFactory.makeMap(ByteArrayEquivalence.INSTANCE, AnyEquivalence.getInstance());
   private Map<byte[], AtomicInteger> topologyIds = CollectionFactory.makeMap(ByteArrayEquivalence.INSTANCE, AnyEquivalence.getInstance());
   private final ConsistentHashFactory hashFactory = new ConsistentHashFactory();

   public TopologyInfo(AtomicInteger topologyId, Collection<SocketAddress> initialServers, Configuration configuration) {
      this.topologyIds.put(EMPTY_BYTES, topologyId);
      this.servers.put(EMPTY_BYTES, initialServers);
      this.hashFactory.init(configuration);
   }

   private Map<SocketAddress, Set<Integer>> getSegmentsByServer(byte[] cacheName) {
      ConsistentHash consistentHash = consistentHashes.get(cacheName);
      if (consistentHash != null) {
         return consistentHash.getSegmentsByServer();
      } else {
         Optional<Integer> numSegments = Optional.ofNullable(segmentsByCache.get(cacheName));
         Optional<Set<Integer>> segments = numSegments.map(n -> range(0, n).boxed().collect(Collectors.toSet()));
         return Immutables.immutableMapWrap(
               servers.get(cacheName).stream().collect(toMap(identity(), s -> segments.orElse(Collections.emptySet())))
         );
      }
   }

   public Collection<SocketAddress> getServers(byte[] cacheName) {
      return servers.computeIfAbsent(cacheName, k -> servers.get(EMPTY_BYTES));
   }

   public Collection<SocketAddress> getServers() {
      return servers.values().stream().flatMap(Collection::stream).collect(Collectors.toList());
   }

   public void updateTopology(Map<SocketAddress, Set<Integer>> servers2Hash, int numKeyOwners, short hashFunctionVersion, int hashSpace,
         byte[] cacheName, AtomicInteger topologyId) {
      ConsistentHash hash = hashFactory.newConsistentHash(hashFunctionVersion);
      if (hash == null) {
         log.noHasHFunctionConfigured(hashFunctionVersion);
      } else {
         hash.init(servers2Hash, numKeyOwners, hashSpace);
      }
      consistentHashes.put(cacheName, hash);
      topologyIds.put(cacheName, topologyId);
   }

   public void updateTopology(SocketAddress[][] segmentOwners, int numSegments, short hashFunctionVersion,
         byte[] cacheName, AtomicInteger topologyId) {
      if (hashFunctionVersion > 0) {
         SegmentConsistentHash hash = hashFactory.newConsistentHash(hashFunctionVersion);
         if (hash == null) {
            log.noHasHFunctionConfigured(hashFunctionVersion);
         } else {
            hash.init(segmentOwners, numSegments);
         }
         consistentHashes.put(cacheName, hash);
      }
      segmentsByCache.put(cacheName, numSegments);
      topologyIds.put(cacheName, topologyId);
   }

   public Optional<SocketAddress> getHashAwareServer(Object key, byte[] cacheName) {
      Optional<SocketAddress> server = Optional.empty();
      if (isTopologyValid(cacheName)) {
         ConsistentHash consistentHash = consistentHashes.get(cacheName);
         if (consistentHash != null) {
            server = Optional.of(consistentHash.getServer(key));
            if (trace) {
               log.tracef("Using consistent hash for determining the server: " + server);
            }
         }
         return server;
      }

      return Optional.empty();
   }

   public boolean isTopologyValid(byte[] cacheName) {
      Integer id = topologyIds.get(cacheName).get();
      Boolean valid = id != HotRodConstants.SWITCH_CLUSTER_TOPOLOGY;
      if (trace)
         log.tracef("Is topology id (%s) valid? %b", id, valid);

      return valid;
   }

   public void updateServers(byte[] cacheName, Collection<SocketAddress> updatedServers) {
      if (cacheName == null || cacheName.length == 0) {
         servers.keySet().forEach(k -> servers.put(k, updatedServers));
      } else {
         servers.put(cacheName, updatedServers);
      }
   }


   public ConsistentHash getConsistentHash(byte[] cacheName) {
      return consistentHashes.get(cacheName);
   }

   public ConsistentHashFactory getConsistentHashFactory() {
      return hashFactory;
   }

   public AtomicInteger createTopologyId(byte[] cacheName, int topologyId) {
      AtomicInteger id = new AtomicInteger(topologyId);
      this.topologyIds.put(cacheName, id);
      return id;
   }

   public void setTopologyId(byte[] cacheName, int topologyId) {
      AtomicInteger id = this.topologyIds.get(cacheName);
      id.set(topologyId);
   }

   public void setAllTopologyIds(int newTopologyId) {
      for (AtomicInteger topologyId : topologyIds.values())
         topologyId.set(newTopologyId);
   }

   public int getTopologyId(byte[] cacheName)  {
      return topologyIds.get(cacheName).get();
   }

   public CacheTopologyInfo getCacheTopologyInfo(byte[] cacheName) {
      return new CacheTopologyInfoImpl(getSegmentsByServer(cacheName), segmentsByCache.get(cacheName),
         topologyIds.get(cacheName).get());
   }

}
