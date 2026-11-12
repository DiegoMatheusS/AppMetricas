# Medição de cercas e detalhes distantes

1. Enquadre a cerca de frente, inteira se possível, com uma referência conhecida no mesmo plano (por exemplo, a altura verificada de um mourão).
2. Use o zoom disponível na câmera e toque no objeto ou em **Medir de longe**. A fotografia permanece fixa para conferir a seleção.
3. Marque os dois extremos da referência amarela e informe seu tamanho real. A escala depende dessa informação.
4. Em **Objeto**, toque na cerca. A seleção recebe um brilho no contorno e o entorno escurece. A régua mede a maior aresta do contorno selecionado; a área mede sua projeção na fotografia. Tocar em outro objeto substitui a medição anterior.
5. Se o scanner escolher apenas uma parte, tente **Ampliar seleção**. Se não houver um contorno completo, use **Pontos** e marque os extremos pretendidos. Não há agrupamento semântico de todos os fios e mourões.
6. Amplie a foto até 8×, mova com dois dedos e escolha **Ponto → Fino** para detalhes pequenos. Arraste uma ponta para visualizar a lupa; tocar numa ponta também abre o tamanho do ponto. O zoom não muda a escala já calibrada.
7. Confira as pontas e salve. Resultados são estimativas; objetos inclinados em relação à câmera e planos diferentes da referência distorcem a medida.

## Comportamento técnico

- A detecção local usa OpenCV: contraste adaptativo, múltiplos limiares, contornos e segmentos Hough. A análise da fotografia ampliada reprocessa o recorte visível usando os pixels originais. Não cria resolução ou profundidade que a câmera não capturou.
- Uma transformação única converte coordenadas entre a fotografia, os gestos e as marcações. A escala usa pixels originais e a referência física, nunca pixels de tela.
- O traçado detalhado controla a seleção e a área automática. Arrastar uma ponta troca a área automática pelo polígono editado pelo usuário.
- Pontos maiores alteram somente a apresentação e a tolerância de seleção. A preferência é compartilhada com a medição em AR.
- No modo automático em AR, o toque seleciona um contorno e reinicia a confirmação. Só há medida física após reconhecer a superfície/profundidade e confirmar estabilidade. O limite de leitura AR não é ampliado artificialmente.
- O efeito de seleção faz uma varredura luminosa por 950 ms e mantém o contorno destacado. Não utiliza reconhecimento ou serviços da Apple.

O comprimento da cerca e a distância entre a câmera e a cerca são grandezas diferentes. A fotografia calibrada estima o primeiro. O segundo requer uma leitura de profundidade ou outro método de levantamento.
