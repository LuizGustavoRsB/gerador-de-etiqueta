package br.com.cristorei.etiquetas;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.net.URI;
import java.util.Map;

/** Rotas locais para autorizar a loja na Shopee Open Platform e conferir o estado da integracao. */
@Path("/api/shopee")
public class ShopeeResource {
    @Inject
    ShopeeClient shopee;

    @GET
    @Path("/status")
    @Produces(MediaType.APPLICATION_JSON)
    public ShopeeClient.Status status() {
        return shopee.status();
    }

    /** Abre a pagina de autorizacao da Shopee (o dono da loja faz o login la). */
    @GET
    @Path("/autorizar")
    public Response autorizar() {
        return Response.seeOther(URI.create(shopee.authorizationUrl())).build();
    }

    /** A Shopee redireciona para ca com ?code=...&shop_id=... depois da autorizacao. */
    @GET
    @Path("/callback")
    @Produces(MediaType.TEXT_HTML + "; charset=UTF-8")
    public String callback(@QueryParam("code") String code, @QueryParam("shop_id") Long shopId) {
        if (code == null || shopId == null) {
            return "<h3>Autorizacao nao concluida</h3><p>A Shopee nao devolveu o codigo ou o shop_id.</p>";
        }
        shopee.exchangeCode(code, shopId);
        return "<h3>Loja autorizada (shop_id " + shopId + ")</h3><p>Pode fechar esta janela.</p>";
    }

    @org.jboss.resteasy.reactive.server.ServerExceptionMapper
    public Response erro(IllegalStateException ex) {
        return Response.status(Response.Status.BAD_GATEWAY).type(MediaType.APPLICATION_JSON)
            .entity(Map.of("erro", String.valueOf(ex.getMessage()))).build();
    }

    /** Valida credenciais e assinatura sem precisar de loja autorizada. */
    @GET
    @Path("/ping")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> ping() {
        var r = shopee.pingPublic();
        return Map.of("ok", true, "request_id", r.path("request_id").asText());
    }
}
