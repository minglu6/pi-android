package dev.pi.android.util;

import java.util.Arrays;
import java.util.List;

public final class FuzzyFilterTest {
    public static void main(String[] args) {
        List<String> items = Arrays.asList("apple", "banana", "cherry");
        List<String> all = FuzzyFilter.filter(items, "", x -> x);
        if (!all.equals(items)) {
            throw new AssertionError("empty query returns all, got " + all);
        }
        List<String> blank = FuzzyFilter.filter(items, "   ", x -> x);
        if (!blank.equals(items)) {
            throw new AssertionError("whitespace query returns all, got " + blank);
        }

        List<String> an = FuzzyFilter.filter(items, "an", x -> x);
        if (!an.equals(Arrays.asList("banana"))) {
            throw new AssertionError("an -> banana, got " + an);
        }

        List<String> ranked = FuzzyFilter.filter(Arrays.asList("a_p_p", "app", "application"), "app", x -> x);
        if (!"app".equals(ranked.get(0))) {
            throw new AssertionError("exact consecutive match first, got " + ranked);
        }

        List<String> exact = FuzzyFilter.filter(Arrays.asList("clone", "cl"), "cl", x -> x);
        if (!exact.equals(Arrays.asList("cl", "clone"))) {
            throw new AssertionError("exact before longer prefix, got " + exact);
        }

        class Item { final String name; Item(String n) { name = n; } }
        List<Item> objs = Arrays.asList(new Item("foo"), new Item("bar"), new Item("foobar"));
        List<Item> foo = FuzzyFilter.filter(objs, "foo", i -> i.name);
        if (foo.size() != 2) throw new AssertionError("foo matches 2, got " + foo.size());

        class Model { final String id; final String provider; Model(String id, String p) { this.id = id; provider = p; } }
        Model m = new Model("gpt-5.5", "openai-codex");
        List<Model> slash = FuzzyFilter.filter(Arrays.asList(m), "openai-codex/gpt-5.5",
                model -> model.id + " " + model.provider);
        if (slash.size() != 1 || slash.get(0) != m) {
            throw new AssertionError("slash-separated tokens must match reordered text");
        }

        try {
            List<String> none = null;
            FuzzyFilter.filter(none, "x", (String x) -> x);
            throw new AssertionError("null items must throw");
        } catch (IllegalArgumentException expected) {
            if (!expected.getMessage().contains("items")) throw expected;
        }
        System.out.println("FuzzyFilterTest ok");
    }
}
