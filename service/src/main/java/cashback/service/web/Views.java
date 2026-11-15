package cashback.service.web;

import cashback.product.claims.ClaimService.ClaimRecord;
import cashback.product.ledger.Ledger;
import cashback.product.ledger.RewardFold;
import cashback.product.terms.Terms;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The JSON shapes the API returns. */
public final class Views {

    private Views() {
    }

    public static Map<String, Object> balance(Ledger.Balance b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("userId", b.userId());
        m.put("pendingCents", b.pendingCents());
        m.put("confirmedCents", b.confirmedCents());
        m.put("paidOutCents", b.paidOutCents());
        m.put("availableCents", b.availableCents());
        m.put("owedCents", b.owedCents());
        List<Object> rewards = new ArrayList<>();
        for (RewardFold.Reward r : b.rewards()) {
            rewards.add(reward(r));
        }
        m.put("rewards", rewards);
        return m;
    }

    public static Map<String, Object> reward(RewardFold.Reward r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rewardId", r.rewardId());
        m.put("kind", r.kind().name());
        m.put("merchantId", r.merchantId());
        m.put("status", r.status().name());
        m.put("cashbackCents", r.cashbackCents());
        m.put("baseCents", r.baseCents());
        m.put("reason", r.reason());
        return m;
    }

    /** A claim for the support queue: the summary fields without the pasted text. */
    public static Map<String, Object> summary(ClaimRecord c, Terms terms) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("claimId", c.ticket().claimId());
        m.put("userId", c.ticket().userId());
        m.put("merchantId", c.ticket().merchantId());
        m.put("merchantName", terms.merchants().containsKey(c.ticket().merchantId())
            ? terms.merchant(c.ticket().merchantId()).name() : c.ticket().merchantId());
        m.put("filedAtMillis", c.ticket().filedAtMillis());
        m.put("status", c.status().name());
        m.put("outcome", c.finalOutcome().name());
        m.put("reason", c.resolution() != null ? c.resolution().reason() : c.decision().reason());
        m.put("deskReason", c.decision().reason());
        m.put("cashbackCents", c.resolution() != null ? c.resolution().cashbackCents() : c.decision().cashbackCents());
        m.put("orderId", c.decision().orderId());
        return m;
    }

    public static Map<String, Object> claim(ClaimRecord c, Terms terms) {
        Map<String, Object> m = summary(c, terms);
        m.put("record", c.toJson());
        return m;
    }
}
