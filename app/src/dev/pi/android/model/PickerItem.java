package dev.pi.android.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** 共享选择器列表项：分组头或可选行。 */
public final class PickerItem {
    public enum Kind { HEADER, ROW }

    public final Kind kind;
    @Nullable public final String header;
    @NonNull public final String title;
    @Nullable public final String subtitle;
    @Nullable public final Object payload;
    public final boolean selected;

    private PickerItem(Kind kind, @Nullable String header, @NonNull String title,
                       @Nullable String subtitle, @Nullable Object payload, boolean selected) {
        this.kind = kind;
        this.header = header;
        this.title = title;
        this.subtitle = subtitle;
        this.payload = payload;
        this.selected = selected;
    }

    public static PickerItem header(@NonNull String label) {
        return new PickerItem(Kind.HEADER, label, "", null, null, false);
    }

    public static PickerItem row(@NonNull String title, @Nullable String subtitle,
                                 @Nullable Object payload, boolean selected) {
        return new PickerItem(Kind.ROW, null, title, subtitle, payload, selected);
    }
}
