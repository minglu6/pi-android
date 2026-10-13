import dev.pi.android.model.MessageQueue;

/** Isolated unit tests for {@link MessageQueue} (no Android runtime). */
public final class MessageQueueTest {
    public static void main(String[] args) {
        enqueueFifoAndPoll();
        rejectEmpty();
        updateRemoveTake();
        clearEmpties();
        System.out.println("MessageQueueTest OK");
    }

    private static void enqueueFifoAndPoll() {
        MessageQueue q = new MessageQueue();
        MessageQueue.Item a = q.enqueue("first");
        MessageQueue.Item b = q.enqueue("second");
        assertEqual(2, q.size());
        assertEqual("first", q.peek().text);
        assertEqual(a.id, q.poll().id);
        assertEqual(b.id, q.poll().id);
        assertTrue(q.isEmpty());
        assertNull(q.poll());
    }

    private static void rejectEmpty() {
        MessageQueue q = new MessageQueue();
        try {
            q.enqueue("  ");
            throw new AssertionError("expected empty reject");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        q.enqueue("ok");
        try {
            q.update(q.peek().id, "");
            throw new AssertionError("expected update reject");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    private static void updateRemoveTake() {
        MessageQueue q = new MessageQueue();
        MessageQueue.Item a = q.enqueue("one");
        MessageQueue.Item b = q.enqueue("two");
        assertTrue(q.update(a.id, "one-edited"));
        assertEqual("one-edited", q.get(a.id).text);
        assertTrue(q.remove(a.id));
        assertNull(q.get(a.id));
        assertEqual(1, q.size());
        MessageQueue.Item taken = q.take(b.id);
        assertEqual(b.id, taken.id);
        assertTrue(q.isEmpty());
        assertNull(q.take(b.id));
        assertTrue(!q.remove(999));
        assertTrue(!q.update(999, "nope"));
    }

    private static void clearEmpties() {
        MessageQueue q = new MessageQueue();
        q.enqueue("a");
        q.enqueue("b");
        q.clear();
        assertTrue(q.isEmpty());
        assertEqual(0, q.snapshot().size());
    }

    private static void assertTrue(boolean v) {
        if (!v) throw new AssertionError("expected true");
    }

    private static void assertNull(Object o) {
        if (o != null) throw new AssertionError("expected null, got " + o);
    }

    private static void assertEqual(Object expected, Object actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError("expected " + expected + " got " + actual);
        }
    }
}
