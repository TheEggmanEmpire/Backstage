#!/usr/bin/env bash
# Google Play version only. Rebuild upstream Dextop's prebuilt PulseAudio stack and DD-Turnip Vulkan driver with
# 16 KB ELF page alignment into backstage/native-16k/: Google Play rejects native libraries without it, and upstream
# ships 4 KB builds. Only the play flavor packages these files; the GitHub version keeps upstream's prebuilt ones.
#
#   scripts/build-native-16k.sh
#
# Needs Linux x86_64, curl, make, a C compiler, python3 with pip, pkg-config and the Android NDK 27.0.12077973
# (ANDROID_HOME/ndk/27.0.12077973 or ANDROID_NDK_HOME). Every source is pinned by SHA-256. Host tools (m4, bison,
# flex, glslang, meson, ninja) are built or installed under build/native-16k/host only.
#
# PulseAudio 13.0, libsndfile 1.0.31 and libltdl (libtool 2.4.7) match the replaced files: NDK r27, API 26,
# unversioned SONAMEs, no dependencies beyond libc/libm/libdl. The DirectAudio sink comes from
# upstream/dextop/third_party/deck-audio. DD-Turnip v0.2.0 uses its own scripts/build_android.sh options.
. "$(dirname "$0")/lib.sh"

W=$ROOT/build/native-16k
SRC=$W/src
HOST=$W/host
OUT=$ROOT/backstage/native-16k
NDK=${ANDROID_NDK_HOME:-${ANDROID_HOME:-$HOME/android-sdk}/ndk/27.0.12077973}
TC=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin
[[ -x $TC/clang ]] || die "Android NDK 27.0.12077973 not found (set ANDROID_NDK_HOME)"
[[ "$(uname -s)/$(uname -m)" == Linux/x86_64 ]] || die "needs a Linux x86_64 host"
mkdir -p "$SRC" "$HOST/bin"
export PATH=$HOST/bin:$PATH

fetch() { # name url sha256
  local f=$SRC/$1
  if ! echo "$3  $f" | sha256sum -c - >/dev/null 2>&1; then
    curl -fsSL --retry 3 -o "$f.part" "$2"
    echo "$3  $f.part" | sha256sum -c - >/dev/null || die "$1: checksum mismatch"
    mv "$f.part" "$f"
  fi
}
fetch m4-1.4.19.tar.xz https://ftp.gnu.org/gnu/m4/m4-1.4.19.tar.xz 63aede5c6d33b6d9b13511cd0be2cac046f2e70fd0a07aa9573a04a82783af96
fetch bison-3.8.2.tar.xz https://ftp.gnu.org/gnu/bison/bison-3.8.2.tar.xz 9bba0214ccf7f1079c5d59210045227bcf619519840ebfa80cd3849cff5a5bf2
fetch flex-2.6.4.tar.gz https://github.com/westes/flex/releases/download/v2.6.4/flex-2.6.4.tar.gz e87aae032bf07c26f85ac0ed3250998c37621d95f8bd748b31f15b33c45ee995
fetch glslang-16.6.0-linux-x86_64-release.tar.gz https://github.com/KhronosGroup/glslang/releases/download/16.6.0/glslang-16.6.0-linux-x86_64-release.tar.gz 2c34071f56ecf39d16233294d3739cf43f981fd39080f2fd9dccb782101191ce
fetch pulseaudio-13.0.tar.xz https://freedesktop.org/software/pulseaudio/releases/pulseaudio-13.0.tar.xz 961b23ca1acfd28f2bc87414c27bb40e12436efcf2158d29721b1e89f3f28057
fetch libsndfile-1.0.31.tar.bz2 https://github.com/libsndfile/libsndfile/releases/download/1.0.31/libsndfile-1.0.31.tar.bz2 a8cfb1c09ea6e90eff4ca87322d4168cdbe5035cb48717b40bf77e751cc02163
fetch libtool-2.4.7.tar.xz https://ftp.gnu.org/gnu/libtool/libtool-2.4.7.tar.xz 4f7f217f057ce655ff22559ad221a0fd8ef84ad1fc5fcb6990cecc333aa1635d
fetch DD-Turnip-v0.2.0-source.tar.xz https://github.com/Droid-Deck/Drivers-CI/releases/download/DD-Turnip-v0.2.0/DD-Turnip-v0.2.0-source.tar.xz f35e476bef5a6649fc66bb3231d5be4330b969fc0ee6032ec4ef6df2c6cae172

