package datapath

import (
	"bytes"
	"testing"
	"time"
)

func TestShizziCamFrameValidation(t *testing.T) {
	now := time.Now()
	jpeg := append([]byte{0xff, 0xd8, 0xff, 0xe0}, bytes.Repeat([]byte{3}, 32)...)
	jpeg = append(jpeg, 0xff, 0xd9)
	if !storeCamFrame("cam-test", jpeg, 87, now) {
		t.Fatal("valid frame rejected")
	}
	frames := camFrameSnapshot(now)
	if got := frames["cam-test"].battery; got != 87 {
		t.Fatalf("battery=%d", got)
	}
	if storeCamFrame("cam-bad", []byte("not-jpeg"), 99, now) {
		t.Fatal("invalid frame accepted")
	}
	if storeCamFrame("cam-too-large", bytes.Repeat([]byte{1}, camMaxFrameBytes+1), 0, now) {
		t.Fatal("oversize accepted")
	}
	if _, ok := camFrameSnapshot(now.Add(camTTL + time.Second))["cam-test"]; ok {
		t.Fatal("expired frame retained")
	}
}
