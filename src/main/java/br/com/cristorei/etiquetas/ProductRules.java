package br.com.cristorei.etiquetas;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Regras do estoque Cristo Rei; NUNCA adivinha variantes desconhecidas. */
public final class ProductRules {
    private static final String[] COLORS = {"TERRACOTA", "AMARELO", "VERMELHO", "MARROM", "PRETO",
        "BRANCO", "BRANCA", "CINZA", "ROSA", "ROXO", "AZUL", "BEGE", "VERDE", "ACAI"};
    private static final Pattern GRANDE_OCR = Pattern.compile("\\b[A-Z0-9]?RANDE\\b");
    private static final Pattern MINI_OCR = Pattern.compile("\\b[A-Z0-9]?M[I1L]N[I1L]\\b");
    public record Decision(String descricao, List<String> problemas) {}

    private ProductRules() {}

    public static Decision describe(List<ChecklistReader.Item> items) {
        List<String> issues = new ArrayList<>();
        LinkedHashMap<String,Integer> grouped = new LinkedHashMap<>();
        if (items.isEmpty()) issues.add("Checklist sem itens");
        for (int i=0; i<items.size(); i++) {
            var item = items.get(i);
            String name = normalize(item.produto());
            String variant = normalize(item.variacao());
            Integer qty = quantity(item.quantidadeOcr());
            if (qty == null) {issues.add("Linha " +(i+1)+": quantidade nao reconhecida: " + item.quantidadeOcr()); continue;}
            String desc = name.contains("PUF") ? pufe(variant) : nonPufe(name, variant);
            if (desc == null) {
                issues.add("Linha " + (i+1) + ": produto/variacao para revisao: " +
                    item.produto() + " | " + item.variacao());
            } else {
                grouped.merge(desc, qty, Integer::sum);
            }
        }
        StringBuilder output = new StringBuilder();
        for (var entry : grouped.entrySet()) {
            if (!output.isEmpty()) output.append(", ");
            output.append(entry.getValue()).append("x ").append(entry.getKey());
        }
        if (grouped.isEmpty()) issues.add("Nenhuma descricao segura gerada");
        if (!ZplWriter.fits(output.toString())) issues.add("Descricao longa demais para o rodape");
        return new Decision(output.toString(), issues);
    }

    private static Integer quantity(String ocr) {
        // Um unico numero valido; ambiguidades nao devem virar pedidos incorretos.
        String s = ocr.strip().replaceAll("\\s+", "");
        if (!s.matches("[0-9]{1,2}")) return null;
        int value = Integer.parseInt(s);
        return value >= 1 && value <= 99 ? value : null;
    }

    private static String pufe(String variation) {
        variation = variation.replace("TERRACOTTA", "TERRACOTA"); // grafia OCR comum
        // O OCR as vezes erra so a primeira letra do tamanho (ex.: "SRANDE", "6RANDE" no lugar de "GRANDE").
        boolean mini = variation.contains("MINI") || variation.contains("INFANTIL") || MINI_OCR.matcher(variation).find();
        boolean grande = variation.contains("GRANDE") || variation.contains("ADULTO") || GRANDE_OCR.matcher(variation).find();
        if (mini == grande) return null;
        String color = null;
        for (String c : COLORS) if (variation.contains(c)) { color = c; break; }
        if (color == null) return null;
        boolean courino = variation.contains("COURINO") || variation.contains("CORINO");
        return (mini ? "MINI " : "") + color + (courino ? " COURINO" : "") + (grande ? " GRANDE" : "");
    }

    private static String nonPufe(String product, String variant) {
        if (product.contains("BICO") && product.contains("PROTETOR"))
            return "BICO PROTETOR AMAMENTACAO";
        if (product.contains("KIT") && product.contains("JANTAR") && variant.contains("GIN"))
            return "KIT JANTAR TACA GIN" + (variant.contains("BRANC") ? " BRANCA" : "");
        if (product.contains("TRAVESSEIRO") && product.contains("ORTOBOM") && product.contains("60") && product.contains("40"))
            return "TRAVESSEIRO ORTOBOM 60X40";
        if (product.contains("LAPIS") && product.contains("LYKE") && product.contains("36"))
            return "LAPIS DE COR LYKE 36 CORES";
        return generic(product, variant);
    }

    private static final Pattern SIZE_OR_CODE = Pattern.compile(".*[0-9]+X[0-9]+.*");

    /**
     * Produto fora das regras: nome curto (ate 3 palavras, parando antes de medidas/codigos do titulo
     * do anuncio) + variacao. Os titulos da Shopee sao longos e cheios de palavras de busca.
     */
    private static String generic(String productRaw, String variantNorm) {
        List<String> words = new ArrayList<>();
        for (String w : productRaw.split(" ")) {
            if (w.isBlank()) continue;
            if (!words.isEmpty() && (w.equals("PAR") || SIZE_OR_CODE.matcher(w).matches() || w.matches("[A-Z0-9]*[0-9][A-Z0-9]*") && words.size() >= 2)) break;
            words.add(w);
            if (words.size() == 3) break;
        }
        if (words.isEmpty() || words.stream().mapToInt(String::length).sum() < 3) return null;
        List<String> variation = new ArrayList<>();
        for (String w : variantNorm.split(" ")) {
            if (!w.isBlank() && !variation.contains(w)) variation.add(w);
            if (variation.size() == 4) break;
        }
        return String.join(" ", words) + (variation.isEmpty() ? "" : " " + String.join(" ", variation));
    }

    public static String normalize(String s) {
        String n = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "").toUpperCase(java.util.Locale.ROOT);
        return n.replaceAll("[^A-Z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }
}
