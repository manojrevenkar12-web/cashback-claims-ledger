package cashback.world;

import cashback.common.Json;
import cashback.common.Keyed;
import cashback.world.Model.CardKind;
import cashback.world.Model.CardMessage;
import cashback.world.Model.CardOffer;
import cashback.world.Model.Claim;
import cashback.world.Model.ClaimTruth;
import cashback.world.Model.Click;
import cashback.world.Model.Event;
import cashback.world.Model.Line;
import cashback.world.Model.NetworkState;
import cashback.world.Model.Order;
import cashback.world.Model.RewardTruth;
import cashback.world.Model.Shop;
import cashback.world.Model.Truth;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates one world from the published terms and {@code world.json}: shoppers click out and order, networks track
 * most orders and report them late, twice and out of order, card schemes send authorisations, clearings and refunds,
 * and some shoppers (and a few fraudsters) file missing-cashback claims. Every draw is keyed.
 */
public final class World {

    public static final long HOUR = 3_600_000L;
    public static final long DAY = 24 * HOUR;

    public final Map<String, Object> cfg;
    public final Keyed k;
    public final LocalDate start;
    public final int days;
    public final long startMillis;
    public final long endMillis;
    public final Map<String, Shop> shops = new LinkedHashMap<>();
    public final Map<String, CardOffer> offers = new LinkedHashMap<>();
    public final List<Order> orders = new ArrayList<>();
    public final List<Click> clicks = new ArrayList<>();
    public final List<Claim> claims = new ArrayList<>();
    public final List<Event> events = new ArrayList<>();
    public final Map<String, RewardTruth> rewardTruths = new LinkedHashMap<>();
    private final java.util.Set<String> orderIds = new java.util.HashSet<>();
    private final Map<String, String> orderKeys = new HashMap<>();
    private final Map<String, List<Click>> clicksOf = new HashMap<>();

    private static final Map<String, String[]> ITEMS = Map.of(
        "de", new String[]{"Wollpullover", "Laufschuhe", "Kaffeebohnen 1 kg", "Fahrradhelm", "Sofakissen",
            "Vitamin D3 Kapseln", "Kopfhörer", "Jeans Slim", "Gartenschere", "Trinkflasche", "Regenjacke",
            "Tischlampe"},
        "fr", new String[]{"Pull en laine", "Baskets", "Café en grains", "Coussin", "Sac à dos", "Robe d'été",
            "Sécateur", "Théière", "Bougie parfumée", "Valise cabine"},
        "es", new String[]{"Zapatillas", "Camiseta", "Aceite de oliva", "Sandalias", "Mochila", "Toalla de playa",
            "Gafas de sol", "Bolso"},
        "en", new String[]{"Wireless mouse", "Board game", "Paperback novel", "Phone case", "USB-C cable",
            "Desk lamp", "Notebook set", "Controller"});
    private static final Map<String, String> GIFT_CARD = Map.of("de", "Geschenkkarte", "fr", "Carte cadeau", "es",
        "Tarjeta regalo", "en", "Gift card");
    private static final Map<String, Map<String, String>> SPECIAL = Map.of(
        "subscription", Map.of("de", "Kaffee-Abo monatlich", "fr", "Abonnement pain hebdo", "es",
            "Suscripción mensual", "en", "Monthly plan"),
        "service", Map.of("de", "Aufbauservice", "fr", "Installation", "es", "Montaje", "en", "Installation"),
        "prescription", Map.of("de", "Rezept Ibuprofen 600", "fr", "Ordonnance", "es", "Receta", "en", "Prescription"),
        "insurance", Map.of("de", "Reiseversicherung", "fr", "Assurance annulation", "es", "Seguro", "en",
            "Insurance"));
    private static final String[] FOREIGN_CODES = {"WELCOME15", "NEWS10", "BLACK20", "STUDENT10", "APP5", "FRIENDS12"};

    public static World load(Path configDir, String worldFile) {
        Map<String, Object> terms = Json.read(configDir.resolve("terms.json"));
        Map<String, Object> cfg = Json.read(configDir.resolve(worldFile));
        return new World(terms, cfg);
    }

    public World(Map<String, Object> terms, Map<String, Object> cfg) {
        this.cfg = cfg;
        this.k = new Keyed(Json.str(cfg, "seed"));
        this.start = LocalDate.parse(Json.str(cfg, "start"));
        this.days = Json.integer(cfg, "days");
        this.startMillis = start.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        this.endMillis = startMillis + days * DAY;
        readTerms(terms);
        generate();
    }

