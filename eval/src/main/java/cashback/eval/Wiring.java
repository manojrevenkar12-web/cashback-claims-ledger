package cashback.eval;

import cashback.product.claims.Claims;
import cashback.product.claims.ConfirmationReader;
import cashback.product.ledger.Fact;
import cashback.product.ledger.RewardStatus;
import cashback.world.Actors;
import cashback.world.Model.CardMessage;
import cashback.world.Model.Claim;
import cashback.world.Model.Click;
import cashback.world.Model.NetworkState;
import cashback.world.World;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Connects the world to the product's ports. This is the only place that knows both: the product sees tickets, facts,
 * clicks and readings, never the world's truth.
 */
final class Wiring {

    private Wiring() {
    }

    static Fact fact(Object worldFact) {
        if (worldFact instanceof NetworkState n) {
            return new Fact.NetworkUpdate(n.eventId(), n.userId(), n.rewardId(), n.shopId(), n.orderId(),
                n.saleCents(), n.cashbackCents(), RewardStatus.valueOf(n.status()), n.updatedAtMillis());
        }
        CardMessage m = (CardMessage) worldFact;
        return switch (m.kind()) {
            case AUTH -> new Fact.CardAuth(m.eventId(), m.userId(), m.txId(), m.offerId(), m.amountCents(),
                m.atMillis());
            case CLEARING -> new Fact.CardClearing(m.eventId(), m.userId(), m.txId(), m.offerId(), m.amountCents(),
                m.atMillis());
            case REFUND -> new Fact.CardRefund(m.eventId(), m.userId(), m.txId(), m.offerId(), m.refundId(),
                m.amountCents(), m.atMillis());
        };
    }

    static Claims.Ticket ticket(Claim c) {
        return new Claims.Ticket(c.claimId(), c.userId(), c.formShopId(), c.filedAtMillis(), c.formOrderId(),
            c.formOrderDate(), c.formAmountCents(), c.order().confirmation());
    }

    static Claims.ClickLog clicks(World w) {
        Map<String, List<Claims.Click>> byKey = new HashMap<>();
        for (Click c : w.clicks) {
            byKey.computeIfAbsent(c.userId() + "|" + c.shopId(), x -> new ArrayList<>())
                .add(new Claims.Click(c.clickId(), c.userId(), c.shopId(), c.atMillis()));
        }
        return (user, merchant) -> byKey.getOrDefault(user + "|" + merchant, List.of());
    }

    /** The model stand-in, behind the product's reader port. */
    static ConfirmationReader reader(World w, Map<String, Claim> claims) {
        Actors.Reader reader = new Actors.Reader(w);
        return (ticket, attempt) -> {
            Actors.Read r = reader.read(claims.get(ticket.claimId()), attempt);
            if (r == null) {
                throw new ConfirmationReader.ReadFailed("no usable answer");
            }
            List<Claims.ReadLine> lines = new ArrayList<>();
            for (Actors.ReadLine l : r.lines()) {
                lines.add(new Claims.ReadLine(l.description(), l.cents()));
            }
            return new Claims.Reading(r.merchantName(), r.orderId(), r.dateText(), lines, r.voucherCode(),
                r.discountCents(), r.shippingCents(), r.totalCents());
        };
    }

    static LocalDate day(long millis) {
        return World.dayOf(millis);
    }
}
