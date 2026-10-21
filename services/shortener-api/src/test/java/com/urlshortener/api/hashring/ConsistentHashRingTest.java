package com.urlshortener.api.hashring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.urlshortener.api.id.Base62;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConsistentHashRingTest {

  private static final List<String> NODES = List.of("redis-1:6379", "redis-2:6379", "redis-3:6379");

  @Test
  void matchesSharedConformanceVectors() throws Exception {
    JsonNode doc;
    try (InputStream in = getClass().getResourceAsStream("/ring-vectors.json")) {
      doc = new ObjectMapper().readTree(in);
    }
    List<String> nodes = new ArrayList<>();
    doc.get("nodes").forEach(n -> nodes.add(n.asText()));
    ConsistentHashRing<String> ring =
        new ConsistentHashRing<>(nodes, doc.get("virtualNodes").asInt());

    for (JsonNode v : doc.get("vectors")) {
      String key = v.get("key").asText();
      assertThat(MurmurHash3.hash32Unsigned(key))
          .as("hash of %s", key)
          .isEqualTo(v.get("hash32").asLong());
      assertThat(ring.indexFor(key)).as("shard of %s", key).isEqualTo(v.get("shard").asInt());
    }
  }

  @Test
  void murmurMatchesPublishedReferenceValues() {
    assertThat(MurmurHash3.hash32("")).isEqualTo(0);
    assertThat(MurmurHash3.hash32("hello")).isEqualTo(0x248bfa47);
    assertThat(MurmurHash3.hash32("The quick brown fox jumps over the lazy dog"))
        .isEqualTo(0x2e4ff723);
  }

  @Test
  void distributesKeysEvenly() {
    ConsistentHashRing<String> ring = new ConsistentHashRing<>(NODES, 150);
    int n = 100_000;
    int[] counts = new int[NODES.size()];
    for (int i = 0; i < n; i++) {
      counts[ring.indexFor("url:" + Base62.encode(i * 7919L + 13))]++;
    }
    double expected = (double) n / NODES.size();
    for (int c : counts) {
      assertThat(c).isBetween((int) (expected * 0.85), (int) (expected * 1.15));
    }
  }

  @Test
  void addingANodeMovesRoughlyOneOverNKeys() {
    ConsistentHashRing<String> three = new ConsistentHashRing<>(NODES, 150);
    List<String> four = new ArrayList<>(NODES);
    four.add("redis-4:6379");
    ConsistentHashRing<String> ringOfFour = new ConsistentHashRing<>(four, 150);

    int n = 100_000;
    int moved = 0;
    for (int i = 0; i < n; i++) {
      String key = "url:" + Base62.encode(i * 7919L + 13);
      if (three.indexFor(key) != ringOfFour.indexFor(key)) {
        moved++;
      }
    }
    // Ideal is exactly 1/4; allow some slack for virtual-node variance.
    assertThat((double) moved / n).isBetween(0.20, 0.30);
  }

  @Test
  void existingKeysStayOnTheirNodeWhenAnotherIsAdded() {
    ConsistentHashRing<String> three = new ConsistentHashRing<>(NODES, 150);
    List<String> four = new ArrayList<>(NODES);
    four.add("redis-4:6379");
    ConsistentHashRing<String> ringOfFour = new ConsistentHashRing<>(four, 150);
    for (int i = 0; i < 10_000; i++) {
      String key = "url:" + Base62.encode(i * 31L + 7);
      int before = three.indexFor(key);
      int after = ringOfFour.indexFor(key);
      // A key either stays put or moves to the *new* node, never between old nodes.
      assertThat(after == before || after == 3).isTrue();
    }
  }

  @Test
  void isDeterministic() {
    ConsistentHashRing<String> a = new ConsistentHashRing<>(NODES, 150);
    ConsistentHashRing<String> b = new ConsistentHashRing<>(NODES, 150);
    for (int i = 0; i < 1000; i++) {
      String key = "url:" + i;
      assertThat(a.nodeFor(key)).isEqualTo(b.nodeFor(key));
    }
  }

  @Test
  void rejectsEmptyRing() {
    assertThatThrownBy(() -> new ConsistentHashRing<>(List.of(), 150))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ConsistentHashRing<>(NODES, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
