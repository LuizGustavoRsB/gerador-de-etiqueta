package br.com.cristorei.etiquetas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Cliente da Shopee Open Platform (API v2). Le as credenciais de shopee.properties (fora do git) e guarda os
 * tokens em shopee-tokens.json. Assina cada chamada com HMAC-SHA256 usando a partner key.
 */
@ApplicationScoped
public class ShopeeClient {
    public record Item(String nome, String variacao, String sku, int quantidade) {}
    public record Status(boolean configurado, boolean autorizado, String shopId, String ambiente) {}

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Path configFile = Path.of(System.getenv().getOrDefault("SHOPEE_CONFIG", "shopee.properties"));
    private final Path tokenFile = Path.of(System.getenv().getOrDefault("SHOPEE_TOKENS", "shopee-tokens.json"));

    private record Settings(long partnerId, String partnerKey, String baseUrl, String redirectUrl) {}
    private record Tokens(String accessToken, String refreshToken, long shopId, long expiresAt) {}

    private Tokens tokens;

    // ---- configuracao ----

    private Settings settings() {
        Properties p = new Properties();
        if (Files.isRegularFile(configFile)) {
            try (var in = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) { p.load(in); }
            catch (IOException e) { throw new IllegalStateException("Nao foi possivel ler " + configFile + ": " + e.getMessage(), e); }
        }
        String id = env("SHOPEE_PARTNER_ID", p.getProperty("shopee.partner-id"));
        String key = env("SHOPEE_PARTNER_KEY", p.getProperty("shopee.partner-key"));
        if (id == null || id.isBlank() || key == null || key.isBlank()) return null;
        return new Settings(Long.parseLong(id.trim()), key.trim(),
            stripSlash(env("SHOPEE_BASE_URL", p.getProperty("shopee.base-url", "https://openplatform.sandbox.test-stable.shopee.sg"))),
            env("SHOPEE_REDIRECT_URL", p.getProperty("shopee.redirect-url", "http://localhost:8080/api/shopee/callback")));
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v != null && !v.isBlank() ? v : fallback;
    }

    private static String stripSlash(String s) { return s.endsWith("/") ? s.substring(0, s.length() - 1) : s; }

    private Settings required() {
        Settings s = settings();
        if (s == null) throw new IllegalStateException(
            "Shopee nao configurada: preencha shopee.partner-id e shopee.partner-key em " + configFile.toAbsolutePath());
        return s;
    }

    public synchronized Status status() {
        Settings s = settings();
        Tokens t = s == null ? null : loadTokens();
        return new Status(s != null, t != null, t == null ? null : String.valueOf(t.shopId),
            s == null ? null : (s.baseUrl.contains("sandbox") ? "sandbox" : "producao"));
    }

    public boolean isReady() { Status s = status(); return s.configurado() && s.autorizado(); }

    // ---- assinatura ----

