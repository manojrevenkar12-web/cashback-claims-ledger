package cashback.service.store;

import cashback.common.Json;
import cashback.product.audit.Audit;
import cashback.product.claims.ClaimService;
import cashback.product.claims.ClaimService.ClaimRecord;
import cashback.product.claims.Claims;
import cashback.product.ledger.Fact;
import cashback.product.ledger.LedgerStore;
import cashback.product.ledger.Records.AccountRecord;
import cashback.product.ledger.Records.Payout;
import cashback.product.ledger.Records.RewardRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Postgres implementations of the stores. Every write is compare-and-set on a version column, exactly like the
 * in-memory stores; the contract tests run against both.
 */
public final class PostgresStores {

    private static final String SCHEMA = """
        create table if not exists rewards (
          user_id text not null, reward_id text not null, order_key text, facts jsonb not null,
          version bigint not null, primary key (user_id, reward_id));
        create index if not exists rewards_order_key on rewards (order_key);
        create table if not exists accounts (user_id text primary key, payouts jsonb not null, version bigint not null);
        create table if not exists claims (
          claim_id text primary key, order_key text, status text not null, filed_at bigint not null,
          body jsonb not null, version bigint not null);
        create index if not exists claims_order_key on claims (order_key);
        create index if not exists claims_status on claims (status);
        create table if not exists audit (seq bigint primary key, entry jsonb not null);
        create table if not exists clicks (
          click_id text primary key, user_id text not null, merchant_id text not null, at_millis bigint not null);
        create index if not exists clicks_user_merchant on clicks (user_id, merchant_id);
        """;

    private final JdbcTemplate jdbc;

    public PostgresStores(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        for (String statement : SCHEMA.split(";")) {
            if (!statement.isBlank()) {
                jdbc.execute(statement);
            }
        }
    }

    /** Removes every row; for tests only. */
    public void truncateAll() {
        jdbc.execute("truncate rewards, accounts, claims, audit, clicks");
    }

    public LedgerStore ledger() {
        return new Ledger();
    }

    public ClaimService.ClaimStore claims() {
        return new ClaimRows();
    }

    public Audit.Log audit() {
        return new AuditLog();
    }

    public ClickStore clicks() {
        return new Clicks();
    }

    @SuppressWarnings("unchecked")
    static List<Fact> facts(String json) {
        List<Fact> out = new ArrayList<>();
        for (Object o : (List<Object>) Json.parse(json)) {
            out.add(Fact.decode((Map<String, Object>) o));
        }
        return out;
    }

    static String json(List<Fact> facts) {
        List<Object> out = new ArrayList<>();
        for (Fact f : facts) {
            out.add(Fact.encode(f));
        }
        return Json.compact(out);
    }

    @SuppressWarnings("unchecked")
    static List<Payout> payouts(String json) {
        List<Payout> out = new ArrayList<>();
        for (Object o : (List<Object>) Json.parse(json)) {
            Map<String, Object> m = (Map<String, Object>) o;
            out.add(new Payout(Json.str(m, "payoutId"), Json.lng(m, "amountCents"), Json.lng(m, "atMillis")));
        }
        return out;
    }

    static String payoutsJson(List<Payout> payouts) {
        List<Object> out = new ArrayList<>();
        for (Payout p : payouts) {
            out.add(Map.of("payoutId", p.payoutId(), "amountCents", p.amountCents(), "atMillis", p.atMillis()));
        }
        return Json.compact(out);
    }

    private final class Ledger implements LedgerStore {

        @Override
        public Optional<RewardRecord> reward(String userId, String rewardId) {
            List<RewardRecord> rows = jdbc.query("select facts::text, version from rewards where user_id = ? and "
                + "reward_id = ?", (rs, n) -> new RewardRecord(userId, rewardId, facts(rs.getString(1)),
                rs.getLong(2)), userId, rewardId);
            return rows.stream().findFirst();
        }

        @Override
        public boolean putReward(RewardRecord next) {
            String orderKey = next.orderKey().orElse(null);
            if (next.version() == 1) {
                return jdbc.update("insert into rewards (user_id, reward_id, order_key, facts, version) values "
                    + "(?, ?, ?, ?::jsonb, 1) on conflict do nothing", next.userId(), next.rewardId(), orderKey,
                    json(next.facts())) == 1;
            }
            return jdbc.update("update rewards set facts = ?::jsonb, version = ?, order_key = coalesce(order_key, ?) "
                + "where user_id = ? and reward_id = ? and version = ?", json(next.facts()), next.version(), orderKey,
                next.userId(), next.rewardId(), next.version() - 1) == 1;
        }

