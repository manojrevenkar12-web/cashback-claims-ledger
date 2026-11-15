package cashback.service;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;

import cashback.common.Json;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** A small client over MockMvc that returns status and parsed JSON. */
record Http(MockMvc mvc) {

    static final String SUPPORT = "support-demo-key";
    static final String SHOPPER = "shopper-demo-key";

    record Reply(int status, Map<String, Object> body) {
    }

    Reply send(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult r = mvc.perform(request).andReturn();
        if (r.getRequest().isAsyncStarted()) {
            r = mvc.perform(asyncDispatch(r)).andReturn();
        }
        String text = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return new Reply(r.getResponse().getStatus(), text.isBlank() ? Map.of() : Json.object(text));
    }

    Reply get(String path, String key) throws Exception {
        MockHttpServletRequestBuilder b = MockMvcRequestBuilders.get(path);
        if (key != null) {
            b.header("X-Api-Key", key);
        }
        return send(b);
    }

    Reply post(String path, String key, Object body) throws Exception {
        MockHttpServletRequestBuilder b = MockMvcRequestBuilders.post(path).contentType(MediaType.APPLICATION_JSON)
            .content(body instanceof String s ? s : Json.compact(body));
        if (key != null) {
            b.header("X-Api-Key", key);
        }
        return send(b);
    }

    Reply signed(String path, String secret, String body) throws Exception {
        return send(MockMvcRequestBuilders.post(path).contentType(MediaType.APPLICATION_JSON).content(body)
            .header("X-Signature", cashback.service.web.Web.signature(secret, body)));
    }

    Reply graphql(String key, String query, Map<String, Object> variables) throws Exception {
        return post("/graphql", key, Map.of("query", query, "variables", variables));
    }
}
