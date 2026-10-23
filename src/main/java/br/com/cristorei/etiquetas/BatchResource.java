package br.com.cristorei.etiquetas;

import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Path("/api/lotes")
@Produces(MediaType.APPLICATION_JSON)
public class BatchResource {

    @Inject
    BatchService batches;

    public record EditRequest(String descricao) {
    }

    @GET
    @Path("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP", "servico", "etiquetas-shopee");
    }

    @POST
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public BatchService.Batch analyze(@RestForm("arquivo") FileUpload file) throws IOException {
        if (file == null || file.uploadedFile() == null) {
            throw new BadRequestException("Envie um arquivo TXT como multipart/form-data na chave 'arquivo'");
        }
        return batches.analyze(Files.readAllBytes(file.uploadedFile()));
    }

    @GET
    @Path("/{id}")
    public BatchService.Batch get(@PathParam("id") UUID id) {
        return batches.get(id);
    }

    @GET
    @Path("/{id}/pendencias")
    public Map<String, Object> pending(@PathParam("id") UUID id) {
        BatchService.Batch batch = batches.get(id);
        List<BatchService.Row> pending = batch.etiquetas.stream()
                .filter(row -> "REVISAR".equals(row.status))
                .toList();
        return Map.of(
                "id", batch.id,
                "total", batch.total,
                "quantidadePendencias", pending.size(),
                "etiquetas", pending);
    }

    @PUT
    @Path("/{id}/etiquetas/{numero}")
    @Consumes(MediaType.APPLICATION_JSON)
    public BatchService.Row correct(@PathParam("id") UUID id,
            @PathParam("numero") int numero,
            EditRequest body) {
        if (body == null || body.descricao() == null || body.descricao().isBlank()) {
            throw new BadRequestException("Informe a propriedade 'descricao'");
        }
        return batches.correct(id, numero, body.descricao());
    }

    @POST
    @Path("/{id}/etiquetas/{numero}/corrigir-densidade")
    public BatchService.Row correctDensity(@PathParam("id") UUID id,
                                           @PathParam("numero") int numero) {
        return batches.correctDensity(id, numero);
    }

    @GET
    @Path("/{id}/impressao")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public Response download(@PathParam("id") UUID id) {
        try {
            byte[] result = batches.generate(id);
            return Response.ok(result, MediaType.APPLICATION_OCTET_STREAM)
                    .header("Content-Disposition", "attachment; filename=etiquetas_com_variacoes.zpl")
                    .build();
        } catch (IllegalStateException ex) {
            BatchService.Batch batch = batches.get(id);

            // CORREÇÃO: Tipagem explícita para evitar conflito de inferência do Map.of
            List<Map<String, Object>> pendencias = new java.util.ArrayList<>();
            for (BatchService.Row r : batch.etiquetas) {
                if ("REVISAR".equals(r.status)) {
                    Map<String, Object> p = new java.util.HashMap<>();
                    p.put("numero", r.numero);
                    p.put("idPedido", r.idPedido != null ? r.idPedido : "");
                    pendencias.add(p);
                }
            }

            return Response.status(Response.Status.CONFLICT)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(Map.of(
                            "erro", ex.getMessage(),
                            "pendencias", pendencias))
                    .build();
        }
    }

    @ServerExceptionMapper
    public Response badData(IllegalArgumentException ex) {
        return Response.status(Response.Status.BAD_REQUEST)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("erro", ex.getMessage()))
                .build();
    }
}