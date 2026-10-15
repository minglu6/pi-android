package dev.pi.android.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

/** 列表已到顶时下滑关闭，不改背景/主题。 */
public final class SwipeDownLayout extends LinearLayout {

    private float downY;
    private boolean tracking;
    private RecyclerView list;
    @Nullable private Runnable onDismiss;
    private int touchSlop;
    private int dismissPx;

    public SwipeDownLayout(@NonNull Context context) {
        super(context);
        init(context);
    }

    public SwipeDownLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public SwipeDownLayout(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        ViewConfiguration vc = ViewConfiguration.get(context);
        touchSlop = vc.getScaledTouchSlop();
        dismissPx = Math.round(64 * context.getResources().getDisplayMetrics().density);
        if (dismissPx <= 0) {
            throw new IllegalStateException("dismissPx=" + dismissPx);
        }
    }

    public void setList(@NonNull RecyclerView list) {
        this.list = list;
    }

    public void setOnDismiss(@NonNull Runnable onDismiss) {
        this.onDismiss = onDismiss;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downY = ev.getY();
                tracking = false;
                setTranslationY(0f);
                return false;
            case MotionEvent.ACTION_MOVE:
                float dy = ev.getY() - downY;
                if (dy > touchSlop && canDismissDown()) {
                    tracking = true;
                    return true;
                }
                return false;
            default:
                return tracking;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!tracking) return super.onTouchEvent(ev);
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                setTranslationY(Math.max(0f, ev.getY() - downY));
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                float dy = ev.getY() - downY;
                tracking = false;
                if (dy >= dismissPx) {
                    setTranslationY(0f);
                    if (onDismiss == null) {
                        throw new IllegalStateException("SwipeDownLayout onDismiss missing");
                    }
                    onDismiss.run();
                } else {
                    setTranslationY(0f);
                }
                return true;
            default:
                return true;
        }
    }

    private boolean canDismissDown() {
        if (list == null) {
            throw new IllegalStateException("SwipeDownLayout list missing");
        }
        return !list.canScrollVertically(-1);
    }
}