    static String sign(String key, String baseString) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(baseString.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static String q(String v) { return URLEncoder.encode(v, StandardCharsets.UTF_8); }

    // ---- autorizacao ----

    /** Link que o dono da loja abre para autorizar o app; a Shopee devolve ?code=...&shop_id=... no redirect. */
    public String authorizationUrl() {
        Settings s = required();
        String path = "/api/v2/shop/auth_partner";
        long ts = System.currentTimeMillis() / 1000;
        return s.baseUrl + path + "?partner_id=" + s.partnerId + "&timestamp=" + ts
            + "&sign=" + sign(s.partnerKey, "" + s.partnerId + path + ts) + "&redirect=" + q(s.redirectUrl);
    }

    public synchronized void exchangeCode(String code, long shopId) {
        Settings s = required();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("shop_id", shopId);
        body.put("partner_id", s.partnerId);
        JsonNode r = publicPost(s, "/api/v2/auth/token/get", body);
        saveTokens(new Tokens(r.path("access_token").asText(), r.path("refresh_token").asText(), shopId,
            System.currentTimeMillis() / 1000 + r.path("expire_in").asLong(14400)));
    }

    private synchronized Tokens validTokens(Settings s) {
        Tokens t = loadTokens();
        if (t == null) throw new IllegalStateException("Loja nao autorizada: abra /api/shopee/autorizar e autorize a loja");
        if (t.expiresAt - 120 > System.currentTimeMillis() / 1000) return t;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("refresh_token", t.refreshToken);
        body.put("shop_id", t.shopId);
        body.put("partner_id", s.partnerId);
        JsonNode r = publicPost(s, "/api/v2/auth/access_token/get", body);
        Tokens fresh = new Tokens(r.path("access_token").asText(), r.path("refresh_token").asText(), t.shopId,
            System.currentTimeMillis() / 1000 + r.path("expire_in").asLong(14400));
        saveTokens(fresh);
        return fresh;
    }

    private Tokens loadTokens() {
        if (tokens != null) return tokens;
        if (!Files.isRegularFile(tokenFile)) return null;
        try {
            JsonNode n = JSON.readTree(Files.readString(tokenFile));
            tokens = new Tokens(n.path("access_token").asText(), n.path("refresh_token").asText(),
                n.path("shop_id").asLong(), n.path("expires_at").asLong());
            return tokens;
        } catch (IOException e) { return null; }
    }

    private void saveTokens(Tokens t) {
        tokens = t;
        try {
            Files.writeString(tokenFile, JSON.writeValueAsString(Map.of("access_token", t.accessToken,
                "refresh_token", t.refreshToken, "shop_id", t.shopId, "expires_at", t.expiresAt)));
        } catch (IOException e) { throw new IllegalStateException("Nao foi possivel gravar " + tokenFile, e); }
    }

    // ---- chamadas ----

    private JsonNode publicPost(Settings s, String path, Map<String, Object> body) {
        long ts = System.currentTimeMillis() / 1000;
        String url = s.baseUrl + path + "?partner_id=" + s.partnerId + "&timestamp=" + ts
            + "&sign=" + sign(s.partnerKey, "" + s.partnerId + path + ts);
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build();
            return parse(http.send(req, HttpResponse.BodyHandlers.ofString()).body());
        } catch (IOException e) { throw new IllegalStateException("Falha ao chamar a Shopee: " + e.getMessage(), e); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }

    /** GET assinado de uma API de loja (partner_id + path + timestamp + access_token + shop_id). */
    private JsonNode shopGet(String path, Map<String, String> params) {
        Settings s = required();
        Tokens t = validTokens(s);
        long ts = System.currentTimeMillis() / 1000;
        StringBuilder url = new StringBuilder(s.baseUrl).append(path)
            .append("?partner_id=").append(s.partnerId).append("&timestamp=").append(ts)
            .append("&access_token=").append(q(t.accessToken)).append("&shop_id=").append(t.shopId)
            .append("&sign=").append(sign(s.partnerKey, "" + s.partnerId + path + ts + t.accessToken + t.shopId));
        params.forEach((k, v) -> url.append('&').append(k).append('=').append(q(v)));
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url.toString())).timeout(Duration.ofSeconds(30)).GET().build();
            return parse(http.send(req, HttpResponse.BodyHandlers.ofString()).body());
        } catch (IOException e) { throw new IllegalStateException("Falha ao chamar a Shopee: " + e.getMessage(), e); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }

    private static JsonNode parse(String body) throws IOException {
        JsonNode n = JSON.readTree(body);
        String error = n.path("error").asText("");
        if (!error.isEmpty()) throw new IllegalStateException("Shopee respondeu erro " + error + ": " + n.path("message").asText());
        return n;
    }

    // ---- pedidos ----

    /** Itens de cada pedido (ate 50 por chamada), indexados por order_sn. Pedidos nao encontrados ficam de fora. */
    public Map<String, List<Item>> orderItems(List<String> orderSns) {
        Map<String, List<Item>> out = new LinkedHashMap<>();
        for (int i = 0; i < orderSns.size(); i += 50) {
            List<String> chunk = orderSns.subList(i, Math.min(i + 50, orderSns.size()));
            JsonNode r = shopGet("/api/v2/order/get_order_detail", Map.of(
                "order_sn_list", String.join(",", chunk), "response_optional_fields", "item_list"));
            out.putAll(parseOrderItems(r));
        }
        return out;
    }

    static Map<String, List<Item>> parseOrderItems(JsonNode root) {
        Map<String, List<Item>> out = new LinkedHashMap<>();
        for (JsonNode order : root.path("response").path("order_list")) {
            List<Item> items = new ArrayList<>();
            for (JsonNode it : order.path("item_list")) {
                String sku = it.path("model_sku").asText("");
                if (sku.isBlank()) sku = it.path("item_sku").asText("");
                items.add(new Item(it.path("item_name").asText(""), it.path("model_name").asText(""), sku,
                    it.path("model_quantity_purchased").asInt(0)));
            }
            out.put(order.path("order_sn").asText(), items);
        }
        return out;
    }

    /** Chamada publica (so partner_id + chave): serve para validar credenciais e assinatura sem autorizar loja. */
    public JsonNode pingPublic() {
        Settings s = required();
        String path = "/api/v2/public/get_shopee_ip_ranges";
        long ts = System.currentTimeMillis() / 1000;
        String url = s.baseUrl + path + "?partner_id=" + s.partnerId + "&timestamp=" + ts
            + "&sign=" + sign(s.partnerKey, "" + s.partnerId + path + ts);
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build();
            return parse(http.send(req, HttpResponse.BodyHandlers.ofString()).body());
        } catch (IOException e) { throw new IllegalStateException("Falha ao chamar a Shopee: " + e.getMessage(), e); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }
}
