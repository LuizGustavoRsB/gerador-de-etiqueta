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

/**
 * REST API para testar no Postman o processamento de etiquetas da Shopee.
 *
 * POST /api/lotes                       - importa etiquetas + checklists
 * GET  /api/lotes/{id}                  - consulta o resultado
 * GET  /api/lotes/{id}/pendencias       - lista itens que precisam de revisao
 * PUT  /api/lotes/{id}/etiquetas/{n}    - corrige descricao da etiqueta n
 * GET  /api/lotes/{id}/impressao        - baixa ZPL (somente com todas aprovadas)
 * GET  /api/lotes/health                - verifica se a API responde
 */
@Path("/api/lotes")
@Produces(MediaType.APPLICATION_JSON)
public class BatchResource {

    @Inject
    BatchService batches;

    public record EditRequest(String descricao) {}

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
        // O upload temporario precisa ser lido durante esta requisicao.
        // O servico armazena apenas o ZPL e os dados necessarios em memoria.
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
            "etiquetas", pending
        );
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
            return Response.status(Response.Status.CONFLICT)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("erro", ex.getMessage()))
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
