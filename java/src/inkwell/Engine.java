package inkwell;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Positional inverted index with BM25 ranking.
 *
 * Postings are stored as three parallel arrays per term: document ids, the offset
 * of each document's positions, and the positions themselves. That layout keeps
 * phrase queries possible without per-document objects, which is what makes a
 * 50,000-document index fit comfortably in memory.
 */
public final class Engine {
    static final class Postings {
        final int[] docs;
        final int[] offsets;
        final int[] positions;

        Postings(int[] docs, int[] offsets, int[] positions) {
            this.docs = docs;
            this.offsets = offsets;
            this.positions = positions;
        }
    }

    static final class Builder {
        int[] docs = new int[8];
        int[] offsets = new int[8];
        IntList positions = new IntList();
        int size = 0;

        void add(int docId, int position) {
            if (size == 0 || docs[size - 1] != docId) {
                if (size == docs.length) {
                    docs = Arrays.copyOf(docs, size * 2);
                    offsets = Arrays.copyOf(offsets, size * 2);
                }
                docs[size] = docId;
                offsets[size] = positions.size;
                size += 1;
            }
            positions.add(position);
        }

        Postings build(int totalPositions) {
            int[] finalOffsets = Arrays.copyOf(offsets, size + 1);
            finalOffsets[size] = positions.size;
            return new Postings(Arrays.copyOf(docs, size), finalOffsets, positions.toArray());
        }
    }

    static final class IntList {
        int[] values = new int[16];
        int size = 0;

        void add(int value) {
            if (size == values.length) values = Arrays.copyOf(values, size * 2);
            values[size++] = value;
        }

        int size() {
            return size;
        }

        int[] toArray() {
            return Arrays.copyOf(values, size);
        }
    }

    private final Map<String, Postings> index = new HashMap<>();
    private String[] titles;
    private String[] bodies;
    private int[] lengths;
    private double averageLength;
    private long postingsBytes;

