# AppMetricas — Medidor para Android

Aplicativo Android nativo em **Kotlin e Jetpack Compose**, para medições estimadas por câmera, cálculo de reservatórios, modelagem arquitetônica assistida e captura guiada para futura reconstrução 3D.

**Estado:** em desenvolvimento, ainda não publicado na Play Store. Android 11 ou superior (API 30+).

## Funcionalidades

### Medições e câmera
- Captura de fotografia com CameraX ou importação da galeria.
- Distância por marcação manual de pontos, com escala calibrada por referência física no **mesmo plano**. Permite calibração por moeda brasileira ou distância conhecida (mm, cm, m).
- **Contorno experimental por toque na fotografia:** seleciona uma região com cor semelhante à do ponto tocado, destaca o contorno e estima largura e altura pela caixa delimitadora. Há três níveis de tolerância para ajustar a seleção.
- **Régua e Área em AR:** em aparelhos compatíveis com ARCore, marque dois extremos para obter uma distância ou marque de 3 a 12 cantos na mesma superfície plana para calcular a área. Os pontos ficam ancorados no ambiente e as distâncias aparecem nas linhas. A medição por foto calibrada continua disponível.
- **Contorno ao vivo na câmera (experimental):** busca automática com confirmação em quadros consecutivos. Você também pode tocar no objeto ou **delimitar a área** por dois cantos. A seleção por área restringe a busca para reduzir interferência de fundos semelhantes; permite redefinir a região e ajustar a tolerância de cor. O app informa **estabilidade visual heurística** e avisa se o contorno toca as bordas, está fragmentado ou oscila entre quadros. CameraX processa offline até cerca de quatro quadros por segundo e compartilha recorte/rotação com a prévia. Mostra largura e altura apenas como porcentagens do quadro, nunca centímetros sem calibração.
- Histórico local dos resultados, exclusão individual com confirmação e exportação de relatório PDF A4.
- Calculadora offline de caixas: volume, área de piso e área superficial.

**Limitações:** o contorno ao vivo depende do contraste de cores; não é reconhecimento semântico, rastreamento confiável de objetos nem medição de profundidade. Movimentos rápidos, reflexos, perspectiva e fundos semelhantes podem causar perda da seleção. Mesmo com estabilidade visual aparentemente boa, o contorno pode selecionar o objeto errado, não identificar partes desconectadas ou cortar as bordas de uma área delimitada. Apesar do corte compartilhado, alinhamento e rotação precisam ser validados em celulares físicos. As proporções do quadro **não são centímetros**. Para medir em unidades físicas, capture uma foto e marque uma referência conhecida no mesmo plano e distância do objeto.

### Tanques e produtos armazenados
- Tanques cilíndricos verticais, horizontais e esféricos, modelados como sólidos geométricos ideais.
- Calcula capacidade total, volume ocupado e volume livre em metros cúbicos e litros.
- **Tanques em AR:** selecione o formato e marque dois extremos para cada dimensão solicitada na mesma sessão. A câmera pode se deslocar entre os pontos. A ferramenta requer profundidade ARCore e preenche a calculadora com as medidas confirmadas. Dimensões externas produzem uma estimativa geométrica.
- Nível do líquido ou produto medido a partir do fundo, com entrada em **metros, centímetros ou percentual da altura interna**.
- Massa estimada opcional, mediante densidade aparente informada em kg/m³.

**Limitação:** o percentual da altura não é igual ao percentual do volume em tanques horizontais ou esféricos. Os cálculos não representam arqueação certificada nem incorporam deformações, fundos irregulares, tubulações, volume morto ou variação de densidade.

### Modo Arquiteto
- Importação de imagens e páginas de PDF como referência visual para traçar plantas.
- Importação de **DXF ASCII** com entidades `LINE` e `LWPOLYLINE` de segmentos retos. Permite visualizar as camadas (layers) e escolher **uma camada** para transformá-la em paredes editáveis; também é possível usar o desenho apenas como imagem de referência.
- Para converter linhas DXF em paredes, exige escolher explicitamente a unidade física: mm, cm, m, polegadas ou pés. Há limite de 300 paredes por importação. Polilinhas curvas com `bulge` não são suportadas.
- Calibração da escala; marcação e edição manual de paredes, portas e janelas; altura de paredes e até seis pavimentos por projeto.
- Zoom de precisão de até 6×, deslocamento da planta com dois dedos, correção manual de perspectiva para folhas planas e alinhamento entre andares por um ou dois pontos de referência.
- Maquete geométrica **3D interativa**, com rotação e zoom. Exportação de geometria no formato **OBJ**, sem texturas ou propriedades BIM.
- Até 20 projetos locais, com criação, duplicação, renomeação, seleção e exclusão confirmada.
- Relatório PDF A4 do projeto e backup/restauração local em ZIP, incluindo plantas e histórico de medições.

