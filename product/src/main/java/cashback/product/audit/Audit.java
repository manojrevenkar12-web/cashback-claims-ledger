package cashback.product.audit;

import cashback.common.Json;
import cashback.common.Keyed;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A hash-chained audit trail: each entry carries the hash of the one before, so a changed or removed entry breaks
 * every hash after it. The actor is whoever held the API key.
 */
public final class Audit {

    public record Entry(long seq, long atMillis, String actor, String action, String subject, String detail,
                        String prevHash, String hash) {

        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seq", seq);
            m.put("atMillis", atMillis);
            m.put("actor", actor);
            m.put("action", action);
            m.put("subject", subject);
            m.put("detail", detail);
            m.put("prevHash", prevHash);
            m.put("hash", hash);
            return m;
        }

        public static Entry fromJson(Map<String, Object> m) {
            return new Entry(Json.lng(m, "seq"), Json.lng(m, "atMillis"), Json.str(m, "actor"),
                Json.str(m, "action"), Json.str(m, "subject"), Json.str(m, "detail"), Json.str(m, "prevHash"),
                Json.str(m, "hash"));
        }
    }

    /** Appends are compare-and-set on the sequence number: {@code append} fails if {@code seq} is taken. */
    public interface Log {
        List<Entry> all();

        Entry last();

        boolean append(Entry entry);
    }

    public static final String GENESIS = "0".repeat(64);

    private final Log log;

    public Audit(Log log) {
        this.log = log;
    }

    public Entry record(long atMillis, String actor, String action, String subject, String detail) {
        for (int attempt = 0; attempt < 100; attempt++) {
            Entry last = log.last();
            long seq = last == null ? 1 : last.seq() + 1;
            String prev = last == null ? GENESIS : last.hash();
            Entry e = new Entry(seq, atMillis, actor, action, subject, detail, prev,
                hash(seq, atMillis, actor, action, subject, detail, prev));
            if (log.append(e)) {
                return e;
            }
        }
        throw new IllegalStateException("audit log contention");
    }

    public record Verification(boolean intact, long entries, long firstBrokenSeq) {
    }

    public Verification verify() {
        String prev = GENESIS;
        long n = 0;
        for (Entry e : log.all()) {
            n++;
            String expected = hash(e.seq(), e.atMillis(), e.actor(), e.action(), e.subject(), e.detail(), prev);
            if (!e.prevHash().equals(prev) || !e.hash().equals(expected) || e.seq() != n) {
                return new Verification(false, n, e.seq());
            }
            prev = e.hash();
        }
        return new Verification(true, n, -1);
    }

    public List<Entry> entries() {
        return log.all();
    }

    static String hash(long seq, long at, String actor, String action, String subject, String detail, String prev) {
        List<Object> parts = new ArrayList<>(List.of(seq, at, actor, action, subject, detail, prev));
        return Keyed.hex(Keyed.sha256(Json.compact(parts)));
    }

    public static final class MemoryLog implements Log {
        private final List<Entry> entries = new ArrayList<>();

        @Override
        public synchronized List<Entry> all() {
            return List.copyOf(entries);
        }

        @Override
        public synchronized Entry last() {
            return entries.isEmpty() ? null : entries.get(entries.size() - 1);
        }

        @Override
        public synchronized boolean append(Entry entry) {
            long expected = entries.isEmpty() ? 1 : entries.get(entries.size() - 1).seq() + 1;
            if (entry.seq() != expected) {
                return false;
            }
            entries.add(entry);
            return true;
        }

        /** For tests: replace an entry as an attacker would. */
        public synchronized void tamper(int index, Entry replacement) {
            entries.set(index, replacement);
        }
    }
}
