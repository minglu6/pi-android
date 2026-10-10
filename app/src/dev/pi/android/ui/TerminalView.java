package dev.pi.android.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.InputType;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import dev.pi.android.ssh.AnsiParser;
import dev.pi.android.util.AppFonts;

/**
 * 轻量终端视图：固定行缓冲 + 基本 ANSI（颜色 / 光标 / 清屏）。
 * 字体默认 MesloLGLDZ Nerd Font Mono；捏合缩放字号。
 */
public class TerminalView extends View {
    public interface OutputSink {
        void onUserInput(byte[] data);
        void onResize(int cols, int rows);
    }

    private static final int FG_DEF = 0xFFE6EDF3;
    private static final int BG_DEF = 0xFF0D1117;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgPaint = new Paint();
    private AnsiParser parser;
    private char[][] cells;
    private int[][] fg;
    private int[][] bg;
    private int cols = 80;
    private int rows = 24;
    private int cx, cy;
    private int curFg = FG_DEF;
    private int curBg = BG_DEF;
    private float cellW, cellH, baseline;
    private float fontSp = AppFonts.SIZE_DEFAULT;
    private OutputSink sink;
    private final GestureDetector gestures;
    private final ScaleGestureDetector scaleGestures;
    private boolean bold;

    public TerminalView(Context context) { this(context, null); }
    public TerminalView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setClickable(true);
        fontSp = AppFonts.sizeSp(context);
        applyTypeface();
        setBackgroundColor(BG_DEF);
        measureCell();
        allocBuffer(cols, rows);
        parser = new AnsiParser(this::onAnsi);
        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onSingleTapUp(MotionEvent e) {
                requestFocus();
                InputMethodManager imm = (InputMethodManager) getContext()
                        .getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(TerminalView.this, InputMethodManager.SHOW_IMPLICIT);
                return true;
            }
            @Override public boolean onDoubleTap(MotionEvent e) {
                android.content.ClipboardManager cm = (android.content.ClipboardManager)
                        getContext().getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null
                        && cm.getPrimaryClip().getItemCount() > 0) {
                    CharSequence t = cm.getPrimaryClip().getItemAt(0).coerceToText(getContext());
                    if (t != null && sink != null) {
                        sink.onUserInput(t.toString().getBytes(StandardCharsets.UTF_8));
                    }
                }
                return true;
            }
        });
        scaleGestures = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override public boolean onScale(ScaleGestureDetector detector) {
                        float next = fontSp * detector.getScaleFactor();
                        next = Math.max(AppFonts.SIZE_MIN, Math.min(AppFonts.SIZE_MAX, next));
                        if (Math.abs(next - fontSp) < 0.15f) return true;
                        fontSp = next;
                        AppFonts.setSizeSp(getContext(), fontSp);
                        applyTypeface();
                        measureCell();
                        int nc = Math.max(20, (int) (getWidth() / cellW));
                        int nr = Math.max(8, (int) (getHeight() / cellH));
                        if (nc != cols || nr != rows) {
                            allocBuffer(nc, nr);
                            if (sink != null) sink.onResize(cols, rows);
                        }
                        invalidate();
                        return true;
                    }
                });
    }

    public void reloadFontFromPrefs() {
        fontSp = AppFonts.sizeSp(getContext());
        applyTypeface();
        measureCell();
        invalidate();
    }

    private void applyTypeface() {
        Typeface tf = AppFonts.mono(getContext(), false);
        paint.setTypeface(tf);
        paint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, fontSp,
                getResources().getDisplayMetrics()));
        paint.setColor(FG_DEF);
        bgPaint.setColor(BG_DEF);
    }

    public void setOutputSink(OutputSink sink) { this.sink = sink; }

    public int cols() { return cols; }
    public int rows() { return rows; }

    public void feed(byte[] data, int len) {
        parser.feed(data, 0, len);
        invalidate();
    }

    public void feed(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        feed(b, b.length);
    }

    public String copyVisible() {
        StringBuilder sb = new StringBuilder();
        for (int y = 0; y < rows; y++) {
            int end = cols;
            while (end > 0 && cells[y][end - 1] == ' ') end--;
            sb.append(cells[y], 0, end).append('\n');
        }
        return sb.toString();
    }

    private void measureCell() {
        Paint.FontMetrics fm = paint.getFontMetrics();
        cellH = fm.descent - fm.ascent + 2f;
        cellW = paint.measureText("M");
        if (cellW <= 0) cellW = paint.getTextSize() * 0.6f;
        baseline = -fm.ascent + 1f;
    }

    private void allocBuffer(int c, int r) {
        cols = Math.max(20, c);
        rows = Math.max(8, r);
        cells = new char[rows][cols];
        fg = new int[rows][cols];
        bg = new int[rows][cols];
        for (int y = 0; y < rows; y++) {
            Arrays.fill(cells[y], ' ');
            Arrays.fill(fg[y], FG_DEF);
            Arrays.fill(bg[y], BG_DEF);
        }
        cx = cy = 0;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (cellW <= 0 || cellH <= 0) return;
        int nc = Math.max(20, (int) (w / cellW));
        int nr = Math.max(8, (int) (h / cellH));
        if (nc != cols || nr != rows) {
            allocBuffer(nc, nr);
            if (sink != null) sink.onResize(cols, rows);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(BG_DEF);
        for (int y = 0; y < rows; y++) {
            float top = y * cellH;
            for (int x = 0; x < cols; ) {
                int color = bg[y][x];
                int x2 = x + 1;
                while (x2 < cols && bg[y][x2] == color) x2++;
                if (color != BG_DEF) {
                    bgPaint.setColor(color);
                    canvas.drawRect(x * cellW, top, x2 * cellW, top + cellH, bgPaint);
                }
                x = x2;
            }
            for (int x = 0; x < cols; x++) {
                char ch = cells[y][x];
                if (ch == ' ') continue;
                paint.setColor(fg[y][x]);
                canvas.drawText(String.valueOf(ch), x * cellW, top + baseline, paint);
            }
        }
        bgPaint.setColor(0x88F09082);
        canvas.drawRect(cx * cellW, cy * cellH, (cx + 1) * cellW, (cy + 1) * cellH, bgPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        boolean scaled = scaleGestures.onTouchEvent(event);
        if (!scaleGestures.isInProgress()) gestures.onTouchEvent(event);
        return true || scaled;
    }

    @Override
    public boolean onCheckIsTextEditor() { return true; }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD;
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN | EditorInfo.IME_FLAG_NO_EXTRACT_UI;
        return new BaseInputConnection(this, false) {
            @Override public boolean commitText(CharSequence text, int newCursorPosition) {
                if (text != null && sink != null) {
                    sink.onUserInput(text.toString().getBytes(StandardCharsets.UTF_8));
                }
                return true;
            }
            @Override public boolean deleteSurroundingText(int beforeLength, int afterLength) {
                if (beforeLength > 0 && sink != null) sink.onUserInput(new byte[]{0x7f});
                return true;
            }
            @Override public boolean sendKeyEvent(KeyEvent event) {
                return TerminalView.this.dispatchKeyEvent(event);
            }
        };
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (sink == null) return super.onKeyDown(keyCode, event);
        byte[] seq = keyToBytes(keyCode, event);
        if (seq != null) {
            sink.onUserInput(seq);
            return true;
        }
        int u = event.getUnicodeChar();
        if (u > 0) {
            sink.onUserInput(new String(Character.toChars(u)).getBytes(StandardCharsets.UTF_8));
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Nullable
    private static byte[] keyToBytes(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_ENTER: return new byte[]{'\r'};
            case KeyEvent.KEYCODE_DEL: return new byte[]{0x7f};
            case KeyEvent.KEYCODE_TAB: return new byte[]{'\t'};
            case KeyEvent.KEYCODE_ESCAPE: return new byte[]{0x1b};
            case KeyEvent.KEYCODE_DPAD_UP: return "\u001b[A".getBytes(StandardCharsets.UTF_8);
            case KeyEvent.KEYCODE_DPAD_DOWN: return "\u001b[B".getBytes(StandardCharsets.UTF_8);
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "\u001b[C".getBytes(StandardCharsets.UTF_8);
            case KeyEvent.KEYCODE_DPAD_LEFT: return "\u001b[D".getBytes(StandardCharsets.UTF_8);
            default:
                if (event.isCtrlPressed()) {
                    int ch = event.getUnicodeChar(0);
                    if (ch >= 64 && ch < 96) return new byte[]{(byte) (ch & 0x1f)};
                    if (ch >= 97 && ch <= 122) return new byte[]{(byte) (ch - 96)};
                }
                return null;
        }
    }

    private void onAnsi(AnsiParser.Op op) {
        switch (op.kind) {
            case PRINT: putChar(op.ch); break;
            case CR: cx = 0; break;
            case LF: lineFeed(); break;
            case BS: if (cx > 0) cx--; break;
            case TAB: cx = Math.min(cols - 1, ((cx / 8) + 1) * 8); break;
            case CUP:
                cy = clamp(op.p1 - 1, 0, rows - 1);
                cx = clamp(op.p2 - 1, 0, cols - 1);
                break;
            case CUU: cy = clamp(cy - Math.max(1, op.p1), 0, rows - 1); break;
            case CUD: cy = clamp(cy + Math.max(1, op.p1), 0, rows - 1); break;
            case CUF: cx = clamp(cx + Math.max(1, op.p1), 0, cols - 1); break;
            case CUB: cx = clamp(cx - Math.max(1, op.p1), 0, cols - 1); break;
            case ED:
                if (op.p1 <= 0) clearFromCursor();
                else if (op.p1 == 2 || op.p1 == 3) clearAll();
                break;
            case EL:
                if (op.p1 <= 0) {
                    for (int x = cx; x < cols; x++) {
                        cells[cy][x] = ' '; fg[cy][x] = curFg; bg[cy][x] = curBg;
                    }
                } else if (op.p1 == 2) {
                    Arrays.fill(cells[cy], ' ');
                    Arrays.fill(fg[cy], curFg);
                    Arrays.fill(bg[cy], curBg);
                }
                break;
            case SGR: applySgr(op.args); break;
            default: break;
        }
    }

    private void putChar(char ch) {
        if (cx >= cols) { cx = 0; lineFeed(); }
        cells[cy][cx] = ch;
        fg[cy][cx] = curFg;
        bg[cy][cx] = curBg;
        cx++;
    }

    private void lineFeed() {
        cy++;
        if (cy >= rows) {
            for (int y = 1; y < rows; y++) {
                System.arraycopy(cells[y], 0, cells[y - 1], 0, cols);
                System.arraycopy(fg[y], 0, fg[y - 1], 0, cols);
                System.arraycopy(bg[y], 0, bg[y - 1], 0, cols);
            }
            Arrays.fill(cells[rows - 1], ' ');
            Arrays.fill(fg[rows - 1], FG_DEF);
            Arrays.fill(bg[rows - 1], BG_DEF);
            cy = rows - 1;
        }
    }

    private void clearAll() {
        for (int y = 0; y < rows; y++) {
            Arrays.fill(cells[y], ' ');
            Arrays.fill(fg[y], FG_DEF);
            Arrays.fill(bg[y], BG_DEF);
        }
        cx = cy = 0;
    }

    private void clearFromCursor() {
        for (int x = cx; x < cols; x++) {
            cells[cy][x] = ' '; fg[cy][x] = FG_DEF; bg[cy][x] = BG_DEF;
        }
        for (int y = cy + 1; y < rows; y++) {
            Arrays.fill(cells[y], ' ');
            Arrays.fill(fg[y], FG_DEF);
            Arrays.fill(bg[y], BG_DEF);
        }
    }

    private void applySgr(int[] args) {
        if (args == null || args.length == 0) {
            curFg = FG_DEF; curBg = BG_DEF; bold = false; return;
        }
        for (int i = 0; i < args.length; i++) {
            int a = args[i];
            if (a == 0) { curFg = FG_DEF; curBg = BG_DEF; bold = false; }
            else if (a == 1) bold = true;
            else if (a == 22) bold = false;
            else if (a >= 30 && a <= 37) curFg = ansiColor(a - 30, bold);
            else if (a >= 90 && a <= 97) curFg = ansiColor(a - 90, true);
            else if (a >= 40 && a <= 47) curBg = ansiColor(a - 40, false);
            else if (a >= 100 && a <= 107) curBg = ansiColor(a - 100, true);
            else if (a == 39) curFg = FG_DEF;
            else if (a == 49) curBg = BG_DEF;
            else if (a == 38 && i + 2 < args.length && args[i + 1] == 5) {
                curFg = xterm256(args[i + 2]); i += 2;
            } else if (a == 48 && i + 2 < args.length && args[i + 1] == 5) {
                curBg = xterm256(args[i + 2]); i += 2;
            }
        }
    }

    private static int ansiColor(int idx, boolean bright) {
        int[] base = {0xFF0D1117, 0xFFFF7B72, 0xFF3FB950, 0xFFD29922,
                0xFF58A6FF, 0xFFBC8CFF, 0xFF39C5CF, 0xFFB1BAC4};
        int[] br = {0xFF6E7681, 0xFFFFA198, 0xFF56D364, 0xFFE3B341,
                0xFF79C0FF, 0xFFD2A8FF, 0xFF56D4DD, 0xFFF0F6FC};
        return (bright ? br : base)[idx & 7];
    }

    private static int xterm256(int n) {
        if (n < 16) return ansiColor(n % 8, n >= 8);
        if (n >= 232) {
            int v = 8 + (n - 232) * 10;
            return 0xFF000000 | (v << 16) | (v << 8) | v;
        }
        n -= 16;
        int r = n / 36, g = (n / 6) % 6, b = n % 6;
        int[] c = {0, 95, 135, 175, 215, 255};
        return 0xFF000000 | (c[r] << 16) | (c[g] << 8) | c[b];
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
