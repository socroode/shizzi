package datapath

import (
	"bufio"
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
	"testing"
	"time"
)

func TestAdminFileProxyRequiresAuthenticatedAdminSession(t *testing.T) {
	manager := newTrafficManager()
	manager.adminConfig.Enabled = true

	server, client := net.Pipe()
	done := make(chan struct{})
	go func() {
		defer close(done)
		defer server.Close()
		request, err := http.NewRequest(
			http.MethodGet,
			"http://192.0.2.1/api/v1/admin/files/upload/status?id=test",
			nil,
		)
		if err != nil {
			t.Errorf("request: %v", err)
			return
		}
		manager.serveAdminAPI(server, request, "192.168.7.66")
	}()

	response, err := io.ReadAll(client)
	_ = client.Close()
	<-done
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(response), "401 Unauthorized") {
		t.Fatalf("unauthenticated file API was not rejected: %q", string(response))
	}
}

func TestAdminFileProxyForwardsAuthenticatedRequestToLoopbackBackend(t *testing.T) {
	backend, err := net.Listen("tcp", adminFileBridgeAddress)
	if err != nil {
		t.Skipf("admin file test port unavailable: %v", err)
	}
	defer backend.Close()

	backendRequest := make(chan string, 1)
	go func() {
		conn, acceptErr := backend.Accept()
		if acceptErr != nil {
			return
		}
		defer conn.Close()
		_ = conn.SetDeadline(time.Now().Add(3 * time.Second))
		reader := bufio.NewReader(conn)
		var received strings.Builder
		for {
			line, readErr := reader.ReadString('\n')
			if readErr != nil {
				return
			}
			received.WriteString(line)
			if line == "\r\n" {
				break
			}
		}
		backendRequest <- received.String()
		body := "{\"ok\":true,\"receivedBytes\":1048576}"
		_, _ = fmt.Fprintf(
			conn,
			"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: %d\r\nConnection: close\r\n\r\n%s",
			len(body),
			body,
		)
	}()

	manager := newTrafficManager()
	manager.adminConfig.Enabled = true
	token := "admin-test-token"
	manager.adminSessions[token] = &adminSession{
		Token: token,
		IP: "192.168.7.66",
		LastSeenMillis: time.Now().UnixMilli(),
	}

	server, client := net.Pipe()
	done := make(chan struct{})
	go func() {
		defer close(done)
		defer server.Close()
		request, requestErr := http.NewRequest(
			http.MethodGet,
			"http://192.0.2.1/api/v1/admin/files/upload/status?id=abc123",
			nil,
		)
		if requestErr != nil {
			t.Errorf("request: %v", requestErr)
			return
		}
		request.Header.Set("X-Shizzi-Admin-Token", token)
		manager.serveAdminAPI(server, request, "192.168.7.66")
	}()

	response, readErr := io.ReadAll(client)
	_ = client.Close()
	<-done
	if readErr != nil {
		t.Fatal(readErr)
	}
	select {
	case raw := <-backendRequest:
		if !strings.Contains(raw, "GET /upload/status?id=abc123 HTTP/1.1") {
			t.Fatalf("unexpected backend request: %q", raw)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("backend did not receive Admin file request")
	}
	if !strings.Contains(string(response), "\"receivedBytes\":1048576") {
		t.Fatalf("backend response was not proxied: %q", string(response))
	}
}


func TestAdminFileProxyPreservesChunkLengthAndBody(t *testing.T) {
	backend, err := net.Listen("tcp", adminFileBridgeAddress)
	if err != nil {
		t.Skipf("admin file test port unavailable: %v", err)
	}
	defer backend.Close()

	backendRequest := make(chan string, 1)
	go func() {
		conn, acceptErr := backend.Accept()
		if acceptErr != nil {
			return
		}
		defer conn.Close()
		_ = conn.SetDeadline(time.Now().Add(3 * time.Second))
		reader := bufio.NewReader(conn)
		var headers strings.Builder
		for {
			line, readErr := reader.ReadString('\n')
			if readErr != nil {
				return
			}
			headers.WriteString(line)
			if line == "\r\n" {
				break
			}
		}
		body := make([]byte, 5)
		if _, readErr := io.ReadFull(reader, body); readErr != nil {
			return
		}
		backendRequest <- headers.String() + "|" + string(body)
		responseBody := "{\"ok\":true,\"receivedBytes\":5}"
		_, _ = fmt.Fprintf(
			conn,
			"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: %d\r\nConnection: close\r\n\r\n%s",
			len(responseBody),
			responseBody,
		)
	}()

	manager := newTrafficManager()
	manager.adminConfig.Enabled = true
	token := "admin-test-token"
	manager.adminSessions[token] = &adminSession{
		Token: token,
		IP: "192.168.7.66",
		LastSeenMillis: time.Now().UnixMilli(),
	}

	server, client := net.Pipe()
	done := make(chan struct{})
	go func() {
		defer close(done)
		defer server.Close()
		request, requestErr := http.NewRequest(
			http.MethodPut,
			"http://192.0.2.1/api/v1/admin/files/upload/chunk?id=abc&offset=0",
			strings.NewReader("abcde"),
		)
		if requestErr != nil {
			t.Errorf("request: %v", requestErr)
			return
		}
		request.Header.Set("Content-Type", "application/octet-stream")
		request.Header.Set("X-Shizzi-Admin-Token", token)
		manager.serveAdminAPI(server, request, "192.168.7.66")
	}()

	response, readErr := io.ReadAll(client)
	_ = client.Close()
	<-done
	if readErr != nil {
		t.Fatal(readErr)
	}
	select {
	case raw := <-backendRequest:
		if !strings.Contains(raw, "Content-Length: 5\r\n") {
			t.Fatalf("Content-Length was not preserved: %q", raw)
		}
		if !strings.HasSuffix(raw, "|abcde") {
			t.Fatalf("binary body was not forwarded: %q", raw)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("backend did not receive upload chunk")
	}
	if !strings.Contains(string(response), "\"receivedBytes\":5") {
		t.Fatalf("chunk response was not proxied: %q", string(response))
	}
}
