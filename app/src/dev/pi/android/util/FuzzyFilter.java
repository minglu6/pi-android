package dev.pi.android.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pi TUI {@code packages/tui/src/fuzzy.ts} 的 JVM 移植：斜杠菜单按命令名模糊过滤。
 */
public final class FuzzyFilter {

    public interface TextOf<T> {
        String get(T item);
    }

    private static final Pattern LETTERS_THEN_DIGITS = Pattern.compile("^([a-z]+)([0-9]+)$");
    private static final Pattern DIGITS_THEN_LETTERS = Pattern.compile("^([0-9]+)([a-z]+)$");
    private static final Pattern WORD_BOUNDARY_PREV = Pattern.compile("[\\s\\-_./:]");

    private FuzzyFilter() {}

    public static final class Match {
        public final boolean matches;
        public final double score;

        public Match(boolean matches, double score) {
            this.matches = matches;
            this.score = score;
        }
    }

    @NonNull
    public static Match match(@Nullable String query, @Nullable String text) {
        String queryLower = query == null ? "" : query.toLowerCase(Locale.ROOT);
        String textLower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        Match primary = matchQuery(queryLower, textLower);
        if (primary.matches) return primary;

        String swapped = swappedQuery(queryLower);
        if (swapped.isEmpty()) return primary;
        Match swappedMatch = matchQuery(swapped, textLower);
        if (!swappedMatch.matches) return primary;
        return new Match(true, swappedMatch.score + 5);
    }

    @NonNull
    public static <T> List<T> filter(@NonNull List<T> items,
                                     @Nullable String query,
                                     @NonNull TextOf<T> getText) {
        if (items == null) throw new IllegalArgumentException("items");
        if (getText == null) throw new IllegalArgumentException("getText");
        String q = query == null ? "" : query;
        if (q.trim().isEmpty()) return new ArrayList<>(items);

        String[] tokens = q.trim().split("[\\s/]+");
        List<String> kept = new ArrayList<>();
        for (String t : tokens) {
            if (!t.isEmpty()) kept.add(t);
        }
        if (kept.isEmpty()) return new ArrayList<>(items);

        List<Scored<T>> results = new ArrayList<>();
        for (T item : items) {
            String text = getText.get(item);
            if (text == null) {
                throw new IllegalStateException("fuzzy text is null");
            }
            double totalScore = 0;
            boolean allMatch = true;
            for (String token : kept) {
                Match m = match(token, text);
                if (m.matches) {
                    totalScore += m.score;
                } else {
                    allMatch = false;
                    break;
                }
            }
            if (allMatch) results.add(new Scored<>(item, totalScore));
        }
        results.sort((a, b) -> Double.compare(a.totalScore, b.totalScore));
        List<T> out = new ArrayList<>(results.size());
        for (Scored<T> r : results) out.add(r.item);
        return out;
    }

    private static Match matchQuery(String normalizedQuery, String textLower) {
        if (normalizedQuery.isEmpty()) return new Match(true, 0);
        if (normalizedQuery.length() > textLower.length()) return new Match(false, 0);

        int queryIndex = 0;
        double score = 0;
        int lastMatchIndex = -1;
        int consecutiveMatches = 0;

        while (queryIndex < normalizedQuery.length()) {
            int i = textLower.indexOf(normalizedQuery.charAt(queryIndex), lastMatchIndex + 1);
            if (i < 0) break;

            boolean isWordBoundary = i == 0
                    || WORD_BOUNDARY_PREV.matcher(String.valueOf(textLower.charAt(i - 1))).matches();

            if (lastMatchIndex == i - 1) {
                consecutiveMatches++;
                score -= consecutiveMatches * 5;
            } else {
                consecutiveMatches = 0;
                if (lastMatchIndex >= 0) {
                    score += (i - lastMatchIndex - 1) * 2;
                }
            }

            if (isWordBoundary) score -= 10;
            score += i * 0.1;

            lastMatchIndex = i;
            queryIndex++;
        }

        if (queryIndex < normalizedQuery.length()) return new Match(false, 0);
        if (normalizedQuery.equals(textLower)) score -= 100;
        return new Match(true, score);
    }

    private static String swappedQuery(String queryLower) {
        Matcher lettersDigits = LETTERS_THEN_DIGITS.matcher(queryLower);
        if (lettersDigits.matches()) {
            return lettersDigits.group(2) + lettersDigits.group(1);
        }
        Matcher digitsLetters = DIGITS_THEN_LETTERS.matcher(queryLower);
        if (digitsLetters.matches()) {
            return digitsLetters.group(2) + digitsLetters.group(1);
        }
        return "";
    }

    private static final class Scored<T> {
        final T item;
        final double totalScore;

        Scored(T item, double totalScore) {
            this.item = item;
            this.totalScore = totalScore;
        }
    }
}
