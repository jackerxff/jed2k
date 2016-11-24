package org.dkf.jed2k.kad;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import org.dkf.jed2k.Pair;
import org.dkf.jed2k.Time;
import org.dkf.jed2k.protocol.kad.KadId;

import java.net.InetSocketAddress;
import java.util.*;

/**
 * Created by inkpot on 23.11.2016.
 */
@Slf4j
public class RoutingTable {

    @Data
    @EqualsAndHashCode
    private static class RoutingTableNode {
        private ArrayList<NodeEntry> replacements = new ArrayList<>();
        private ArrayList<NodeEntry> live_nodes = new ArrayList<>();
        private long lastActive = Time.currentTime();
    }

    private ArrayList<RoutingTableNode> buckets = new ArrayList<>();
    private KadId self;

    // the last time need_bootstrap() returned true
    private long lastBootstrap;

    // the last time the routing table was refreshed.
    // this is used to stagger buckets needing refresh
    // to be at least 45 seconds apart.
    private long lastRefresh;

    // the last time we refreshed our own bucket
    // refreshed every 15 minutes
    private long lastSelfRefresh;

    // this is a set of all the endpoints that have
    // been identified as router nodes. They will
    // be used in searches, but they will never
    // be added to the routing table.
    Set<InetSocketAddress> routerNodes = new HashSet<>();

    // these are all the IPs that are in the routing
    // table. It's used to only allow a single entry
    // per IP in the whole table. Currently only for
    // IPv4
    //MultiSemultiset<address_v4::bytes_type> m_ips;

    private int bucketSize;

    public Pair<Integer, Integer> size() {
        int nodes = 0;
        int replacements = 0;
        for (final RoutingTableNode node: buckets) {
            nodes += node.getLive_nodes().size();
            replacements += node.getReplacements().size();
        }

        return Pair.make(nodes, replacements);
    }

    public int numGlobalNodes() {
        int deepestBucket = 0;
        int deepestSize = 0;
        for (final RoutingTableNode node: buckets) {
            deepestSize = node.getLive_nodes().size(); // + i->replacements.size();
            if (deepestSize < bucketSize) break;
            // this bucket is full
            ++deepestBucket;
        }

        if (deepestBucket == 0) return 1 + deepestSize;

        if (deepestSize < bucketSize / 2) return (1 << deepestBucket) * bucketSize;
        else return (2 << deepestBucket) * deepestSize;
    }

    public void touchBucket(final KadId target) {
        RoutingTableNode node = findBucket(target);
        node.setLastActive(Time.currentTime());
    }

    public RoutingTableNode findBucket(final KadId id) {

        int numBuckets = buckets.size();
        if (numBuckets == 0) {
            buckets.add(new RoutingTableNode());
            // add 160 seconds to prioritize higher buckets (i.e. buckets closer to us)
            buckets.get(buckets.size() - 1).setLastActive(Time.currentTime() + 160*1000);
            ++numBuckets;
        }

        int bucket_index = Math.min(KadId.TOTAL_BITS - 1 - KadId.distanceExp(self, id), numBuckets - 1);
        assert (bucket_index < buckets.size());
        assert (bucket_index >= 0);

        return buckets.get(bucket_index);
    }

    KadId needRefresh() {
        long now = Time.currentTime();

        // refresh our own bucket once every 15 minutes
        if (now - lastSelfRefresh > 15*1000*60) {
            lastSelfRefresh = now;
            log.debug("table need_refresh [ bucket: self target: {}]", self);
            return self;
        }

        if (buckets.isEmpty()) return null;

        RoutingTableNode bucket = Collections.min(buckets, new Comparator<RoutingTableNode>() {
            @Override
            public int compare(RoutingTableNode lhs, RoutingTableNode rhs) {
                // add the number of nodes to prioritize buckets with few nodes in them
                long diff = lhs.getLastActive() + (lhs.getLive_nodes().size() * 5)*1000 -
                        rhs.getLastActive() + (rhs.getLive_nodes().size() * 5)*1000;
                if (diff < 0) return -1;
                if (diff > 0) return 1;
                return 0;
            }
        });

        assert bucket != null;

        int i = buckets.indexOf(bucket);

        assert i >= 0;
        assert i < buckets.size();

        if (now - bucket.getLastActive() < 15*1000*60) return null;
        if (now - lastRefresh < 45*1000) return null;

        // generate a random node_id within the given bucket
        KadId target = new KadId(KadId.random(false));
        int num_bits = i + 1;  // std::distance(begin, itr) + 1 in C++
        KadId mask = new KadId();

        for (int j = 0; j < num_bits; ++j) {
            mask.set(j/8, (byte)(mask.at(j/8) | (byte)(0x80 >> (j&7))));
        };

        // target = (target & ~mask) | (root & mask)
        KadId root = new KadId(self);
        root.bitsAnd(mask);
        target.bitsAnd(mask.bitsInverse());
        target.bitsOr(root);

        // make sure this is in another subtree than m_id
        // clear the (num_bits - 1) bit and then set it to the
        // inverse of m_id's corresponding bit.
        int bitPos = (num_bits - 1) / 8;
        target.set(bitPos, (byte)(target.at(bitPos) & (byte)(~(0x80 >> ((num_bits - 1) % 8)))));
        target.set(bitPos, (byte)(target.at(bitPos) | (byte)((~(self.at(bitPos))) & (byte)(0x80 >> ((num_bits - 1) % 8)))));

        assert(KadId.distanceExp(self, target) == KadId.TOTAL_BITS - num_bits);

        log.debug("table need_refresh [ bucket: {} target: {} ]", num_bits, target);
        lastRefresh = now;
        return target;
    }

}