    private void readTerms(Map<String, Object> terms) {
        for (Map<String, Object> m : Json.objects(terms, "merchants")) {
            Shop s = new Shop(Json.str(m, "id"), Json.str(m, "name"), Json.str(m, "locale"),
                Json.str(m, "dateOrder"), Json.str(m, "network"), Json.integer(m, "cashbackBp"),
                Json.integer(m, "cookieDays"), Json.integer(m, "claimAfterDays"), Json.integer(m, "claimWithinDays"),
                Json.strings(m, "excluded"), Json.strings(m, "platformVouchers"));
            shops.put(s.id(), s);
        }
        for (Map<String, Object> o : Json.objects(terms, "offers")) {
            CardOffer c = new CardOffer(Json.str(o, "id"), Json.str(o, "merchantId"), Json.integer(o, "cashbackBp"),
                Json.lng(o, "minSpendCents"), Json.lng(o, "maxCashbackCents"), Json.integer(o, "holdDays"),
                Json.integer(o, "authExpiryDays"));
            offers.put(c.id(), c);
        }
    }

    public Map<String, Object> section(String name) {
        return Json.obj(cfg, name);
    }

    private void generate() {
        int shoppers = Json.integer(cfg, "shoppers");
        List<Shop> shopList = new ArrayList<>(shops.values());
        List<CardOffer> offerList = new ArrayList<>(offers.values());
        Map<String, Object> o = section("orders");
        for (int u = 0; u < shoppers; u++) {
            String user = String.format("u-%05d", u);
            int n = poisson(Json.num(cfg, "ordersPerShopperMean"), "orders", user);
            for (int i = 0; i < n; i++) {
                Shop shop = shopList.get(k.pick(shopList.size(), "shop", user, i));
                long at = startMillis + (long) (k.u("orderAt", user, i) * (days - 1) * DAY);
                makeOrder(user, i, shop, at, o);
            }
            if (k.chance(Json.num(cfg, "cardLinkedShare"), "card", user)) {
                int visits = poisson(Json.num(cfg, "storeVisitsPerCardShopperMean"), "visits", user);
                for (int i = 0; i < visits; i++) {
                    CardOffer offer = offerList.get(k.pick(offerList.size(), "offer", user, i));
                    long at = startMillis + (long) (k.u("visitAt", user, i) * (days - 1) * DAY);
                    makeCardTransaction(user, i, offer, at);
                }
            }
        }
        orders.sort(Comparator.comparingLong(Order::atMillis));
        for (Click c : clicks) {
            clicksOf.computeIfAbsent(c.userId() + "|" + c.shopId(), x -> new ArrayList<>()).add(c);
        }
        orders.replaceAll(this::track);
        for (Order order : orders) {
            makeClaims(order);
        }
        claims.sort(Comparator.comparingLong(Claim::filedAtMillis).thenComparing(Claim::claimId));
        events.sort(Comparator.comparingLong(Event::deliveredAtMillis).thenComparing(Event::eventId));
    }

    private int poisson(double mean, String purpose, Object id) {
        double limit = StrictMath.exp(-mean);
        double p = 1;
        int n = 0;
        while (true) {
            p *= k.u(purpose, id, n);
            if (p < limit) {
                return n;
            }
            n++;
        }
    }

    // ---- online orders ------------------------------------------------------------------------------------------

