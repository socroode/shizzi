package datapath

import (
	"fmt"
	"os"
	"runtime"
	"strconv"
	"testing"
	"time"
)

func TestCam18VirtualRelayEndurance(t *testing.T) {
	raw := os.Getenv("SHIZZICAM_ENDURANCE_SECONDS")
	if raw == "" {
		t.Skip("optional real-time virtual relay endurance")
	}
	seconds, e := strconv.Atoi(raw)
	if e != nil || seconds < 10 || seconds > 86400 {
		t.Fatal("invalid endurance duration")
	}
	camVideoHub.Lock()
	camVideoHub.cameras = map[string]*camH264Channel{}
	camVideoHub.Unlock()
	camOpusHub.Lock()
	camOpusHub.channels = map[string]*camOpusChannel{}
	camOpusHub.Unlock()
	var videoChannels []chan camH264Packet
	var audioChannel chan camOpusPacket
	now := time.Now()
	for i := 0; i < 8; i++ {
		id := fmt.Sprintf("endurance-%d", i)
		camH264Put(id, camH264Packet{data: []byte{0, 0, 0, 1, 0x67}, width: 1280, height: 720, flags: 2}, now)
		ch, _, ok := camH264Subscribe(id)
		if !ok {
			t.Fatal("video subscribe")
		}
		videoChannels = append(videoChannels, ch)
		defer camH264Unsubscribe(id, ch)
		if i == 0 {
			audioChannel, _, _ = camOpusSubscribe("listen:" + id)
			defer camOpusUnsubscribe("listen:"+id, audioChannel)
		}
	}
	video := time.NewTicker(time.Second / 22)
	defer video.Stop()
	audio := time.NewTicker(20 * time.Millisecond)
	defer audio.Stop()
	stop := time.NewTimer(time.Duration(seconds) * time.Second)
	defer stop.Stop()
	var startMemory, endMemory runtime.MemStats
	runtime.GC()
	runtime.ReadMemStats(&startMemory)
	payload := make([]byte, 12500)
	sound := make([]byte, 320)
	videoCount, audioCount := 0, 0
	started := time.Now()
	for {
		select {
		case at := <-video.C:
			for i, ch := range videoChannels {
				flags := byte(0)
				if videoCount%22 == 0 {
					flags = 1
				}
				if !camH264Put(fmt.Sprintf("endurance-%d", i), camH264Packet{data: payload, width: 1280, height: 720, flags: flags, ptsUs: uint64(at.UnixMicro())}, at) {
					t.Fatal("video rejected")
				}
				select {
				case <-ch:
				case <-time.After(time.Second):
					t.Fatal("video stall")
				}
			}
			videoCount++
		case at := <-audio.C:
			for i := 0; i < 8; i++ {
				if !camOpusPut(fmt.Sprintf("listen:endurance-%d", i), camOpusPacket{data: sound, ptsUs: uint64(at.UnixMicro())}, at) {
					t.Fatal("audio rejected")
				}
			}
			select {
			case <-audioChannel:
			case <-time.After(time.Second):
				t.Fatal("audio stall")
			}
			audioCount++
		case <-stop.C:
			runtime.GC()
			runtime.ReadMemStats(&endMemory)
			duration := time.Since(started).Seconds()
			t.Logf("VIRTUAL: 8 independent 720p-size payloads, one audio listener, %.1f seconds; video %.2f frames/s/camera, audio %.2f packets/s/camera; heap before=%d after=%d bytes. Synthetic payloads, no Android encoder, radio, or physical display.", duration, float64(videoCount)/duration, float64(audioCount)/duration, startMemory.HeapAlloc, endMemory.HeapAlloc)
			if float64(videoCount)/duration < 19 || float64(audioCount)/duration < 45 {
				t.Fatal("virtual relay throughput below test threshold")
			}
			return
		}
	}
}
