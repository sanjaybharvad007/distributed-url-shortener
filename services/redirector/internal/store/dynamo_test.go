package store

import (
	"context"
	"errors"
	"strconv"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb/types"
	"github.com/prometheus/client_golang/prometheus"

	"github.com/urlshortener/redirector/internal/metrics"
)

type fakeDynamo struct {
	items map[string]map[string]types.AttributeValue
	calls atomic.Int64
	delay time.Duration
	err   error
}

func (f *fakeDynamo) GetItem(ctx context.Context, in *dynamodb.GetItemInput, _ ...func(*dynamodb.Options)) (*dynamodb.GetItemOutput, error) {
	f.calls.Add(1)
	if f.delay > 0 {
		select {
		case <-time.After(f.delay):
		case <-ctx.Done():
			return nil, ctx.Err()
		}
	}
	if f.err != nil {
		return nil, f.err
	}
	code := in.Key["short_code"].(*types.AttributeValueMemberS).Value
	return &dynamodb.GetItemOutput{Item: f.items[code]}, nil
}

func (f *fakeDynamo) DescribeTable(context.Context, *dynamodb.DescribeTableInput, ...func(*dynamodb.Options)) (*dynamodb.DescribeTableOutput, error) {
	return &dynamodb.DescribeTableOutput{Table: &types.TableDescription{TableStatus: types.TableStatusActive}}, nil
}

func item(url, status string, expires int64) map[string]types.AttributeValue {
	it := map[string]types.AttributeValue{
		"long_url": &types.AttributeValueMemberS{Value: url},
		"status":   &types.AttributeValueMemberS{Value: status},
	}
	if expires > 0 {
		it["expires_at"] = &types.AttributeValueMemberN{Value: strconv.FormatInt(expires, 10)}
	}
	return it
}

func newStore(f *fakeDynamo) *DynamoStore {
	m := metrics.New(prometheus.NewRegistry())
	return NewWithAPI(f, "short_urls", 500*time.Millisecond, m)
}

func TestGetActiveLink(t *testing.T) {
	f := &fakeDynamo{items: map[string]map[string]types.AttributeValue{
		"abc1234": item("https://example.com/", "ACTIVE", time.Now().Add(time.Hour).Unix()),
	}}
	l, err := newStore(f).Get(context.Background(), "abc1234")
	if err != nil {
		t.Fatal(err)
	}
	if l.LongURL != "https://example.com/" || l.ExpiresAt.IsZero() {
		t.Fatalf("unexpected link %+v", l)
	}
}

func TestInactiveAndExpiredAreNotFound(t *testing.T) {
	f := &fakeDynamo{items: map[string]map[string]types.AttributeValue{
		"deleted": item("https://example.com/", "DELETED", 0),
		"expired": item("https://example.com/", "ACTIVE", time.Now().Add(-time.Minute).Unix()),
	}}
	s := newStore(f)
	for _, code := range []string{"deleted", "expired", "missing"} {
		if _, err := s.Get(context.Background(), code); !errors.Is(err, ErrNotFound) {
			t.Errorf("%s: expected ErrNotFound, got %v", code, err)
		}
	}
}

func TestSingleflightCoalescesConcurrentLookups(t *testing.T) {
	f := &fakeDynamo{
		items: map[string]map[string]types.AttributeValue{"hot1234": item("https://example.com/hot", "ACTIVE", 0)},
		delay: 50 * time.Millisecond,
	}
	s := newStore(f)
	var wg sync.WaitGroup
	for i := 0; i < 50; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if _, err := s.Get(context.Background(), "hot1234"); err != nil {
				t.Error(err)
			}
		}()
	}
	wg.Wait()
	if n := f.calls.Load(); n != 1 {
		t.Fatalf("expected 1 DynamoDB call for 50 concurrent lookups, got %d", n)
	}
}

func TestTimeoutIsEnforced(t *testing.T) {
	f := &fakeDynamo{delay: 2 * time.Second}
	m := metrics.New(prometheus.NewRegistry())
	s := NewWithAPI(f, "short_urls", 30*time.Millisecond, m)
	start := time.Now()
	_, err := s.Get(context.Background(), "slow1234")
	if err == nil {
		t.Fatal("expected timeout error")
	}
	if time.Since(start) > 500*time.Millisecond {
		t.Fatalf("timeout not enforced, took %v", time.Since(start))
	}
}

func TestBackendErrorsPropagate(t *testing.T) {
	f := &fakeDynamo{err: errors.New("throttled")}
	if _, err := newStore(f).Get(context.Background(), "x1234"); err == nil || errors.Is(err, ErrNotFound) {
		t.Fatalf("expected backend error, got %v", err)
	}
}
