package br.com.cristorei.etiquetas;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class BatchService {
    @ConfigProperty(name="ocr.command", defaultValue="tesseract")
    String ocrCommand;
    @ConfigProperty(name="ocr.language", defaultValue="eng")
    String ocrLanguage;

    private final Map<UUID, Batch> batches = new ConcurrentHashMap<>();

    public static class Row {
        public int numero;
        public String descricao;
        public String status;
        public List<String> avisos = new ArrayList<>();
        public List<ChecklistReader.Item> itens = new ArrayList<>();
        @com.fasterxml.jackson.annotation.JsonIgnore
        public String original;
    }
    public static class Batch {
        public UUID id;
        public Instant criadoEm;
        public List<Row> etiquetas;
        public int total;
    }

    public Batch analyze(byte[] uploaded) {
        if (uploaded == null || uploaded.length > 20 * 1024 * 1024) {
            throw new IllegalArgumentException("Arquivo ausente ou maior que 20 MB");
        }
        String text = new String(uploaded, StandardCharsets.UTF_8);
        List<String> lines = text.lines().filter(l -> !l.isBlank()).toList();
        if (lines.isEmpty() || (lines.size() % 2) != 0)
            throw new IllegalArgumentException("Esperado: uma etiqueta e um checklist por pedido, em linhas alternadas");
        if (lines.size() > 1000) throw new IllegalArgumentException("Limite de 500 pedidos por lote");

        ChecklistReader ocr = new ChecklistReader(ocrCommand, ocrLanguage);
        Batch batch = new Batch();
        batch.id = UUID.randomUUID();
        batch.criadoEm = Instant.now();
        batch.total = lines.size()/2;
        batch.etiquetas = new ArrayList<>(batch.total);

        for (int i = 0; i < lines.size(); i += 2) {
            Row row = new Row();
            row.numero = i/2 + 1;
            row.original = lines.get(i);
            row.status = "REVISAR";
            batch.etiquetas.add(row);
            try {
                ZplDecoder.Raster shipping = ZplDecoder.decode(row.original);
                ZplDecoder.Raster checklist = ZplDecoder.decode(lines.get(i+1));
                double density = PrintSafety.peak20(shipping);
                if (density >= 650) row.avisos.add(
                    "Pico de densidade 20 linhas = " + String.format(java.util.Locale.ROOT, "%.1f", density)
                    + ": revisar risco de travamento na Zebra");
                if (shipping.width() != checklist.width() || shipping.height() != checklist.height()) {
                    row.avisos.add("Dimensoes de etiqueta e checklist diferentes");
                }
                ChecklistReader.Extraction extraction = ocr.extract(checklist);
                row.itens = extraction.items();
                row.avisos.addAll(extraction.warnings());
                ProductRules.Decision decision = ProductRules.describe(row.itens);
                row.descricao = decision.descricao();
                row.avisos.addAll(decision.problemas());
                if (row.avisos.isEmpty()) row.status = "OK_AUTOMATICO";
            } catch (Exception ex) {
                row.avisos.add("Falha no processamento: " + ex.getMessage());
            }
        }
        batches.put(batch.id, batch);
        return batch;
    }

    public Batch get(UUID id) {
        Batch batch = batches.get(id);
        if (batch == null) throw new IllegalArgumentException("Lote nao encontrado (armazenamento apenas em memoria)");
        return batch;
    }

    public Row correct(UUID id, int number, String description) {
        Batch batch = get(id);
        synchronized (batch) {
            if (number < 1 || number > batch.total) throw new IllegalArgumentException("Numero da etiqueta invalido");
            if (!ZplWriter.fits(description)) throw new IllegalArgumentException("Descricao nao cabe em duas linhas");
            Row row = batch.etiquetas.get(number-1);
            row.descricao = description;
            row.status = "CORRIGIDO_MANUALMENTE";
            row.avisos.clear();
            return row;
        }
    }

    public byte[] generate(UUID id) {
        Batch batch = get(id);
        synchronized (batch) {
            StringBuilder zpl = new StringBuilder(batch.total * 15_000);
            for (Row row : batch.etiquetas) {
                if ("REVISAR".equals(row.status)) {
                    throw new IllegalStateException("Etiqueta " + row.numero + " exige revisao antes de gerar o lote");
                }
                zpl.append(ZplWriter.append(row.original, row.descricao)).append('\n');
            }
            return zpl.toString().getBytes(StandardCharsets.US_ASCII);
        }
    }
}
