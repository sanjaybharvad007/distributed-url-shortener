// Package hashring implements a consistent-hash ring with virtual nodes.
//
// The hash function and ring construction are byte-for-byte identical to the
// Java implementation in shortener-api (com.urlshortener.api.hashring) so that
// both services route a given key to the same Redis shard. Shared conformance
// vectors live in testdata/ring-vectors.json.
package hashring

import "math/bits"

const (
	c1 uint32 = 0xcc9e2d51
	c2 uint32 = 0x1b873593
)

// Murmur3_32 computes MurmurHash3 x86_32 of data with the given seed.
func Murmur3_32(data []byte, seed uint32) uint32 {
	h1 := seed
	n := len(data)
	nblocks := n / 4

	for i := 0; i < nblocks; i++ {
		b := data[i*4 : i*4+4]
		k1 := uint32(b[0]) | uint32(b[1])<<8 | uint32(b[2])<<16 | uint32(b[3])<<24
		k1 *= c1
		k1 = bits.RotateLeft32(k1, 15)
		k1 *= c2

		h1 ^= k1
		h1 = bits.RotateLeft32(h1, 13)
		h1 = h1*5 + 0xe6546b64
	}

	var k1 uint32
	tail := data[nblocks*4:]
	switch len(tail) {
	case 3:
		k1 ^= uint32(tail[2]) << 16
		fallthrough
	case 2:
		k1 ^= uint32(tail[1]) << 8
		fallthrough
	case 1:
		k1 ^= uint32(tail[0])
		k1 *= c1
		k1 = bits.RotateLeft32(k1, 15)
		k1 *= c2
		h1 ^= k1
	}

	h1 ^= uint32(n) //nolint:gosec // length is mixed in modulo 2^32 by the MurmurHash3 spec
	h1 ^= h1 >> 16
	h1 *= 0x85ebca6b
	h1 ^= h1 >> 13
	h1 *= 0xc2b2ae35
	h1 ^= h1 >> 16
	return h1
}

// HashString hashes a string with seed 0 (the convention shared with Java).
func HashString(s string) uint32 {
	return Murmur3_32([]byte(s), 0)
}
