package datapath

import (
	"encoding/json"
	"fmt"
	"strings"
	"testing"
	"time"
)

func TestRectificatifAndroidLogTimezones(t *testing.T) {
	for _, name := range []string{"Pacific/Tahiti", "UTC", "Asia/Kathmandu", "Europe/Paris"} {
		t.Run(name, func(t *testing.T) {
			zone, err := time.LoadLocation(name)
			if err != nil {
				t.Fatal(err)
			}
			now := time.Now().UTC()
			raw := bpfFailureLine(now.Add(-2*time.Second).In(zone), "192.168.7.61", "192.0.2.2", "41000", "51000", "203.0.113.10")
			if name == "Pacific/Tahiti" {
				_, oldCount := parseRecentBPFFailedAttributions(raw, now)
				if oldCount != 0 {
					t.Fatal("UTC-only interpretation unexpectedly recovered Tahiti log")
				}
			}
			r := newFlowAttributionResolver()
			payload, _ := json.Marshal(map[string]any{"androidTimezone": name})
			r.setPresenceJSON(string(payload))
			r.dumpFn = func() (string, error) { return raw, nil }
			key := flowAttributionKey{"tcp", "192.0.2.2", 51000, "203.0.113.10", 443}
			if got := r.resolve(key, false); got != "192.168.7.61" {
				t.Fatalf("resolved %q", got)
			}
			s := r.snapshot()
			if !s.AndroidTimezoneKnown || s.AndroidTimezone != name || s.RecentBPFFailures != 1 || s.BPFObservedFailures != 1 || s.BPFRejectedTimestamps != 0 {
				t.Fatalf("clock/failure diagnostics: %+v", s)
			}
		})
	}
}

func TestRectificatifClockFallbackAndMissingClock(t *testing.T) {
	r := newFlowAttributionResolver()
	r.setPresenceJSON(`{"androidTimezone":"OEM/Custom","androidUtcOffsetSeconds":-36000}`)
	s := r.snapshot()
	if !s.AndroidTimezoneKnown || s.AndroidTimezone != "UTC-10:00" || s.AndroidTimezoneSource != "android-offset" {
		t.Fatalf("offset fallback: %+v", s)
	}
	zone := time.FixedZone("phone", -36000)
	raw := bpfFailureLine(time.Now().Add(-2*time.Second).In(zone), "192.168.7.61", "192.0.2.2", "41000", "51000", "203.0.113.10")
	r.dumpFn = func() (string, error) { return raw, nil }
	key := flowAttributionKey{"tcp", "192.0.2.2", 51000, "203.0.113.10", 443}
	if r.resolve(key, false) != "192.168.7.61" {
		t.Fatal("explicit Android offset did not recover flow")
	}
	for _, bad := range []string{`{"androidTimezone":"OEM/Custom"}`, `{"androidTimezone":"Local"}`, `{"androidTimezone":"","androidUtcOffsetSeconds":86400}`} {
		r.setPresenceJSON(bad)
		if got := r.resolve(key, false); got != "" {
			t.Fatalf("invalid clock admitted %q", got)
		}
		s = r.snapshot()
		if s.AndroidTimezoneKnown || s.BPFClockSkipped != 1 || s.TimezoneError == "" {
			t.Fatalf("missing clock diagnostics: %+v", s)
		}
	}
}

func TestRectificatifClockChangePurgesCachedLogIdentity(t *testing.T) {
	zone, _ := time.LoadLocation("Pacific/Tahiti")
	raw := bpfFailureLine(time.Now().Add(-2*time.Second).In(zone), "192.168.7.61", "192.0.2.2", "41000", "51000", "203.0.113.10")
	r := newFlowAttributionResolver()
	r.setPresenceJSON(`{"androidTimezone":"Pacific/Tahiti"}`)
	r.dumpFn = func() (string, error) { return raw, nil }
	key := flowAttributionKey{"tcp", "192.0.2.2", 51000, "203.0.113.10", 443}
	if r.resolve(key, false) != "192.168.7.61" {
		t.Fatal("initial recovery failed")
	}
	r.setPresenceJSON(`{"androidTimezone":"UTC"}`)
	if r.resolve(key, false) != "" {
		t.Fatal("clock change reused old log identity")
	}
	if s := r.snapshot(); s.BPFRejectedTimestamps != 1 || s.RecentBPFFailures != 0 {
		t.Fatalf("stale clock diagnostics: %+v", s)
	}
}

func TestRectificatifTimezoneKeepsStrictAgeAndTupleChecks(t *testing.T) {
	zone, _ := time.LoadLocation("Pacific/Tahiti")
	now := time.Now().In(zone)
	lines := []string{
		bpfFailureLine(now.Add(-2*time.Second), "192.168.7.61", "192.0.2.2", "41000", "51000", "203.0.113.10"),
		bpfFailureLine(now.Add(-9*time.Second), "192.168.7.62", "192.0.2.2", "41000", "51001", "203.0.113.10"),
		bpfFailureLine(now.Add(3*time.Second), "192.168.7.63", "192.0.2.2", "41000", "51002", "203.0.113.10"),
	}
	flows, count, diagnostic := parseBPFFailureAttributions(strings.Join(lines, "\n"), now, true)
	if len(flows) != 1 || count != 1 || diagnostic.observed != 3 || diagnostic.rejectedTimestamps != 2 {
		t.Fatalf("age window changed: %d/%+v", count, diagnostic)
	}
	r := newFlowAttributionResolver()
	r.setPresenceJSON(`{"androidTimezone":"Pacific/Tahiti"}`)
	r.dumpFn = func() (string, error) { return lines[0], nil }
	for _, key := range []flowAttributionKey{
		{"udp", "192.0.2.2", 51000, "203.0.113.10", 443},
		{"tcp", "192.0.2.2", 51000, "203.0.113.11", 443},
		{"tcp", "192.0.2.2", 51000, "203.0.113.10", 80},
	} {
		if got := r.resolve(key, false); got != "" {
			t.Fatalf("different tuple inherited client %q", got)
		}
	}
}

func TestRectificatifPartialFailuresStayVisibleAfterOtherClientsRecover(t *testing.T) {
	r := newFlowAttributionResolver()
	r.setPresenceJSON(`{"androidTimezone":"UTC"}`)
	r.dumpFn = func() (string, error) { return "", fmt.Errorf("simulated tethering timeout") }
	missing := flowAttributionKey{"tcp", "192.0.2.2", 51002, "203.0.113.10", 443}
	if r.resolve(missing, false) != "" {
		t.Fatal("failed dump resolved an identity")
	}
	r.dumpFn = func() (string, error) { return sampleTetheringDump, nil }
	r.refreshIfOlderThan(0)
	known := flowAttributionKey{"tcp", "192.0.2.2", 61001, "142.250.74.14", 443}
	if r.resolve(known, false) != "192.168.43.20" {
		t.Fatal("other client did not recover")
	}
	s := r.snapshot()
	if s.LastError != "" || s.LastMiss != "" || s.DumpFailures != 1 || s.LastDumpError == "" || s.LastDumpErrorMillis == 0 || s.LastUnresolvedFlow != formatFlowAttributionKey(missing) || s.LastUnresolvedMillis == 0 {
		t.Fatalf("partial failure disappeared from diagnostics: %+v", s)
	}
}