    private void makeOrder(String user, int i, Shop shop, long at, Map<String, Object> o) {
        String key = user + "/" + i;
        LocalDate day = dayOf(at);
        int nLines = (int) k.between(Json.integer(o, "linesMin"), Json.integer(o, "linesMax"), "lines", key);
        String[] items = ITEMS.get(shop.locale());
        List<Line> lines = new ArrayList<>();
        List<Integer> qty = new ArrayList<>();
        for (int j = 0; j < nLines; j++) {
            if (k.chance(Json.num(o, "giftCardLine"), "gift", key, j)) {
                long value = 1000L * k.between(1, 10, "giftValue", key, j) * 5 / 2;
                lines.add(new Line(GIFT_CARD.get(shop.locale()) + " " + value / 100 + " €", value, "gift_card"));
                qty.add(1);
                continue;
            }
            List<String> specials = shop.excluded().stream().filter(SPECIAL::containsKey).toList();
            if (!specials.isEmpty() && k.chance(Json.num(o, "specialLine"), "special", key, j)) {
                String cat = specials.get(k.pick(specials.size(), "specialCat", key, j));
                long cents = k.between(990, 6990, "specialCents", key, j);
                lines.add(new Line(SPECIAL.get(cat).get(shop.locale()), cents, cat));
                qty.add(1);
                continue;
            }
            String item = items[k.pick(items.length, "item", key, j)];
            int q = (int) k.between(1, 3, "qty", key, j);
            long unit = k.between(Json.lng(o, "lineCentsMin"), Json.lng(o, "lineCentsMax"), "unit", key, j);
            lines.add(new Line(item, unit * q, "standard"));
            qty.add(q);
        }
        long sum = lines.stream().mapToLong(Line::cents).sum();
        String voucher = null;
        boolean platform = false;
        long discount = 0;
        double vu = k.u("voucher", key);
        if (!shop.platformVouchers().isEmpty() && vu < Json.num(o, "platformVoucher")) {
            voucher = shop.platformVouchers().get(k.pick(shop.platformVouchers().size(), "pv", key));
            platform = true;
        } else if (vu > 1 - Json.num(o, "foreignVoucher")) {
            voucher = FOREIGN_CODES[k.pick(FOREIGN_CODES.length, "fv", key)];
        }
        if (voucher != null) {
            long pct = k.between(5, 20, "pct", key);
            discount = sum * pct / 100;
        }
        long shipping = k.chance(Json.num(o, "shippingCharged"), "ship", key) ? k.between(395, 695, "shipCents", key)
            : 0;
        long total = sum - discount + shipping;
        String orderId;
        int draw = 0;
        do {
            orderId = shop.id().substring(2, 4).toUpperCase() + "-" + String.format("%05d",
                k.between(10000, 99999, "oid", key, draw)) + "-" + String.format("%02d", k.between(10, 99, "oid2",
                key, draw));
            draw++;
        } while (!orderIds.add(shop.id() + "#" + orderId));

        Long clickAt = null;
        boolean valid = false;
        if (k.chance(Json.num(o, "clickedThroughPlatform"), "clicked", key)) {
            long daysBefore;
            if (k.chance(Json.num(o, "clickTooOldShare"), "tooOld", key)) {
                daysBefore = shop.cookieDays() + k.between(1, 10, "oldBy", key);
            } else if (k.chance(Json.num(o, "clickSameDay"), "sameDay", key)) {
                daysBefore = 0;
            } else {
                daysBefore = k.between(1, Math.max(1, shop.cookieDays()), "before", key);
            }
            long clickDayStart = day.minusDays(daysBefore).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
            long latest = daysBefore == 0 ? at - clickDayStart - 60_000 : DAY - 1;
            clickAt = clickDayStart + (long) (k.u("clickTime", key) * Math.max(0, latest));
            clicks.add(new Click("c-" + key, user, shop.id(), clickAt));
        }
        String text = Render.confirmation(shop.locale(), shop.dateOrder(), shop.name(), orderId, day, lines, qty,
            voucher, discount, shipping, total);
        boolean hard = k.chance(Json.num(section("reader"), "hardShare"), "hard", key);
        orders.add(new Order(orderId, user, shop, day, at, lines, voucher, platform, discount, shipping, total,
            clickAt, valid, false, null, text, hard));
        orderKeys.put(orderId + "@" + shop.id(), key);
    }

