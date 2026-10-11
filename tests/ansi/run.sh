#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
BUILD="$(mktemp -d "${TMPDIR:-/tmp}/pi-ansi-test.XXXXXX")"
trap 'rm -rf "$BUILD"' EXIT
# Minimal Android stubs for compile of AnsiSgr (render uses android.text spans).
mkdir -p "$BUILD/stubs/android/text/style" "$BUILD/stubs/android/graphics" \
         "$BUILD/stubs/androidx/annotation"
cat > "$BUILD/stubs/androidx/annotation/NonNull.java" <<'EOF'
package androidx.annotation;
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME) @Target({ElementType.METHOD, ElementType.PARAMETER, ElementType.FIELD})
public @interface NonNull {}
EOF
cat > "$BUILD/stubs/androidx/annotation/Nullable.java" <<'EOF'
package androidx.annotation;
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME) @Target({ElementType.METHOD, ElementType.PARAMETER, ElementType.FIELD})
public @interface Nullable {}
EOF
cat > "$BUILD/stubs/android/graphics/Typeface.java" <<'EOF'
package android.graphics;
public class Typeface { public static final int BOLD = 1; }
EOF
cat > "$BUILD/stubs/android/text/Spanned.java" <<'EOF'
package android.text;
public interface Spanned { int SPAN_EXCLUSIVE_EXCLUSIVE = 33; }
EOF
cat > "$BUILD/stubs/android/text/SpannableStringBuilder.java" <<'EOF'
package android.text;
public class SpannableStringBuilder implements CharSequence {
  private final String s;
  public SpannableStringBuilder(CharSequence src) { s = src == null ? "" : src.toString(); }
  public void setSpan(Object what, int start, int end, int flags) {}
  public int length() { return s.length(); }
  public char charAt(int i) { return s.charAt(i); }
  public CharSequence subSequence(int a, int b) { return s.substring(a, b); }
  public String toString() { return s; }
}
EOF
cat > "$BUILD/stubs/android/text/style/ForegroundColorSpan.java" <<'EOF'
package android.text.style;
public class ForegroundColorSpan { public ForegroundColorSpan(int color) {} }
EOF
cat > "$BUILD/stubs/android/text/style/BackgroundColorSpan.java" <<'EOF'
package android.text.style;
public class BackgroundColorSpan { public BackgroundColorSpan(int color) {} }
EOF
cat > "$BUILD/stubs/android/text/style/StyleSpan.java" <<'EOF'
package android.text.style;
public class StyleSpan { public StyleSpan(int style) {} }
EOF
find "$BUILD/stubs" "$ROOT/app/src/dev/pi/android/util/AnsiSgr.java" "$HERE/AnsiSgrTest.java" -name '*.java' > "$BUILD/sources.txt"
javac --release 11 -encoding UTF-8 -d "$BUILD/classes" @"$BUILD/sources.txt"
java -ea -cp "$BUILD/classes" AnsiSgrTest
