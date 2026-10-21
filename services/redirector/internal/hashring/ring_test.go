package hashring

import (
	"encoding/json"
	"fmt"
	"os"
	"testing"
)

var threeNodes = []string{"redis-1:6379", "redis-2:6379", "redis-3:6379"}

func TestMurmurReferenceValues(t *testing.T) {
	cases := map[string]uint32{
		"":      0,
		"hello": 0x248bfa47,
		"The quick brown fox jumps over the lazy dog": 0x2e4ff723,
	}
	for in, want := range cases {
		if got := HashString(in); got != want {
			t.Errorf("hash(%q) = %#x, want %#x", in, got, want)
		}
	}
}

func TestSharedConformanceVectors(t *testing.T) {
	raw, err := os.ReadFile("testdata/ring-vectors.json")
	if err != nil {
		t.Fatal(err)
	}
	var doc struct {
		Nodes        []string `json:"nodes"`
		VirtualNodes int      `json:"virtualNodes"`
		Vectors      []struct {
			Key    string `json:"key"`
			Hash32 uint32 `json:"hash32"`
			Shard  int    `json:"shard"`
		} `json:"vectors"`
	}
	if err := json.Unmarshal(raw, &doc); err != nil {
		t.Fatal(err)
	}
	ring, err := New(doc.Nodes, doc.VirtualNodes)
	if err != nil {
		t.Fatal(err)
	}
	for _, v := range doc.Vectors {
		if got := HashString(v.Key); got != v.Hash32 {
			t.Errorf("hash(%q) = %d, want %d", v.Key, got, v.Hash32)
		}
		if got := ring.Index(v.Key); got != v.Shard {
			t.Errorf("shard(%q) = %d, want %d", v.Key, got, v.Shard)
		}
	}
}

func TestDistribution(t *testing.T) {
	ring, _ := New(threeNodes, DefaultVirtualNodes)
	const n = 100000
	counts := make([]int, len(threeNodes))
	for i := 0; i < n; i++ {
		counts[ring.Index(fmt.Sprintf("url:%d", i*7919+13))]++
	}
	expected := float64(n) / float64(len(threeNodes))
	for i, c := range counts {
		if float64(c) < expected*0.85 || float64(c) > expected*1.15 {
			t.Errorf("node %d got %d keys, expected ~%.0f", i, c, expected)
		}
	}
}

func TestAddingNodeMovesAboutOneOverN(t *testing.T) {
	three, _ := New(threeNodes, DefaultVirtualNodes)
	four, _ := New(append(append([]string{}, threeNodes...), "redis-4:6379"), DefaultVirtualNodes)
	const n = 100000
	moved := 0
	for i := 0; i < n; i++ {
		k := fmt.Sprintf("url:%d", i*7919+13)
		before, after := three.Index(k), four.Index(k)
		if before != after {
			moved++
			if after != 3 {
				t.Fatalf("key %q moved between existing nodes (%d -> %d)", k, before, after)
			}
		}
	}
	frac := float64(moved) / n
	if frac < 0.20 || frac > 0.30 {
		t.Errorf("moved %.3f of keys, expected ~0.25", frac)
	}
}

func TestRejectsBadInput(t *testing.T) {
	if _, err := New(nil, 150); err == nil {
		t.Error("expected error for empty node list")
	}
	if _, err := New(threeNodes, 0); err == nil {
		t.Error("expected error for zero virtual nodes")
	}
}

func BenchmarkIndex(b *testing.B) {
	ring, _ := New(threeNodes, DefaultVirtualNodes)
	keys := make([]string, 1024)
	for i := range keys {
		keys[i] = fmt.Sprintf("url:%d", i)
	}
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		ring.Index(keys[i&1023])
	}
}
