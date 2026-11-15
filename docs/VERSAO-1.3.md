# MeuDrive 1.3 — biblioteca, visualização e operações em lote

## Ver todas as fotos e vídeos

Abra **Galeria** no aplicativo ou navegador. A biblioteca reúne fotos, vídeos e PDFs de todas as subpastas da **pasta compartilhada pelo servidor**, ordenados por data, com filtros e **Carregar mais**. A pesquisa informa que procura entre os itens já carregados.

No A06, para incluir DCIM, Download e `Android/media`, desligue o servidor, abra **Servidor**, permita acesso aos arquivos e ative **Compartilhar armazenamento interno**. Depois ligue novamente. Se essa opção estiver desativada, a galeria mostra apenas a pasta MeuDrive. O app não expõe `Android/data`, `Android/obb`, dados privados dos outros aplicativos nem cartão SD nesta versão.

A indexação é mantida por um minuto; toque em **Atualizar** para reexaminar após mudanças. A primeira consulta pode levar alguns segundos. Há limites de 50 mil mídias, 200 mil entradas examinadas e 10 segundos por varredura para preservar o A06. Quando um deles ou uma pasta inacessível impedir a listagem completa, a interface informa que a consulta foi parcial; navegue também pelas pastas para conferir o restante. Não há afirmação de que uma consulta parcial representa todos os arquivos.

## Abrir no app

- **Fotos:** miniatura e visualização maior, com orientação EXIF. Setas permitem passar pelos itens carregados.
- **Vídeos:** miniatura do quadro inicial e player com reproduzir, pausar e avançar. O arquivo é transmitido sob demanda com HTTP Range, sem copiar tudo para RAM.
- **PDF:** miniatura da primeira página e leitor local com páginas, zoom, download e solicitação de senha quando o documento estiver protegido. PDF.js 6.5.438 é incluído no APK/pacote PC; não depende de CDN nem do leitor PDF do WebView.

A reprodução de vídeo depende dos codecs do aparelho/navegador. Quando um formato não puder ser reproduzido, a interface oferece tentativa novamente e download do original. HEIC/AVIF dependem do decodificador disponível. No servidor PC, instale as dependências de `scripts/requirements-pc.txt`; miniaturas de vídeo usam **ffmpeg** no PATH. Windows: `winget install --id Gyan.FFmpeg -e`. As prévias no A06 usam os recursos nativos do Android, sem precisar do ffmpeg no celular.

As miniaturas são autenticadas, limitadas a duas decodificações simultâneas e cache de 24 MiB. O navegador agenda no máximo duas miniaturas visíveis por vez. HTML e SVG ativos não são abertos como conteúdo confiável do servidor. Os originais permanecem disponíveis para download.

## Selecionar e excluir em lote

Segure um arquivo por cerca de meio segundo ou marque a caixa no cartão. Você pode marcar vários, escolher os itens exibidos ou cancelar a seleção. Até 200 arquivos por operação; pastas não participam da seleção em lote.

**Excluir selecionados** pede confirmação e mostra falhas individuais. Apenas os arquivos confirmados como excluídos saem da seleção. A exclusão é permanente, sem lixeira nesta versão. Abrir um arquivo ou mudar de pasta não é uma confirmação de exclusão.

## Minha rede e envio direto

**Minha rede** separa os servidores MeuDrive descobertos na rede local e os clientes autenticados que mantêm a página ativa. Clientes são registrados por heartbeat e somem após cerca de 90 segundos sem atividade. Isso não é um inventário de todos os aparelhos do roteador. Dispositivos sem MeuDrive/compartilhamento autorizado não expõem seus arquivos automaticamente.

Para copiar arquivos entre aparelhos:

1. Ligue MeuDrive nos dois servidores, na mesma rede, com a mesma senha da casa. No PC, execute `pc-server.py` para a pasta escolhida.
2. No servidor de origem, selecione arquivos e toque em **Enviar para aparelho**.
3. Escolha o destino encontrado e confirme. Acompanhe o andamento na tela Minha rede.

Os servidores copiam diretamente para `Transferencias` no destino. Os originais são mantidos; não é uma operação de mover. O navegador pode estar fora de casa usando Tailscale ou HTTPS: a cópia continua na rede dos servidores. Destinos precisam ser descobertos pelo servidor de origem; mDNS não atravessa VPN automaticamente. Não é aceito um endereço arbitrário enviado pelo navegador.

Há uma cópia em execução e uma na fila. O andamento permanece na memória do servidor; se ele for desligado, recomece a operação e confira o destino antes de repetir para evitar cópias duplicadas. Falha de senha, rede, armazenamento ou mudança do arquivo é exibida como falha, sem declarar sucesso.

## Tela apagada e segundo plano

- **Servidor A06:** continua com serviço em primeiro plano, notificação e wake/Wi-Fi locks enquanto ligado; não exige tela acesa.
- **Envio no aplicativo Android:** tanto **Compartilhar → MeuDrive** quanto **+ Enviar** no navegador interno encaminham os arquivos escolhidos para o serviço nativo de envio. A notificação mostra andamento e cancelamento, mesmo saindo da tela. O WebView usa a sessão do servidor escolhido, sem reenviar os arquivos também por JavaScript.
- **Cópia entre servidores:** ocorre no próprio servidor de origem, independente da página ficar aberta.
- **Navegador externo:** o envio comum continua sujeito à suspensão da aba/navegador. Para enviar no celular com a tela apagada, use o aplicativo Android e acompanhe a notificação.

Desative restrições de bateria para MeuDrive e Tailscale e mantenha a rede disponível. O Android ainda pode encerrar processos por parada forçada, pressão de memória, temperatura ou políticas da Samsung. Não há promessa de uptime absoluto. Progresso de upload fica no servidor por até 24 horas. Se o sistema encerrar o serviço, selecione novamente os arquivos pendentes; permissões temporárias de compartilhamento podem exigir nova seleção. Não reenviar automaticamente após morte do processo evita usar uma permissão de arquivo que expirou.

Esta versão foi testada em JVM, navegador e servidores PC de desenvolvimento. Não houve teste físico de bloqueio de tela no seu A06 nem medição de velocidade pela sua rede.

## Instalação e assinatura

O APK é da versão 1.3, código 4, Android 11+. Builds de depuração feitos em execuções distintas podem ter assinaturas diferentes. Se o Android bloquear a atualização, **não desinstale sem antes copiar os arquivos necessários e anotar as configurações**; desinstalar apaga a área privada do app. Não há desinstalação automática. Uma distribuição permanente precisa guardar a mesma chave de assinatura em todas as versões.
