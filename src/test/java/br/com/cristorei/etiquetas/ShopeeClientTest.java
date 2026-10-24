package br.com.cristorei.etiquetas;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

public class ShopeeClientTest {
    @Test void assinaturaHmacSha256() {
        // Valor de referencia calculado com outra implementacao (node crypto).
        assertEquals("e21d71b04a7d2657b0db6b6cff2b441df406b732bca726664655228b78529434", ShopeeClient.sign("chave-de-teste", "1234/api/v2/shop/auth_partner1700000000"));
    }

    @Test void leItensDoExemploDaDocumentacao() throws Exception {
        String json = """
            {"error":"","message":"","response":{"order_list":[{"order_sn":"2404098R48U37H","item_list":[
              {"item_name":"Kem PUFF QUADRADO","item_sku":"","model_name":"60g (Papaya)","model_sku":"QAZ-SADOER-05","model_quantity_purchased":2},
              {"item_name":"Outro","item_sku":"S1","model_name":"","model_sku":"","model_quantity_purchased":1}]}]}}
            """;
        var itens = ShopeeClient.parseOrderItems(new ObjectMapper().readTree(json));
        assertEquals(List.of(
            new ShopeeClient.Item("Kem PUFF QUADRADO", "60g (Papaya)", "QAZ-SADOER-05", 2),
            new ShopeeClient.Item("Outro", "", "S1", 1)), itens.get("2404098R48U37H"));
    }

    @Test void idDoOcrComLetrasSobrandoAindaCasaComOPedido() {
        assertEquals(List.of("P2610217655H2E6", "2610217655H2E6", "610217655H2E6"),
            BatchService.orderSnCandidates("P2610217655H2E6"));
        assertTrue(BatchService.orderSnCandidates("ABC").isEmpty());
    }
}
