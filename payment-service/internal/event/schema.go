package event

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"

	avro "github.com/hamba/avro/v2"
)

type subjectSchema struct {
	id     int32
	schema avro.Schema
}

// schemaCache resolves Avro schemas from the Confluent Schema Registry and
// memoizes both the latest schema for a subject and schemas by numeric ID.
// The registry is the single source of truth for contracts; payment-service
// never embeds its own .avsc copies (unlike the JVM side which compiles them
// from common/events/avro).
type schemaCache struct {
	base  string
	hc    *http.Client
	mu    sync.Mutex
	byID  map[int32]avro.Schema
	bySub map[string]subjectSchema
}

func newSchemaCache(baseURL string) (*schemaCache, error) {
	u, err := url.Parse(baseURL)
	if err != nil {
		return nil, fmt.Errorf("invalid schema registry url %q: %w", baseURL, err)
	}
	if !strings.HasPrefix(u.Scheme, "http") {
		return nil, fmt.Errorf("schema registry url %q: scheme must be http(s)", baseURL)
	}
	return &schemaCache{
		base:  strings.TrimRight(baseURL, "/"),
		hc:    &http.Client{Timeout: 5 * time.Second},
		byID:  make(map[int32]avro.Schema),
		bySub: make(map[string]subjectSchema),
	}, nil
}

// Latest returns the (cached) latest registered schema and registry ID for a subject.
func (c *schemaCache) Latest(ctx context.Context, subject string) (avro.Schema, int32, error) {
	c.mu.Lock()
	if ss, ok := c.bySub[subject]; ok {
		c.mu.Unlock()
		return ss.schema, ss.id, nil
	}
	c.mu.Unlock()

	endpoint := fmt.Sprintf("%s/subjects/%s/versions/latest", c.base, url.PathEscape(subject))
	var out struct {
		ID     int32  `json:"id"`
		Schema string `json:"schema"`
	}
	if err := c.getJSON(ctx, endpoint, &out); err != nil {
		return nil, 0, fmt.Errorf("resolve %s: %w", subject, err)
	}
	s, err := parseSchema(out.Schema)
	if err != nil {
		return nil, 0, fmt.Errorf("parse %s: %w", subject, err)
	}
	c.mu.Lock()
	c.bySub[subject] = subjectSchema{id: out.ID, schema: s}
	c.byID[out.ID] = s
	c.mu.Unlock()
	return s, out.ID, nil
}

// ByID returns the (cached) Avro schema for a numeric registry schema ID.
func (c *schemaCache) ByID(ctx context.Context, id int32) (avro.Schema, error) {
	c.mu.Lock()
	if s, ok := c.byID[id]; ok {
		c.mu.Unlock()
		return s, nil
	}
	c.mu.Unlock()

	endpoint := fmt.Sprintf("%s/schemas/ids/%d", c.base, id)
	var out struct {
		Schema string `json:"schema"`
	}
	if err := c.getJSON(ctx, endpoint, &out); err != nil {
		return nil, fmt.Errorf("resolve schema id %d: %w", id, err)
	}
	s, err := parseSchema(out.Schema)
	if err != nil {
		return nil, fmt.Errorf("parse schema id %d: %w", id, err)
	}
	c.mu.Lock()
	c.byID[id] = s
	c.mu.Unlock()
	return s, nil
}

func (c *schemaCache) getJSON(ctx context.Context, endpoint string, dst any) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
	if err != nil {
		return err
	}
	req.Header.Set("Accept", "application/vnd.schemaregistry.v1+json, application/json")
	resp, err := c.hc.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(io.LimitReader(resp.Body, 2048))
		return fmt.Errorf("schema registry %d: %s", resp.StatusCode, strings.TrimSpace(string(body)))
	}
	return json.NewDecoder(resp.Body).Decode(dst)
}

func parseSchema(raw string) (avro.Schema, error) {
	return avro.Parse(raw)
}