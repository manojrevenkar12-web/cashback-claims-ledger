package cashback.service.web;

import cashback.common.Json;
import cashback.service.Settings;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.NoSuchElementException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.filter.OncePerRequestFilter;

/** Shared web pieces: who is calling, signed webhooks, JSON responses and errors. */
public final class Web {

    private Web() {
    }

    /** The holder of the API key: an actor name for the audit trail and a role. */
    public record Caller(String actor, String role) {

        public boolean isSupport() {
            return role.equals("support");
        }

        /** Shoppers see only their own account and claims; support sees all. */
        public void mayAct(String userId) {
            if (!isSupport() && !actor.equals("shopper:" + userId)) {
                throw new Forbidden("this key cannot act for " + userId);
            }
        }

        public void mustBeSupport() {
            if (!isSupport()) {
                throw new Forbidden("only support can do this");
            }
        }
    }

    public static final class Forbidden extends RuntimeException {
        public Forbidden(String message) {
            super(message);
        }
    }

    public static final class Unauthorized extends RuntimeException {
        public Unauthorized(String message) {
            super(message);
        }
    }

    public static Caller caller(HttpServletRequest request) {
        Object c = request.getAttribute(Caller.class.getName());
        if (!(c instanceof Caller caller)) {
            throw new Unauthorized("an API key is needed");
        }
        return caller;
    }

    public static ResponseEntity<String> json(HttpStatus status, Object body) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(Json.compact(body));
    }

    public static Map<String, Object> body(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("a JSON body is needed");
        }
        if (raw.length() > 1_000_000) {
            throw new IllegalArgumentException("body too large");
        }
        return Json.object(raw);
    }

    /** API keys for everything under /api and /graphql except the signed webhooks. */
    @Component
    public static class ApiKeyFilter extends OncePerRequestFilter {
        private final Settings settings;

        public ApiKeyFilter(Settings settings) {
            this.settings = settings;
        }

        @Override
        protected boolean shouldNotFilter(HttpServletRequest request) {
            String path = request.getRequestURI();
            boolean guarded = path.startsWith("/api/") || path.equals("/graphql");
            boolean signed = path.startsWith("/api/postbacks/") || path.equals("/api/card-events");
            return !guarded || signed;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
            var key = settings.key(request.getHeader("X-Api-Key"));
            if (key.isEmpty()) {
                response.setStatus(401);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":\"a valid X-Api-Key header is needed\"}");
                return;
            }
            request.setAttribute(Caller.class.getName(), new Caller(key.get().actor(), key.get().role()));
            chain.doFilter(request, response);
        }
    }

    /** Webhooks are signed: {@code X-Signature: sha256=<hex HMAC of the raw body>} with the source's secret. */
    public static boolean signed(String secret, String body, String header) {
        if (header == null || !header.startsWith("sha256=")) {
            return false;
        }
        byte[] given;
        try {
            given = HexFormat.of().parseHex(header.substring("sha256=".length()));
        } catch (IllegalArgumentException e) {
            return false;
        }
        return MessageDigest.isEqual(hmac(secret, body), given);
    }

    public static byte[] hmac(String secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String signature(String secret, String body) {
        return "sha256=" + HexFormat.of().formatHex(hmac(secret, body));
    }

    @RestControllerAdvice
    public static class Errors {

        @ExceptionHandler(IllegalArgumentException.class)
        ResponseEntity<String> bad(IllegalArgumentException e) {
            return json(HttpStatus.BAD_REQUEST, Map.of("error", String.valueOf(e.getMessage())));
        }

        @ExceptionHandler(ClassCastException.class)
        ResponseEntity<String> wrongType(ClassCastException e) {
            return json(HttpStatus.BAD_REQUEST, Map.of("error", "a field has the wrong type"));
        }

        @ExceptionHandler(NoSuchElementException.class)
        ResponseEntity<String> missing(NoSuchElementException e) {
            return json(HttpStatus.NOT_FOUND, Map.of("error", String.valueOf(e.getMessage())));
        }

        @ExceptionHandler(Forbidden.class)
        ResponseEntity<String> forbidden(Forbidden e) {
            return json(HttpStatus.FORBIDDEN, Map.of("error", e.getMessage()));
        }

        @ExceptionHandler(Unauthorized.class)
        ResponseEntity<String> unauthorized(Unauthorized e) {
            return json(HttpStatus.UNAUTHORIZED, Map.of("error", e.getMessage()));
        }
    }
}
