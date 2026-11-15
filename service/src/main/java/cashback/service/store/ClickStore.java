package cashback.service.store;

import cashback.product.claims.Claims;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** The platform's record of shoppers clicking out to merchants; the claims desk reads it. */
public interface ClickStore extends Claims.ClickLog {

    /** Records a click once; recording the same click id again changes nothing. */
    void add(Claims.Click click);

    final class Memory implements ClickStore {
        private final Map<String, List<Claims.Click>> byKey = new ConcurrentHashMap<>();
        private final Map<String, Boolean> seen = new ConcurrentHashMap<>();

        @Override
        public void add(Claims.Click click) {
            if (seen.putIfAbsent(click.clickId(), Boolean.TRUE) == null) {
                byKey.compute(click.userId() + "|" + click.merchantId(), (k, list) -> {
                    List<Claims.Click> next = list == null ? new ArrayList<>() : new ArrayList<>(list);
                    next.add(click);
                    return next;
                });
            }
        }

        @Override
        public List<Claims.Click> clicks(String userId, String merchantId) {
            return List.copyOf(byKey.getOrDefault(userId + "|" + merchantId, List.of()));
        }
    }
}
