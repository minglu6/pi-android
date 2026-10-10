package dev.pi.android.util;

import java.util.Collection;

/** 小工具：集合成员判断（模型列表去重等）。 */
public final class Colls {
    private Colls() {}

    public static <T> boolean has(Collection<T> items, T needle) {
        for (T item : items) {
            if (item != null && item.equals(needle)) return true;
        }
        return false;
    }
}
