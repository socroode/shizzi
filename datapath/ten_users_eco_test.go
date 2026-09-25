package datapath

import (
	"encoding/json"
	"fmt"
	"sync"
	"testing"
	"time"
)

// TestTenConcurrentEco1000FClients models ten simultaneous prepaid customers
// using the current Eco 1000F offer:
//   2 Mbps download / 1 Mbps upload / 12 GB / 30 days.
//
// It verifies simultaneous authorization, independent balances, per-client
// policy, live accounting and the existing 5 Mbps global upload ceiling.
func TestTenConcurrentEco1000FClients(t *testing.T) {
	const (
		users       = 10
		downBps     = int64(2_000_000)
		upBps       = int64(1_000_000)
		quotaBytes  = int64(12_000_000_000)
		durationMin = int64(30 * 24 * 60)

		downChunk = 250_000
		upChunk   = 100_000
	)

	manager := newTrafficManager()

	accounts := make([]PortalAccount, 0, users)
	passes := make([]PortalPass, 0, users)
	for i := 0; i < users; i++ {
		accounts = append(accounts, PortalAccount{
			Number:  fmt.Sprintf("700000%02d", i),
			Pin:     fmt.Sprintf("91%04d", i),
			Name:    fmt.Sprintf("ECO-%02d", i+1),
			Enabled: true,
		})
		passes = append(passes, PortalPass{
			Code:            fmt.Sprintf("ECO1K%02d", i),
			Name:            "Eco 1000F",
			DownloadBps:     downBps,
			UploadBps:       upBps,
			DownloadUnit:    "MBPS",
			UploadUnit:      "MBPS",
			QuotaBytes:      quotaBytes,
			QuotaUnit:       "GB",
			DurationMinutes: durationMin,
			DurationUnit:    "DAYS",
			Enabled:         true,
		})
	}

	raw, err := json.Marshal(portalConfigPayload{
		Title:    "TEKOMOPAO WIFI",
		Message:  "Eco 1000F concurrent test",
		Passes:   passes,
		Accounts: accounts,
	})
	if err != nil {
		t.Fatalf("encode portal config: %v", err)
	}
	manager.setPortalConfig(true, string(raw))

	ips := make([]string, users)
	before := time.Now().UnixMilli()
	for i := 0; i < users; i++ {
		ip := fmt.Sprintf("192.168.43.%d", 20+i)
		ips[i] = ip

		ok, message, token := manager.submitPortalAccountLoginWithSession(
			ip,
			accounts[i].Number,
			accounts[i].Pin,
		)
		if !ok || token == "" {
			t.Fatalf("user %d login failed: ok=%v token=%q message=%s", i+1, ok, token, message)
		}

		ok, message = manager.submitPortalRechargeWithSession(
			ip,
			passes[i].Code,
			token,
		)
		if !ok {
			t.Fatalf("user %d recharge failed: %s", i+1, message)
		}

		// Android applies the active prepaid plan as the live client rate.
		manager.setClientPolicy(ip, ClientPolicy{
			DownloadBitsPerSecond: downBps,
			UploadBitsPerSecond:   upBps,
		})

		account := manager.portalAccounts[accounts[i].Number]
		if account.DataBalanceBytes != quotaBytes {
			t.Fatalf("user %d balance=%d, want %d", i+1, account.DataBalanceBytes, quotaBytes)
		}
		if account.DataDownloadBps != downBps || account.DataUploadBps != upBps {
			t.Fatalf(
				"user %d rate=%d/%d, want %d/%d",
				i+1,
				account.DataDownloadBps,
				account.DataUploadBps,
				downBps,
				upBps,
			)
		}
		if account.DataExpiresAtMillis < before+durationMin*60_000-5_000 {
			t.Fatalf("user %d validity shorter than 30 days", i+1)
		}
		if !manager.portalAuthorizedFor(ip) {
			t.Fatalf("user %d not authorized after recharge", i+1)
		}
	}

	runConcurrent := func(dir direction, byteCount int) time.Duration {
		start := time.Now()
		var wg sync.WaitGroup
		errors := make(chan string, users)
		for i, ip := range ips {
			wg.Add(1)
			go func(user int, clientIP string) {
				defer wg.Done()
				if !manager.waitAllowed(clientIP, dir, byteCount) {
					errors <- fmt.Sprintf("user %d denied", user+1)
					return
				}
				manager.account(clientIP, dir, byteCount)
			}(i, ip)
		}
		wg.Wait()
		close(errors)
		for message := range errors {
			t.Error(message)
		}
		return time.Since(start)
	}

	downloadElapsed := runConcurrent(directionDownload, downChunk)
	uploadElapsed := runConcurrent(directionUpload, upChunk)

	// 10 x 100 KB = 1 MB of upload. The current manager has a 5 Mbps
	// aggregate upload ceiling and a 250 ms burst allowance.
	if uploadElapsed < 900*time.Millisecond {
		t.Fatalf(
			"global upload limiter appears bypassed: 10 users uploaded 1 MB in %s",
			uploadElapsed,
		)
	}

	var stats trafficStatsSnapshot
	if err := json.Unmarshal([]byte(manager.statsJSON()), &stats); err != nil {
		t.Fatalf("decode traffic stats: %v", err)
	}
	if len(stats.Clients) != users {
		t.Fatalf("client count=%d, want %d", len(stats.Clients), users)
	}
	if stats.TotalDownBytes != int64(users*downChunk) {
		t.Fatalf("download total=%d, want %d", stats.TotalDownBytes, users*downChunk)
	}
	if stats.TotalUpBytes != int64(users*upChunk) {
		t.Fatalf("upload total=%d, want %d", stats.TotalUpBytes, users*upChunk)
	}

	for i, ip := range ips {
		status := manager.portalUsageStatusFor(ip)
		if !status.Authenticated || !status.Authorized {
			t.Fatalf("user %d status not active: %+v", i+1, status)
		}
		wantRemaining := quotaBytes - int64(downChunk+upChunk)
		if status.RemainingBytes != wantRemaining {
			t.Fatalf(
				"user %d remaining=%d, want %d",
				i+1,
				status.RemainingBytes,
				wantRemaining,
			)
		}
		if status.Speed != "2.00 Mbps / 1.00 Mbps" {
			t.Fatalf("user %d speed=%q", i+1, status.Speed)
		}
	}

	t.Logf(
		"10 Eco 1000F users passed: down=%s, up=%s, total down=%d B, total up=%d B",
		downloadElapsed,
		uploadElapsed,
		stats.TotalDownBytes,
		stats.TotalUpBytes,
	)
}


