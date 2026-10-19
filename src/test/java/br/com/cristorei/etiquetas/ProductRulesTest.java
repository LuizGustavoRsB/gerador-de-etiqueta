package br.com.cristorei.etiquetas;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

public class ProductRulesTest {
    @Test void somaPromocao() {
        var data = List.of(
            new ChecklistReader.Item("PUFE QUADRADO", "PRETO SUEDE GRANDE", "2"),
            new ChecklistReader.Item("PUFE QUADRADO", "PRETO SUEDE GRANDE", "1")
        );
        var result = ProductRules.describe(data);
        assertEquals("3x PRETO GRANDE", result.descricao());
        assertTrue(result.problemas().isEmpty());
    }
    @Test void coresMisturadas() {
        var data = List.of(
            new ChecklistReader.Item("PUFF QUADRADO", "ROSA SUEDE MINI", "1"),
            new ChecklistReader.Item("PUFF QUADRADO", "MARROM SUEDE MINI", "1")
        );
        assertEquals("1x MINI ROSA, 1x MINI MARROM", ProductRules.describe(data).descricao());
    }
    @Test void produtoNovoBloqueia() {
        var result = ProductRules.describe(List.of(new ChecklistReader.Item("PRODUTO DESCONHECIDO", "SEM", "1")));
        assertFalse(result.problemas().isEmpty());
    }
    @Test void textoNaoDestrutivo() {
        var original = "~DGR:DEMO.GRF,2,1,:Z64:XXXX:1234^XA^XGR:DEMO.GRF,1,1^FS^PQ1,0,0,N^XZ^XA^IDR:DEMO.GRF^FS^XZ";
        var output = ZplWriter.append(original,"2x Mini Rosa");
        assertTrue(output.contains("^FD2X MINI ROSA^FS^PQ1,0,0,N^XZ"));
        assertTrue(output.contains("^IDR:DEMO.GRF^FS^XZ"));
    }
}
