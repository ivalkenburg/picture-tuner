# Toolchain for generating and verifying Picture Tuner's test patterns and branding exports,
# so nothing has to be installed on the developer machine.
#
#   docker build -t picture-tuner-tools -f tools/Dockerfile .
#   docker run --rm -u "$(id -u):$(id -g)" -v "$PWD:/work" picture-tuner-tools python3 tools/generate_patterns.py
#
# See README.md ("Patterns") for more commands. Works on x86_64 and arm64 hosts.
# Ubuntu 24.04: Debian 13 no longer packages GPAC (MP4Box), which muxes the Dolby Vision clips.
FROM ubuntu:24.04

# ffmpeg (libx265 encoder + decoder used for verification), MP4Box (gpac), Cairo for CairoSVG, Python.
RUN apt-get update \
 && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
      ffmpeg gpac libcairo2 python3 python3-venv ca-certificates curl \
 && rm -rf /var/lib/apt/lists/*

# dovi_tool: pinned release, checksum-verified static build for the image architecture.
ARG DOVI_TOOL_VERSION=2.3.4
ARG DOVI_TOOL_SHA256_X86_64=1844258e13c26607b32224bf1fa82b595d3b35949f5467405fda560daad32b3f
ARG DOVI_TOOL_SHA256_AARCH64=b4f22a7db56954efe4ed8d02276d0f991799e84602f3584711be6c9df950cb15
RUN set -eu; \
    arch="$(uname -m)"; \
    case "$arch" in \
      x86_64)        sha="$DOVI_TOOL_SHA256_X86_64" ;; \
      aarch64|arm64) arch=aarch64; sha="$DOVI_TOOL_SHA256_AARCH64" ;; \
      *) echo "unsupported architecture: $arch" >&2; exit 1 ;; \
    esac; \
    url="https://github.com/quietvoid/dovi_tool/releases/download/${DOVI_TOOL_VERSION}/dovi_tool-${DOVI_TOOL_VERSION}-${arch}-unknown-linux-musl.tar.gz"; \
    curl -fsSL "$url" -o /tmp/dovi.tgz; \
    echo "${sha}  /tmp/dovi.tgz" | sha256sum -c -; \
    tar -xzf /tmp/dovi.tgz -C /usr/local/bin ./dovi_tool; \
    rm /tmp/dovi.tgz; \
    dovi_tool --version

# Python packages: pinned versions (pattern labels are drawn with Pillow's bundled font, so its version matters).
COPY tools/requirements.txt /tmp/requirements.txt
RUN python3 -m venv /opt/venv \
 && /opt/venv/bin/pip install --no-cache-dir -r /tmp/requirements.txt CairoSVG==2.9.1 \
 && rm /tmp/requirements.txt
ENV PATH=/opt/venv/bin:$PATH

# Running as the host user (-u) gives no home directory: use /tmp. GPAC's key is created here once,
# readable by any user, so MP4Box doesn't announce creating it on every run.
ENV HOME=/tmp
RUN MP4Box -version >/dev/null 2>&1; chmod -R a+rwX /tmp/.gpac
WORKDIR /work
CMD ["python3", "tools/generate_patterns.py", "--verify-only"]