// TestOppoReno9ProfileTenEcoUsersSustainedLoad is a virtual load profile for
// the user's OPPO Reno9 hotspot. It does not emulate ColorOS or the Wi-Fi radio;
// it stresses Shizzi's userspace datapath/accounting with 10 simultaneous Eco
// customers under the current default aggregate limits (40 Mbps down / 5 Mbps up).
func TestOppoReno9ProfileTenEcoUsersSustainedLoad(t *testing.T) {
	const (
		users      = 10
		rounds     = 10
		downBps    = int64(2_000_000)
		upBps      = int64(1_000_000)
		quotaBytes = int64(12_000_000_000)

		downChunk = 100_000
		upChunk   = 50_000
	)

	manager := newTrafficManager()
	manager.setGlobalPolicy(40_000_000, 5_000_000, 0)

	ips := make([]string, users)
	accounts := make([]PortalAccount, 0, users)
	passes := make([]PortalPass, 0, users)
	for i := 0; i < users; i++ {
		accounts = append(accounts, PortalAccount{
			Number:  fmt.Sprintf("710000%02d", i),
			Pin:     fmt.Sprintf("81%04d", i),
			Name:    fmt.Sprintf("RENO9-ECO-%02d", i+1),
			Enabled: true,
		})
		passes = append(passes, PortalPass{
			Code:            fmt.Sprintf("R9ECO%03d", i),
			Name:            "Eco 1000F",
			DownloadBps:     downBps,
			UploadBps:       upBps,
			DownloadUnit:    "MBPS",
			UploadUnit:      "MBPS",
			QuotaBytes:      quotaBytes,
			QuotaUnit:       "GB",
			DurationMinutes: 30 * 24 * 60,
			DurationUnit:    "DAYS",
			Enabled:         true,
		})
	}

	raw, err := json.Marshal(portalConfigPayload{
		Title:    "TEKOMOPAO WIFI",
		Message:  "OPPO Reno9 virtual load profile",
		Passes:   passes,
		Accounts: accounts,
	})
	if err != nil {
		t.Fatalf("encode portal config: %v", err)
	}
	manager.setPortalConfig(true, string(raw))

	for i := 0; i < users; i++ {
		ip := fmt.Sprintf("192.168.43.%d", 40+i)
		ips[i] = ip

		ok, message, token := manager.submitPortalAccountLoginWithSession(
			ip, accounts[i].Number, accounts[i].Pin,
		)
		if !ok || token == "" {
			t.Fatalf("user %d login failed: %s", i+1, message)
		}
		ok, message = manager.submitPortalRechargeWithSession(ip, passes[i].Code, token)
		if !ok {
			t.Fatalf("user %d recharge failed: %s", i+1, message)
		}

		manager.setClientPolicy(ip, ClientPolicy{
			DownloadBitsPerSecond: downBps,
			UploadBitsPerSecond:   upBps,
		})
	}

	start := time.Now()
	errCh := make(chan string, users*2)
	var wg sync.WaitGroup

	for i, ip := range ips {
		user := i + 1
		clientIP := ip

		wg.Add(2)

		go func() {
			defer wg.Done()
			for round := 0; round < rounds; round++ {
				if !manager.waitAllowed(clientIP, directionDownload, downChunk) {
					errCh <- fmt.Sprintf("user %d download denied at round %d", user, round+1)
					return
				}
				manager.account(clientIP, directionDownload, downChunk)
			}
		}()

		go func() {
			defer wg.Done()
			for round := 0; round < rounds; round++ {
				if !manager.waitAllowed(clientIP, directionUpload, upChunk) {
					errCh <- fmt.Sprintf("user %d upload denied at round %d", user, round+1)
					return
				}
				manager.account(clientIP, directionUpload, upChunk)
			}
		}()
	}

	wg.Wait()
	close(errCh)
	for message := range errCh {
		t.Error(message)
	}

	elapsed := time.Since(start)

	var stats trafficStatsSnapshot
	if err := json.Unmarshal([]byte(manager.statsJSON()), &stats); err != nil {
		t.Fatalf("decode traffic stats: %v", err)
	}

	wantDown := int64(users * rounds * downChunk)
	wantUp := int64(users * rounds * upChunk)
	if stats.TotalDownBytes != wantDown {
		t.Fatalf("download total=%d, want %d", stats.TotalDownBytes, wantDown)
	}
	if stats.TotalUpBytes != wantUp {
		t.Fatalf("upload total=%d, want %d", stats.TotalUpBytes, wantUp)
	}
	if len(stats.Clients) != users {
		t.Fatalf("client count=%d, want %d", len(stats.Clients), users)
	}

	// 5 MB total upload at a 5 Mbps global ceiling is about 8 seconds,
	// minus the limiter's 250 ms burst allowance. Allow generous CI variance.
	if elapsed < 6*time.Second {
		t.Fatalf("sustained load completed too quickly; global upload cap may be bypassed: %s", elapsed)
	}
	if elapsed > 30*time.Second {
		t.Fatalf("sustained virtual load unexpectedly slow: %s", elapsed)
	}

	wantPerUserRemaining := quotaBytes - int64(rounds*(downChunk+upChunk))
	for i, ip := range ips {
		status := manager.portalUsageStatusFor(ip)
		if !status.Authenticated || !status.Authorized {
			t.Fatalf("user %d lost authorization: %+v", i+1, status)
		}
		if status.RemainingBytes != wantPerUserRemaining {
			t.Fatalf(
				"user %d remaining=%d, want %d",
				i+1,
				status.RemainingBytes,
				wantPerUserRemaining,
			)
		}
		if status.Speed != "2.00 Mbps / 1.00 Mbps" {
			t.Fatalf("user %d speed=%q", i+1, status.Speed)
		}
	}

	t.Logf(
		"OPPO Reno9 virtual profile passed: 10 users, %d rounds, elapsed=%s, down=%d B, up=%d B",
		rounds,
		elapsed,
		stats.TotalDownBytes,
		stats.TotalUpBytes,
	)
}
