package net.leaf.treegen.common;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Caches generated trees to avoid immediate repetition and save CPU.
 * Uses a simple LRU cache for memory management.
 */
public final class TreeCache {
    private final int capacity;
    private final int maxReuses;
    private final Map<CacheKey, CachedModel> cache;

    private record CacheKey(String speciesId, long seed) {}

    private static final class CachedModel {
        final SegmentedTreeModel model;
        int uses;

        CachedModel(SegmentedTreeModel model) {
            this.model = model;
            this.uses = 0;
        }
    }

    public TreeCache(int capacity, int maxReuses) {
        this.capacity = capacity;
        this.maxReuses = maxReuses;
        this.cache = new LinkedHashMap<>(capacity, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<CacheKey, CachedModel> eldest) {
                return size() > TreeCache.this.capacity;
            }
        };
    }

    public SegmentedTreeModel getOrGenerate(String speciesId, long seed, GeneratorFunction generator) {
        CacheKey key = new CacheKey(speciesId, seed);

        synchronized (cache) {
            CachedModel cached = cache.get(key);
            if (cached != null) {
                cached.uses++;
                if (maxReuses > 0 && cached.uses >= maxReuses) {
                    cache.remove(key);
                }
                return cached.model;
            }
        }

        TreeModel model = generator.generate(seed);
        if (model == null) return null;

        SegmentedTreeModel segmented = new SegmentedTreeModel(model);
        synchronized (cache) {
            cache.put(key, new CachedModel(segmented));
        }
        return segmented;
    }

    @FunctionalInterface
    public interface GeneratorFunction {
        TreeModel generate(long seed);
    }
}
