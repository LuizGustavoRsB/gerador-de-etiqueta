package br.com.cristorei.etiquetas;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.zip.DeflaterOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class DensityCorrectorTest {
    @Test void atenuaBlocoPretoExtensoERecalculaPico() throws Exception {
        int width = 816, height = 80, bpr = width / 8;
        byte[] raw = new byte[bpr * height];
        // Bloco preto extenso no centro, com margem branca.
        for (int y = 20; y < 60; y++)
            for (int x = 10; x < 806; x++)
                raw[y*bpr + x/8] |= (byte)(1 << (7-x%8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DeflaterOutputStream def = new DeflaterOutputStream(out)) { def.write(raw); }
        String b64 = Base64.getEncoder().encodeToString(out.toByteArray());
        String zpl = "~DGR:DEMO.GRF," + raw.length + "," + bpr + ",:Z64:" + b64 + ":0000^XA^PQ1,0,0,N^XZ";
        var result = DensityCorrector.correct(zpl, 650);
        assertTrue(result.dotsRemoved() > 0);
        assertTrue(result.after() < result.before());
        assertTrue(result.zpl().contains("^XA^PQ1,0,0,N^XZ"));
        assertNotEquals(zpl, result.zpl());
    }

    @Test void naoAlteraRasterAbaixoDoLimite() throws Exception {
        int width = 816, height = 80, bpr = width / 8;
        byte[] raw = new byte[bpr * height];
        for (int y = 20; y < 60; y++)
            for (int x = 0; x < 20; x++)
                raw[y*bpr + x/8] |= (byte)(1 << (7-x%8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DeflaterOutputStream def = new DeflaterOutputStream(out)) { def.write(raw); }
        String b64 = Base64.getEncoder().encodeToString(out.toByteArray());
        String zpl = "~DGR:DEMO.GRF," + raw.length + "," + bpr + ",:Z64:" + b64 + ":0000^XA^PQ1,0,0,N^XZ";
        var result = DensityCorrector.correct(zpl, 650);
        assertEquals(0, result.dotsRemoved());
        assertEquals(zpl, result.zpl());
    }
}