    /**
     * An order is attributable when any click by the shopper to that shop falls in the cookie window up to the order
     * day (the last click wins, as with a real cookie). Attributable orders are tracked unless tracking breaks.
     */
    private Order track(Order o) {
        String key = orderKeys.get(o.orderId() + "@" + o.shop().id());
        List<Click> mine = clicksOf.getOrDefault(o.userId() + "|" + o.shop().id(), List.of());
        // The terms count days: a click on or before the order day, at most cookieDays before, qualifies.
        boolean valid = mine.stream().anyMatch(c -> {
            long before = ChronoUnit.DAYS.between(dayOf(c.atMillis()), o.day());
            return before >= 0 && before <= o.shop().cookieDays();
        });
        // A cookie only tracks if the click really came before the purchase.
        boolean cookie = mine.stream().anyMatch(c -> c.atMillis() <= o.atMillis()
            && ChronoUnit.DAYS.between(dayOf(c.atMillis()), o.day()) <= o.shop().cookieDays());
        Map<String, Object> net = section("networks");
        boolean tracked = valid && cookie && !k.chance(Json.num(Json.obj(net, "trackingLoss"), o.shop().network()), "lost", key);
        Long reportAt = tracked ? o.atMillis() + HOUR * k.between(1, Json.lng(net, "reportHoursMax"), "report", key)
            : null;
        Order t = new Order(o.orderId(), o.userId(), o.shop(), o.day(), o.atMillis(), o.lines(), o.voucherCode(),
            o.platformVoucher(), o.discountCents(), o.shippingCents(), o.totalCents(), o.clickAtMillis(), valid,
            tracked, reportAt, o.confirmation(), o.hard());
        if (tracked) {
            networkLifecycle(t, key);
        }
        return t;
    }

    /** The network's updates for a tracked order, each delivered late, sometimes twice, sometimes replayed. */
    private void networkLifecycle(Order order, String key) {
        Map<String, Object> net = section("networks");
        String reward = "r-" + order.orderId();
        long eligible = Engine.eligible(order.lines(), order.discountCents(), order.shop());
        List<NetworkState> states = new ArrayList<>();
        long t = order.reportAtMillis();
        long sale = eligible;
        states.add(state(order, reward, sale, "PENDING", t, 1));
        if (k.chance(Json.num(net, "adjustShare"), "adjust", key)) {
            t += DAY * k.between(2, 15, "adjustAt", key);
            sale = sale * k.between(40, 90, "adjustPct", key) / 100;
            states.add(state(order, reward, sale, "PENDING", t, 2));
        }
        long finalAt = order.atMillis() + DAY * k.between(Json.lng(net, "approvalDaysMin"),
            Json.lng(net, "approvalDaysMax"), "approve", key);
        boolean declined = (order.voucherCode() != null && !order.platformVoucher()) || eligible == 0
            || k.chance(Json.num(net, "declineShare"), "decline", key);
        states.add(state(order, reward, sale, declined ? "DECLINED" : "CONFIRMED", Math.max(finalAt, t + HOUR), 3));
        if (!declined && k.chance(Json.num(net, "reversalShare"), "reverse", key)) {
            states.add(state(order, reward, sale, "DECLINED", Math.max(finalAt, t + HOUR) + DAY * k.between(10, 30,
                "reverseAt", key), 4));
        }
        List<NetworkState> happened = states.stream().filter(s -> s.updatedAtMillis() <= endMillis).toList();
        if (happened.isEmpty()) {
            return;
        }
        NetworkState last = happened.get(happened.size() - 1);
        rewardTruths.put(order.userId() + "|" + reward, new RewardTruth(order.userId(), reward, last.status(),
            last.status().equals("DECLINED") ? 0 : last.cashbackCents()));
        deliver(happened.stream().map(s -> (Object) s).toList(), key, happened.stream()
            .map(NetworkState::updatedAtMillis).toList());
    }

    private NetworkState state(Order order, String reward, long sale, String status, long at, int n) {
        return new NetworkState("n-" + order.orderId() + "-" + n, order.userId(), reward, order.shop().id(),
            order.orderId(), sale, Engine.cashback(sale, order.shop().cashbackBp()), status, at);
    }

    // ---- in-store card transactions -----------------------------------------------------------------------------

