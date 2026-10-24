package br.com.cristorei.etiquetas;

import java.util.ArrayList;
import java.util.List;

/** Preserva o Z64 e adiciona ^FO/^A0N/^FD antes de ^PQ na etiqueta original. */
public final class ZplWriter {
    private static final String END_OF_PRINT = "^PQ1,0,0,N^XZ";
    private static final int MAX_COLUMNS = 42;
    private ZplWriter() {}

    /** A etiqueta da Shopee termina por volta de y=1140; abaixo disso cabem ate 3 linhas. */
    private static final int MAX_LINES = 3;

    public static boolean fits(String text) {
        try { return lines(text).size() <= MAX_LINES; }
        catch (Exception e) { return false; }
    }

    public static String append(String original, String description) {
        if (!original.contains(END_OF_PRINT) || original.indexOf(END_OF_PRINT) != original.lastIndexOf(END_OF_PRINT))
            throw new IllegalArgumentException("Etiqueta sem bloco de impressao esperado");
        List<String> texts = lines(description);
        if (texts.isEmpty() || texts.size() > MAX_LINES)
            throw new IllegalArgumentException("Descricao nao cabe no espaco de tres linhas");
        // Ate 2 linhas: layout original. 3 linhas: fonte um pouco menor para caber no rodape.
        boolean compact = texts.size() > 2;
        int top = compact ? 1145 : 1150, step = compact ? 22 : 24;
        String font = compact ? "^A0N,17,16" : "^A0N,19,18";
        StringBuilder overlay = new StringBuilder();
        for (int i = 0; i < texts.size(); i++) {
            overlay.append("^FO16,").append(top + step*i).append(font).append("^FD")
                   .append(texts.get(i)).append("^FS");
        }
        return original.replace(END_OF_PRINT, overlay + END_OF_PRINT);
    }

    public static List<String> lines(String text) {
        // Mantem virgulas entre itens e converte acentos para ASCII, sem comandos ZPL.
        String safe = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "").toUpperCase(java.util.Locale.ROOT)
            .replaceAll("[^A-Z0-9,/ ]", " ").replaceAll(" +", " ").trim();
        if (safe.isBlank()) return List.of();
        if (safe.length() <= MAX_COLUMNS) return List.of(safe);
        // Preenche cada linha com quantos itens couberem, sem quebrar um item no meio.
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String item : safe.split(",\\s*")) {
            if (item.isBlank()) continue;
            if (item.length() > MAX_COLUMNS) {
                if (!line.isEmpty()) { out.add(line.toString()); line.setLength(0); }
                out.addAll(wrap(item));
                continue;
            }
            if (!line.isEmpty() && line.length() + 2 + item.length() > MAX_COLUMNS) {
                out.add(line.toString());
                line.setLength(0);
            }
            if (!line.isEmpty()) line.append(", ");
            line.append(item);
        }
        if (!line.isEmpty()) out.add(line.toString());
        return out;
    }

    private static List<String> wrap(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" +")) {
            if (word.length() > MAX_COLUMNS) throw new IllegalArgumentException("Palavra longa demais para a etiqueta: " + word);
            if (!line.isEmpty() && line.length() + 1 + word.length() > MAX_COLUMNS) {
                out.add(line.toString()); line.setLength(0);
            }
            if (!line.isEmpty()) line.append(' ');
            line.append(word);
        }
        if (!line.isEmpty()) out.add(line.toString());
        return out;
    }
}
