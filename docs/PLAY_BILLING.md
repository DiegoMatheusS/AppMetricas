# Pro Completo pela Google Play

O app integra Play Billing 9.1.0 com um produto de compra única e permanente, sem assinatura e sem consumo do produto. Consulta o preço localizado da loja, abre a confirmação oficial, confere recibos, confirma a entrega e restaura as compras da conta conectada à Play Store.

## Ativação na loja

1. Preparar o aplicativo `br.com.medidor.app` na Play Console e uma faixa de testes internos.
2. Criar o produto `pro_completo` com opção de compra permanente, sem aluguel ou pré-venda. O preço previsto é R$ 129,90; cadastrar esse preço na loja. O app sempre mostra o preço efetivo retornado pela Google Play.
3. Em GitHub > Settings > Secrets and variables > Actions > Variables, configurar:
   - `APPMETRICAS_PLAY_PRODUCT_ID`: o ID real do produto (padrão: `pro_completo`).
   - `APPMETRICAS_PLAY_LICENSE_KEY`: a chave **pública** de licenciamento RSA fornecida pela Play Console, em Base64. Não usar a chave privada de assinatura, senha, chave de serviço ou token de API.
4. Executar `Signed release APK` na `main` depois das validações. Ele gera APK e AAB assinados com o certificado existente e os valores configurados. Enviar o artifact `appmetricas-signed-bundle-<numero>` para a faixa de testes internos.
5. Cadastrar os testadores/licenciadores na Play Console, instalar pela faixa de testes e conferir compra, cancelamento, pagamento pendente, restauração, troca de conta e reembolso. O APK baixado diretamente não comprova que o produto está publicado ou disponível para aquela conta.

## Acesso e recibos

- Sem chave de licenciamento configurada, as ferramentas continuam liberadas para testes. Os botões de compra e restauração ficam indisponíveis; o app não simula pagamento nem declara Pro comprado.
- Com chave configurada, Régua, Área, medições por foto, calculadoras manuais e histórico continuam disponíveis. Scanner 3D, Arquiteto, contador pela câmera e tanques pela câmera exigem Pro.
- Liberação somente com recibo de compra concluída, de uma unidade do produto correto, para o pacote correto e com assinatura RSA válida. Pagamento pendente, compra cancelada, recibo alterado ou assinatura inválida não liberam acesso.
- A compra é não consumível: não pode ser comprada novamente enquanto for propriedade da conta. `Restaurar compra` consulta as compras atuais da Google Play e remove uma liberação antiga quando a loja confirma que ela deixou de existir.
- Após validar uma compra, o app confirma a entrega com `acknowledgePurchase`. Falhas temporárias são exibidas e a confirmação é tentada novamente nas próximas consultas.
- O recibo local é cifrado com AES-GCM e chave vinculada ao Android Keystore do aparelho. A assinatura é conferida novamente ao abrir o app; a cópia offline vale por até sete dias desde a última consulta bem-sucedida. Sem conexão, um reembolso pode ser reconhecido apenas na próxima consulta. Dados de pagamento não são coletados pelo app.

## Limite de operação

Este projeto continua sem servidor de contas/pagamentos. A verificação é feita no cliente com a chave pública e as compras retornadas pelo Play Billing. Para uma operação comercial com notificações de reembolso em tempo real e verificação independente do aplicativo, a evolução indicada é um backend usando Google Play Developer API e RTDN. Nenhuma credencial de serviço deve ser colocada no APK.

Referências oficiais: https://developer.android.com/google/play/billing/integrate e https://developer.android.com/google/play/billing/security.
