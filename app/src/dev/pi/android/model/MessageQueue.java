package dev.pi.android.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Client-side follow-up queue while an agent turn is running.
 * Items are not chat bubbles; the UI renders them above the composer and
 * drains them FIFO after {@code agent_settled} (or abort settles).
 */
public final class MessageQueue {

    public static final class Item {
        public final long id;
        public String text;

        Item(long id, String text) {
            this.id = id;
            this.text = text;
        }
    }

    private final ArrayList<Item> items = new ArrayList<>();
    private long nextId = 1L;

    public Item enqueue(String text) {
        String t = requireText(text);
        Item item = new Item(nextId++, t);
        items.add(item);
        return item;
    }

    public boolean remove(long id) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id == id) {
                items.remove(i);
                return true;
            }
        }
        return false;
    }

    public boolean update(long id, String text) {
        String t = requireText(text);
        Item item = get(id);
        if (item == null) return false;
        item.text = t;
        return true;
    }

    /** Remove and return the head item, or null if empty. */
    public Item poll() {
        if (items.isEmpty()) return null;
        return items.remove(0);
    }

    public Item peek() {
        if (items.isEmpty()) return null;
        return items.get(0);
    }

    public Item get(long id) {
        for (Item item : items) {
            if (item.id == id) return item;
        }
        return null;
    }

    /** Remove and return the item with {@code id}, or null. */
    public Item take(long id) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id == id) return items.remove(i);
        }
        return null;
    }

    public void clear() {
        items.clear();
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public int size() {
        return items.size();
    }

    public List<Item> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(items));
    }

    private static String requireText(String text) {
        if (text == null) throw new IllegalArgumentException("queue text is null");
        String t = text.trim();
        if (t.isEmpty()) throw new IllegalArgumentException("queue text is empty");
        return t;
    }
}
