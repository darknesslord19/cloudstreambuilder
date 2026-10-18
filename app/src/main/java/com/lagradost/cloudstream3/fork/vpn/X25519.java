package com.lagradost.cloudstream3.fork.vpn;

import java.security.SecureRandom;

/**
 * X25519 (RFC 7748) for the WireGuard keys of WARP. A port of crypto_scalarmult from TweetNaCl
 * (public domain), so no crypto library is needed and it works on every Android version.
 */
public final class X25519 {
    private X25519() {}

    private static final long[] _121665 = {0xDB41, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};

    private static void car25519(long[] o) {
        for (int i = 0; i < 16; i++) {
            o[i] += (1L << 16);
            long c = o[i] >> 16;
            if (i < 15) o[i + 1] += c - 1; else o[0] += 38 * (c - 1);
            o[i] -= c << 16;
        }
    }

    private static void sel25519(long[] p, long[] q, int b) {
        long c = ~(b - 1);
        for (int i = 0; i < 16; i++) {
            long t = c & (p[i] ^ q[i]);
            p[i] ^= t;
            q[i] ^= t;
        }
    }

    private static void pack25519(byte[] o, long[] n) {
        long[] m = new long[16], t = new long[16];
        System.arraycopy(n, 0, t, 0, 16);
        car25519(t);
        car25519(t);
        car25519(t);
        for (int j = 0; j < 2; j++) {
            m[0] = t[0] - 0xffed;
            for (int i = 1; i < 15; i++) {
                m[i] = t[i] - 0xffff - ((m[i - 1] >> 16) & 1);
                m[i - 1] &= 0xffff;
            }
            m[15] = t[15] - 0x7fff - ((m[14] >> 16) & 1);
            int b = (int) ((m[15] >> 16) & 1);
            m[14] &= 0xffff;
            sel25519(t, m, 1 - b);
        }
        for (int i = 0; i < 16; i++) {
            o[2 * i] = (byte) (t[i] & 0xff);
            o[2 * i + 1] = (byte) ((t[i] >> 8) & 0xff);
        }
    }

    private static void unpack25519(long[] o, byte[] n) {
        for (int i = 0; i < 16; i++) o[i] = (n[2 * i] & 0xff) + ((long) (n[2 * i + 1] & 0xff) << 8);
        o[15] &= 0x7fff;
    }

    private static void A(long[] o, long[] a, long[] b) { for (int i = 0; i < 16; i++) o[i] = a[i] + b[i]; }

    private static void Z(long[] o, long[] a, long[] b) { for (int i = 0; i < 16; i++) o[i] = a[i] - b[i]; }

    private static void M(long[] o, long[] a, long[] b) {
        long[] t = new long[31];
        for (int i = 0; i < 16; i++) for (int j = 0; j < 16; j++) t[i + j] += a[i] * b[j];
        for (int i = 0; i < 15; i++) t[i] += 38 * t[i + 16];
        System.arraycopy(t, 0, o, 0, 16);
        car25519(o);
        car25519(o);
    }

    private static void S(long[] o, long[] a) { M(o, a, a); }

    private static void inv25519(long[] o, long[] i) {
        long[] c = new long[16];
        System.arraycopy(i, 0, c, 0, 16);
        for (int a = 253; a >= 0; a--) {
            S(c, c);
            if (a != 2 && a != 4) M(c, c, i);
        }
        System.arraycopy(c, 0, o, 0, 16);
    }

    /** q = n * p (both 32 bytes, little endian). */
    public static byte[] scalarMult(byte[] n, byte[] p) {
        byte[] z = new byte[32];
        long[] x = new long[80];
        long[] a = new long[16], b = new long[16], c = new long[16], d = new long[16], e = new long[16], f = new long[16];
        System.arraycopy(n, 0, z, 0, 32);
        z[31] = (byte) ((z[31] & 127) | 64);
        z[0] &= (byte) 248;
        long[] xp = new long[16];
        unpack25519(xp, p);
        for (int i = 0; i < 16; i++) {
            b[i] = xp[i];
            d[i] = a[i] = c[i] = 0;
        }
        a[0] = d[0] = 1;
        for (int i = 254; i >= 0; --i) {
            int r = ((z[i >>> 3] & 0xff) >>> (i & 7)) & 1;
            sel25519(a, b, r);
            sel25519(c, d, r);
            A(e, a, c);
            Z(a, a, c);
            A(c, b, d);
            Z(b, b, d);
            S(d, e);
            S(f, a);
            M(a, c, a);
            M(c, b, e);
            A(e, a, c);
            Z(a, a, c);
            S(b, a);
            Z(c, d, f);
            M(a, c, _121665);
            A(a, a, d);
            M(c, c, a);
            M(a, d, f);
            M(d, b, xp);
            S(b, e);
            sel25519(a, b, r);
            sel25519(c, d, r);
        }
        long[] inv = new long[16];
        inv25519(inv, c);
        long[] out = new long[16];
        M(out, a, inv);
        byte[] q = new byte[32];
        pack25519(q, out);
        return q;
    }

    private static final byte[] BASE = new byte[32];
    static { BASE[0] = 9; }

    public static byte[] publicKey(byte[] privateKey) { return scalarMult(privateKey, BASE); }

    /** A new clamped private key. */
    public static byte[] newPrivateKey() {
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        k[0] &= (byte) 248;
        k[31] = (byte) ((k[31] & 127) | 64);
        return k;
    }
}
