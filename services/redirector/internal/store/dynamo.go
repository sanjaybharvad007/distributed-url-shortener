// Package store reads short links from DynamoDB (the system of record).
package store

import (
	"context"
	"errors"
	"strconv"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	awsconfig "github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb/types"
	"golang.org/x/sync/singleflight"

	"github.com/urlshortener/redirector/internal/metrics"
)

// ErrNotFound is returned for unknown, deleted or expired codes.
var ErrNotFound = errors.New("short link not found")

// Link is the subset of a short_urls item the redirector needs.
type Link struct {
	Code      string
	LongURL   string
	ExpiresAt time.Time // zero = never
}

// Store is the read-only DynamoDB port used by the redirector.
type Store interface {
	Get(ctx context.Context, code string) (Link, error)
	Ping(ctx context.Context) error
}

// DynamoAPI is the slice of the DynamoDB client we use (for test doubles).
type DynamoAPI interface {
	GetItem(ctx context.Context, in *dynamodb.GetItemInput, opts ...func(*dynamodb.Options)) (*dynamodb.GetItemOutput, error)
	DescribeTable(ctx context.Context, in *dynamodb.DescribeTableInput, opts ...func(*dynamodb.Options)) (*dynamodb.DescribeTableOutput, error)
}

// DynamoStore implements Store on DynamoDB with single-flight de-duplication:
// a thundering herd on one cold code results in one GetItem.
type DynamoStore struct {
	api     DynamoAPI
	table   string
	timeout time.Duration
	group   singleflight.Group
	m       *metrics.Metrics
	now     func() time.Time
}

// New builds a DynamoStore from the default AWS config, honouring an optional
// endpoint override for DynamoDB Local.
func New(ctx context.Context, endpoint, table string, timeout time.Duration, m *metrics.Metrics) (*DynamoStore, error) {
	cfg, err := awsconfig.LoadDefaultConfig(ctx)
	if err != nil {
		return nil, err
	}
	client := dynamodb.NewFromConfig(cfg, func(o *dynamodb.Options) {
		if endpoint != "" {
			o.BaseEndpoint = aws.String(endpoint)
		}
	})
	return NewWithAPI(client, table, timeout, m), nil
}

// NewWithAPI builds a store over an existing client (used by tests).
func NewWithAPI(api DynamoAPI, table string, timeout time.Duration, m *metrics.Metrics) *DynamoStore {
	return &DynamoStore{api: api, table: table, timeout: timeout, m: m, now: time.Now}
}

// Get fetches an active link or returns ErrNotFound.
func (s *DynamoStore) Get(ctx context.Context, code string) (Link, error) {
	v, err, shared := s.group.Do(code, func() (any, error) {
		return s.get(ctx, code)
	})
	if shared {
		s.m.SingleflightHit.Inc()
	}
	if err != nil {
		return Link{}, err
	}
	return v.(Link), nil
}

func (s *DynamoStore) get(ctx context.Context, code string) (Link, error) {
	ctx, cancel := context.WithTimeout(ctx, s.timeout)
	defer cancel()

	start := time.Now()
	out, err := s.api.GetItem(ctx, &dynamodb.GetItemInput{
		TableName:            aws.String(s.table),
		Key:                  map[string]types.AttributeValue{"short_code": &types.AttributeValueMemberS{Value: code}},
		ProjectionExpression: aws.String("long_url, expires_at, #s"),
		ExpressionAttributeNames: map[string]string{
			"#s": "status",
		},
		ConsistentRead: aws.Bool(false), // eventually consistent is fine for redirects and half the cost
	})
	s.m.DynamoDuration.Observe(time.Since(start).Seconds())
	if err != nil {
		s.m.DynamoCalls.WithLabelValues("GetItem", "error").Inc()
		return Link{}, err
	}
	if len(out.Item) == 0 {
		s.m.DynamoCalls.WithLabelValues("GetItem", "notfound").Inc()
		return Link{}, ErrNotFound
	}
	link, ok := parse(code, out.Item, s.now())
	if !ok {
		s.m.DynamoCalls.WithLabelValues("GetItem", "inactive").Inc()
		return Link{}, ErrNotFound
	}
	s.m.DynamoCalls.WithLabelValues("GetItem", "ok").Inc()
	return link, nil
}

// parse converts an item into a Link, returning false if it is deleted or expired.
func parse(code string, item map[string]types.AttributeValue, now time.Time) (Link, bool) {
	if st, ok := item["status"].(*types.AttributeValueMemberS); ok && st.Value != "ACTIVE" {
		return Link{}, false
	}
	urlAttr, ok := item["long_url"].(*types.AttributeValueMemberS)
	if !ok || urlAttr.Value == "" {
		return Link{}, false
	}
	link := Link{Code: code, LongURL: urlAttr.Value}
	if exp, ok := item["expires_at"].(*types.AttributeValueMemberN); ok {
		if secs, err := strconv.ParseInt(exp.Value, 10, 64); err == nil {
			link.ExpiresAt = time.Unix(secs, 0)
			if !link.ExpiresAt.After(now) {
				return Link{}, false
			}
		}
	}
	return link, true
}

// Ping verifies the table is reachable and active.
func (s *DynamoStore) Ping(ctx context.Context) error {
	ctx, cancel := context.WithTimeout(ctx, s.timeout)
	defer cancel()
	out, err := s.api.DescribeTable(ctx, &dynamodb.DescribeTableInput{TableName: aws.String(s.table)})
	if err != nil {
		return err
	}
	if st := out.Table.TableStatus; st != types.TableStatusActive && st != types.TableStatusUpdating {
		return errors.New("table status " + string(st))
	}
	return nil
}
