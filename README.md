# MVP: Etiquetas Shopee — Java 21 e Quarkus

## Requisitos
- Java 21
- Maven 3.9+
- Tesseract OCR 5 instalado no Windows ou Linux (`tesseract --version`)
- Por padrão, pacote OCR `eng`, suficiente para os testes de produtos/variações em letras maiúsculas. `por+eng` pode melhorar alguns nomes.

## Executar
1. Ajuste `ocr.command` em `src/main/resources/application.properties`, se `tesseract.exe` não estiver no PATH.
2. Rode `mvn quarkus:dev`.
3. Abra `http://127.0.0.1:8080` no navegador do PC.
4. Envie `thermal_zpl_shipping_label_and_packing_listXX.txt` da Shopee.
5. Confira as descrições. Corrija qualquer linha marcada `REVISAR`.
6. Baixe o `.zpl` e realize **impressão-teste física** antes do lote.

## REST API
- `POST /api/lotes` multipart parte `arquivo`: analisa e cria lote em memória.
- `GET /api/lotes/{id}`: consulta o lote.
- `PUT /api/lotes/{id}/etiquetas/{numero}` corpo `{"descricao":"1x MINI ROSA"}`: corrige/valida uma linha.
- `GET /api/lotes/{id}/impressao`: somente baixa quando não houver pendências; em caso contrário, HTTP 409.

## Escopo e limitações importantes
- Implementado para **o layout confirmado dos arquivos recentes da Shopee**: 1 etiqueta/linha; 1 checklist/linha; alternados, gráfico Z64 `~DG`; imagem 816 × 1218; checklist rotacionado 90° horário para OCR.
- O OCR usa Tesseract local, é falível e pode exigir revisão de produtos novos ou variações mal reconhecidas. Não trata pedidos como verdadeiros apenas porque o Tesseract retornou texto.
- A correspondência etiqueta/checklist é **posicional** (pares consecutivos); para um sistema de produção, acrescentar conferência por ID do pedido usando leitura das duas imagens.
- Os dicionários em `ProductRules` são conservadores: produto não cadastrado é marcado para revisão, nunca se deve imprimir chute de cor ou quantidade.
- Os comandos `^FO16,1150` usam a zona inferior observada no layout atual e funcionaram como base nas etiquetas antigas, mas cada transportadora precisa de teste de sobreposição na etiqueta real.
- O projeto **não envia trabalhos diretamente à impressora** e não acessa a conta Shopee. O ZPL é gerado para download, protegido contra alterações no raster original.
- Não armazena histórico nem dados entre reinícios; o lote vive apenas na memória. Acrescentar persistência, expiração e autenticação antes de expor a API em rede.
- Este pacote é uma implementação de referência; validar compilação completa com Maven no ambiente de desenvolvimento e testar OCR e impressão física antes de uso em produção.

## Onde adaptar
- Layout: `ChecklistReader.java` (cortes x/y e linhas de tabela)
- Produtos e variações: `ProductRules.java`
- Posição e fonte do rodapé: `ZplWriter.java`
- Rotas: `BatchResource.java`

## Testes que consegui executar no ambiente de referencia
- Compilacao via `javac` das classes de negocio (`ZplDecoder`, `ChecklistReader`, `ProductRules`, `ZplWriter` e `PrintSafety`).
- Comparacao do OCR com os relatorios conferidos anteriormente: **40 de 40 e 91 de 91 descricoes coincidentes** depois da normalizacao de caixa.
- Tambem foi possivel extrair itens dos 83 checklists de outro lote; isso nao equivale a conferir visualmente todos os pedidos.
- Ainda **nao** executei um build completo do Quarkus via Maven nem validei impressao fisica ou integracao com a GC420t. Um desenvolvedor deve fazer esses testes antes de distribuir a aplicacao.
- O lote fica na memoria ate reiniciar o aplicativo; dados pessoais nao devem ser armazenados desnecessariamente em logs ou servicos externos.

## Testar pelo Postman
1. Inicie a API com `mvn quarkus:dev`. Certifique-se de que o Tesseract esteja instalado.
2. Importe `postman/CristoRei-Etiquetas-Shopee.postman_collection.json` no Postman.
3. Use `0 - Health` para confirmar que a API responde em `http://localhost:8080`.
4. No request `1 - Enviar TXT e analisar etiquetas`, aba **Body > form-data**, selecione `arquivo` com tipo **File**, escolha o arquivo `.txt` da Shopee, e clique em **Send**. Nao defina manualmente o header Content-Type; o Postman inclui o boundary do multipart.
5. O Postman guarda automaticamente o `id` na variavel de collection `loteId`.
6. Execute `2 - Consultar lote` e `3 - Listar pendencias`. As linhas com status `REVISAR` exigem conferencia antes da impressao.
7. Para ajustar uma descricao, defina a variavel `numero` com o numero da etiqueta, confira o checklist e use `4 - Corrigir descricao` (`{"descricao":"1x Mini Rosa"}`). Nao execute esta requisicao automaticamente sem revisar o pedido.
8. Finalmente, em `5 - Baixar ZPL final`, use **Send and Download** para salvar `etiquetas_com_variacoes.zpl`. Se houver pendencias, a API retorna HTTP **409** com JSON explicativo.

**Limites e seguranca:** o projeto usa `127.0.0.1:8080` e armazena os lotes em memoria; o upload limite e 20 MB. O processamento por OCR pode demorar conforme o numero de pedidos. A correcao manual e uma aprovacao da descricao, mas nao valida problemas fisicos da impressora nem a qualidade dos codigos de barras. Nao exponha esta API a rede sem autenticacao.

## Executar com Docker

Requisitos: Docker Desktop instalado e em execução. Na raiz do projeto, execute:

```bash
docker build -t shopee-etiquetas .
docker run --rm -p 8080:8080 shopee-etiquetas
```

Abra `http://localhost:8080`. A imagem inclui Java 21 e Tesseract com os idiomas inglês e português. O lote continua armazenado apenas na memória: se o contêiner parar ou reiniciar, o lote será perdido.

Para publicar em um serviço de hospedagem, configure a porta como `8080` (ou permita que a variável `PORT` seja definida pelo serviço). **Antes de expor a aplicação à internet, adicione autenticação e revise a proteção dos dados dos pedidos.** O projeto ainda é um MVP e precisa de testes com arquivos reais e impressão física.
