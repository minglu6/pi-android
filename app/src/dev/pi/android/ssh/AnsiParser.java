package dev.pi.android.ssh;

import androidx.annotation.NonNull;

/** 极简 CSI / 单字符控制解析器，够用 xterm 日常输出。 */
public final class AnsiParser {
    public enum Kind { PRINT, CR, LF, BS, TAB, CUP, CUU, CUD, CUF, CUB, ED, EL, SGR, IGNORE }

    public static final class Op {
        public final Kind kind;
        public final char ch;
        public final int p1, p2;
        public final int[] args;

        Op(Kind kind, char ch, int p1, int p2, int[] args) {
            this.kind = kind;
            this.ch = ch;
            this.p1 = p1;
            this.p2 = p2;
            this.args = args;
        }

        static Op print(char c) { return new Op(Kind.PRINT, c, 0, 0, null); }
        static Op ctrl(Kind k) { return new Op(k, '\0', 0, 0, null); }
        static Op csi(Kind k, int p1, int p2, int[] args) { return new Op(k, '\0', p1, p2, args); }
    }

    public interface Sink {
        void onOp(@NonNull Op op);
    }

    private enum State { GROUND, ESC, CSI }
    private State state = State.GROUND;
    private final StringBuilder csi = new StringBuilder();
    private final Sink sink;
    private final StringBuilder utf = new StringBuilder();

    public AnsiParser(@NonNull Sink sink) {
        this.sink = sink;
    }

    public void feed(byte[] data, int off, int len) {
        // 按 UTF-8 解码；控制字节单独处理
        for (int i = off; i < off + len; i++) {
            int b = data[i] & 0xff;
            if (state == State.GROUND && b >= 0x80) {
                // 简化：收集 UTF-8 多字节
                utf.append((char) b); // fallback latin1 for high bytes if incomplete
                // Better: use charset decoder - for MVP map via String
            }
            handleByte(b);
        }
    }

    private void handleByte(int b) {
        switch (state) {
            case GROUND:
                if (b == 0x1b) { state = State.ESC; return; }
                if (b == '\r') { sink.onOp(Op.ctrl(Kind.CR)); return; }
                if (b == '\n') { sink.onOp(Op.ctrl(Kind.LF)); return; }
                if (b == 0x08) { sink.onOp(Op.ctrl(Kind.BS)); return; }
                if (b == '\t') { sink.onOp(Op.ctrl(Kind.TAB)); return; }
                if (b == 0x07) return; // bell
                if (b < 0x20) return;
                if (b < 0x80) {
                    sink.onOp(Op.print((char) b));
                    return;
                }
                // multi-byte: stash as ISO and let String rebuild — collect remaining later
                sink.onOp(Op.print((char) b));
                return;
            case ESC:
                if (b == '[') { state = State.CSI; csi.setLength(0); return; }
                if (b == ']') { state = State.GROUND; return; } // ignore OSC
                if (b == '(' || b == ')') { state = State.GROUND; return; } // charset
                state = State.GROUND;
                return;
            case CSI:
                if (b >= 0x40 && b <= 0x7e) {
                    parseCsi((char) b, csi.toString());
                    state = State.GROUND;
                    return;
                }
                csi.append((char) b);
                if (csi.length() > 64) { state = State.GROUND; }
                return;
            default:
                state = State.GROUND;
        }
    }

    private void parseCsi(char finalByte, String body) {
        String[] parts = body.split(";");
        int[] args = new int[Math.max(1, parts.length)];
        for (int i = 0; i < parts.length; i++) {
            try {
                args[i] = parts[i].isEmpty() ? 0 : Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                args[i] = 0;
            }
        }
        int a0 = args.length > 0 ? args[0] : 0;
        int a1 = args.length > 1 ? args[1] : 0;
        switch (finalByte) {
            case 'H':
            case 'f':
                sink.onOp(Op.csi(Kind.CUP, a0 == 0 ? 1 : a0, a1 == 0 ? 1 : a1, args));
                break;
            case 'A': sink.onOp(Op.csi(Kind.CUU, a0 == 0 ? 1 : a0, 0, args)); break;
            case 'B': sink.onOp(Op.csi(Kind.CUD, a0 == 0 ? 1 : a0, 0, args)); break;
            case 'C': sink.onOp(Op.csi(Kind.CUF, a0 == 0 ? 1 : a0, 0, args)); break;
            case 'D': sink.onOp(Op.csi(Kind.CUB, a0 == 0 ? 1 : a0, 0, args)); break;
            case 'J': sink.onOp(Op.csi(Kind.ED, a0, 0, args)); break;
            case 'K': sink.onOp(Op.csi(Kind.EL, a0, 0, args)); break;
            case 'm': sink.onOp(Op.csi(Kind.SGR, 0, 0, args)); break;
            default: sink.onOp(Op.ctrl(Kind.IGNORE)); break;
        }
    }
}
