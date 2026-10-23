#!/usr/bin/env bash
set -euo pipefail
fixture_path="${RUNNER_TEMP:?}/shizzicam-android-audio-${ANDROID_API_LEVEL:?}.json"
fixture_log="${RUNNER_TEMP}/shizzicam-android-audio-${ANDROID_API_LEVEL}.log"
rm -f "$fixture_path" "$fixture_path.stop"
(cd datapath && SHIZZI_ANDROID_AUDIO_FIXTURE_OUTPUT="$fixture_path" go test -count=1 -run '^TestCamAndroidAudioFixture$' -timeout=8m .) > "$fixture_log" 2>&1 &
fixture_pid=$!
cleanup(){ touch "$fixture_path.stop"; kill "$fixture_pid" 2>/dev/null || true; }
trap cleanup EXIT
python3 - "$fixture_path" <<'PY'
from pathlib import Path
import sys,time
p=Path(sys.argv[1])
for _ in range(1200):
    if p.is_file():break
    time.sleep(0.1)
else:raise SystemExit('Go Android audio fixture not ready')
PY
fixture_base64=$(base64 -w0 "$fixture_path")
./gradlew -PshizziClientsOnly=true :camrtc:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.fixture=$fixture_base64" --no-daemon --max-workers=2
touch "$fixture_path.stop"
wait "$fixture_pid"
trap - EXIT
