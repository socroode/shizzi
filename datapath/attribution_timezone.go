package datapath

import (
	"fmt"
	"strings"
	"time"
)

// Only the privileged Android presence channel supplies this clock metadata.
// It affects interpretation of offset-less OS logs, never account identities
// or their expiration instants. Standard Android IDs use the platform tzdata;
// a custom OEM zone can fall back to Android's explicit current UTC offset.
func (r *flowAttributionResolver) setAndroidLogTimezone(rawZone string, offsetSeconds *int) {
	zone := strings.TrimSpace(rawZone)
	var location *time.Location
	source, problem := "", ""
	if zone != "" && len(zone) <= 128 && zone != "Local" {
		var err error
		location, err = time.LoadLocation(zone)
		if err == nil {
			source = "android-zone"
		} else {
			problem = "Android timezone unavailable: " + zone
		}
	} else {
		problem = "Android timezone missing or invalid"
	}
	if location == nil && offsetSeconds != nil && *offsetSeconds >= -18*3600 && *offsetSeconds <= 18*3600 {
		offset := *offsetSeconds
		sign := "+"
		absolute := offset
		if absolute < 0 {
			sign, absolute = "-", -absolute
		}
		zone = fmt.Sprintf("UTC%s%02d:%02d", sign, absolute/3600, (absolute%3600)/60)
		if absolute%60 != 0 {
			zone += fmt.Sprintf(":%02d", absolute%60)
		}
		location = time.FixedZone(zone, offset)
		source, problem = "android-offset", ""
	}
	if location == nil {
		zone = ""
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	if zone != r.logTimezone || source != r.logTimezoneSource || problem != r.logTimezoneError {
		r.logClockGeneration++
		r.bpfFailedFlows = make(map[flowAttributionKey]string)
		r.recentBPFFailures = 0
		r.bpfDiagnostics = bpfFailureDiagnostics{}
		r.lastRefresh = time.Time{}
	}
	r.logLocation = location
	r.logTimezone = zone
	r.logTimezoneKnown = location != nil
	r.logTimezoneSource = source
	r.logTimezoneError = problem
	r.logClockRequired = true
}
