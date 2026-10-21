package com.urlshortener.api.hashring;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Consistent-hash ring with virtual nodes.
 *
 * <p>Each physical node is placed on the ring {@code virtualNodes} times at the positions {@code
 * hash(node + "#" + i)}. A key is owned by the first virtual node at or after {@code hash(key)}
 * (wrapping around). Adding or removing a node therefore moves only ~1/N of the keys.
 *
 * <p>Immutable and thread-safe. The ring is built identically in the Go redirector; see the shared
 * test vectors.
 */
public final class ConsistentHashRing<N> {

  public static final int DEFAULT_VIRTUAL_NODES = 150;

  private final long[] positions; // sorted, unsigned 32-bit hashes
  private final int[] owners; // index into nodes, parallel to positions
  private final List<N> nodes;

  public ConsistentHashRing(List<N> nodes, int virtualNodes) {
    if (nodes == null || nodes.isEmpty()) {
      throw new IllegalArgumentException("at least one node is required");
    }
    if (virtualNodes < 1) {
      throw new IllegalArgumentException("virtualNodes must be >= 1");
    }
    this.nodes = Collections.unmodifiableList(new ArrayList<>(nodes));

    int total = nodes.size() * virtualNodes;
    long[] pos = new long[total];
    int[] own = new int[total];
    int idx = 0;
    for (int n = 0; n < nodes.size(); n++) {
      String label = String.valueOf(nodes.get(n));
      for (int v = 0; v < virtualNodes; v++) {
        pos[idx] = MurmurHash3.hash32Unsigned(label + "#" + v);
        own[idx] = n;
        idx++;
      }
    }
    // Sort positions and carry owners along. Ties (rare) resolve to the lower node index so that
    // Java and Go produce the same answer.
    Integer[] order = new Integer[total];
    for (int i = 0; i < total; i++) {
      order[i] = i;
    }
    Arrays.sort(
        order,
        (a, b) -> {
          int c = Long.compare(pos[a], pos[b]);
          return c != 0 ? c : Integer.compare(own[a], own[b]);
        });
    this.positions = new long[total];
    this.owners = new int[total];
    for (int i = 0; i < total; i++) {
      this.positions[i] = pos[order[i]];
      this.owners[i] = own[order[i]];
    }
  }

  /** Returns the node that owns {@code key}. */
  public N nodeFor(String key) {
    return nodes.get(indexFor(key));
  }

  /** Returns the index (in the original node list) of the node that owns {@code key}. */
  public int indexFor(String key) {
    long h = MurmurHash3.hash32Unsigned(key);
    int i = Arrays.binarySearch(positions, h);
    if (i < 0) {
      i = -i - 1; // insertion point = first position > h
    }
    if (i >= positions.length) {
      i = 0; // wrap around
    }
    return owners[i];
  }

  public List<N> nodes() {
    return nodes;
  }

  public int size() {
    return nodes.size();
  }
}
