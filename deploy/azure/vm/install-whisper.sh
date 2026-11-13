#!/usr/bin/env bash
set -euo pipefail
umask 027

# Ubuntu 24.04, CPU only. Source and model are immutable and verified.
# No API key, account, pip package or network access is needed at runtime.
WHISPER_CPP_COMMIT="d1be6fde11ac6e0407606b4e42fe72d34add8037" # v1.9.5
MODEL_REVISION="5359861c739e955e79d9a303bcbc70fb988958b1"
MODEL_SHA256="c577b9a86e7e048a0b7eada054f4dd79a56bbfa911fbdacf900ac5b567cbb7d9"
PREFIX="/opt/classforge/whisper"
DATA_DIR="/opt/classforge/data/whisper"
MODEL_NAME="ggml-base-q8_0.bin" # Multilingual; do not use a .en model for Spanish.
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"

if [[ ${EUID} -ne 0 ]]; then
  echo "Run with sudo: sudo bash deploy/azure/vm/install-whisper.sh" >&2
  exit 1
fi
# shellcheck source=/dev/null
source /etc/os-release
if [[ ${ID} != ubuntu || ${VERSION_ID} != 24.04 ]]; then
  echo "This installer supports Ubuntu 24.04." >&2
  exit 1
fi
for asset in whisper-local.py classforge-whisper.service; do
  if [[ ! -s "$SCRIPT_DIR/$asset" ]]; then
    echo "Missing installer asset: $asset" >&2
    exit 1
  fi
done

BUILD_ROOT="$(mktemp -d /tmp/classforge-whisper.XXXXXX)"
trap 'rm -rf -- "$BUILD_ROOT"' EXIT
SOURCE_DIR="$BUILD_ROOT/source"

apt-get -o Acquire::Retries=3 update
DEBIAN_FRONTEND=noninteractive apt-get -o Acquire::Retries=3 install -y --no-install-recommends \
  build-essential ca-certificates cmake curl git libgomp1 python3

getent group classforge >/dev/null || groupadd --system classforge
if ! id classforge >/dev/null 2>&1; then
  useradd --system --gid classforge --no-create-home --home-dir /opt/classforge \
    --shell /usr/sbin/nologin classforge
fi

git init --quiet "$SOURCE_DIR"
git -C "$SOURCE_DIR" remote add origin https://github.com/ggml-org/whisper.cpp.git
git -C "$SOURCE_DIR" fetch --quiet --depth 1 origin "$WHISPER_CPP_COMMIT"
git -C "$SOURCE_DIR" checkout --quiet --detach FETCH_HEAD
if [[ "$(git -C "$SOURCE_DIR" rev-parse HEAD)" != "$WHISPER_CPP_COMMIT" ]]; then
  echo "Unexpected whisper.cpp source revision." >&2
  exit 1
fi
cmake -S "$SOURCE_DIR" -B "$SOURCE_DIR/build" \
  -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF \
  -DWHISPER_BUILD_IS_DEV=OFF -DWHISPER_BUILD_EXAMPLES=ON \
  -DWHISPER_BUILD_SERVER=ON -DWHISPER_BUILD_TESTS=OFF \
  -DGGML_BACKEND_DL=OFF -DGGML_NATIVE=ON -DGGML_OPENMP=ON \
  -DGGML_CUDA=OFF -DGGML_METAL=OFF -DGGML_VULKAN=OFF -DGGML_BLAS=OFF
cmake --build "$SOURCE_DIR/build" --config Release --target whisper-server --parallel 2
"$SOURCE_DIR/build/bin/whisper-server" --help >/dev/null 2>&1

if [[ -f "$DATA_DIR/$MODEL_NAME" ]] && \
   echo "$MODEL_SHA256  $DATA_DIR/$MODEL_NAME" | sha256sum --check --status; then
  MODEL_SOURCE="$DATA_DIR/$MODEL_NAME"
else
  MODEL_SOURCE="$BUILD_ROOT/$MODEL_NAME"
  curl --fail --location --retry 3 --connect-timeout 20 --max-time 600 \
    "https://huggingface.co/ggerganov/whisper.cpp/resolve/$MODEL_REVISION/$MODEL_NAME" \
    --output "$MODEL_SOURCE"
  echo "$MODEL_SHA256  $MODEL_SOURCE" | sha256sum --check --status
fi

# Compile/download failures above leave a running installation untouched.
systemctl stop classforge-whisper.service 2>/dev/null || true
install -d -o root -g classforge -m 0750 "$PREFIX" "$DATA_DIR"
install -o root -g classforge -m 0750 "$SOURCE_DIR/build/bin/whisper-server" "$PREFIX/whisper-server"
install -o root -g classforge -m 0750 "$SCRIPT_DIR/whisper-local.py" "$PREFIX/whisper-local.py"
if [[ "$MODEL_SOURCE" != "$DATA_DIR/$MODEL_NAME" ]]; then
  install -o root -g classforge -m 0640 "$MODEL_SOURCE" "$DATA_DIR/$MODEL_NAME"
fi
chown root:classforge "$DATA_DIR/$MODEL_NAME"
chmod 0640 "$DATA_DIR/$MODEL_NAME"
install -o root -g classforge -m 0640 "$SOURCE_DIR/samples/jfk.wav" "$DATA_DIR/smoke.wav"
install -o root -g root -m 0644 "$SCRIPT_DIR/classforge-whisper.service" \
  /etc/systemd/system/classforge-whisper.service
printf 'whisper.cpp v1.9.5 %s\nmodel %s sha256 %s\n' \
  "$WHISPER_CPP_COMMIT" "$MODEL_NAME" "$MODEL_SHA256" > "$PREFIX/VERSION"

systemctl daemon-reload
systemctl enable --now classforge-whisper.service
READY=false
for ((attempt = 0; attempt < 45; attempt++)); do
  if curl --fail --silent --max-time 2 http://127.0.0.1:8093/health > "$BUILD_ROOT/health.json"; then
    if python3 - "$BUILD_ROOT/health.json" <<'PY'
import json, sys
assert json.load(open(sys.argv[1], encoding="utf-8")).get("status") == "ok"
PY
    then
      READY=true
      break
    fi
  fi
  if ! systemctl is-active --quiet classforge-whisper.service; then
    break
  fi
  sleep 1
done
if [[ "$READY" != true ]]; then
  echo "Whisper did not become ready. Inspect: journalctl -u classforge-whisper -n 40 --no-pager" >&2
  exit 1
fi

# The upstream English sample checks decoding/protocol. Use your own Spanish WAV
# for recognition quality; the runtime is deliberately fixed to Spanish.
curl --fail --silent --show-error --max-time 45 \
  -F "file=@$DATA_DIR/smoke.wav;type=audio/wav" \
  -F language=es -F response_format=json \
  http://127.0.0.1:8093/inference --output "$BUILD_ROOT/smoke.json"
python3 - "$BUILD_ROOT/smoke.json" <<'PY'
import json, sys
text = json.load(open(sys.argv[1], encoding="utf-8")).get("text")
if not isinstance(text, str) or not text.strip():
    raise SystemExit("Whisper smoke test returned no transcript.")
PY
echo "Whisper ready: http://127.0.0.1:8093 (CPU, base-q8_0, es). WAV smoke test passed."