**Limitações:** importar PDF não identifica automaticamente paredes. Converter DXF depende da seleção correta da camada e das unidades; outras linhas da camada escolhida também poderão virar paredes. Não há suporte nativo para DWG, IFC ou RVT. A maquete é esquemática, não um projeto executivo nem um modelo BIM certificado. O backup é manual, não criptografado e não possui sincronização em nuvem.

### Realidade aumentada (ARCore)
- **AR na mesa:** posiciona a maquete em uma superfície horizontal detectada.
- **AR sobre papel:** tenta reconhecer a planta impressa e ancorar nela uma maquete em escala de apresentação, a partir da largura física informada da folha.
- Exibe informações sobre rastreamento e avisa quando a imagem de referência tem poucos detalhes.

**Limitação:** é uma função experimental, disponível apenas em aparelhos compatíveis com ARCore. A precisão da âncora, o reconhecimento da impressão e a estabilidade ainda precisam de validação em aparelho físico. A visualização não representa aferição de obra.

### Scanner 3D — preparação das fotografias
- Captura guiada de **20 posições** (12 fotografias horizontais e 8 elevadas) por objeto, com câmera ou galeria.
- Até oito sessões locais; permite trocar fotografias, acompanhar o progresso e renomear ou excluir sessões.
- Exportação e importação de **cada sessão** em ZIP independente, com validação de integridade SHA-256.
- Análise offline e aproximada de exposição, detalhes/contraste e possíveis fotografias muito semelhantes.
- Comparação de pontos visuais entre fotos por descritores binários e RANSAC 2D; verificação experimental de coerência epipolar entre imagens.

**Limitação importante:** ainda **não** reconstrói uma malha 3D, não calcula profundidade ou medidas tridimensionais e não recupera posições métricas das câmeras. Análises e correspondências são heurísticas ou experimentais, sujeitas a falsos positivos e negativos. O backup do Scanner é separado do backup do Modo Arquiteto.

### Versão Pro
Modelo comercial previsto: versão básica gratuita e um único **Pro Completo** por **R$ 129,90**, em pagamento único. **A compra e o desbloqueio ainda não estão implementados.** Não há assinatura obrigatória prevista.

## Tecnologias e compilação

- Android Gradle Plugin **9.2.1**; Gradle **9.4.1**; Kotlin embutido no AGP; Jetpack Compose BOM **2026.09.00**.
- CameraX **1.6.2**, ARCore, OpenGL ES 2.0 e Java **17**.
- `compileSdk 37`, `targetSdk 36`, `minSdk 30`.

No Android Studio, abra a pasta do projeto, instale o Android SDK 37 e sincronize o Gradle.

Compilação e testes pela linha de comando:

```bash
gradle :app:assembleDebug :app:testDebugUnitTest
```

O GitHub Actions executa a compilação e os testes automatizados a cada atualização pertinente.

## Armazenamento, segurança e validação

- Os projetos, fotos e resultados ficam no aparelho. Não há upload obrigatório, login ou serviço pago de processamento.
- Os arquivos ZIP de backup **não são criptografados** e devem ser armazenados em local seguro.
- O protótipo utiliza armazenamento local simples; migração para Room e testes instrumentados de restauração estão previstos antes de uso em produção.
- Testes automatizados **não substituem testes em celular real**: ainda é necessário validar câmera, importação PDF/DXF, contornos, níveis de tanque, AR, ZIP, PDF, OBJ, desempenho e consumo de memória.
- As medições do aplicativo são estimativas e **não substituem laudos técnicos, certificações, medições fiscais ou levantamentos profissionais**.

## Próximas etapas

1. Evoluir o contorno aproximado no vídeo ao vivo para rastreamento mais estável, com calibração dinâmica ou profundidade confiável antes de mostrar medidas físicas.
2. Melhorias na importação e identificação de paredes e aberturas de plantas, com suporte progressivo a outros formatos arquitetônicos.
3. Preparar o Scanner 3D para calibração das câmeras, triangulação e reconstrução geométrica offline, após validação com fotografias reais.
4. Reforçar persistência, restauração de backups e testes instrumentados em Android.
5. Implementar a contagem assistida de objetos e o desbloqueio real do Pro Completo.