# ------------------------------------------------------------------------------------------------ host tools
host_tool() { # tarball binary
  [[ -x $HOST/bin/$2 ]] && return
  log "host: $1"
  rm -rf "$W/host-build" && mkdir -p "$W/host-build" && tar -xf "$SRC/$1" -C "$W/host-build"
  (cd "$W/host-build"/*/ && ./configure --prefix="$HOST" >/dev/null && make -j"$(nproc)" >/dev/null && make install >/dev/null)
}
host_tool m4-1.4.19.tar.xz m4
host_tool bison-3.8.2.tar.xz bison
host_tool flex-2.6.4.tar.gz flex
if [[ ! -x $HOST/bin/glslangValidator ]]; then
  # glslangValidator is a symlink to glslang in the archive.
  rm -rf "$W/glslang" && mkdir -p "$W/glslang" && tar -xzf "$SRC/glslang-16.6.0-linux-x86_64-release.tar.gz" -C "$W/glslang"
  cp -L "$W/glslang/bin/glslangValidator" "$HOST/bin/glslangValidator"
fi
[[ -x $HOST/py/bin/meson ]] || python3 -m pip install --quiet --target "$HOST/py" meson==1.12.1 ninja==1.13.0
export PYTHONPATH=$HOST/py PATH=$HOST/py/bin:$PATH

# ---------------------------------------------------------------------------------------------- PulseAudio
log "audio: libltdl, libsndfile, PulseAudio 13.0"
A=$W/audio SYS=$W/audio/sysroot
rm -rf "$A" && mkdir -p "$A" "$SYS"
(
  export CC=$TC/aarch64-linux-android26-clang CXX=$TC/aarch64-linux-android26-clang++
  export AR=$TC/llvm-ar RANLIB=$TC/llvm-ranlib STRIP=$TC/llvm-strip NM=$TC/llvm-nm
  # -ffile-prefix-map keeps local paths out of the binaries; the -Wno-* let 2019-era C build with clang 18.
  export CFLAGS="-O2 -fPIC -ffile-prefix-map=$W=. -ffile-prefix-map=$ROOT=. -Wno-strict-prototypes \
-Wno-error=implicit-function-declaration -Wno-error=int-conversion -Wno-error=incompatible-function-pointer-types"
  export CXXFLAGS="-O2 -fPIC" LDFLAGS="-Wl,-z,max-page-size=16384 -L$SYS/lib" CPPFLAGS="-I$SYS/include"
  export PKG_CONFIG_LIBDIR=$SYS/lib/pkgconfig PKG_CONFIG_PATH=
  cd "$A"
  tar -xf "$SRC/libtool-2.4.7.tar.xz"
  (cd libtool-2.4.7/libltdl && ./configure --host=aarch64-linux-android --prefix="$SYS" --enable-ltdl-install \
      --disable-static --enable-shared >/dev/null &&
    sed -i 's/-version-info [^ ]*/-avoid-version/' Makefile && make -j"$(nproc)" >/dev/null && make install >/dev/null)
  tar -xf "$SRC/libsndfile-1.0.31.tar.bz2"
  (cd libsndfile-1.0.31 && ./configure --host=aarch64-linux-android --prefix="$SYS" --disable-static --enable-shared \
      --disable-external-libs --disable-sqlite --disable-alsa --disable-full-suite --disable-mpeg >/dev/null &&
    sed -i 's/-version-info [^ ]*/-avoid-version/' Makefile && make -j"$(nproc)" >/dev/null && make install >/dev/null)
  tar -xf "$SRC/pulseaudio-13.0.tar.xz"
  cd pulseaudio-13.0
  patch -p1 -s < "$ROOT/scripts/native-16k/pulseaudio-13.0-atomic-cast.patch"
  # execinfo backtrace() exists only from API 33; the prebuilt set has none.
  ac_cv_header_execinfo_h=no ax_cv_PTHREAD_PRIO_INHERIT=no ./configure --host=aarch64-linux-android --prefix=/usr \
    --disable-static --enable-shared --disable-neon-opt --disable-alsa --disable-esound --disable-glib2 --disable-x11 \
    --disable-gtk3 --disable-openssl --without-caps --with-database=simple --disable-memfd --disable-gsettings \
    --disable-gconf --disable-dbus --disable-udev --disable-bluez5 --disable-avahi --disable-jack --disable-lirc \
    --disable-hal-compat --disable-tcpwrap --disable-orc --disable-systemd-daemon --disable-systemd-login \
    --disable-systemd-journal --without-soxr --without-speex --without-fftw --disable-webrtc-aec --disable-tests \
    --disable-oss-output --disable-oss-wrapper --disable-coreaudio-output --disable-solaris --disable-waveout \
    --disable-asyncns --disable-manpages --disable-default-build-tests --disable-legacy-database-entry-format \
    --without-zsh-completion-dir --without-bash-completion-dir >/dev/null
  sed -i 's|^#define PA_CFLAGS .*|#define PA_CFLAGS "-O2"|' config.h
  sed -i 's|-DPA_BUILDDIR=\\"$(abs_builddir)\\"|-DPA_BUILDDIR=\\"/build\\"|; s/-version-info \$(LIBPULSE[A-Z_]*_VERSION_INFO)/-avoid-version/' src/Makefile
  make -j"$(nproc)" >/dev/null
  mkdir -p "$A/out"
  P=src/.libs
  cp $P/libpulse.so $P/libpulsecommon-13.0.so $P/libpulsecore-13.0.so $P/libprotocol-native.so \
     $P/module-native-protocol-tcp.so "$SYS/lib/libsndfile.so" "$SYS/lib/libltdl.so" "$A/out/"
  cp $P/pulseaudio "$A/out/libpulseaudio.so"
  DA=$ROOT/upstream/dextop/third_party/deck-audio
  "$TC/aarch64-linux-android28-clang" $CFLAGS -shared -Wno-unused-parameter -Wno-visibility -DHAVE_CONFIG_H \
    -I. -Isrc -I"$SYS/include" -I"$DA" -Wl,-z,max-page-size=16384 -o "$A/out/module-directaudio-native-sink.so" \
    "$DA/module-directaudio-native-sink.c" -L"$A/out" -l:libpulsecore-13.0.so -l:libpulsecommon-13.0.so -l:libpulse.so -laaudio
  for f in "$A"/out/*.so; do "$STRIP" --strip-unneeded "$f"; done
)

# ----------------------------------------------------------------------------------------------- DD-Turnip
log "turnip: DD-Turnip v0.2.0"
T=$W/turnip
rm -rf "$T" && mkdir -p "$T" && tar -xJf "$SRC/DD-Turnip-v0.2.0-source.tar.xz" -C "$T"
D=$T/DD-Turnip-v0.2.0-source
(cd "$D/mesa" && git init -q && git add -A >/dev/null && git -c user.email=build@local -c user.name=build commit -qm source)
python3 "$D/drivers/scripts/apply_patches.py" android "$D/mesa" >/dev/null
cat > "$T/cross.ini" <<INI
[binaries]
ar = '$TC/llvm-ar'
c = ['$TC/aarch64-linux-android34-clang']
cpp = ['$TC/aarch64-linux-android34-clang++', '-fno-exceptions', '-fno-unwind-tables', '-fno-asynchronous-unwind-tables', '--start-no-unused-arguments', '-static-libstdc++', '--end-no-unused-arguments']
c_ld = '$TC/ld.lld'
cpp_ld = '$TC/ld.lld'
strip = '$TC/llvm-strip'
pkg-config = ['env', 'PKG_CONFIG_LIBDIR=$TC/pkg-config', '/usr/bin/pkg-config']

[built-in options]
c_link_args = ['-Wl,-z,max-page-size=16384']
cpp_link_args = ['-Wl,-z,max-page-size=16384']

[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'armv8'
endian = 'little'
INI
cat > "$T/native.ini" <<INI
[binaries]
c = '$TC/clang'
cpp = '$TC/clang++'
ar = '$TC/llvm-ar'
strip = '$TC/llvm-strip'
c_ld = '$TC/ld.lld'
cpp_ld = '$TC/ld.lld'
INI
flags="-D__ANDROID__ -Wno-error -Wno-deprecated-declarations -Wno-incompatible-pointer-types-discards-qualifiers -Wno-incompatible-pointer-types"
CFLAGS="$flags" CXXFLAGS="$flags" meson setup "$T/build" "$D/mesa" --cross-file "$T/cross.ini" \
  --native-file "$T/native.ini" --prefix "$T/install" -Dbuildtype=release -Dstrip=true -Dplatforms=android \
  -Dplatform-sdk-version=36 -Dandroid-stub=true -Dandroid-libbacktrace=disabled -Dgallium-drivers= \
  -Dvulkan-drivers=freedreno -Dvulkan-beta=true -Dfreedreno-kmds=kgsl -Degl=disabled -Dvideo-codecs= >/dev/null
ninja -C "$T/build" -j "$(nproc)" install >/dev/null

# ------------------------------------------------------------------------------------------------- install
rm -rf "$OUT/jniLibs" "$OUT/assets"
mkdir -p "$OUT/jniLibs/arm64-v8a" "$OUT/assets/deck-audio/modules"
for f in libltdl libpulse libpulseaudio libpulsecommon-13.0 libpulsecore-13.0 libsndfile; do
  install -m644 "$A/out/$f.so" "$OUT/jniLibs/arm64-v8a/"
done
install -m644 "$T/install/lib/libvulkan_freedreno.so" "$OUT/jniLibs/arm64-v8a/libvulkan_dextop_turnip.so"
for f in libprotocol-native module-native-protocol-tcp module-directaudio-native-sink; do
  install -m644 "$A/out/$f.so" "$OUT/assets/deck-audio/modules/"
done
for f in "$OUT"/jniLibs/arm64-v8a/*.so "$OUT"/assets/deck-audio/modules/*.so; do
  readelf -lW "$f" | awk '/LOAD/{print $NF}' | grep -qv '^0x4000$\|^0x10000$' && die "$f: LOAD segment below 16 KB"
  grep -qaF "$ROOT" "$f" && die "$f: contains the local checkout path"
done
log "wrote backstage/native-16k ($(find "$OUT" -name '*.so' | wc -l) libraries, 16 KB aligned)"
