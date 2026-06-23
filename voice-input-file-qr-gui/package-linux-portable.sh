#!/usr/bin/env bash
set -euo pipefail

arch="${1:?arch required, for example amd64 or arm64}"
version="${2:?version required, for example dev-6}"
build_dir="${3:?build directory required}"
release_root="${4:-release-assets}"
exe="${build_dir}/voiceinput-file-qr-gui"
package_name="voiceinput-file-qr-gui-linux-${arch}-v${version}"
package_dir="${release_root}/${package_name}"
lib_dir="${package_dir}/lib"

if [[ ! -x "$exe" ]]; then
  echo "Executable not found: $exe" >&2
  exit 1
fi

rm -rf "$package_dir"
mkdir -p "$lib_dir"
cp "$exe" "${package_dir}/voiceinput-file-qr-gui"
cp "voice-input-file-qr-gui/README.md" "${package_dir}/README.md"

strip "${package_dir}/voiceinput-file-qr-gui" || true

should_skip_lib() {
  local path="$1"
  local base
  base="$(basename "$path")"

  case "$base" in
    linux-vdso.so*|ld-linux*.so*|ld-*.so*|libc.so*|libm.so*|libpthread.so*|libdl.so*|librt.so*|libresolv.so*|libnss_*.so*|libanl.so*|libutil.so*)
      return 0
      ;;
  esac

  case "$path" in
    /usr/lib/*/d3000m/*|/usr/lib/*/mali/*)
      return 0
      ;;
  esac

  return 1
}

list_deps() {
  local target="$1"
  ldd "$target" 2>/dev/null | awk '
    /=>[[:space:]]*\/.*\.so/ { print $3 }
    /^[[:space:]]*\/.*\.so/ { print $1 }
  ' | sort -u
}

declare -A seen
queue=("${package_dir}/voiceinput-file-qr-gui")

while ((${#queue[@]})); do
  current="${queue[0]}"
  queue=("${queue[@]:1}")

  while IFS= read -r dep; do
    [[ -n "$dep" && -f "$dep" ]] || continue
    if should_skip_lib "$dep"; then
      continue
    fi

    base="$(basename "$dep")"
    dest="${lib_dir}/${base}"
    if [[ -n "${seen[$base]:-}" ]]; then
      continue
    fi
    seen[$base]=1

    cp -L "$dep" "$dest"
    chmod 755 "$dest" || true
    queue+=("$dest")
  done < <(list_deps "$current")
done

patchelf --force-rpath --set-rpath '$ORIGIN/lib' "${package_dir}/voiceinput-file-qr-gui"
while IFS= read -r lib; do
  patchelf --force-rpath --set-rpath '$ORIGIN' "$lib" 2>/dev/null || true
done < <(find "$lib_dir" -type f -name '*.so*' | sort)

cat > "${package_dir}/run.sh" <<'EOF'
#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"
exec ./voiceinput-file-qr-gui "$@"
EOF
chmod +x "${package_dir}/run.sh"

{
  echo "package=${package_name}"
  echo "built_at=$(date -Is)"
  echo "system=$(uname -a)"
  echo "glibc=$(ldd --version | head -n1)"
  echo "wxwidgets=$(wx-config --version 2>/dev/null || true)"
  echo "opencv=$(opencv_version 2>/dev/null || true)"
  echo "compiler=$(${CXX:-c++} --version | head -n1)"
  echo
  echo "Notes:"
  echo "- Third-party GUI/media libraries are bundled under ./lib."
  echo "- glibc and the system dynamic loader are intentionally not bundled."
  echo "- Built on Ubuntu 20.04 class glibc for older Linux compatibility."
} > "${package_dir}/BUILD-INFO.txt"

ldd "${package_dir}/voiceinput-file-qr-gui" > "${package_dir}/ldd-after.txt" || true
{
  echo "Executable GLIBC versions:"
  objdump -T "${package_dir}/voiceinput-file-qr-gui" | grep -o 'GLIBC_[0-9.]*' | sort -Vu || true
  echo
  echo "Bundled library GLIBC versions:"
  find "$lib_dir" -type f -name '*.so*' -print0 | xargs -0 -r objdump -T 2>/dev/null | grep -o 'GLIBC_[0-9.]*' | sort -Vu || true
  echo
  echo "Bundled library GLIBCXX versions:"
  find "$lib_dir" -type f -name '*.so*' -print0 | xargs -0 -r objdump -T 2>/dev/null | grep -o 'GLIBCXX_[0-9.]*' | sort -Vu || true
} > "${package_dir}/symbol-versions.txt"

find "$package_dir" -type f | sort > "${package_dir}/files.txt"

mkdir -p "$release_root"
tar -czf "${release_root}/${package_name}.tar.gz" -C "$release_root" "$package_name"
sha256sum "${release_root}/${package_name}.tar.gz" > "${release_root}/${package_name}.tar.gz.sha256"
ls -lh "${release_root}/${package_name}.tar.gz"
