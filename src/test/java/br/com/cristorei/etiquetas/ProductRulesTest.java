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
    @Test void toleraErroDeOcrNoTamanho() {
        var grande = ProductRules.describe(List.of(new ChecklistReader.Item(
            "PUFF QUADRADO REFORÇADO SUEDE CUBO", "AMARELO SUEDE,SRANDE", "1")));
        assertEquals("1x AMARELO GRANDE", grande.descricao());
        assertTrue(grande.problemas().isEmpty());
        var mini = ProductRules.describe(List.of(new ChecklistReader.Item("PUFF QUADRADO", "ROSA SUEDE,M1NI", "1")));
        assertEquals("1x MINI ROSA", mini.descricao());
    }
    @Test void produtoSemNomeLegivelBloqueia() {
        var result = ProductRules.describe(List.of(new ChecklistReader.Item("", "SEM", "1")));
        assertFalse(result.problemas().isEmpty());
    }
    @Test void produtoForaDasRegrasGeraDescricaoCurta() {
        var baquetas = ProductRules.describe(List.of(new ChecklistReader.Item(
            "BAQUETAS VANA BY CHUMBAS PAR 2B/2BL 5B/5BL BA/BAL 7A/7AL 42 CM BATERIA INSTRUMENTO", "5B / 5BL", "1")));
        assertEquals("1x BAQUETAS VANA BY 5B 5BL", baquetas.descricao());
        assertTrue(baquetas.problemas().isEmpty());
        var tela = ProductRules.describe(List.of(new ChecklistReader.Item("TELA DE PINTURA 20X30 30X40", "20X30", "2")));
        assertEquals("2x TELA DE PINTURA 20X30", tela.descricao());
        var capa = ProductRules.describe(List.of(new ChecklistReader.Item(
            "Kit 4 Capa De Chuva Descartavel Transparente Incolor Unisex", "", "1")));
        assertEquals("1x KIT 4 CAPA", capa.descricao());
    }
    @Test void variosItensPreenchemALinha() {
        var linhas = ZplWriter.lines("2x MINI ROSA, 1x MINI VERDE, 1x MINI AMARELO, 1x MINI AZUL");
        assertEquals(List.of("2X MINI ROSA, 1X MINI VERDE", "1X MINI AMARELO, 1X MINI AZUL"), linhas);
        assertTrue(ZplWriter.fits("2x MINI ROSA, 1x MINI VERDE, 1x MINI AMARELO, 1x MINI AZUL"));
        // 3 linhas usam fonte menor; 4 linhas nao cabem.
        var tres = "1x MINI ROSA, 1x MINI VERDE, 1x MINI AZUL, 1x MINI BEGE, 1x MINI CINZA, 1x MINI PRETO, 1x MINI ROXO";
        assertEquals(3, ZplWriter.lines(tres).size());
        var zpl = ZplWriter.append("^XA^PQ1,0,0,N^XZ", tres);
        assertTrue(zpl.contains("^FO16,1145^A0N,17,16^FD"));
        assertFalse(ZplWriter.fits(tres + ", " + tres));
    }
    @Test void textoNaoDestrutivo() {
        var original = "~DGR:DEMO.GRF,2,1,:Z64:XXXX:1234^XA^XGR:DEMO.GRF,1,1^FS^PQ1,0,0,N^XZ^XA^IDR:DEMO.GRF^FS^XZ";
        var output = ZplWriter.append(original,"2x Mini Rosa");
        assertTrue(output.contains("^FD2X MINI ROSA^FS^PQ1,0,0,N^XZ"));
        assertTrue(output.contains("^IDR:DEMO.GRF^FS^XZ"));
    }
}
