#!/usr/bin/env bash
# Fetches the video player's engine — Media3 ExoPlayer and the few libraries it needs — into libs/, each file checked
# against its pinned SHA-256. build.sh runs this when libs/ is missing. libs/*.jar go into the APK; libs/compile/
# holds annotations needed only to compile.
set -euo pipefail
cd "$(dirname "$0")/.."

G=https://dl.google.com/android/maven2
C=https://repo1.maven.org/maven2
M=1.11.1   # Media3

sha() { if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi; }

mkdir -p libs/compile libs/.cache
get() {   # url sha256 destination-jar
  local url="$1" sum="$2" dest="$3" file
  file="libs/.cache/$(basename "$url")"
  if [ ! -f "$file" ] || [ "$(sha "$file")" != "$sum" ]; then
    curl -fsSL "$url" -o "$file.part"
    mv "$file.part" "$file"
    [ "$(sha "$file")" = "$sum" ] || { rm -f "$file"; echo "checksum mismatch: $url" >&2; exit 1; }
  fi
  case "$file" in
    *.aar) unzip -p "$file" classes.jar > "$dest" ;;
    *) cp "$file" "$dest" ;;
  esac
}

media3() { get "$G/androidx/media3/media3-$1/$M/media3-$1-$M.aar" "$2" "libs/media3-$1.jar"; }
media3 common        50813cf219c7c93ed8a32a750891ca6d1280729ecf7a90c49567adacd67ef31b
media3 container     930fca2f018f06f1d08c12599f8c97f31eddbd728edee5de06eb53a882f8fc54
media3 database      520e6f814e42a7d428db187ac073b453c0fcba0f4f5f4edd1cf33fafd2cfb740
media3 datasource    490fd23c7562a6a1183d63bf854b291ae3a77dce5a151450aa1937148c80c60b
media3 decoder       c3bcd704959026e0d644b0260f9e682a2e26e7357c2cf82d990bcb323f800d49
media3 extractor     6b2d58d23a0c53425249d8b7c945600019d61a8b404c8fb8cd9ca860c5738508
media3 exoplayer     588592baec110837c33ce0c82bb76464203e6b0621177bbe5d88779001a6f1ed
media3 exoplayer-hls bd7aeac8dc60b3ee926eddbe05e29f27dc53c81514cae3271b2edab693f34262

get "$C/com/google/guava/guava/33.3.1-android/guava-33.3.1-android.jar" \
  2c3e41d1b380f2044d257947a3aa82dabf3ae4b978622745254aa18b6cf89ab0 libs/guava.jar
get "$C/com/google/guava/failureaccess/1.0.2/failureaccess-1.0.2.jar" \
  8a8f81cf9b359e3f6dfa691a1e776985c061ef2f223c9b2c80753e1b458e8064 libs/failureaccess.jar
# 1.1.0, same API as the 1.2.0 Media3 asks for: build-tools 34's d8 crashes on 1.2.0's classes
get "$G/androidx/concurrent/concurrent-futures/1.1.0/concurrent-futures-1.1.0.jar" \
  0ce067c514a0d1049d1bebdf709e344ed3266fe9744275682937cdcb13334e9e libs/concurrent-futures.jar
get "$G/androidx/exifinterface/exifinterface/1.3.6/exifinterface-1.3.6.aar" \
  1804105e9e05fdd8f760413bad5de498c381aa329f4f9d94c851bc891ac654c6 libs/exifinterface.jar

get "$G/androidx/annotation/annotation-jvm/1.6.0/annotation-jvm-1.6.0.jar" \
  60b10b5ef5769b79570172e015b8159405c92f034ba88b9391a977589c9deb4e libs/compile/annotation.jar
get "$G/androidx/annotation/annotation-experimental/1.3.1/annotation-experimental-1.3.1.aar" \
  e4d49dc3880b7b7437ea54ce71461e02897faa4e1ecc53e63ecadb1a14c20c9c libs/compile/annotation-experimental.jar

echo "libs ready: $(ls libs/*.jar | wc -l | tr -d ' ') jars"