        @Override
        public List<RewardRecord> rewards(String userId) {
            return jdbc.query("select reward_id, facts::text, version from rewards where user_id = ? order by "
                + "reward_id", (rs, n) -> new RewardRecord(userId, rs.getString(1), facts(rs.getString(2)),
                rs.getLong(3)), userId);
        }

        @Override
        public Optional<RewardRecord> rewardForOrder(String merchantId, String orderId) {
            List<RewardRecord> rows = jdbc.query("select user_id, reward_id, facts::text, version from rewards "
                + "where order_key = ? order by reward_id limit 1", (rs, n) -> new RewardRecord(rs.getString(1),
                rs.getString(2), facts(rs.getString(3)), rs.getLong(4)), RewardRecord.orderKey(merchantId, orderId));
            return rows.stream().findFirst();
        }

        @Override
        public AccountRecord account(String userId) {
            List<AccountRecord> rows = jdbc.query("select payouts::text, version from accounts where user_id = ?",
                (rs, n) -> new AccountRecord(userId, payouts(rs.getString(1)), rs.getLong(2)), userId);
            return rows.stream().findFirst().orElse(AccountRecord.empty(userId));
        }

        @Override
        public boolean putAccount(AccountRecord next) {
            if (next.version() == 1) {
                return jdbc.update("insert into accounts (user_id, payouts, version) values (?, ?::jsonb, 1) "
                    + "on conflict do nothing", next.userId(), payoutsJson(next.payouts())) == 1;
            }
            return jdbc.update("update accounts set payouts = ?::jsonb, version = ? where user_id = ? and version = ?",
                payoutsJson(next.payouts()), next.version(), next.userId(), next.version() - 1) == 1;
        }
    }

    private final class ClaimRows implements ClaimService.ClaimStore {

        private ClaimRecord row(String body) {
            return ClaimRecord.fromJson(Json.object(body));
        }

        @Override
        public Optional<ClaimRecord> get(String claimId) {
            return jdbc.query("select body::text from claims where claim_id = ?", (rs, n) -> row(rs.getString(1)),
                claimId).stream().findFirst();
        }

        @Override
        public boolean put(ClaimRecord next) {
            String body = Json.compact(next.toJson());
            if (next.version() == 1) {
                return jdbc.update("insert into claims (claim_id, order_key, status, filed_at, body, version) values "
                    + "(?, ?, ?, ?, ?::jsonb, 1) on conflict do nothing", next.ticket().claimId(), next.orderKey(),
                    next.status().name(), next.ticket().filedAtMillis(), body) == 1;
            }
            return jdbc.update("update claims set status = ?, body = ?::jsonb, version = ? where claim_id = ? and "
                + "version = ?", next.status().name(), body, next.version(), next.ticket().claimId(),
                next.version() - 1) == 1;
        }

        @Override
        public List<ClaimRecord> byOrder(String orderKey) {
            return jdbc.query("select body::text from claims where order_key = ?", (rs, n) -> row(rs.getString(1)),
                orderKey);
        }

        @Override
        public List<ClaimRecord> all() {
            return jdbc.query("select body::text from claims order by filed_at, claim_id",
                (rs, n) -> row(rs.getString(1)));
        }
    }

    private final class AuditLog implements Audit.Log {

        @Override
        public List<Audit.Entry> all() {
            return jdbc.query("select entry::text from audit order by seq",
                (rs, n) -> Audit.Entry.fromJson(Json.object(rs.getString(1))));
        }

        @Override
        public Audit.Entry last() {
            return jdbc.query("select entry::text from audit order by seq desc limit 1",
                (rs, n) -> Audit.Entry.fromJson(Json.object(rs.getString(1)))).stream().findFirst().orElse(null);
        }

        @Override
        public boolean append(Audit.Entry entry) {
            try {
                // The primary key on seq makes a racing second append fail instead of forking the chain.
                return jdbc.update("insert into audit (seq, entry) values (?, ?::jsonb)", entry.seq(),
                    Json.compact(entry.toJson())) == 1;
            } catch (DuplicateKeyException e) {
                return false;
            }
        }
    }

    private final class Clicks implements ClickStore {

        @Override
        public void add(Claims.Click click) {
            jdbc.update("insert into clicks (click_id, user_id, merchant_id, at_millis) values (?, ?, ?, ?) "
                + "on conflict do nothing", click.clickId(), click.userId(), click.merchantId(), click.atMillis());
        }

        @Override
        public List<Claims.Click> clicks(String userId, String merchantId) {
            return jdbc.query("select click_id, at_millis from clicks where user_id = ? and merchant_id = ? "
                + "order by at_millis", (rs, n) -> new Claims.Click(rs.getString(1), userId, merchantId,
                rs.getLong(2)), userId, merchantId);
        }
    }
}
