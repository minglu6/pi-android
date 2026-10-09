package dev.pi.android.util;

import java.util.Collection;

/** 小工具：列表去重判断（模型列表里同名项只留一个）。 */
public final class Json {
    private Json() {}

    public static <T> boolean has(Collection<T> items, T needle) {
        for (T item : items) if (item != null && item.equals(needle)) return true;
        return false;
    }
}
