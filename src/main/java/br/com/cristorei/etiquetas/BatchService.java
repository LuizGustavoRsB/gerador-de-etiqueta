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
    @jakarta.inject.Inject
    ShopeeClient shopee;

    @ConfigProperty(name="ocr.command", defaultValue="tesseract")
    String ocrCommand;
    @ConfigProperty(name="ocr.language", defaultValue="eng")
    String ocrLanguage;

    private final Map<UUID, Batch> batches = new ConcurrentHashMap<>();

    public static class Row {
        public int numero;
        public String idPedido; // NOVO: ID extraido da etiqueta
        public String fonte = "OCR"; // OCR ou SHOPEE_API: de onde vieram os itens
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

        checkOcrAvailable();
        ChecklistReader ocr = new ChecklistReader(ocrCommand, ocrLanguage);
        LabelIdReader idReader = new LabelIdReader(ocrCommand, ocrLanguage); // NOVO

        Batch batch = new Batch();
        batch.id = UUID.randomUUID();
        batch.criadoEm = Instant.now();
        batch.total = lines.size()/2;
        batch.etiquetas = new ArrayList<>(batch.total);

        // Com a loja autorizada na Shopee, os itens vem da API (texto exato, sem OCR); o OCR do checklist
        // continua como plano B para pedidos que a API nao devolver.
        Map<Integer, String> ids = new ConcurrentHashMap<>();
        Map<String, List<ShopeeClient.Item>> api = Map.of();
        if (shopee.isReady()) {
            try {
                java.util.stream.IntStream.range(0, lines.size() / 2).parallel().forEach(n -> {
                    try {
                        String id = idReader.extract(ZplDecoder.decode(lines.get(n * 2)));
                        if (id != null) ids.put(n, id);
                    } catch (Exception ignored) { }
                });
                List<String> wanted = ids.values().stream().flatMap(id -> orderSnCandidates(id).stream()).distinct().toList();
                api = shopee.orderItems(wanted);
            } catch (Exception ex) {
                api = Map.of(); // API fora do ar ou token invalido: segue so com OCR
            }
        }
        final Map<String, List<ShopeeClient.Item>> apiItems = api;

        // Cada pedido e independente e o gargalo e o processo do Tesseract: processa em paralelo.
        List<Row> rows = java.util.stream.IntStream.range(0, lines.size() / 2).parallel()
            .mapToObj(n -> process(ocr, idReader, n + 1, lines.get(n * 2), lines.get(n * 2 + 1), ids.get(n), apiItems))
            .toList();
        batch.etiquetas.addAll(rows);
        batches.put(batch.id, batch);
        return batch;
    }

    /** Falha uma vez, com mensagem clara, em vez de repetir o erro do OCR em cada etiqueta. */
    private void checkOcrAvailable() {
        try {
            Process p = new ProcessBuilder(ocrCommand, "--version").redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (p.waitFor(15, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0) return;
            p.destroyForcibly();
        } catch (java.io.IOException ignored) {
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        throw new IllegalArgumentException("Tesseract OCR nao encontrado (comando: " + ocrCommand + "). "
            + "Instale o Tesseract em C:\\Program Files\\Tesseract-OCR com os idiomas ingles e portugues, "
            + "ou defina a variavel OCR_COMMAND com o caminho do tesseract.exe, e abra o app de novo.");
    }

    /** O OCR do ID as vezes sobra letras no inicio (ex.: "P2610217655H2E6"); o order_sn da Shopee tem 14 caracteres. */
    static List<String> orderSnCandidates(String ocrId) {
        List<String> out = new ArrayList<>();
        for (int skip = 0; skip <= 2 && ocrId.length() - skip >= 10; skip++) out.add(ocrId.substring(skip));
        return out;
    }

    private static List<ShopeeClient.Item> itemsFromApi(String ocrId, Map<String, List<ShopeeClient.Item>> apiItems) {
        for (String sn : orderSnCandidates(ocrId)) {
            List<ShopeeClient.Item> items = apiItems.get(sn);
            if (items != null) return items;
        }
        return null;
    }

    private Row process(ChecklistReader ocr, LabelIdReader idReader, int numero, String shippingZpl, String checklistZpl,
                        String knownId, Map<String, List<ShopeeClient.Item>> apiItems) {
        Row row = new Row();
        row.numero = numero;
        row.original = shippingZpl;
        row.status = "REVISAR";
        try {
            ZplDecoder.Raster shipping = ZplDecoder.decode(shippingZpl);
            ZplDecoder.Raster checklist = ZplDecoder.decode(checklistZpl);

            row.idPedido = knownId != null ? knownId : idReader.extract(shipping);
            if (row.idPedido == null) {
                row.avisos.add("ID do pedido não legível na etiqueta");
            }

            double density = PrintSafety.peak20(shipping);
            if (density >= 650) row.avisos.add(
                "Pico de densidade 20 linhas = " + String.format(java.util.Locale.ROOT, "%.1f", density)
                + ": revisar risco de travamento na Zebra");
            if (shipping.width() != checklist.width() || shipping.height() != checklist.height()) {
                row.avisos.add("Dimensoes de etiqueta e checklist diferentes");
            }
            List<ShopeeClient.Item> fromApi = knownId == null ? null : itemsFromApi(knownId, apiItems);
            if (fromApi != null && !fromApi.isEmpty()) {
                row.fonte = "SHOPEE_API";
                row.itens = fromApi.stream().map(i -> new ChecklistReader.Item(i.nome(), i.variacao(),
                    String.valueOf(i.quantidade()))).toList();
            } else {
                ChecklistReader.Extraction extraction = ocr.extract(checklist);
                row.itens = extraction.items();
                row.avisos.addAll(extraction.warnings());
            }
            ProductRules.Decision decision = ProductRules.describe(row.itens);
            row.descricao = decision.descricao();
            row.avisos.addAll(decision.problemas());
            if (row.avisos.isEmpty()) row.status = "OK_AUTOMATICO";
        } catch (Exception ex) {
            row.avisos.add("Falha no processamento: " + ex.getMessage());
        }
        return row;
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
            if (!ZplWriter.fits(description)) throw new IllegalArgumentException("Descricao nao cabe em tres linhas");
            Row row = batch.etiquetas.get(number-1);
            row.descricao = description;
            row.status = "CORRIGIDO_MANUALMENTE";
            row.avisos.clear();
            return row;
        }
    }

    public Row correctDensity(UUID id, int number) {
        Batch batch = get(id);
        synchronized (batch) {
            if (number < 1 || number > batch.total) throw new IllegalArgumentException("Numero da etiqueta invalido");
            Row row = batch.etiquetas.get(number - 1);
            DensityCorrector.Result result = DensityCorrector.correct(row.original, 650);
            if (result.dotsRemoved() == 0) {
                throw new IllegalArgumentException("A densidade nao exigiu alteracao ou nao foi possivel corrigir com seguranca.");
            }
            row.original = result.zpl();
            row.avisos.removeIf(a -> a.startsWith("Pico de densidade 20 linhas"));
            row.avisos.add("Densidade corrigida: pico de " +
                String.format(java.util.Locale.ROOT, "%.1f", result.before()) + " para " +
                String.format(java.util.Locale.ROOT, "%.1f", result.after()) +
                " pontos pretos por linha (atenuacao de blocos pretos extensos). Teste a legibilidade antes de imprimir.");
            if (row.avisos.size() == 1 && row.avisos.get(0).startsWith("Densidade corrigida:")) {
                row.status = "OK_AUTOMATICO";
            }
            return row;
        }
    }

    public byte[] generate(UUID id) {
        Batch batch = get(id);
        synchronized (batch) {
            StringBuilder zpl = new StringBuilder(batch.total * 15_000);
            for (Row row : batch.etiquetas) {
                if ("REVISAR".equals(row.status)) {
                    // NOVO: Inclui o ID na mensagem de erro
                    String msg = "Etiqueta " + row.numero;
                    if (row.idPedido != null) msg += " (pedido " + row.idPedido + ")";
                    msg += " exige revisao antes de gerar o lote";
                    throw new IllegalStateException(msg);
                }
                zpl.append(ZplWriter.append(row.original, row.descricao)).append('\n');
            }
            return zpl.toString().getBytes(StandardCharsets.US_ASCII);
        }
    }
}