    private void makeCardTransaction(String user, int i, CardOffer offer, long at) {
        Map<String, Object> c = section("card");
        String key = user + "/card/" + i;
        String tx = "t-" + user + "-" + i;
        long amount = k.between(Json.lng(c, "amountCentsMin"), Json.lng(c, "amountCentsMax"), "amount", key);
        List<CardMessage> messages = new ArrayList<>();
        messages.add(new CardMessage("a-" + tx, user, tx, offer.id(), CardKind.AUTH, null, amount, at));
        Long clearedAt = null;
        long cleared = 0;
        if (k.chance(Json.num(c, "clearingShare"), "clears", key)) {
            clearedAt = at + DAY * k.between(0, Json.lng(c, "clearingDaysMax"), "clearAt", key) + HOUR;
            cleared = k.chance(Json.num(c, "clearingDiffersShare"), "differs", key)
                ? amount + k.between(-amount / 10, amount / 5, "tip", key) : amount;
            messages.add(new CardMessage("c-" + tx, user, tx, offer.id(), CardKind.CLEARING, null, cleared, clearedAt));
        }
        long refunded = 0;
        if (clearedAt != null && k.chance(Json.num(c, "refundShare"), "refunds", key)) {
            boolean full = k.chance(Json.num(c, "fullRefundShare"), "full", key);
            long r = full ? cleared : cleared * k.between(10, 60, "refundPct", key) / 100;
            long rAt = clearedAt + DAY * k.between(1, Json.lng(c, "refundDaysMax"), "refundAt", key);
            messages.add(new CardMessage("f-" + tx + "-1", user, tx, offer.id(), CardKind.REFUND, "rf-" + tx + "-1", r,
                rAt));
            refunded = r;
        }
        List<CardMessage> happened = messages.stream().filter(m -> m.atMillis() <= endMillis).toList();
        // The world's own reckoning of the reward at the end, from what happened (not from what was delivered).
        boolean hasClearing = happened.stream().anyMatch(m -> m.kind() == CardKind.CLEARING);
        long refundedByEnd = happened.stream().anyMatch(m -> m.kind() == CardKind.REFUND) ? refunded : 0;
        String status;
        long cashback;
        if (!hasClearing) {
            boolean expired = endMillis >= at + offer.authExpiryDays() * DAY;
            status = expired ? "DECLINED" : "PENDING";
            cashback = expired ? 0 : Engine.cardCashback(offer, Math.max(0, amount - refundedByEnd));
        } else {
            long net = Math.max(0, cleared - refundedByEnd);
            cashback = Engine.cardCashback(offer, net);
            if (net < offer.minSpendCents()) {
                status = "DECLINED";
                cashback = 0;
            } else {
                status = endMillis < clearedAt + offer.holdDays() * DAY ? "PENDING" : "CONFIRMED";
            }
        }
        rewardTruths.put(user + "|" + tx, new RewardTruth(user, tx, status, cashback));
        deliver(happened.stream().map(m -> (Object) m).toList(), key, happened.stream()
            .map(CardMessage::atMillis).toList());
    }

    private void deliver(List<Object> facts, String key, List<Long> times) {
        Map<String, Object> d = section("delivery");
        for (int i = 0; i < facts.size(); i++) {
            Object f = facts.get(i);
            String id = eventId(f);
            long at = times.get(i);
            events.add(new Event(id, at + delay(d, key, i, 0), f));
            if (k.chance(Json.num(d, "duplicateShare"), "dup", key, i)) {
                events.add(new Event(id, at + delay(d, key, i, 1), f));
            }
        }
        if (!facts.isEmpty() && k.chance(Json.num(d, "replayShare"), "replay", key)) {
            long replayAt = times.get(times.size() - 1) + DAY * k.between(1, 20, "replayAt", key);
            for (Object f : facts) {
                events.add(new Event(eventId(f), replayAt, f));
            }
        }
    }

    private long delay(Map<String, Object> d, String key, int i, int copy) {
        if (k.chance(Json.num(d, "longDelayShare"), "long", key, i, copy)) {
            return (long) (k.exponential(Json.num(d, "longDelayDaysMean"), "longDelay", key, i, copy) * DAY);
        }
        return (long) (k.exponential(Json.num(d, "delayHoursMean"), "delayH", key, i, copy) * HOUR);
    }

    private static String eventId(Object f) {
        return f instanceof NetworkState n ? n.eventId() : ((CardMessage) f).eventId();
    }

    // ---- claims -------------------------------------------------------------------------------------------------

