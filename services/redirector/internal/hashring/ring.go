package hashring

import (
	"errors"
	"sort"
	"strconv"
)

// DefaultVirtualNodes is the number of ring positions per physical node.
const DefaultVirtualNodes = 150

type point struct {
	hash  uint32
	owner int
}

// Ring is an immutable consistent-hash ring. Safe for concurrent use.
type Ring struct {
	nodes  []string
	points []point // sorted by hash, ties broken by owner index
}

// New builds a ring from an ordered list of node addresses.
func New(nodes []string, virtualNodes int) (*Ring, error) {
	if len(nodes) == 0 {
		return nil, errors.New("hashring: at least one node is required")
	}
	if virtualNodes < 1 {
		return nil, errors.New("hashring: virtualNodes must be >= 1")
	}
	points := make([]point, 0, len(nodes)*virtualNodes)
	for i, n := range nodes {
		for v := 0; v < virtualNodes; v++ {
			points = append(points, point{hash: HashString(n + "#" + strconv.Itoa(v)), owner: i})
		}
	}
	sort.Slice(points, func(a, b int) bool {
		if points[a].hash != points[b].hash {
			return points[a].hash < points[b].hash
		}
		return points[a].owner < points[b].owner
	})
	cp := make([]string, len(nodes))
	copy(cp, nodes)
	return &Ring{nodes: cp, points: points}, nil
}

// Index returns the index (into Nodes) of the node that owns key.
func (r *Ring) Index(key string) int {
	h := HashString(key)
	i := sort.Search(len(r.points), func(i int) bool { return r.points[i].hash >= h })
	if i == len(r.points) {
		i = 0
	}
	return r.points[i].owner
}

// Node returns the address of the node that owns key.
func (r *Ring) Node(key string) string {
	return r.nodes[r.Index(key)]
}

// Nodes returns the node list in ring order.
func (r *Ring) Nodes() []string {
	out := make([]string, len(r.nodes))
	copy(out, r.nodes)
	return out
}

// Size is the number of physical nodes.
func (r *Ring) Size() int {
	return len(r.nodes)
}