    void build(Path documents) throws IOException {
        List<String> titleList = new ArrayList<>();
        List<String> bodyList = new ArrayList<>();
        List<Integer> lengthList = new ArrayList<>();
        Map<String, Builder> builders = new HashMap<>();
        long started = System.nanoTime();

        try (BufferedReader reader = Files.newBufferedReader(documents, StandardCharsets.UTF_8)) {
            String line;
            int docId = 0;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] parts = line.split("\t", 3);
                if (parts.length < 3) continue;
                String title = parts[1];
                String body = parts[2];
                titleList.add(title);
                bodyList.add(body);
                List<String> tokens = tokenize(title + " " + body);
                lengthList.add(tokens.size());
                for (int position = 0; position < tokens.size(); position++) {
                    builders.computeIfAbsent(tokens.get(position), key -> new Builder())
                        .add(docId, position);
                }
                docId += 1;
            }
        }

        titles = titleList.toArray(new String[0]);
        bodies = bodyList.toArray(new String[0]);
        lengths = new int[lengthList.size()];
        long total = 0;
        for (int index = 0; index < lengths.length; index++) {
            lengths[index] = lengthList.get(index);
            total += lengths[index];
        }
        averageLength = lengths.length == 0 ? 0 : (double) total / lengths.length;

        long bytes = 0;
        for (Map.Entry<String, Builder> entry : builders.entrySet()) {
            Postings postings = entry.getValue().build(0);
            index.put(entry.getKey(), postings);
            bytes += (long) postings.docs.length * 4 + (long) postings.offsets.length * 4
                + (long) postings.positions.length * 4 + 64;
        }
        postingsBytes = bytes;
        System.out.printf("indexed %d documents, %d terms, %.1f MB postings, %.0f ms%n",
            titles.length, index.size(), bytes / 1e6, (System.nanoTime() - started) / 1e6);
    }

    static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String lower = text.toLowerCase();
        for (int index = 0; index < lower.length(); index++) {
            char c = lower.charAt(index);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                current.append(c);
            } else if (current.length() > 0) {
                tokens.add(current.toString());
                current.setLength(0);
            }
        }
        if (current.length() > 0) tokens.add(current.toString());
        return tokens;
    }

    record Query(List<String> terms, List<List<String>> phrases, List<String> excluded) {}

    static Query parse(String raw) {
        List<String> terms = new ArrayList<>();
        List<List<String>> phrases = new ArrayList<>();
        List<String> excluded = new ArrayList<>();
        int index = 0;
        while (index < raw.length()) {
            char c = raw.charAt(index);
            if (c == '"') {
                int end = raw.indexOf('"', index + 1);
                if (end < 0) end = raw.length();
                List<String> phrase = tokenize(raw.substring(index + 1, end));
                if (!phrase.isEmpty()) phrases.add(phrase);
                index = end + 1;
            } else if (Character.isWhitespace(c)) {
                index += 1;
            } else {
                int end = index;
                while (end < raw.length() && !Character.isWhitespace(raw.charAt(end)) && raw.charAt(end) != '"') {
                    end += 1;
                }
                String word = raw.substring(index, end);
                boolean negated = word.startsWith("-");
                if (negated) word = word.substring(1);
                List<String> tokens = tokenize(word);
                if (!tokens.isEmpty()) {
                    if (negated) excluded.add(tokens.get(0));
                    else terms.add(tokens.get(0));
                }
                index = end;
            }
        }
        return new Query(terms, phrases, excluded);
    }

    record Hit(int docId, double score) {}

    List<Hit> search(Query query, int limit) {
        int[] candidates = null;
        for (String term : query.terms()) {
            int[] docs = index.containsKey(term) ? index.get(term).docs : new int[0];
            candidates = candidates == null ? docs : intersect(candidates, docs);
            if (candidates.length == 0) return List.of();
        }
        for (List<String> phrase : query.phrases()) {
            int[] docs = phraseCandidates(phrase);
            candidates = candidates == null ? docs : intersect(candidates, docs);
            if (candidates.length == 0) return List.of();
        }
        if (candidates == null) {
            candidates = new int[titles.length];
            for (int index = 0; index < candidates.length; index++) candidates[index] = index;
        }
        if (!query.excluded().isEmpty()) {
            Set<Integer> removed = new LinkedHashSet<>();
            for (String term : query.excluded()) {
                Postings postings = index.get(term);
                if (postings == null) continue;
                for (int doc : postings.docs) removed.add(doc);
            }
            candidates = Arrays.stream(candidates).filter(doc -> !removed.contains(doc)).toArray();
        }

        List<Hit> hits = new ArrayList<>(candidates.length);
        for (int doc : candidates) {
            hits.add(new Hit(doc, score(query, doc)));
        }
        hits.sort((a, b) -> Double.compare(b.score(), a.score()));
        if (hits.size() > limit) hits = new ArrayList<>(hits.subList(0, limit));
        return hits;
    }

    private int[] phraseCandidates(List<String> phrase) {
        int[] docs = null;
        for (String term : phrase) {
            Postings postings = index.get(term);
            if (postings == null) return new int[0];
            docs = docs == null ? postings.docs : intersect(docs, postings.docs);
            if (docs.length == 0) return docs;
        }
        List<Integer> matched = new ArrayList<>();
        for (int doc : docs) {
            if (containsPhrase(doc, phrase)) matched.add(doc);
        }
        int[] result = new int[matched.size()];
        for (int index = 0; index < result.length; index++) result[index] = matched.get(index);
        return result;
    }

    private boolean containsPhrase(int doc, List<String> phrase) {
        Postings first = index.get(phrase.get(0));
        int slot = Arrays.binarySearch(first.docs, doc);
        if (slot < 0) return false;
        int start = first.offsets[slot];
        int end = first.offsets[slot + 1];
        for (int position = start; position < end; position++) {
            int anchor = first.positions[position];
            boolean matched = true;
            for (int offset = 1; offset < phrase.size() && matched; offset++) {
                Postings next = index.get(phrase.get(offset));
                int nextSlot = Arrays.binarySearch(next.docs, doc);
                if (nextSlot < 0) {
                    matched = false;
                    break;
                }
                int nextStart = next.offsets[nextSlot];
                int nextEnd = next.offsets[nextSlot + 1];
                boolean found = false;
                for (int cursor = nextStart; cursor < nextEnd; cursor++) {
                    if (next.positions[cursor] == anchor + offset) {
                        found = true;
                        break;
                    }
                }
                matched = found;
            }
            if (matched) return true;
        }
        return false;
    }

    private double score(Query query, int doc) {
        double score = 0;
        int length = lengths[doc];
        for (String term : query.terms()) {
            score += bm25(term, doc, length);
        }
        for (List<String> phrase : query.phrases()) {
            for (String term : phrase) score += bm25(term, doc, length) * 0.5;
        }
        return score;
    }

    private double bm25(String term, int doc, int length) {
        Postings postings = index.get(term);
        if (postings == null) return 0;
        int slot = Arrays.binarySearch(postings.docs, doc);
        if (slot < 0) return 0;
        int frequency = postings.offsets[slot + 1] - postings.offsets[slot];
        double idf = Math.log(1 + (titles.length - postings.docs.length + 0.5) / (postings.docs.length + 0.5));
        double k1 = 1.2;
        double b = 0.75;
        return idf * frequency * (k1 + 1) / (frequency + k1 * (1 - b + b * length / averageLength));
    }

    private static int[] intersect(int[] left, int[] right) {
        int[] out = new int[Math.min(left.length, right.length)];
        int i = 0;
        int j = 0;
        int cursor = 0;
        while (i < left.length && j < right.length) {
            if (left[i] == right[j]) {
                out[cursor++] = left[i];
                i++;
                j++;
            } else if (left[i] < right[j]) {
                i++;
            } else {
                j++;
            }
        }
        return Arrays.copyOf(out, cursor);
    }

    String snippetFor(int doc, Query query) {
        String body = bodies[doc];
        String lower = body.toLowerCase();
        int position = -1;
        for (String term : query.terms()) {
            position = lower.indexOf(term);
            if (position >= 0) break;
        }
        if (position < 0) position = 0;
        int start = Math.max(0, position - 60);
        int end = Math.min(body.length(), start + 200);
        String text = body.substring(start, end).replace('\t', ' ').replace('\n', ' ');
        return start > 0 ? "…" + text : text;
    }

    int documentCount() {
        return titles.length;
    }

    int termCount() {
        return index.size();
    }

    long postingsBytes() {
        return postingsBytes;
    }

    String title(int doc) {
        return titles[doc];
    }

    double averageLength() {
        return averageLength;
    }
}