    private void makeClaims(Order order) {
        Map<String, Object> c = section("claims");
        String key = order.orderId();
        double p;
        if (!order.validClick()) {
            p = Json.num(c, "noClickFiles");
        } else if (!order.tracked()) {
            p = Json.num(c, "untrackedFiles");
        } else {
            p = Json.num(c, "trackedFilesAnyway");
        }
        if (k.chance(p, "files", key)) {
            long filed = claimTime(order, c, key, 0);
            if (filed < endMillis) {
                boolean wrongShop = k.chance(Json.num(c, "wrongMerchant"), "wrongShop", key);
                String formShop = order.shop().id();
                if (wrongShop) {
                    List<String> others = shops.keySet().stream().filter(s -> !s.equals(order.shop().id())).toList();
                    formShop = others.get(k.pick(others.size(), "otherShop", key));
                }
                addClaim("cl-" + key, order.userId(), formShop, order, filed, false, c);
            }
        }
        if (k.chance(Json.num(c, "fraudCopies"), "fraud", key)) {
            String fraudster = "u-f" + key.replaceAll("[^0-9]", "");
            long filed = claimTime(order, c, key, 1);
            if (filed < endMillis) {
                if (k.chance(Json.num(c, "fraudsterClicked"), "fraudClick", key)) {
                    long clickAt = order.atMillis() - HOUR * k.between(1, 20, "fraudClickAt", key);
                    clicks.add(new Click("c-f-" + key, fraudster, order.shop().id(), clickAt));
                }
                addClaim("cl-f-" + key, fraudster, order.shop().id(), order, filed, true, c);
            }
        }
    }

    private long claimTime(Order order, Map<String, Object> c, String key, int who) {
        long daysAfter;
        if (k.chance(Json.num(c, "lateShare"), "late", key, who)) {
            daysAfter = order.shop().claimWithinDays() + k.between(1, 40, "lateBy", key, who);
        } else {
            daysAfter = Json.lng(c, "daysAfterMin") + (long) k.exponential(Json.num(c, "daysAfterMean"), "after", key,
                who);
        }
        long dayStart = order.day().plusDays(daysAfter).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        return dayStart + (long) (k.u("claimHour", key, who) * 20 * HOUR) + 2 * HOUR;
    }

    private void addClaim(String claimId, String userId, String formShop, Order order, long filed, boolean fraud,
                          Map<String, Object> c) {
        Map<String, Object> form = Json.obj(c, "form");
        String formId = order.orderId();
        if (k.chance(Json.num(form, "orderIdTypo"), "typo", claimId)) {
            char[] chars = formId.toCharArray();
            int pos = 3 + k.pick(5, "typoPos", claimId);
            chars[pos] = chars[pos] == '9' ? '0' : (char) (chars[pos] + 1);
            formId = new String(chars);
        }
        LocalDate formDate = k.chance(Json.num(form, "dateWrong"), "dateWrong", claimId)
            ? order.day().plusDays(k.between(1, 5, "dateBy", claimId)) : order.day();
        long formAmount = k.chance(Json.num(form, "amountIsTotal"), "amountTotal", claimId) ? order.totalCents()
            : (order.totalCents() - order.shippingCents()) / 100 * 100;
        Claim claim = new Claim(claimId, userId, formShop, order, filed, formId, formDate, formAmount,
            truth(order, userId, formShop, filed, fraud), fraud);
        claims.add(claim);
    }

    /** What is right for a claim, in the order the terms publish their rules. */
    private ClaimTruth truth(Order order, String userId, String formShop, long filed, boolean fraud) {
        if (fraud) {
            return new ClaimTruth(Truth.FRAUD, "fraud", 0, 0);
        }
        if (!formShop.equals(order.shop().id())) {
            return new ClaimTruth(Truth.REJECT, "other_merchant", 0, 0);
        }
        if (order.tracked() && order.reportAtMillis() <= filed) {
            return new ClaimTruth(Truth.ALREADY_TRACKED, "already_tracked", 0, 0);
        }
        long age = ChronoUnit.DAYS.between(order.day(), dayOf(filed));
        Shop s = order.shop();
        if (age < s.claimAfterDays()) {
            return new ClaimTruth(Truth.WAIT, "too_early", 0, 0);
        }
        if (age > s.claimWithinDays()) {
            return new ClaimTruth(Truth.REJECT, "too_late", 0, 0);
        }
        if (!order.validClick()) {
            return new ClaimTruth(Truth.REJECT, "no_qualifying_click", 0, 0);
        }
        if (order.voucherCode() != null && !order.platformVoucher()) {
            return new ClaimTruth(Truth.REJECT, "foreign_voucher", 0, 0);
        }
        long eligible = Engine.eligible(order.lines(), order.discountCents(), s);
        if (eligible == 0) {
            return new ClaimTruth(Truth.REJECT, "excluded_items", 0, 0);
        }
        return new ClaimTruth(Truth.FILE, "eligible", eligible, Engine.cashback(eligible, s.cashbackBp()));
    }

    public static LocalDate dayOf(long millis) {
        return java.time.Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate();
    }
}
